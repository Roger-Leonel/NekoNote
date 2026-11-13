package br.com.korvon.nekonote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.korvon.nekonote.visual.RaizNekoNote
import br.com.korvon.nekonote.visual.tema.TemaNekoNote

class AtividadePrincipal : ComponentActivity() {
    private val conteiner get() = (application as AplicativoNekoNote).conteiner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tratarDeepLink(intent)
        setContent {
            TemaNekoNote {
                RaizNekoNote(conteiner)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tratarDeepLink(intent)
    }

    private fun tratarDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "nekonote" && uri.host == "notion") {
            conteiner.retornoNotion.value = uri.getQueryParameter("status") ?: "erro"
        }
    }
}
