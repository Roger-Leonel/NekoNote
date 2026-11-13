package br.com.korvon.nekonote.visual

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.visual.telas.TelaCadastro
import br.com.korvon.nekonote.visual.telas.TelaCaptura
import br.com.korvon.nekonote.visual.telas.TelaConfiguracoes
import br.com.korvon.nekonote.visual.telas.TelaConfigurar
import br.com.korvon.nekonote.visual.telas.TelaLogin
import br.com.korvon.nekonote.visual.telas.TelaProcessando
import br.com.korvon.nekonote.visual.telas.TelaSairConta
import br.com.korvon.nekonote.visual.telas.TelaSelecao
import br.com.korvon.nekonote.visual.telas.TelaAbertura

object Rotas {
    const val ABERTURA = "abertura"
    const val LOGIN = "login"
    const val CADASTRO = "cadastro/{passo}"
    const val CAPTURA = "captura"
    const val SELECAO = "selecao"
    const val CONFIGURAR = "configurar"
    const val PROCESSANDO = "processando"
    const val CONFIGURACOES = "configuracoes"
    const val SAIR_CONTA = "sair_conta"

    fun cadastro(passo: Int) = "cadastro/$passo"
}

private val rotasPublicas = setOf(Rotas.ABERTURA, Rotas.LOGIN, Rotas.CADASTRO)

@Composable
fun RaizNekoNote(conteiner: ContainerApp) {
    CompositionLocalProvider(ConteinerLocal provides conteiner) {
        val nav = rememberNavController()
        val usuario by conteiner.auth.usuario.collectAsState()
        val entrada by nav.currentBackStackEntryAsState()

        LaunchedEffect(usuario, entrada?.destination?.route) {
            val rota = entrada?.destination?.route
            if (usuario == null && rota != null && rota !in rotasPublicas) nav.irParaLogin()
        }

        NavHost(nav, startDestination = Rotas.ABERTURA) {
            composable(Rotas.ABERTURA) {
                TelaAbertura(conteiner) { destino -> nav.navigate(destino) { popUpTo(Rotas.ABERTURA) { inclusive = true } } }
            }
            composable(Rotas.LOGIN) {
                TelaLogin(
                    conteiner,
                    aoEntrar = { destino -> nav.navigate(destino) { popUpTo(Rotas.LOGIN) { inclusive = true } } },
                )
            }
            composable(
                Rotas.CADASTRO,
                arguments = listOf(navArgument("passo") { type = NavType.IntType }),
            ) { entry ->
                TelaCadastro(
                    conteiner,
                    passoInicial = entry.arguments?.getInt("passo") ?: 1,
                    aoVoltarAoLogin = { nav.irParaLogin() },
                    aoConcluir = { nav.navigate(Rotas.CAPTURA) { popUpTo(0) } },
                )
            }
            composable(Rotas.CAPTURA) {
                TelaCaptura(
                    conteiner,
                    aoAvancar = { nav.navigate(Rotas.SELECAO) },
                    aoAbrirConfiguracoes = { nav.navigate(Rotas.CONFIGURACOES) },
                )
            }
            composable(Rotas.SELECAO) {
                TelaSelecao(conteiner, aoVoltar = { nav.popBackStack() }, aoAvancar = { nav.navigate(Rotas.CONFIGURAR) })
            }
            composable(Rotas.CONFIGURAR) {
                TelaConfigurar(
                    conteiner,
                    aoVoltar = { nav.popBackStack() },
                    aoAbrirConfiguracoes = { nav.navigate(Rotas.CONFIGURACOES) },
                    aoConcluir = { nav.navigate(Rotas.PROCESSANDO) },
                )
            }
            composable(Rotas.PROCESSANDO) {
                TelaProcessando(
                    conteiner,
                    aoVoltar = { nav.popBackStack() },
                    aoNovaCaptura = { nav.popBackStack(Rotas.CAPTURA, inclusive = false) },
                )
            }
            composable(Rotas.CONFIGURACOES) {
                TelaConfiguracoes(conteiner, aoVoltar = { nav.popBackStack() }, aoAbrirSairConta = { nav.navigate(Rotas.SAIR_CONTA) })
            }
            composable(Rotas.SAIR_CONTA) {
                TelaSairConta(conteiner, aoVoltar = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.irParaLogin() {
    navigate(Rotas.LOGIN) { popUpTo(0) { inclusive = true } }
}
