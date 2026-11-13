package br.com.korvon.nekonote.visual.telas

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.korvon.nekonote.BuildConfig
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.dados.Configuracao
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.visual.Rotas
import br.com.korvon.nekonote.visual.abrirCustomTab
import br.com.korvon.nekonote.visual.componentes.Aviso
import br.com.korvon.nekonote.visual.componentes.Marca
import br.com.korvon.nekonote.visual.componentes.Pata
import br.com.korvon.nekonote.visual.destinoPosLogin
import br.com.korvon.nekonote.visual.paraUsuario
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TelaAbertura(conteiner: ContainerApp, aoDecidir: (String) -> Unit) {
    LaunchedEffect(Unit) {
        delay(1200)
        val destino = if (conteiner.auth.usuario.value == null) Rotas.LOGIN else destinoPosLogin(conteiner)
        aoDecidir(destino)
    }
    Box(Modifier.fillMaxSize().background(Cores.Laranja), contentAlignment = Alignment.Center) {
        Marca(Color.Black, tamanhoFonte = 38)
    }
}

@Composable
fun TelaLogin(conteiner: ContainerApp, aoEntrar: (String) -> Unit) {
    val escopo = rememberCoroutineScope()
    val contexto = LocalContext.current
    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    fun entrar() {
        if (carregando) return
        carregando = true
        erro = null
        escopo.launch {
            try {
                conteiner.auth.entrarComGoogle(contexto)
                aoEntrar(destinoPosLogin(conteiner))
            } catch (e: Exception) {
                Log.e("NekoAuth", "login com google falhou", e)
                val cancelado = (e as? ExcecaoNeko)?.tipo == ExcecaoNeko.Tipo.CANCELADO
                if (!cancelado) erro = e.paraUsuario()
                else if (BuildConfig.DEBUG) erro = "Login cancelado (${e.cause?.message ?: "sem detalhe"}). Se você escolheu uma conta, confira o SHA-1 do app e o ID do cliente Web no Google Cloud."
                carregando = false
            }
        }
    }

    val ligacao = TextLinkStyles(SpanStyle(color = Cores.Laranja, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))
    val aviso = buildAnnotatedString {
        append("Ao continuar, você concorda com os ")
        withLink(LinkAnnotation.Clickable("termos", ligacao) { abrirCustomTab(contexto, Configuracao.urlTermos) }) { append("Termos de uso") }
        append(" e a ")
        withLink(LinkAnnotation.Clickable("privacidade", ligacao) { abrirCustomTab(contexto, Configuracao.urlPrivacidade) }) { append("Política de privacidade") }
        append(".")
    }

    Box(Modifier.fillMaxSize().background(Cores.Grafite)) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 120.dp, y = (-90).dp).size(320.dp).clip(CircleShape).background(Cores.Laranja.copy(alpha = 0.14f)))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-110).dp, y = 120.dp).size(260.dp).clip(CircleShape).background(Cores.Roxo.copy(alpha = 0.10f)))

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Pata(76.dp, Cores.Laranja)
            Spacer(Modifier.height(18.dp))
            Text("NekoNote", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Fotografe suas páginas e receba notas prontas no Notion.",
                color = Color.White.copy(alpha = 0.65f), fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.weight(1.3f))

            BotaoGoogle(carregando = carregando, aoClicar = ::entrar)
            erro?.let { Aviso(it, Modifier.padding(top = 16.dp)) }
            Spacer(Modifier.height(28.dp))
            Text(aviso, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BotaoGoogle(carregando: Boolean, aoClicar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).shadow(4.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(Color.White).clickable(enabled = !carregando, onClick = aoClicar).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (carregando) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Cores.Laranja, strokeWidth = 2.5.dp)
        } else {
            Image(painterResource(R.drawable.google_g), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            if (carregando) "Entrando…" else "Continuar com o Google",
            color = Color(0xFF1F1F1F), fontFamily = Maven, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
        )
    }
}
