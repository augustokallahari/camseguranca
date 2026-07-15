package br.com.kallahari.camseguranca

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

// Fila de upload dos segmentos gravados: cada arquivo pendente fica salvo em
// disco (pasta "fila_envio") até ser confirmado pelo servidor. Se o envio
// falhar (sem internet, servidor fora do ar), o arquivo continua na fila e é
// tentado de novo no próximo ciclo — mesma lógica offline-first do PontoFácil,
// só que aqui o que fica pendente é o arquivo de vídeo, não um registro JSON.
object FilaEnvio {
    private const val TAG = "FilaEnvio"
    private const val LIMITE_FILA_BYTES = 500L * 1024 * 1024 // 500MB
    private var ultimaSincronizacaoMs: Long = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun pastaFila(context: Context): File {
        val dir = File(context.filesDir, "fila_envio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun enfileirar(context: Context, arquivoGravado: File) {
        val destino = File(pastaFila(context), arquivoGravado.name)
        arquivoGravado.copyTo(destino, overwrite = true)
        arquivoGravado.delete()
        aplicarLimiteDaFila(context)
    }

    // Se ficar muito tempo offline, descarta os segmentos mais antigos pra não
    // lotar o armazenamento do celular — prioriza os mais recentes.
    private fun aplicarLimiteDaFila(context: Context) {
        val arquivos = pastaFila(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = arquivos.sumOf { it.length() }
        var i = 0
        while (total > LIMITE_FILA_BYTES && i < arquivos.size) {
            total -= arquivos[i].length()
            arquivos[i].delete()
            i++
        }
    }

    fun tamanhoDaFila(context: Context): Int = pastaFila(context).listFiles()?.size ?: 0

    fun ultimaSincronizacao(): Long = ultimaSincronizacaoMs

    // Tenta enviar tudo que estiver pendente. Chamado periodicamente pelo
    // serviço enquanto ele estiver rodando.
    fun tentarEnviarTudo(context: Context) {
        val apiBase = Prefs.apiBase(context) ?: return
        val token = Prefs.token(context) ?: return
        val arquivos = pastaFila(context).listFiles()?.sortedBy { it.lastModified() } ?: return

        for (arquivo in arquivos) {
            val ok = enviarArquivo(apiBase, token, arquivo)
            if (ok) {
                arquivo.delete()
                ultimaSincronizacaoMs = System.currentTimeMillis()
            } else {
                // Assim que um falhar (provável falta de conexão), para a
                // tentativa nesse ciclo — os próximos já estão na fila e serão
                // tentados de novo no próximo ciclo, na ordem certa.
                break
            }
        }
    }

    private fun enviarArquivo(apiBase: String, token: String, arquivo: File): Boolean {
        return try {
            val corpo = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("duracao_seg", "6")
                .addFormDataPart(
                    "segmento", arquivo.name,
                    arquivo.asRequestBody("video/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(apiBase.trimEnd('/') + "/upload_segmento.php")
                .header("Authorization", "Bearer $token")
                .post(corpo)
                .build()

            client.newCall(request).execute().use { resposta ->
                if (!resposta.isSuccessful) {
                    Log.w(TAG, "Upload falhou (HTTP ${resposta.code}): ${arquivo.name}")
                    return false
                }
                val corpoResposta = resposta.body?.string() ?: ""
                val ok = corpoResposta.contains("\"ok\":true")
                if (!ok) Log.w(TAG, "Servidor rejeitou o segmento ${arquivo.name}: $corpoResposta")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro de rede ao enviar ${arquivo.name}: ${e.message}")
            false
        }
    }
}
