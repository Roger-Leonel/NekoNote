package br.com.korvon.nekonote.visual.telas

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.R
import br.com.korvon.nekonote.dados.Conta
import br.com.korvon.nekonote.dados.NotionEstado
import br.com.korvon.nekonote.dados.PaginaNotion
import br.com.korvon.nekonote.visual.abrirAutorizacaoNotion
import br.com.korvon.nekonote.visual.componentes.AvatarGato
import br.com.korvon.nekonote.visual.componentes.Aviso
import br.com.korvon.nekonote.visual.componentes.BotaoCheio
import br.com.korvon.nekonote.visual.componentes.DropdownPaginas
import br.com.korvon.nekonote.visual.obterModelo
import br.com.korvon.nekonote.visual.paraUsuario
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoConfiguracoes(
    val carregando: Boolean = true,
    val conta: Conta? = null,
    val paginas: List<PaginaNotion> = emptyList(),
    val trabalhandoNotion: Boolean = false,
    val aguardandoNotion: Boolean = false,
    val sucesso: String? = null,
    val conexaoRemovida: Boolean = false,
    val erro: String? = null,
)

class ModeloConfiguracoes(private val c: ContainerApp) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoConfiguracoes())
    val estado: StateFlow<EstadoConfiguracoes> = _estado
    val usuario get() = c.auth.usuario.value

    init {
        carregar()
        viewModelScope.launch {
            c.retornoNotion.collect { status ->
                if (status == null) return@collect
                c.retornoNotion.value = null
                when (status) {
                    "ok" -> { _estado.update { it.copy(sucesso = "Notion conectado!", erro = null) }; carregar() }
                    "cancelado" -> _estado.update { it.copy(aguardandoNotion = false, erro = "Você cancelou a autorização no Notion.") }
                    else -> _estado.update { it.copy(aguardandoNotion = false, erro = "Não foi possível conectar ao Notion.") }
                }
            }
        }
    }

    fun carregar() {
        viewModelScope.launch {
            try {
                val conta = c.api.conta()
                val paginas = if (conta.notion.conectado) runCatching { c.api.paginas() }.getOrDefault(emptyList()) else emptyList()
                _estado.update { it.copy(carregando = false, conta = conta, paginas = paginas, aguardandoNotion = false) }
            } catch (e: Exception) {
                _estado.update { it.copy(carregando = false, aguardandoNotion = false, erro = e.paraUsuario()) }
            }
        }
    }

    fun conectarNotion(abrir: (String) -> Unit) {
        _estado.update { it.copy(trabalhandoNotion = true, erro = null, sucesso = null) }
        viewModelScope.launch {
            try {
                val url = c.api.iniciarConexaoNotion()
                _estado.update { it.copy(trabalhandoNotion = false, aguardandoNotion = true) }
                abrir(url)
            } catch (e: Exception) {
                _estado.update { it.copy(trabalhandoNotion = false, erro = e.paraUsuario()) }
            }
        }
    }

    fun fecharPopup() {
        _estado.update { it.copy(conexaoRemovida = false) }
    }

    fun desconectarNotion() {
        _estado.update { it.copy(trabalhandoNotion = true, erro = null, sucesso = null, conexaoRemovida = false) }
        viewModelScope.launch {
            try {
                c.api.desconectarNotion()
                _estado.update { it.copy(trabalhandoNotion = false, conexaoRemovida = true) }
                carregar()
            } catch (e: Exception) {
                _estado.update { it.copy(trabalhandoNotion = false, erro = e.paraUsuario()) }
            }
        }
    }
}

@Composable
fun TelaConfiguracoes(conteiner: ContainerApp, aoVoltar: () -> Unit, aoAbrirSairConta: () -> Unit) {
    val modelo = obterModelo({ ModeloConfiguracoes(it) }, conteiner)
    val estado by modelo.estado.collectAsState()
    val contexto = LocalContext.current
    var confirmarDesconexao by remember { mutableStateOf(false) }

    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo, estado.aguardandoNotion) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME && estado.aguardandoNotion) modelo.carregar() }
        ciclo.addObserver(obs)
        onDispose { ciclo.removeObserver(obs) }
    }

    val usuario = modelo.usuario
    val abrir: (String) -> Unit = { url -> abrirAutorizacaoNotion(contexto, url) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voltar", tint = Color.Black,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = aoVoltar).padding(6.dp),
                )
                Icon(Icons.Filled.Person, null, tint = Color.Black, modifier = Modifier.size(28.dp))
                Text("  MINHA CONTA", fontSize = 16.sp, color = Color.Black, fontFamily = Maven, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_sair), "Sair da conta", tint = Cores.Vermelho,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = aoAbrirSairConta).padding(10.dp),
                )
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                AvatarGato(124.dp)
                Text(usuario?.nome.orEmpty(), fontSize = 22.sp, color = Color.Black, modifier = Modifier.padding(top = 10.dp))
                Text(usuario?.email.orEmpty(), fontSize = 13.sp, color = Color(0xFF777777), modifier = Modifier.padding(top = 2.dp))
                Box(Modifier.padding(top = 10.dp).size(width = 90.dp, height = 3.dp).background(Color(0xFFD9D9D9)))

                Spacer(Modifier.height(24.dp))
                Rotulo("Conexão com o Notion")
                SecaoNotion(estado, modelo, usuario?.primeiroNome.orEmpty(), abrir, aoDesconectar = { confirmarDesconexao = true })

                estado.sucesso?.let { Aviso(it, Modifier.padding(top = 16.dp), fundo = Color(0xFFDFF5E1), corTexto = Color(0xFF1B5E20)) }
                estado.erro?.let { Aviso(it, Modifier.padding(top = 16.dp)) }

                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }

        PopupConexaoRemovida(estado.conexaoRemovida, aoFechar = modelo::fecharPopup, modifier = Modifier.align(Alignment.TopCenter))
    }

    if (confirmarDesconexao) {
        AlertDialog(
            onDismissRequest = { confirmarDesconexao = false },
            title = { Text("Desconectar o Notion?") },
            text = { Text("O NekoNote apaga o acesso guardado e deixa de criar notas até você reconectar. Para remover também no Notion, vá em Configurações → Conexões.") },
            confirmButton = { TextButton({ confirmarDesconexao = false; modelo.desconectarNotion() }) { Text("Desconectar") } },
            dismissButton = { TextButton({ confirmarDesconexao = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Rotulo(texto: String) {
    Text(texto, fontSize = 18.sp, color = Color.Black, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@Composable
private fun SecaoNotion(estado: EstadoConfiguracoes, modelo: ModeloConfiguracoes, nome: String, abrir: (String) -> Unit, aoDesconectar: () -> Unit) {
    val conta = estado.conta
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF3F3F3)).padding(14.dp)) {
        when {
            estado.carregando -> Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            conta == null -> Text("Não foi possível carregar a conexão.", color = Color(0xFF555555))
            conta.notion.estado == NotionEstado.CONECTADO -> {
                CartaoNotionConta(conta, nome, habilitado = !estado.trabalhandoNotion, aoDesconectar = aoDesconectar)
                if (estado.paginas.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    DropdownPaginas(estado.paginas)
                }
            }
            else -> {
                val reconectar = conta.notion.estado == NotionEstado.RECONECTAR
                if (reconectar) {
                    Text(
                        "A conexão com o Notion expirou. Reconecte para continuar criando notas.",
                        fontSize = 14.sp, color = Color(0xFF444444), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                BotaoNotion(
                    if (reconectar) "Reconectar Notion" else "Conectar com Notion",
                    habilitado = !estado.trabalhandoNotion, aoClicar = { modelo.conectarNotion(abrir) },
                )
            }
        }
    }
}

@Composable
private fun CartaoNotionConta(conta: Conta, nome: String, habilitado: Boolean, aoDesconectar: () -> Unit) {
    val transicao = rememberInfiniteTransition(label = "borda")
    val angulo by transicao.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "angulo",
    )
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .drawBehind {
                drawRect(Cores.Verde.copy(alpha = 0.3f))
                val lado = maxOf(size.width, size.height) * 1.6f
                rotate(angulo, pivot = center) {
                    drawRect(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            0.93f to Color(0xFF2EBF4F),
                            0.985f to Cores.Verde,
                            1f to Color.Transparent,
                            center = center,
                        ),
                        topLeft = Offset(center.x - lado / 2, center.y - lado / 2),
                        size = Size(lado, lado),
                    )
                }
            }
            .padding(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color.White).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Cores.CinzaTela), contentAlignment = Alignment.Center) {
                val icone = conta.notion.espacoIcone
                if (icone != null && icone.startsWith("http")) AsyncImage(icone, null, Modifier.size(48.dp))
                else Text(icone ?: "N", fontSize = 26.sp)
            }
            Text(
                nome.ifBlank { "Conta do Notion" }, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (habilitado) Cores.Verde else Cores.Verde.copy(alpha = 0.4f))
                    .clickable(enabled = habilitado, onClick = aoDesconectar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_desconectar), "Desconectar do Notion", tint = Color.Black, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun BotaoNotion(texto: String, habilitado: Boolean, aoClicar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (habilitado) Color.Black else Color.Black.copy(alpha = 0.4f))
            .clickable(enabled = habilitado, onClick = aoClicar).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.notion_logo), null, Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
        Text(texto, color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PopupConexaoRemovida(visivel: Boolean, aoFechar: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(visivel) {
        if (visivel) {
            delay(4000)
            aoFechar()
        }
    }
    AnimatedVisibility(
        visible = visivel,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Column(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Cores.Vermelho)
                .clickable(onClick = aoFechar).padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text("Conexão com o Notion removida", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Para remover o acesso também no Notion, vá em Configurações → Conexões.",
                color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
