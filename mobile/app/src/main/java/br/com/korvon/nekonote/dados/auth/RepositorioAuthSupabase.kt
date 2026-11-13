package br.com.korvon.nekonote.dados.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import br.com.korvon.nekonote.BuildConfig
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.dados.ExcecaoNeko.Tipo
import br.com.korvon.nekonote.dados.UsuarioAuth
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

class RepositorioAuthSupabase(
    private val http: OkHttpClient,
    urlAuth: String,
    private val contextoApp: Context,
) : RepositorioAuth {
    private val urlAuth = urlAuth.trimEnd('/')
    private val prefs = contextoApp.getSharedPreferences("nekonote_sessao", Context.MODE_PRIVATE)
    private val trava = Mutex()

    private val _usuario = MutableStateFlow(lerUsuario())
    override val usuario: StateFlow<UsuarioAuth?> = _usuario

    override suspend fun entrarComGoogle(contexto: Context) {
        val nonceBruto = nonceAleatorio()
        val idTokenGoogle = idTokenDoGoogle(contexto, nonceBruto)
        val resposta = enviar(
            "POST", "/token?grant_type=id_token",
            JSONObject().put("provider", "google").put("id_token", idTokenGoogle).put("nonce", nonceBruto),
        )
        salvarSessao(resposta)
    }

    override suspend fun sair() {
        val token = prefs.getString(CHAVE_ACESSO, null)
        limparSessao()
        if (token != null) {
            try {
                enviar("POST", "/logout", JSONObject(), token)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        try {
            CredentialManager.create(contextoApp).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    override suspend fun definirNome(nomeCompleto: String) {
        val token = idToken()
        val resposta = enviar("PUT", "/user", JSONObject().put("data", JSONObject().put("nome_completo", nomeCompleto)), token)
        prefs.edit().putString(CHAVE_USUARIO, resposta.toString()).apply()
        _usuario.value = lerUsuario()
    }

    override suspend fun idToken(forcarRenovacao: Boolean): String = trava.withLock {
        val acesso = prefs.getString(CHAVE_ACESSO, null)
        val renovacao = prefs.getString(CHAVE_RENOVACAO, null)
        if (acesso == null || renovacao == null) throw ExcecaoNeko("Sessão expirada. Entre novamente.", Tipo.NAO_AUTENTICADO)
        val expiraEm = prefs.getLong(CHAVE_EXPIRA_EM, 0L)
        if (!forcarRenovacao && expiraEm - agora() > MARGEM_SEGUNDOS) return acesso
        try {
            val resposta = enviar("POST", "/token?grant_type=refresh_token", JSONObject().put("refresh_token", renovacao))
            salvarSessao(resposta)
            resposta.getString("access_token")
        } catch (e: ExcecaoNeko) {
            if (e.tipo == Tipo.NAO_AUTENTICADO) limparSessao()
            throw e
        }
    }

    private suspend fun idTokenDoGoogle(contexto: Context, nonceBruto: String): String {
        if (BuildConfig.ID_CLIENTE_WEB_GOOGLE.isBlank()) {
            throw ExcecaoNeko("Login com Google não configurado: falta google.idClienteWeb no local.properties.", Tipo.GERAL)
        }
        val opcao = GetSignInWithGoogleOption.Builder(BuildConfig.ID_CLIENTE_WEB_GOOGLE)
            .setNonce(sha256Hex(nonceBruto))
            .build()
        val pedido = GetCredentialRequest.Builder().addCredentialOption(opcao).build()
        val resposta = try {
            CredentialManager.create(contexto).getCredential(contexto, pedido)
        } catch (e: GetCredentialCancellationException) {
            throw ExcecaoNeko("Login cancelado.", Tipo.CANCELADO, e)
        } catch (e: NoCredentialException) {
            throw ExcecaoNeko("Nenhuma conta Google encontrada neste aparelho. Adicione uma conta nas configurações do Android.", Tipo.GERAL, e)
        } catch (e: GetCredentialException) {
            throw ExcecaoNeko("Não foi possível entrar com o Google. Tente novamente.", Tipo.GERAL, e)
        }
        val credencial = resposta.credential
        if (credencial !is CustomCredential || credencial.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw ExcecaoNeko("Não foi possível entrar com o Google. Tente novamente.", Tipo.GERAL)
        }
        return try {
            GoogleIdTokenCredential.createFrom(credencial.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            throw ExcecaoNeko("Não foi possível entrar com o Google. Tente novamente.", Tipo.GERAL, e)
        }
    }

    private suspend fun enviar(metodo: String, caminho: String, corpo: JSONObject, token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val pedido = Request.Builder()
                .url(urlAuth + caminho)
                .method(metodo, corpo.toString().toRequestBody("application/json".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            try {
                http.newCall(pedido).execute().use { resp ->
                    val texto = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> if (texto.isBlank()) JSONObject() else JSONObject(texto)
                        resp.code == 400 || resp.code == 401 || resp.code == 403 ->
                            throw ExcecaoNeko("Sessão expirada. Entre novamente.", Tipo.NAO_AUTENTICADO)
                        else -> throw ExcecaoNeko("Não foi possível entrar agora. Tente novamente.", Tipo.GERAL)
                    }
                }
            } catch (e: IOException) {
                throw ExcecaoNeko("Sem conexão com o servidor. Verifique sua internet.", Tipo.REDE, e)
            }
        }

    private fun salvarSessao(resposta: JSONObject) {
        val expiraEm = if (resposta.has("expires_at")) resposta.getLong("expires_at") else agora() + resposta.optLong("expires_in", 3600)
        prefs.edit()
            .putString(CHAVE_ACESSO, resposta.getString("access_token"))
            .putString(CHAVE_RENOVACAO, resposta.getString("refresh_token"))
            .putLong(CHAVE_EXPIRA_EM, expiraEm)
            .apply { resposta.optJSONObject("user")?.let { putString(CHAVE_USUARIO, it.toString()) } }
            .apply()
        _usuario.value = lerUsuario()
    }

    private fun limparSessao() {
        prefs.edit().clear().apply()
        _usuario.value = null
    }

    private fun lerUsuario(): UsuarioAuth? {
        if (prefs.getString(CHAVE_ACESSO, null) == null) return null
        val u = prefs.getString(CHAVE_USUARIO, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val meta = u.optJSONObject("user_metadata")
        val nome = listOf("nome_completo", "full_name", "name").firstNotNullOfOrNull { meta?.optString(it)?.takeIf { v -> v.isNotBlank() } }
        return UsuarioAuth(u.getString("id"), u.optString("email"), nome.orEmpty())
    }

    private fun agora() = System.currentTimeMillis() / 1000

    private fun nonceAleatorio(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256Hex(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CHAVE_ACESSO = "acesso"
        const val CHAVE_RENOVACAO = "renovacao"
        const val CHAVE_EXPIRA_EM = "expira_em"
        const val CHAVE_USUARIO = "usuario"
        const val MARGEM_SEGUNDOS = 60L
    }
}
