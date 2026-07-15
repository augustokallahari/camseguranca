package br.com.kallahari.camseguranca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.util.concurrent.Executors

// Serviço de primeiro plano que grava vídeo+áudio continuamente em segmentos
// de ~6s e os entrega pra FilaEnvio. Roda mesmo com a tela apagada — é o que
// permite a câmera de segurança funcionar 24h sem o Android matar o processo,
// e é justamente esse serviço que obriga o Android a mostrar a notificação
// fixa (nunca escondida) enquanto grava.
class GravacaoServico : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "GravacaoServico"
        private const val CANAL_ID = "camseguranca_gravando"
        private const val NOTIFICACAO_ID = 1001
        private const val DURACAO_SEGMENTO_MS = 6000L
        private const val INTERVALO_SYNC_MS = 10000L
        var rodando = false
            private set
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var gravacaoAtual: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var parando = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            Executors.newSingleThreadExecutor().execute { FilaEnvio.tentarEnviarTudo(applicationContext) }
            handler.postDelayed(this, INTERVALO_SYNC_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        criarCanalNotificacao()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        if (!temPermissoes()) {
            Log.e(TAG, "Sem permissão de câmera/microfone — serviço não pode iniciar.")
            stopSelf()
            return START_NOT_STICKY
        }

        val notificacao = construirNotificacao(Prefs.cameraNome(this) ?: "CamSegurança")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICACAO_ID, notificacao,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICACAO_ID, notificacao)
        }

        adquirirWakeLock()
        rodando = true
        Prefs.marcarServicoAtivo(this, true)

        iniciarCamera()
        handler.postDelayed(syncRunnable, INTERVALO_SYNC_MS)

        return START_STICKY
    }

    private fun temPermissoes(): Boolean {
        val cam = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val mic = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
        return cam == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }

    private fun iniciarCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                iniciarProximoSegmento()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao vincular câmera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun iniciarProximoSegmento() {
        if (parando) return
        val vc = videoCapture ?: return

        val pastaTemp = File(cacheDir, "gravando").apply { if (!exists()) mkdirs() }
        val arquivo = File(pastaTemp, "seg_${System.currentTimeMillis()}.mp4")
        val opcoesSaida = FileOutputOptions.Builder(arquivo).build()

        val pendente = vc.output.prepareRecording(this, opcoesSaida)
            .withAudioEnabled()

        gravacaoAtual = pendente.start(executor) { evento ->
            if (evento is VideoRecordEvent.Finalize) {
                if (evento.hasError()) {
                    Log.w(TAG, "Segmento com erro (${evento.error}), descartando.")
                    if (arquivo.exists()) arquivo.delete()
                } else {
                    FilaEnvio.enfileirar(applicationContext, arquivo)
                }
                if (!parando) iniciarProximoSegmento()
            }
        }

        // Encerra este segmento após ~6s, o que dispara o Finalize acima e
        // encadeia o próximo — é assim que a gravação fica contínua.
        handler.postDelayed({ gravacaoAtual?.stop() }, DURACAO_SEGMENTO_MS)
    }

    private fun adquirirWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamSeguranca::GravacaoWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // renovado a cada onStartCommand
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID, "Câmera de segurança ativa", NotificationManager.IMPORTANCE_LOW
            )
            canal.description = "Notificação fixa enquanto a câmera e o microfone estão gravando"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacao(cameraNome: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("📷🎙 $cameraNome — gravando")
            .setContentText("Câmera de segurança ativa. Toque para abrir.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        parando = true
        rodando = false
        Prefs.marcarServicoAtivo(this, false)
        handler.removeCallbacks(syncRunnable)
        gravacaoAtual?.stop()
        cameraProvider?.unbindAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
