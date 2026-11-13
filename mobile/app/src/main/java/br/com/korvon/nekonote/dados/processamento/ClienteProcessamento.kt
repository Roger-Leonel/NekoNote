package br.com.korvon.nekonote.dados.processamento

import br.com.korvon.nekonote.dados.Configuracao
import br.com.korvon.nekonote.dados.EventoProcessamento
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.dados.PedidoProcessamento
import br.com.korvon.nekonote.dados.auth.RepositorioAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

interface ClienteProcessamento {
    fun processar(pedido: PedidoProcessamento): Flow<EventoProcessamento>
}

class ClienteProcessamentoWs(
    private val client: OkHttpClient,
    private val auth: RepositorioAuth,
) : ClienteProcessamento {
    override fun processar(pedido: PedidoProcessamento): Flow<EventoProcessamento> = flow {
        val idPedido = UUID.randomUUID().toString()
        val mensagem = withContext(Dispatchers.Default) { montarMensagem(pedido, idPedido) }
        if (mensagem == null) {
            emit(EventoProcessamento.Erro("As imagens ficaram grandes demais. Remova algumas fotos e tente de novo."))
            return@flow
        }

        var tentativa = 0
        while (true) {
            var terminou = false
            tentarUmaVez(mensagem).collect { ev ->
                emit(ev)
                if (ev is EventoProcessamento.Resultado || ev is EventoProcessamento.Erro) terminou = true
            }
            if (terminou) return@flow
            if (tentativa >= MAX_TENTATIVAS) {
                emit(EventoProcessamento.Erro("Conexão perdida. Verifique sua internet e tente novamente."))
                return@flow
            }
            tentativa++
            emit(EventoProcessamento.Reconectando(tentativa))
            delay(1000L shl (tentativa - 1))
        }
    }

    private fun tentarUmaVez(mensagem: (String) -> String): Flow<EventoProcessamento> = callbackFlow {
        val token = try {
            auth.idToken(forcarRenovacao = true)
        } catch (e: ExcecaoNeko) {
            trySend(EventoProcessamento.Erro(e.message.orEmpty()))
            close()
            awaitClose()
            return@callbackFlow
        }
        var terminal = false
        val req = Request.Builder().url(Configuracao.urlWebSocket).header("Authorization", "Bearer $token").build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(mensagem(token))) {
                    terminal = true
                    trySend(EventoProcessamento.Erro("As imagens ficaram grandes demais para enviar."))
                    close()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = interpretar(text) ?: return
                if (ev is EventoProcessamento.Resultado || ev is EventoProcessamento.Erro) terminal = true
                trySend(ev)
                if (terminal) {
                    webSocket.close(1000, null)
                    close()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401 || response?.code == 403) {
                    terminal = true
                    trySend(EventoProcessamento.Erro("Sessão expirada. Entre novamente."))
                }
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        })
        awaitClose { socket.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun interpretar(texto: String): EventoProcessamento? = try {
        val j = JSONObject(texto)
        when (j.optString("tipo")) {
            "inicio" -> EventoProcessamento.Inicio(j.optInt("total"))
            "progresso" -> EventoProcessamento.Progresso(
                j.optInt("percentual"), j.optInt("imagem_atual"), j.optInt("total"), j.optString("mensagem"),
            )
            "enviando" -> EventoProcessamento.Enviando(j.optString("mensagem", "Gerando resumos..."))
            "criando" -> EventoProcessamento.Enviando(j.optString("mensagem", "Criando a página no Notion..."), 98)
            "resultado" -> EventoProcessamento.Resultado(
                urlPagina = if (j.isNull("url_pagina")) null else j.optString("url_pagina").takeIf { it.startsWith("https://") },
                mensagem = j.optString("mensagem", "Pronto!"),
            )
            "erro" -> EventoProcessamento.Erro(j.optString("mensagem", "Erro no processamento."))
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun montarMensagem(p: PedidoProcessamento, idPedido: String): ((String) -> String)? {
        val imagens = codificarImagens(p.imagens) ?: return null
        return { idToken ->
            JSONObject()
                .put("acao", "processar")
                .put("id_token", idToken)
                .put("id_pedido", idPedido)
                .put("id_pagina", p.idPagina)
                .put(
                    "modalidades",
                    JSONObject().put("topicos", p.modalidades.topicos).put("resumo", p.modalidades.resumo)
                        .put("assunto", p.modalidades.assunto).put("apresentacao", p.modalidades.apresentacao),
                )
                .put("temperamento", p.temperamento?.valor ?: JSONObject.NULL)
                .put("imagens", JSONArray(imagens))
                .toString()
        }
    }

    private fun codificarImagens(arquivos: List<File>): List<String>? {
        val lista = arquivos.map { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }
        val total = lista.sumOf { it.length.toLong() }
        return if (total <= Configuracao.ORCAMENTO_BASE64_BYTES) lista else null
    }

    private companion object {
        const val MAX_TENTATIVAS = 3
    }
}
