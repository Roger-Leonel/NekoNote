package br.com.korvon.nekonote.visual

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.dados.NotionEstado

val ConteinerLocal = staticCompositionLocalOf<ContainerApp> { error("ContainerApp não fornecido") }

@Composable
fun tecladoAberto(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

@Composable
inline fun <reified M : ViewModel> obterModelo(crossinline criar: (ContainerApp) -> M, conteiner: ContainerApp): M =
    viewModel(factory = viewModelFactory { initializer { criar(conteiner) } })

fun Throwable.paraUsuario(): String =
    (this as? ExcecaoNeko)?.message ?: "Algo deu errado. Tente novamente."

fun abrirAutorizacaoNotion(context: Context, url: String) {
    abrirCustomTab(context, url)
}

fun abrirCustomTab(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } catch (e: ActivityNotFoundException) {
        abrirUrl(context, url)
    }
}

fun abrirUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
    }
}

suspend fun destinoPosLogin(c: ContainerApp): String {
    val idUsuario = c.auth.usuario.value?.idUsuario ?: return Rotas.LOGIN
    return try {
        val concluido = c.api.conta().cadastroConcluido
        c.prefs.marcarOnboarding(idUsuario, concluido)
        if (concluido) Rotas.CAPTURA else Rotas.cadastro(1)
    } catch (e: ExcecaoNeko) {
        when {
            e.tipo == ExcecaoNeko.Tipo.NAO_AUTENTICADO -> Rotas.LOGIN
            c.prefs.onboardingConcluido(idUsuario) -> Rotas.CAPTURA
            else -> Rotas.cadastro(1)
        }
    }
}
