package br.com.korvon.nekonote.dados.api

import br.com.korvon.nekonote.dados.Conta
import br.com.korvon.nekonote.dados.PaginaNotion

interface ApiNeko {
    suspend fun conta(): Conta

    suspend fun iniciarConexaoNotion(): String
    suspend fun desconectarNotion()
    suspend fun paginas(): List<PaginaNotion>
    suspend fun excluirConta()

    suspend fun finalizarCadastro()
}
