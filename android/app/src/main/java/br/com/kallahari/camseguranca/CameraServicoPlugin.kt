package br.com.kallahari.camseguranca

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "CameraServico",
    permissions = [
        Permission(strings = [android.Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [android.Manifest.permission.RECORD_AUDIO], alias = "microfone"),
    ]
)
class CameraServicoPlugin : Plugin() {

    @PluginMethod
    fun iniciar(call: PluginCall) {
        val apiBase = call.getString("apiBase")
        val token = call.getString("token")
        val cameraNome = call.getString("cameraNome") ?: "CamSegurança"

        if (apiBase.isNullOrEmpty() || token.isNullOrEmpty()) {
            call.reject("apiBase e token são obrigatórios")
            return
        }

        Prefs.salvarPareamento(context, apiBase, token, cameraNome)

        if (getPermissionState("camera") != com.getcapacitor.PermissionState.GRANTED ||
            getPermissionState("microfone") != com.getcapacitor.PermissionState.GRANTED
        ) {
            requestPermissionForAliases(arrayOf("camera", "microfone"), call, "onPermissaoResultado")
            return
        }

        iniciarServico(call)
    }

    @PermissionCallback
    private fun onPermissaoResultado(call: PluginCall) {
        if (getPermissionState("camera") == com.getcapacitor.PermissionState.GRANTED &&
            getPermissionState("microfone") == com.getcapacitor.PermissionState.GRANTED
        ) {
            iniciarServico(call)
        } else {
            call.reject("Permissão de câmera/microfone negada — sem elas o app não pode gravar.")
        }
    }

    private fun iniciarServico(call: PluginCall) {
        val servicoIntent = Intent(context, GravacaoServico::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(servicoIntent)
        } else {
            context.startService(servicoIntent)
        }
        pedirIsencaoBateria()
        call.resolve()
    }

    private fun pedirIsencaoBateria() {
        try {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:" + context.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // Alguns fabricantes bloqueiam essa tela — não é fatal, só significa
            // que o usuário precisa liberar manualmente nas configurações da bateria.
        }
    }

    @PluginMethod
    fun parar(call: PluginCall) {
        Prefs.marcarServicoAtivo(context, false)
        context.stopService(Intent(context, GravacaoServico::class.java))
        Prefs.limparPareamento(context)
        call.resolve()
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val resultado = JSObject()
        resultado.put("rodando", GravacaoServico.rodando)
        resultado.put("filaPendente", FilaEnvio.tamanhoDaFila(context))
        val ultima = FilaEnvio.ultimaSincronizacao()
        resultado.put("ultimaSincronizacao", if (ultima > 0) java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(ultima)) else null)
        call.resolve(resultado)
    }
}
