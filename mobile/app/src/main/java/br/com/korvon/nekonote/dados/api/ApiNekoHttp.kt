package br.com.korvon.nekonote.dados.api

import br.com.korvon.nekonote.dados.Conta
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.dados.ExcecaoNeko.Tipo
import br.com.korvon.nekonote.dados.NotionEstado
import br.com.korvon.nekonote.dados.NotionStatus
import br.com.korvon.nekonote.dados.PaginaNotion
import br.com.korvon.nekonote.dados.auth.RepositorioAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class ApiNekoHttp(
    private val client: OkHttpClient,
    private val auth: RepositorioAuth,
    private val baseUrl: String,
) : ApiNeko {
    override suspend fun conta(): Conta {
        val j = chamar("GET", "/conta")
        val n = j.optJSONObject("notion")
        val status = NotionStatus(
            estado = when (n?.optString("status")) {
                "conectado" -> NotionEstado.CONECTADO
                "reconectar" -> NotionEstado.RECONECTAR
                else -> NotionEstado.DESCONECTADO
            },
            espacoNome = n?.optStringOrNull("espaco_nome"),
            espacoIcone = n?.optStringOrNull("espaco_icone"),
            paginasLiberadas = n?.optInt("paginas", 0) ?: 0,
        )
        return Conta(
            email = j.optString("email"),
            idPublico = j.optString("id_publico"),
            notion = status,
            cadastroConcluido = j.optBoolean("cadastro_concluido", false),
        )
    }

    override suspend fun finalizarCadastro() {
        chamar("POST", "/conta/finalizar", "{}")
    }

    override suspend fun iniciarConexaoNotion(): String {
        val j = chamar("POST", "/notion/conectar", "{}")
        return j.optStringOrNull("url_autorizacao")
            ?: throw ExcecaoNeko("Resposta inesperada do servidor.", Tipo.GERAL)
    }

    override suspend fun desconectarNotion() {
        chamar("DELETE", "/notion/conexao")
    }

    override suspend fun paginas(): List<PaginaNotion> {
        val arr = chamar("GET", "/notion/paginas").optJSONArray("paginas") ?: return emptyList()
        return List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            PaginaNotion(p.getString("id"), p.optString("titulo").ifBlank { "Sem título" }, p.optStringOrNull("icone"), p.optStringOrNull("pai"))
        }
    }

    override suspend fun excluirConta() {
        chamar("DELETE", "/conta")
    }

    private suspend fun chamar(metodo: String, caminho: String, corpo: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            var resposta = executar(metodo, caminho, corpo, auth.idToken())
            if (resposta.first == 401) resposta = executar(metodo, caminho, corpo, auth.idToken(forcarRenovacao = true))
            val (codigo, texto) = resposta
            val json = try {
                if (texto.isBlank()) JSONObject() else JSONObject(texto)
            } catch (e: JSONException) {
                JSONObject()
            }
            when {
                codigo in 200..299 -> json
                codigo == 401 -> throw ExcecaoNeko("Sessão expirada. Entre novamente.", Tipo.NAO_AUTENTICADO)
                codigo == 409 || json.optString("status") == "reconectar" ->
                    throw ExcecaoNeko("A conexão com o Notion expirou. Reconecte para continuar.", Tipo.NOTION_RECONECTAR)
                else -> throw ExcecaoNeko(
                    json.optStringOrNull("mensagem") ?: json.optStringOrNull("detail") ?: "Erro no servidor ($codigo).",
                )
            }
        }

    private fun executar(metodo: String, caminho: String, corpo: String?, token: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(baseUrl + caminho)
            .header("Authorization", "Bearer $token")
            .method(metodo, corpo?.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { r -> return r.code to (r.body.string()) }
        } catch (e: IOException) {
            throw ExcecaoNeko("Sem conexão com o servidor. Verifique sua internet.", Tipo.REDE, e)
        }
    }
}

private fun JSONObject.optStringOrNull(chave: String): String? =
    if (isNull(chave)) null else optString(chave).takeIf { it.isNotBlank() }
