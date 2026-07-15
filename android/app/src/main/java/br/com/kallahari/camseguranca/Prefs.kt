package br.com.kallahari.camseguranca

import android.content.Context
import android.content.SharedPreferences

// Guarda o pareamento (API base + token + nome da câmera) fora do localStorage
// da WebView, porque o BootReceiver e o serviço precisam ler isso mesmo com o
// app/WebView fechado (ex: depois de reiniciar o celular).
object Prefs {
    private const val ARQUIVO = "camseguranca_prefs"
    private const val CHAVE_API_BASE = "api_base"
    private const val CHAVE_TOKEN = "token"
    private const val CHAVE_NOME = "camera_nome"
    private const val CHAVE_ATIVO = "servico_ativo"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun salvarPareamento(context: Context, apiBase: String, token: String, cameraNome: String) {
        sp(context).edit()
            .putString(CHAVE_API_BASE, apiBase)
            .putString(CHAVE_TOKEN, token)
            .putString(CHAVE_NOME, cameraNome)
            .apply()
    }

    fun limparPareamento(context: Context) {
        sp(context).edit().clear().apply()
    }

    fun apiBase(context: Context): String? = sp(context).getString(CHAVE_API_BASE, null)
    fun token(context: Context): String? = sp(context).getString(CHAVE_TOKEN, null)
    fun cameraNome(context: Context): String? = sp(context).getString(CHAVE_NOME, null)

    fun temPareamento(context: Context): Boolean =
        !token(context).isNullOrEmpty() && !apiBase(context).isNullOrEmpty()

    fun marcarServicoAtivo(context: Context, ativo: Boolean) {
        sp(context).edit().putBoolean(CHAVE_ATIVO, ativo).apply()
    }

    fun servicoDeveEstarAtivo(context: Context): Boolean = sp(context).getBoolean(CHAVE_ATIVO, false)
}
