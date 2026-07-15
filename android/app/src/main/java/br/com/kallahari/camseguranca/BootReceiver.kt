package br.com.kallahari.camseguranca

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// Reinicia a gravação sozinho depois que o celular liga (queda de energia,
// reboot manual) — sem isso, a câmera fixa ficaria "morta" até alguém abrir o
// app manualmente.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.temPareamento(context) || !Prefs.servicoDeveEstarAtivo(context)) return

        val servicoIntent = Intent(context, GravacaoServico::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(servicoIntent)
        } else {
            context.startService(servicoIntent)
        }
    }
}
