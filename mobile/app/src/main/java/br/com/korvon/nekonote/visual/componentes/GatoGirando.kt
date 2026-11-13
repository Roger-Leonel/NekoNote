package br.com.korvon.nekonote.visual.componentes

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.aspectRatio
import br.com.korvon.nekonote.R

private object CacheGato {
    @Volatile
    private var svg: SvgAnimado? = null

    fun obter(contexto: android.content.Context): SvgAnimado =
        svg ?: synchronized(this) { svg ?: SvgAnimado.carregar(contexto.applicationContext, R.raw.gato_loader).also { svg = it } }
}

@Composable
fun GatoGirando(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val svg = remember { CacheGato.obter(contexto) }
    var quadroNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val inicio = withFrameNanos { it }
        while (true) quadroNs = withFrameNanos { it } - inicio
    }

    Canvas(modifier.aspectRatio(1f)) {
        drawIntoCanvas { svg.desenhar(it.nativeCanvas, size.minDimension, quadroNs / 1_000_000_000f) }
    }
}
