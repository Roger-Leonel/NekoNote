package br.com.korvon.nekonote.visual.telas

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.dados.NotionEstado
import br.com.korvon.nekonote.dados.PaginaNotion
import br.com.korvon.nekonote.dados.montarArvore
import br.com.korvon.nekonote.visual.abrirAutorizacaoNotion
import br.com.korvon.nekonote.visual.componentes.ArvorePaginas
import br.com.korvon.nekonote.visual.componentes.Aviso
import br.com.korvon.nekonote.visual.componentes.BotaoCheio
import br.com.korvon.nekonote.visual.componentes.CampoPilula
import br.com.korvon.nekonote.visual.componentes.IndicadorPassos
import br.com.korvon.nekonote.visual.obterModelo
import br.com.korvon.nekonote.visual.paraUsuario
import br.com.korvon.nekonote.visual.tecladoAberto
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class EstadoCadastro(
    val passo: Int = 1,
    val email: String = "",
    val nome: String = "",
    val sobrenome: String = "",
    val salvandoNome: Boolean = false,
    val erroNome: String? = null,
    val finalizando: Boolean = false,
    val erroFinalizar: String? = null,
    val conta: Conta? = null,
    val paginas: List<PaginaNotion> = emptyList(),
    val carregandoConta: Boolean = false,
    val carregandoPaginas: Boolean = false,
    val conectando: Boolean = false,
    val aguardandoNotion: Boolean = false,
    val erroNotion: String? = null,
) {
    val notionConectado get() = conta?.notion?.conectado == true
    val ocupadoComNotion get() = conectando || aguardandoNotion || carregandoConta
    val nomeCompleto get() = "${nome.trim()} ${sobrenome.trim()}".trim()
    val nomeValido get() = nome.trim().length >= 2 && sobrenome.trim().length >= 2
}

class ModeloCadastro(private val c: ContainerApp, passoInicial: Int) : ViewModel() {
    private val _estado = MutableStateFlow(
        EstadoCadastro(passo = passoInicial.coerceIn(1, 3), email = c.auth.usuario.value?.email.orEmpty()),
    )
    val estado: StateFlow<EstadoCadastro> = _estado

    private var encerrado = false

    init {
        viewModelScope.launch {
            if (passoInicial <= 1) runCatching { c.api.desconectarNotion() }
            carregarConta()
        }
        viewModelScope.launch {
            c.retornoNotion.collect { status ->
                if (status == null) return@collect
                c.retornoNotion.value = null
                when (status) {
                    "ok" -> carregarConta()
                    "cancelado" -> _estado.update { it.copy(aguardandoNotion = false, erroNotion = "Você cancelou a autorização no Notion.") }
                    else -> _estado.update { it.copy(aguardandoNotion = false, erroNotion = "Não foi possível conectar ao Notion. Tente novamente.") }
                }
            }
        }
    }

    fun carregarConta() {
        _estado.update { it.copy(carregandoConta = true) }
        viewModelScope.launch {
            try {
                val conta = c.api.conta()
                _estado.update {
                    it.copy(
                        conta = conta, aguardandoNotion = false, carregandoConta = false,
                        erroNotion = if (conta.notion.estado == NotionEstado.RECONECTAR) "A conexão expirou. Reconecte o Notion." else it.erroNotion.takeIf { _ -> !conta.notion.conectado },
                    )
                }
                if (conta.notion.conectado) carregarPaginas()
            } catch (e: Exception) {
                _estado.update { it.copy(aguardandoNotion = false, carregandoConta = false, erroNotion = e.paraUsuario()) }
            }
        }
    }

    private fun carregarPaginas() {
        _estado.update { it.copy(carregandoPaginas = true) }
        viewModelScope.launch {
            val paginas = runCatching { c.api.paginas() }.getOrDefault(emptyList())
            _estado.update { it.copy(paginas = paginas, carregandoPaginas = false) }
        }
    }

    fun conectarNotion(abrir: (String) -> Unit) {
        if (_estado.value.ocupadoComNotion) return
        _estado.update { it.copy(conectando = true, erroNotion = null) }
        viewModelScope.launch {
            try {
                val url = c.api.iniciarConexaoNotion()
                _estado.update { it.copy(conectando = false, aguardandoNotion = true) }
                abrir(url)
            } catch (e: Exception) {
                _estado.update { it.copy(conectando = false, erroNotion = e.paraUsuario()) }
            }
        }
    }

    fun nome(v: String) = _estado.update { it.copy(nome = v, erroNome = null) }
    fun sobrenome(v: String) = _estado.update { it.copy(sobrenome = v, erroNome = null) }

    fun salvarNome() {
        val s = _estado.value
        if (s.salvandoNome) return
        if (!s.nomeValido) {
            _estado.update { it.copy(erroNome = "Informe seu nome e sobrenome.") }
            return
        }
        _estado.update { it.copy(salvandoNome = true, erroNome = null) }
        viewModelScope.launch {
            try {
                c.auth.definirNome(s.nomeCompleto)
                _estado.update { it.copy(salvandoNome = false, passo = 2) }
            } catch (e: Exception) {
                _estado.update { it.copy(salvandoNome = false, erroNome = e.paraUsuario()) }
            }
        }
    }

    fun avancar() {
        _estado.update { it.copy(passo = (it.passo + 1).coerceAtMost(3)) }
    }

    fun voltarPasso() {
        _estado.update { it.copy(passo = (it.passo - 1).coerceAtLeast(1)) }
    }

    fun sair(depois: () -> Unit) = reiniciar(depois)

    fun reiniciar(depois: () -> Unit) {
        if (encerrado) return
        encerrado = true
        val idUsuario = c.auth.usuario.value?.idUsuario
        c.escopo.launch {
            try {
                withTimeout(6_000) { c.api.excluirConta() }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            }
            idUsuario?.let { c.prefs.limparUsuario(it) }
            c.auth.sair()
        }
        depois()
    }

    fun aoSairDoApp(depois: () -> Unit) {
        val s = _estado.value
        if (s.aguardandoNotion || s.conectando || s.finalizando) return
        reiniciar(depois)
    }

    fun finalizar(depois: () -> Unit) {
        if (_estado.value.finalizando) return
        _estado.update { it.copy(finalizando = true, erroFinalizar = null) }
        viewModelScope.launch {
            try {
                c.api.finalizarCadastro()
                encerrado = true
                c.auth.usuario.value?.idUsuario?.let { c.prefs.marcarOnboarding(it) }
                depois()
            } catch (e: Exception) {
                _estado.update { it.copy(finalizando = false, erroFinalizar = e.paraUsuario()) }
            }
        }
    }
}

@Composable
fun TelaCadastro(conteiner: ContainerApp, passoInicial: Int, aoVoltarAoLogin: () -> Unit, aoConcluir: () -> Unit) {
    val modelo = obterModelo({ ModeloCadastro(it, passoInicial) }, conteiner)
    val estado by modelo.estado.collectAsState()
    val contexto = LocalContext.current

    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> if (modelo.estado.value.aguardandoNotion) modelo.carregarConta()
                Lifecycle.Event.ON_STOP -> modelo.aoSairDoApp(aoVoltarAoLogin)
                else -> {}
            }
        }
        ciclo.addObserver(obs)
        onDispose { ciclo.removeObserver(obs) }
    }

    val voltar: () -> Unit = if (estado.passo == 1) ({ modelo.sair(aoVoltarAoLogin) }) else ({ modelo.voltarPasso() })
    androidx.activity.compose.BackHandler(onBack = voltar)

    val corBotao = if (estado.passo == 3) Cores.Roxo else Cores.Laranja
    val titulo = if (estado.passo == 1) "Crie sua\nmiauconta!" else "Quase lá,\nmiauusuário!"
    val subtitulo = when (estado.passo) {
        1 -> "Como podemos te chamar?"
        2 -> "Falta conectar o Notion"
        else -> "Confira seus dados"
    }

    Box(Modifier.fillMaxSize().background(Cores.Grafite)) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 110.dp, y = (-70).dp).size(330.dp).clip(CircleShape).background(Cores.LaranjaClaro))

        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(bottomEnd = 24.dp)).background(Cores.Roxo).clickable(onClick = voltar)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White)
                    Text("voltar", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 28.dp)) {
                    Text(titulo, color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 42.sp)
                    Text(subtitulo, color = Cores.Laranja, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Cores.CinzaTela),
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(width = 60.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFBDBDBD)))
                    Spacer(Modifier.height(18.dp))
                    IndicadorPassos(estado.passo)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(1.dp).background(Color(0xFFCFCFCF)))
                    Spacer(Modifier.height(24.dp))
                    when (estado.passo) {
                        1 -> PassoNome(estado, modelo)
                        2 -> PassoNotion(estado, modelo) { url -> abrirAutorizacaoNotion(contexto, url) }
                        else -> PassoConfirmar(estado)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                val habilitado = when (estado.passo) {
                    1 -> !estado.salvandoNome
                    2 -> estado.notionConectado && !estado.carregandoConta
                    else -> !estado.finalizando
                }
                if (!tecladoAberto()) Box(
                    Modifier.fillMaxWidth().background(if (habilitado) corBotao else corBotao.copy(alpha = 0.45f)).navigationBarsPadding()
                        .clickable(enabled = habilitado) {
                            when (estado.passo) {
                                1 -> modelo.salvarNome()
                                2 -> modelo.avancar()
                                else -> modelo.finalizar(aoConcluir)
                            }
                        }.padding(vertical = 22.dp, horizontal = 24.dp),
                ) {
                    if (estado.salvandoNome || estado.finalizando) {
                        CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (estado.passo == 3) "Finalizar" else "Próximo",
                            color = Color.White, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        if (estado.passo < 3) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}

@Composable
private fun PassoNome(estado: EstadoCadastro, modelo: ModeloCadastro) {
    Text("Seus dados", fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
    Spacer(Modifier.height(10.dp))
    Text(
        "Usamos seu nome para personalizar o app.",
        fontSize = 14.sp, color = Color(0xFF444444), textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    CampoPilula(
        estado.nome, modelo::nome, "nome", Icons.Filled.Person, teclado = KeyboardType.Text,
        fundo = Cores.CinzaCampo, raio = 8.dp, sombra = false, erro = estado.erroNome != null && estado.nome.trim().length < 2,
    )
    Spacer(Modifier.height(14.dp))
    CampoPilula(
        estado.sobrenome, modelo::sobrenome, "sobrenome", Icons.Filled.Person, acaoIme = ImeAction.Done,
        fundo = Cores.CinzaCampo, raio = 8.dp, sombra = false, erro = estado.erroNome != null && estado.sobrenome.trim().length < 2,
    )
    estado.erroNome?.let { Aviso(it, Modifier.padding(top = 12.dp)) }
    Spacer(Modifier.height(14.dp))
    CampoPilula(
        estado.email, {}, "e-mail", Icons.Filled.Email,
        fundo = Cores.CinzaCampo, raio = 8.dp, sombra = false, habilitado = false,
    )
}

@Composable
private fun PassoNotion(estado: EstadoCadastro, modelo: ModeloCadastro, abrir: (String) -> Unit) {
    Text("Integração Notion", fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
    Spacer(Modifier.height(10.dp))
    Text(
        "Autorize o NekoNote no Notion e escolha as páginas que ele pode usar.",
        fontSize = 14.sp, color = Color(0xFF444444), textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    val conta = estado.conta
    if (estado.notionConectado && conta != null) {
        CartaoEspaco(conta)
        Spacer(Modifier.height(12.dp))
        Text(
            "Trocar de espaço de trabalho ou liberar outras páginas", fontSize = 13.sp,
            color = if (estado.ocupadoComNotion) Color(0xFF9A9A9A) else Cores.Roxo, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !estado.ocupadoComNotion) { modelo.conectarNotion(abrir) }.padding(8.dp),
        )
        if (estado.ocupadoComNotion) CarregandoNotion(estado)
    } else {
        val ocupado = estado.ocupadoComNotion
        BotaoCheio(
            texto = when {
                estado.conectando -> "Abrindo…"
                estado.aguardandoNotion -> "Aguardando autorização…"
                estado.carregandoConta -> "Finalizando conexão…"
                else -> "Conectar com Notion"
            },
            aoClicar = { modelo.conectarNotion(abrir) },
            cor = Color.Black,
            habilitado = !ocupado,
            modifier = Modifier.fillMaxWidth(),
            icone = {
                if (ocupado) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
                else Image(painterResource(R.drawable.notion_logo), null, Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            },
        )
        if (ocupado) CarregandoNotion(estado)
    }
    estado.erroNotion?.let {
        Aviso(it, Modifier.padding(top = 14.dp), acao = "Tentar de novo", aoAcionar = { modelo.conectarNotion(abrir) })
    }
}

@Composable
private fun CarregandoNotion(estado: EstadoCadastro) {
    Spacer(Modifier.height(10.dp))
    Text(
        if (estado.aguardandoNotion && !estado.carregandoConta) "Conclua a autorização no navegador e volte ao app."
        else "Conectando ao Notion, isso pode levar alguns segundos…",
        fontSize = 13.sp, color = Color(0xFF555555), textAlign = TextAlign.Center,
    )
}

@Composable
internal fun CartaoEspaco(conta: Conta, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Cores.CinzaTela), contentAlignment = Alignment.Center) {
            val icone = conta.notion.espacoIcone
            if (icone != null && icone.startsWith("http")) AsyncImage(icone, null, Modifier.size(48.dp))
            else Text(icone ?: "N", fontSize = 26.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(conta.notion.espacoNome ?: "Espaço de trabalho conectado", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.Black)
            val n = conta.notion.paginasLiberadas
            Text(if (n == 1) "1 página liberada" else "$n páginas liberadas", fontSize = 13.sp, color = Color(0xFF555555))
        }
        Box(Modifier.size(28.dp).clip(CircleShape).background(Cores.Verde), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PassoConfirmar(estado: EstadoCadastro) {
    Text("Confirme os dados", fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.Black)
    Spacer(Modifier.height(18.dp))
    LinhaDado("nome", estado.nomeCompleto.ifBlank { "—" })
    Spacer(Modifier.height(10.dp))
    LinhaDado("e-mail", estado.email.ifBlank { "—" })
    estado.erroFinalizar?.let { Aviso(it, Modifier.padding(top = 14.dp)) }
    if (estado.paginas.isNotEmpty()) {
        val arvore = remember(estado.paginas) { montarArvore(estado.paginas) }
        Spacer(Modifier.height(20.dp))
        Text(
            if (estado.paginas.size == 1) "Página liberada" else "Páginas liberadas",
            fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ArvorePaginas(arvore, Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp), expandirTudo = false)
    } else if (estado.carregandoPaginas) {
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(6.dp))
        Text("Carregando páginas liberadas…", fontSize = 13.sp, color = Color(0xFF555555))
    }
}

@Composable
private fun LinhaDado(rotulo: String, valor: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(rotulo, modifier = Modifier.weight(0.32f), textAlign = TextAlign.Center, fontSize = 16.sp, color = Color.Black)
        Text(
            valor, fontSize = 15.sp, color = Color.Black, maxLines = 1,
            modifier = Modifier.weight(0.68f).clip(RoundedCornerShape(8.dp)).background(Cores.CinzaCampo).padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}
