package br.com.korvon.nekonote.visual.componentes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ENTRADA_LINHAS_MS = 800L
private const val INTERVALO_LINHAS_MS = 420L

@Composable
fun AnimacaoConcluido(itens: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        LogoNotionComOnda()
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itens.forEachIndexed { i, texto -> LinhaComCheck(texto, ENTRADA_LINHAS_MS + i * INTERVALO_LINHAS_MS) }
        }
    }
}

@Composable
private fun LogoNotionComOnda() {
    val escala = remember { Animatable(0f) }
    val onda = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { escala.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)) }
        delay(180)
        onda.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }

    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val p = onda.value
            if (p > 0f && p < 1f) {
                drawCircle(
                    Color.White.copy(alpha = (1f - p) * 0.8f),
                    radius = size.minDimension / 2 * (0.62f + 0.38f * p),
                    style = Stroke(width = 5.dp.toPx() * (1f - p) + 1.dp.toPx()),
                )
            }
        }
        Box(
            Modifier.size(104.dp).graphicsLayer { scaleX = escala.value; scaleY = escala.value }
                .clip(RoundedCornerShape(28.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.notion_logo), "Notion", Modifier.size(70.dp))
        }
    }
}

@Composable
private fun LinhaComCheck(texto: String, atrasoMs: Long) {
    val aparecer = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    val traco = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(atrasoMs)
        launch { aparecer.animateTo(1f, tween(220)) }
        launch { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
        delay(110)
        traco.animateTo(1f, tween(280, easing = LinearEasing))
    }

    Row(
        Modifier.graphicsLayer { alpha = aparecer.value; translationX = (1f - aparecer.value) * 24.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Check(traco.value, Cores.Laranja, Modifier.size(30.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(texto, color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun Check(progresso: Float, cor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val completo = Path().apply {
            moveTo(size.width * 0.27f, size.height * 0.53f)
            lineTo(size.width * 0.44f, size.height * 0.69f)
            lineTo(size.width * 0.74f, size.height * 0.34f)
        }
        val parcial = Path()
        val medidor = PathMeasure().apply { setPath(completo, false) }
        medidor.getSegment(0f, medidor.length * progresso.coerceIn(0f, 1f), parcial, true)
        drawPath(parcial, cor, style = Stroke(width = 3.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
