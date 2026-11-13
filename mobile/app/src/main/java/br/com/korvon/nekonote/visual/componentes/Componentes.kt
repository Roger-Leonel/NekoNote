package br.com.korvon.nekonote.visual.componentes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven

@Composable
fun Pata(tamanho: Dp, cor: Color = Color.Black, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.pata),
        contentDescription = null,
        colorFilter = ColorFilter.tint(cor),
        modifier = modifier.size(tamanho),
    )
}

@Composable
fun Marca(cor: Color, tamanhoFonte: Int = 34, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NekoNote", color = cor, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = tamanhoFonte.sp)
        Pata((tamanhoFonte * 1.1).dp, cor)
    }
}

@Composable
fun AvatarGato(tamanho: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.avatar_gato),
        contentDescription = "Avatar",
        modifier = modifier.size(tamanho).clip(CircleShape),
    )
}

@Composable
fun CampoPilula(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String,
    icone: ImageVector,
    modifier: Modifier = Modifier,
    senha: Boolean = false,
    teclado: KeyboardType = KeyboardType.Text,
    acaoIme: ImeAction = ImeAction.Next,
    fundo: Color = Cores.CinzaCampo,
    raio: Dp = 28.dp,
    sombra: Boolean = true,
    erro: Boolean = false,
    habilitado: Boolean = true,
) {
    TextField(
        value = valor,
        onValueChange = aoMudar,
        enabled = habilitado,
        placeholder = { Text(rotulo, fontSize = 17.sp, color = Color(0xFF3C3C3C)) },
        trailingIcon = { Icon(icone, contentDescription = null, tint = if (habilitado) Color.Black else Color(0xFF8A8A8A)) },
        singleLine = true,
        isError = erro,
        visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (senha) KeyboardType.Password else teclado, imeAction = acaoIme),
        shape = RoundedCornerShape(raio),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fundo,
            unfocusedContainerColor = fundo,
            errorContainerColor = fundo,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Cores.Vermelho,
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black,
            errorTextColor = Color.Black,
            disabledContainerColor = fundo.copy(alpha = 0.55f),
            disabledIndicatorColor = Color.Transparent,
            disabledTextColor = Color(0xFF5E5E5E),
            cursorColor = Cores.Laranja,
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(if (sombra) Modifier.shadow(6.dp, RoundedCornerShape(raio)) else Modifier)
            .height(62.dp),
    )
}

@Composable
fun BotaoCheio(
    texto: String,
    aoClicar: () -> Unit,
    modifier: Modifier = Modifier,
    cor: Color = Cores.Laranja,
    corTexto: Color = Color.White,
    habilitado: Boolean = true,
    raio: Dp = 20.dp,
    icone: @Composable (() -> Unit)? = null,
) {
    val fundo = if (habilitado) cor else cor.copy(alpha = 0.4f)
    Row(
        modifier = modifier
            .shadow(if (habilitado) 6.dp else 0.dp, RoundedCornerShape(raio))
            .clip(RoundedCornerShape(raio))
            .background(fundo)
            .clickable(enabled = habilitado, onClick = aoClicar)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(texto, color = corTexto, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        if (icone != null) {
            Box(Modifier.padding(start = 14.dp)) { icone() }
        }
    }
}

@Composable
fun Aviso(
    texto: String,
    modifier: Modifier = Modifier,
    acao: String? = null,
    aoAcionar: (() -> Unit)? = null,
    fundo: Color = Color(0xFFFFE3E0),
    corTexto: Color = Color(0xFF8A1C13),
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fundo).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(texto, color = corTexto, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (acao != null && aoAcionar != null) {
            Text(
                acao, color = corTexto, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = aoAcionar).padding(6.dp),
            )
        }
    }
}

@Composable
fun IndicadorPassos(passoAtual: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        PassoIcone(ativo = passoAtual >= 1) { Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        PassoLinha(passoAtual >= 2)
        PassoIcone(ativo = passoAtual >= 2) {
            Image(painterResource(if (passoAtual >= 2) R.drawable.notion_logo_branco else R.drawable.notion_logo), null, Modifier.size(38.dp))
        }
        PassoLinha(passoAtual >= 3)
        PassoIcone(ativo = passoAtual >= 3) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
    }
}

@Composable
private fun PassoIcone(ativo: Boolean, conteudo: @Composable () -> Unit) {
    Box(
        Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(if (ativo) Cores.Roxo else Color(0xFFDCDCDC)),
        contentAlignment = Alignment.Center,
    ) { conteudo() }
}

@Composable
private fun PassoLinha(ativa: Boolean) {
    Box(Modifier.padding(horizontal = 6.dp).size(width = 44.dp, height = 3.dp).background(if (ativa) Cores.RoxoClaro else Color(0xFFD0D0D0)))
}

@Composable
fun Titulo(texto: String, modifier: Modifier = Modifier, cor: Color = Color.Black, tamanho: Int = 22) {
    Text(texto, modifier, color = cor, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = tamanho.sp, textAlign = TextAlign.Center)
}
