package br.com.korvon.nekonote.visual.telas

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.dados.EventoProcessamento
import br.com.korvon.nekonote.dados.PedidoProcessamento
import br.com.korvon.nekonote.visual.abrirUrl
import br.com.korvon.nekonote.visual.obterModelo
import br.com.korvon.nekonote.visual.componentes.AnimacaoConcluido
import br.com.korvon.nekonote.visual.componentes.GatoGirando
import br.com.korvon.nekonote.visual.componentes.Marca
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EstadoProcessando {
    data class Enviando(val percentual: Int = 0, val mensagem: String = "Carregando suas miaufotos...") : EstadoProcessando
    data class Reconectando(val tentativa: Int) : EstadoProcessando
    data class Sucesso(val url: String?, val mensagem: String, val itens: List<String> = emptyList()) : EstadoProcessando
    data class Erro(val mensagem: String) : EstadoProcessando
}

class ModeloProcessando(private val c: ContainerApp) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoProcessando>(EstadoProcessando.Enviando())
    val estado: StateFlow<EstadoProcessando> = _estado
    private var job: Job? = null
    private var itensConcluidos: List<String> = emptyList()

    init {
        iniciar()
    }

    fun iniciar() {
        val pedido = c.pedido
        if (pedido == null) {
            _estado.value = EstadoProcessando.Erro("Nada para enviar. Volte e configure a página.")
            return
        }
        itensConcluidos = itensDoPedido(pedido)
        job?.cancel()
        _estado.value = EstadoProcessando.Enviando()
        job = viewModelScope.launch {
            c.processamento.processar(pedido).collect { ev ->
                _estado.update {
                    when (ev) {
                        is EventoProcessamento.Inicio -> EstadoProcessando.Enviando(0, "Enviando ${ev.total} foto(s)...")
                        is EventoProcessamento.Progresso -> EstadoProcessando.Enviando(ev.percentual, ev.mensagem)
                        is EventoProcessamento.Enviando -> EstadoProcessando.Enviando(ev.percentual, ev.mensagem)
                        is EventoProcessamento.Reconectando -> EstadoProcessando.Reconectando(ev.tentativa)
                        is EventoProcessamento.Resultado -> EstadoProcessando.Sucesso(ev.urlPagina, ev.mensagem, itensConcluidos)
                        is EventoProcessamento.Erro -> EstadoProcessando.Erro(ev.mensagem)
                    }
                }
                if (ev is EventoProcessamento.Resultado) {
                    c.fotos.limpar()
                    c.pedido = null
                }
            }
        }
    }

    private fun itensDoPedido(p: PedidoProcessamento): List<String> = buildList {
        val fotos = p.imagens.size
        add(if (fotos == 1) "1 foto lida" else "$fotos fotos lidas")
        if (p.modalidades.topicos) add("Tópicos adicionados")
        if (p.modalidades.resumo) add("Resumo adicionado")
        if (p.modalidades.assunto) add("Assuntos adicionados")
        if (p.modalidades.apresentacao) add("Apresentação adicionada")
    }

    fun cancelar() {
        job?.cancel()
    }

    override fun onCleared() {
        job?.cancel()
    }
}

@Composable
fun TelaProcessando(conteiner: ContainerApp, aoVoltar: () -> Unit, aoNovaCaptura: () -> Unit) {
    val modelo = obterModelo({ ModeloProcessando(it) }, conteiner)
    val estado by modelo.estado.collectAsState()
    val contexto = LocalContext.current

    BackHandler {
        modelo.cancelar()
        aoVoltar()
    }

    Box(Modifier.fillMaxSize().background(Cores.Laranja).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = estado) {
                is EstadoProcessando.Enviando -> {
                    GatoProgresso(s.percentual)
                    Spacer(Modifier.height(24.dp))
                    Texto("CARREGANDO SUAS MIAUFOTOS...")
                    Spacer(Modifier.height(6.dp))
                    Text(s.mensagem, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    BotaoContorno("Cancelar") { modelo.cancelar(); aoVoltar() }
                }
                is EstadoProcessando.Reconectando -> {
                    GatoProgresso(null)
                    Spacer(Modifier.height(24.dp))
                    Texto("RECONECTANDO...")
                    Text("Tentativa ${s.tentativa} de 3", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                    Spacer(Modifier.height(28.dp))
                    BotaoContorno("Cancelar") { modelo.cancelar(); aoVoltar() }
                }
                is EstadoProcessando.Sucesso -> {
                    AnimacaoConcluido(s.itens)
                    Spacer(Modifier.height(24.dp))
                    Texto(if (s.url != null) "MIAU! SUA NOTA ESTÁ PRONTA" else "MIAU! TEXTOS ENVIADOS")
                    Text(s.mensagem, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    if (s.url != null) BotaoBranco("Abrir no Notion") { abrirUrl(contexto, s.url) }
                    Spacer(Modifier.height(12.dp))
                    BotaoContorno("Nova captura", aoNovaCaptura)
                }
                is EstadoProcessando.Erro -> {
                    Image(painterResource(R.drawable.gato_carregando), null, Modifier.size(180.dp).graphicsLayer { alpha = 0.7f })
                    Spacer(Modifier.height(16.dp))
                    Texto("NÃO DEU CERTO")
                    Text(s.mensagem, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    BotaoBranco("Tentar novamente") { modelo.iniciar() }
                    Spacer(Modifier.height(12.dp))
                    BotaoContorno("Voltar") { aoVoltar() }
                }
            }
        }
        Marca(Color.White, tamanhoFonte = 26, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

@Composable
private fun GatoProgresso(percentual: Int?) {
    val animado by animateIntAsState(percentual ?: 0, tween(500), label = "pct")
    Box(Modifier.size(330.dp), contentAlignment = Alignment.Center) {
        GatoGirando(Modifier.fillMaxSize())
        if (percentual != null) {
            Text(
                "$animado%", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp,
            )
        }
    }
}

@Composable
private fun Texto(t: String) {
    Text(t, color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
}

@Composable
private fun BotaoBranco(texto: String, aoClicar: () -> Unit) {
    Text(
        texto, color = Cores.Laranja, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(onClick = aoClicar).padding(vertical = 16.dp),
    )
}

@Composable
private fun BotaoContorno(texto: String, aoClicar: () -> Unit) {
    Text(
        texto, color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = aoClicar).padding(vertical = 14.dp),
    )
}
