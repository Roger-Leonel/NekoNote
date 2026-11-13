package br.com.korvon.nekonote.dados.auth

import android.content.Context
import br.com.korvon.nekonote.dados.UsuarioAuth
import kotlinx.coroutines.flow.StateFlow

interface RepositorioAuth {
    val usuario: StateFlow<UsuarioAuth?>

    suspend fun entrarComGoogle(contexto: Context)
    suspend fun sair()

    suspend fun definirNome(nomeCompleto: String)

    suspend fun idToken(forcarRenovacao: Boolean = false): String
}
