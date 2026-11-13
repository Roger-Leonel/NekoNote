package br.com.korvon.nekonote.visual.telas

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.dados.MAX_FOTOS
import br.com.korvon.nekonote.dados.fotos.Foto
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun TelaSelecao(conteiner: ContainerApp, aoVoltar: () -> Unit, aoAvancar: () -> Unit) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    val sessao = conteiner.fotos
    val fotos by sessao.fotos.collectAsState()
    val ocupadas by sessao.ocupadas.collectAsState()
    val selecionadas = fotos.filter { it.selecionada }
    var confirmarIlegiveis by remember { mutableStateOf(false) }
    var ampliada by remember { mutableStateOf<String?>(null) }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        escopo.launch {
            val resultado = sessao.adicionarDaGaleria(uris)
            if (resultado.falhas > 0) {
                Toast.makeText(contexto, "${resultado.falhas} foto(s) não puderam ser abertas.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun abrirGaleria() {
        galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun alternar(id: String) {
        if (!sessao.alternar(id)) Toast.makeText(contexto, "Você pode marcar até $MAX_FOTOS fotos para enviar.", Toast.LENGTH_SHORT).show()
    }

    fun proximo() {
        when {
            ocupadas > 0 -> Toast.makeText(contexto, "Aguarde, as fotos ainda estão sendo preparadas.", Toast.LENGTH_SHORT).show()
            selecionadas.isEmpty() -> Toast.makeText(contexto, "Selecione ao menos uma foto.", Toast.LENGTH_SHORT).show()
            selecionadas.any { it.legivel == false } -> confirmarIlegiveis = true
            else -> aoAvancar()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)).background(Cores.Grafite)
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = aoVoltar).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Text("Voltar", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SELECIONE SUAS FOTOS PARA ANÁLISE", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    Text("${selecionadas.size}", color = Cores.Laranja, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
                    Text("/$MAX_FOTOS", color = Color.White.copy(alpha = 0.6f), fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 2.dp, top = 14.dp))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (fotos.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nenhuma foto ainda", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(
                            "Fotografe suas páginas ou abra a galeria. Você marca até $MAX_FOTOS fotos para enviar.",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val completo = selecionadas.size == minOf(fotos.size, MAX_FOTOS)
                        SeletorTodas(
                            todas = completo, limite = fotos.size > MAX_FOTOS,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 10.dp),
                        ) { sessao.selecionarTodas(!completo) }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            items(fotos, key = { it.id }) { foto ->
                                val ordem = if (foto.selecionada) selecionadas.indexOfFirst { it.id == foto.id } + 1 else null
                                Miniatura(foto, ordem, aoAlternar = { alternar(foto.id) }, aoAmpliar = { ampliada = foto.id })
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Cores.Laranja).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Cores.Grafite).clickable(onClick = ::abrirGaleria).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Abrir\nGaleria", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = ::proximo).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Próximo", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }

        if (ocupadas > 0) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cores.Laranja)
                    Text("Preparando suas fotos...", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }

    fotos.firstOrNull { it.id == ampliada }?.let { foto ->
        VisualizadorFoto(
            foto,
            aoFechar = { ampliada = null },
            aoRemover = {
                sessao.remover(foto.id)
                ampliada = null
            },
        )
    }

    if (confirmarIlegiveis) {
        val n = selecionadas.count { it.legivel == false }
        AlertDialog(
            onDismissRequest = { confirmarIlegiveis = false },
            title = { Text("Fotos com pouco texto") },
            text = { Text(if (n == 1) "1 foto parece ter pouco texto legível. Enviar mesmo assim?" else "$n fotos parecem ter pouco texto legível. Enviar mesmo assim?") },
            confirmButton = { TextButton({ confirmarIlegiveis = false; aoAvancar() }) { Text("Enviar mesmo assim") } },
            dismissButton = { TextButton({ confirmarIlegiveis = false }) { Text("Revisar") } },
        )
    }
}

@Composable
private fun SeletorTodas(todas: Boolean, limite: Boolean, modifier: Modifier = Modifier, aoClicar: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background(if (todas) Cores.Laranja else Color.White.copy(alpha = 0.1f))
            .border(1.5.dp, if (todas) Cores.Laranja else Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = aoClicar)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .background(if (todas) Color.White else Color.Transparent)
                .border(1.5.dp, Color.White, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (todas) Icon(Icons.Filled.Check, null, tint = Cores.Laranja, modifier = Modifier.size(16.dp))
        }
        Text(
            if (!todas) "Selecionar todas" else if (limite) "Máximo selecionado" else "Todas selecionadas",
            color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
    }
}

@Composable
private fun Miniatura(foto: Foto, ordem: Int?, aoAlternar: () -> Unit, aoAmpliar: () -> Unit) {
    val moldura = if (foto.selecionada) Modifier.border(3.dp, Cores.Laranja) else Modifier
    Box(Modifier.aspectRatio(0.72f).then(moldura).background(Color.White).clickable(onClick = aoAmpliar)) {
        AsyncImage(foto.arquivo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (!foto.selecionada) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Box(
            Modifier.align(Alignment.TopStart).padding(5.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)).clickable(onClick = aoAmpliar),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Delete, "Deletar foto", tint = Color.White, modifier = Modifier.size(16.dp)) }
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(RoundedCornerShape(5.dp))
                .background(if (ordem != null) Cores.Laranja else Color.White.copy(alpha = 0.85f))
                .border(1.5.dp, if (ordem != null) Color.White else Color.Black, RoundedCornerShape(5.dp))
                .clickable(onClick = aoAlternar),
            contentAlignment = Alignment.Center,
        ) {
            if (ordem != null) Text("$ordem", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
        if (foto.legivel == null) {
            CircularProgressIndicator(
                color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp),
            )
        }
        if (foto.legivel == false) {
            Text(
                "pouco texto", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Cores.Vermelho)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun VisualizadorFoto(foto: Foto, aoFechar: () -> Unit, aoRemover: () -> Unit) {
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = aoFechar)) {
                AsyncImage(foto.arquivo, "Foto ampliada", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                if (foto.legivel == false) {
                    Text(
                        "pouco texto detectado", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp).clip(RoundedCornerShape(8.dp)).background(Cores.Vermelho)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotaoVisualizador("Deletar", Cores.Vermelho, Modifier.weight(1f), aoRemover)
                BotaoVisualizador("Fechar", Cores.Grafite, Modifier.weight(1f), aoFechar)
            }
        }
    }
}

@Composable
private fun BotaoVisualizador(texto: String, cor: Color, modifier: Modifier, aoClicar: () -> Unit) {
    Text(
        texto, color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(cor).clickable(onClick = aoClicar).padding(vertical = 14.dp),
    )
}
