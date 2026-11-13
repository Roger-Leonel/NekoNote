package br.com.korvon.nekonote.visual.componentes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.dados.NoPagina
import br.com.korvon.nekonote.dados.PaginaNotion
import br.com.korvon.nekonote.dados.montarArvore
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import coil3.compose.AsyncImage

private const val LOTE_DE_FILHOS = 50

private const val NIVEIS_VISIVEIS = 2

@Composable
fun DropdownPaginas(paginas: List<PaginaNotion>, modifier: Modifier = Modifier) {
    var aberto by rememberSaveable { mutableStateOf(false) }
    val arvore = remember(paginas) { montarArvore(paginas) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().clickable { aberto = !aberto }.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.notion_logo), "Notion", Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
            Text(
                if (paginas.size == 1) "1 página" else "${paginas.size} páginas",
                fontFamily = Maven, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.Black,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Seta(aberto, Color.Black)
        }
        AnimatedVisibility(aberto) {
            Box(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp)) {
                if (arvore.isEmpty()) {
                    Text("Nenhuma página liberada.", fontSize = 13.sp, color = Color(0xFF666666), modifier = Modifier.padding(8.dp))
                } else {
                    ArvorePaginas(arvore)
                }
            }
        }
    }
}

@Composable
fun ArvorePaginas(nos: List<NoPagina>, modifier: Modifier = Modifier, expandirTudo: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        nos.forEach { NoDaArvore(it, nivel = 0, expandirTudo = expandirTudo) }
    }
}

@Composable
private fun NoDaArvore(no: NoPagina, nivel: Int, expandirTudo: Boolean) {
    var aberto by rememberSaveable(no.pagina.id) { mutableStateOf(expandirTudo) }
    val temFilhos = no.filhos.isNotEmpty() && nivel + 1 < NIVEIS_VISIVEIS
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .then(if (temFilhos) Modifier.clickable { aberto = !aberto } else Modifier)
                .padding(start = (nivel * 18).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (temFilhos) Seta(aberto, Cores.Roxo) else Box(Modifier.size(24.dp))
            IconePagina(no.pagina.icone)
            Text(
                no.pagina.titulo,
                fontSize = 14.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (nivel == 0) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            val subs = no.filhos.size
            if (subs > 0) {
                Text(
                    if (subs == 1) "1 página" else "$subs páginas",
                    fontSize = 11.sp, color = Color(0xFF777777), modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (temFilhos) {
            AnimatedVisibility(aberto) {
                var visiveis by rememberSaveable(no.pagina.id, "visiveis") { mutableStateOf(LOTE_DE_FILHOS) }
                Column(Modifier.fillMaxWidth()) {
                    no.filhos.take(visiveis).forEach { NoDaArvore(it, nivel + 1, expandirTudo) }
                    if (no.filhos.size > visiveis) {
                        Text(
                            "Mostrar mais (${no.filhos.size - visiveis} restantes)",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cores.Roxo,
                            modifier = Modifier.padding(start = ((nivel + 1) * 18 + 24).dp, top = 6.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(8.dp)).clickable { visiveis += LOTE_DE_FILHOS }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Seta(aberta: Boolean, cor: Color) {
    Icon(
        Icons.Filled.KeyboardArrowDown, contentDescription = if (aberta) "Recolher" else "Expandir", tint = cor,
        modifier = Modifier.size(24.dp).rotate(if (aberta) 0f else -90f),
    )
}

@Composable
fun IconePagina(icone: String?, modifier: Modifier = Modifier, tamanho: Dp = 20.dp) {
    Box(modifier.size(tamanho), contentAlignment = Alignment.Center) {
        when {
            icone.isNullOrBlank() ->
                Icon(painterResource(R.drawable.ic_folha_papel), null, tint = Color(0xFF6B6B6B), modifier = Modifier.size(tamanho * 0.9f))
            icone.startsWith("http") -> AsyncImage(icone, null, Modifier.size(tamanho).clip(RoundedCornerShape(3.dp)))
            else -> Text(icone, fontSize = (tamanho.value * 0.8f).sp, maxLines = 1)
        }
    }
}
