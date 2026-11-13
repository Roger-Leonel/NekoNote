package br.com.korvon.nekonote.visual.tema

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import br.com.korvon.nekonote.R

object Cores {
    val Laranja = Color(0xFFFF6B14)
    val LaranjaClaro = Color(0xFFFF7A1A)
    val Grafite = Color(0xFF231F20)
    val CinzaTela = Color(0xFFEBEBEB)
    val CinzaCampo = Color(0xFFD9D9D9)
    val CinzaCard = Color(0xFFE8E6E1)
    val Roxo = Color(0xFFB364E6)
    val RoxoClaro = Color(0xFFD9B3F5)
    val Verde = Color(0xFF7DF08F)
    val Amarelo = Color(0xFFFFD43B)
    val Vermelho = Color(0xFFE5252A)
    val Branco = Color.White
    val Preto = Color.Black
}

@Suppress("OPT_IN_USAGE")
val Maven = FontFamily(
    Font(R.font.maven_pro, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.maven_pro, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.maven_pro, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.maven_pro, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.maven_pro, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private val Tipografia = Typography(
    displayMedium = TextStyle(fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Maven, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    labelLarge = TextStyle(fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun TemaNekoNote(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Cores.Laranja,
            onPrimary = Color.White,
            secondary = Cores.Roxo,
            onSecondary = Color.White,
            background = Cores.Laranja,
            onBackground = Color.White,
            surface = Color.White,
            onSurface = Color.Black,
            error = Cores.Vermelho,
        ),
        typography = Tipografia,
        content = content,
    )
}
