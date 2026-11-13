package br.com.korvon.nekonote.visual.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.dados.Modalidades
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.dados.NotionEstado
import br.com.korvon.nekonote.dados.NoPagina
import br.com.korvon.nekonote.dados.PaginaNotion
import br.com.korvon.nekonote.dados.Temperamento
import br.com.korvon.nekonote.dados.montarArvore
import br.com.korvon.nekonote.dados.PedidoProcessamento
import br.com.korvon.nekonote.visual.componentes.Aviso
import br.com.korvon.nekonote.visual.obterModelo
import br.com.korvon.nekonote.visual.paraUsuario
import br.com.korvon.nekonote.visual.tecladoAberto
import br.com.korvon.nekonote.visual.componentes.IconePagina
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoConfigurar(
    val carregando: Boolean = true,
    val paginas: List<PaginaNotion> = emptyList(),
    val erro: String? = null,
    val precisaConectar: Boolean = false,
    val pagina: PaginaNotion? = null,
    val modalidades: Modalidades = Modalidades(),
    val temperamento: Temperamento? = null,
) {
    val podeConcluir get() = !carregando && pagina != null && modalidades.algumaLigada && !precisaConectar
}

class ModeloConfigurar(private val c: ContainerApp) : ViewModel() {
    private val idUsuario get() = c.auth.usuario.value?.idUsuario.orEmpty()
    private val _estado = MutableStateFlow(EstadoConfigurar())
    val estado: StateFlow<EstadoConfigurar> = _estado

    init {
        carregar()
    }

    fun carregar() {
        _estado.update { it.copy(carregando = true, erro = null, precisaConectar = false) }
        viewModelScope.launch {
            try {
                val conta = c.api.conta()
                if (conta.notion.estado != NotionEstado.CONECTADO) {
                    _estado.update { it.copy(carregando = false, precisaConectar = true, erro = "Conecte o Notion para escolher onde salvar a nota.") }
                    return@launch
                }
                val paginas = c.api.paginas()
                val salva = c.prefs.ultimaPagina(idUsuario)
                _estado.update { s ->
                    s.copy(
                        carregando = false, paginas = paginas,
                        pagina = s.pagina?.takeIf { p -> paginas.any { it.id == p.id } } ?: paginas.firstOrNull { it.id == salva } ?: paginas.firstOrNull(),
                        erro = if (paginas.isEmpty()) "Nenhuma página liberada. Reconecte o Notion e escolha ao menos uma página." else null,
                        precisaConectar = paginas.isEmpty(),
                    )
                }
            } catch (e: Exception) {
                val reconectar = (e as? ExcecaoNeko)?.tipo == ExcecaoNeko.Tipo.NOTION_RECONECTAR
                _estado.update { it.copy(carregando = false, erro = e.paraUsuario(), precisaConectar = reconectar) }
            }
        }
    }

    fun escolherPagina(p: PaginaNotion) = _estado.update { it.copy(pagina = p) }
    fun modalidades(m: Modalidades) = _estado.update { it.copy(modalidades = m) }
    fun temperamento(t: Temperamento?) = _estado.update { it.copy(temperamento = t) }

    fun concluir(): Boolean {
        val s = _estado.value
        if (!s.podeConcluir) return false
        val imagens = c.fotos.selecionadas.map { it.arquivo }
        if (imagens.isEmpty()) return false
        c.prefs.salvarUltimaPagina(idUsuario, s.pagina!!.id)
        c.pedido = PedidoProcessamento(s.pagina.id, s.modalidades, imagens, s.temperamento)
        return true
    }
}

@Composable
fun TelaConfigurar(conteiner: ContainerApp, aoVoltar: () -> Unit, aoAbrirConfiguracoes: () -> Unit, aoConcluir: () -> Unit) {
    val modelo = obterModelo({ ModeloConfigurar(it) }, conteiner)
    val estado by modelo.estado.collectAsState()
    var escolhendo by remember { mutableStateOf(false) }

    val formaCartao = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(Modifier.fillMaxSize().background(Cores.Laranja).statusBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Column(Modifier.fillMaxSize().shadow(8.dp, formaCartao).clip(formaCartao).background(Color.White)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Box(Modifier.align(Alignment.Center).size(width = 60.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFC9C9C9)))
                    Icon(
                        Icons.Filled.Settings, "Configurações", tint = Color(0xFF444444),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(top = 14.dp).size(36.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = aoAbrirConfiguracoes).padding(4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("Próximo passo", fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = Color.Black)
                Text("configure sua página", fontFamily = Maven, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Color.Black)
                Spacer(Modifier.height(24.dp))

                Text("Página de inserção", fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(50)).background(Cores.CinzaCampo)
                        .clickable(enabled = estado.paginas.isNotEmpty()) { escolhendo = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (estado.carregando) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                        Text("  carregando…", fontSize = 15.sp, color = Color.Black, modifier = Modifier.weight(1f))
                    } else {
                        estado.pagina?.let { IconePagina(it.icone, tamanho = 22.dp) }
                        Text(
                            estado.pagina?.titulo ?: "nenhuma página",
                            fontSize = 15.sp, color = Color.Black, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                        )
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.Black)
                }
                estado.erro?.let {
                    Aviso(
                        it, Modifier.padding(top = 12.dp),
                        acao = if (estado.precisaConectar) "Conectar" else "Tentar de novo",
                        aoAcionar = if (estado.precisaConectar) aoAbrirConfiguracoes else modelo::carregar,
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "O título e o emoji da página são escolhidos pela IA.",
                    fontFamily = Maven, fontSize = 14.sp, color = Color(0xFF8A8A8A), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(0.9f).height(1.dp).background(Color(0xFFDDDDDD)))
                Spacer(Modifier.height(12.dp))
                Text("Personalize sua experiência", fontFamily = Maven, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.Black)
                Spacer(Modifier.height(12.dp))

                SecaoTitulo("Geral")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFAFAFA)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val m = estado.modalidades
                    Opcao("Tópicos", "Organiza o texto só em tópicos", m.topicos) { modelo.modalidades(m.copy(topicos = it)) }
                    Opcao("Resumo", "Resumir todo o texto", m.resumo) { modelo.modalidades(m.copy(resumo = it)) }
                    Opcao("Assunto", "Separar o texto por assuntos", m.assunto) { modelo.modalidades(m.copy(assunto = it)) }
                    Opcao("Apresentação", "Roteiro de slides com o que falar em cada um", m.apresentacao) { modelo.modalidades(m.copy(apresentacao = it)) }
                }
                if (!estado.modalidades.algumaLigada) {
                    Text("Ligue ao menos uma opção.", color = Cores.Vermelho, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(Modifier.height(20.dp))
                SecaoTitulo("Temperamento")
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Temperamento.entries.forEach { t ->
                        OpcaoTemperamento(t, escolhido = t == estado.temperamento) { modelo.temperamento(if (estado.temperamento == t) null else t) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (!tecladoAberto()) Box(
                Modifier.fillMaxWidth().background(if (estado.podeConcluir) Cores.Grafite else Cores.Grafite.copy(alpha = 0.45f)).navigationBarsPadding()
                    .clickable(enabled = estado.podeConcluir) { if (modelo.concluir()) aoConcluir() }.padding(vertical = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Concluir", color = Color.White, fontFamily = Maven, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
        }
    }

    if (escolhendo) {
        SeletorPagina(estado.paginas, estado.pagina, aoEscolher = { modelo.escolherPagina(it); escolhendo = false }, aoFechar = { escolhendo = false })
    }
}

@Composable
private fun SecaoTitulo(texto: String) {
    Text(
        texto, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun OpcaoTemperamento(t: Temperamento, escolhido: Boolean, aoEscolher: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(forma)
            .background(if (escolhido) Cores.Laranja.copy(alpha = 0.1f) else Color(0xFFFAFAFA))
            .border(2.dp, if (escolhido) Cores.Laranja else Color.Transparent, forma)
            .clickable(onClick = aoEscolher).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t.emoji, fontSize = 24.sp)
        Column(Modifier.weight(1f)) {
            Text(t.rotulo, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
            Text(t.descricao, fontSize = 13.sp, color = Color(0xFF7A7A7A))
        }
        if (escolhido) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Cores.Laranja), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Opcao(titulo: String, descricao: String, ligado: Boolean, aoMudar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontSize = 18.sp, color = Color.Black)
            Text(descricao, fontSize = 15.sp, color = Color(0xFF7A7A7A))
        }
        Switch(
            checked = ligado, onCheckedChange = aoMudar,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF2B2B2B), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Color(0xFFCFCFCF), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SeletorPagina(paginas: List<PaginaNotion>, atual: PaginaNotion?, aoEscolher: (PaginaNotion) -> Unit, aoFechar: () -> Unit) {
    var busca by remember { mutableStateOf("") }
    val arvore = remember(paginas) { montarArvore(paginas) }
    var abertos by remember { mutableStateOf(ancestrais(paginas, atual)) }
    val termo = busca.trim()
    val visiveis = remember(arvore, termo) { if (termo.isEmpty()) arvore else filtrarArvore(arvore, termo) }
    val linhas = remember(visiveis, abertos, termo) {
        buildList { achatar(visiveis, 0, abertos, expandirTudo = termo.isNotEmpty(), saida = this) }
    }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Página de inserção") },
        text = {
            Column {
                TextField(
                    value = busca, onValueChange = { busca = it }, singleLine = true, placeholder = { Text("Buscar página") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (linhas.isEmpty()) {
                    Text("Nenhuma página encontrada.", color = Color(0xFF666666), modifier = Modifier.padding(12.dp))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(linhas, key = { it.first.pagina.id }) { (no, nivel) ->
                        val p = no.pagina
                        val temFilhos = no.filhos.isNotEmpty()
                        val aberto = termo.isNotEmpty() || p.id in abertos
                        Row(
                            Modifier.fillMaxWidth().clickable { aoEscolher(p) }.padding(start = (nivel * 18).dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable(enabled = temFilhos && termo.isEmpty()) {
                                    abertos = if (p.id in abertos) abertos - p.id else abertos + p.id
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (temFilhos) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown, if (aberto) "Recolher" else "Expandir", tint = Cores.Roxo,
                                        modifier = Modifier.size(24.dp).rotate(if (aberto) 0f else -90f),
                                    )
                                }
                            }
                            IconePagina(p.icone, tamanho = 22.dp)
                            Text(
                                p.titulo, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 12.dp).weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (p.id == atual?.id) FontWeight.Bold else FontWeight.Normal,
                                color = if (p.id == atual?.id) Cores.Laranja else Color.Black,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(aoFechar) { Text("Fechar") } },
    )
}

private fun ancestrais(paginas: List<PaginaNotion>, atual: PaginaNotion?): Set<String> {
    val porId = paginas.associateBy { it.id }
    val ids = HashSet<String>()
    var pai = atual?.pai
    while (pai != null && ids.add(pai)) pai = porId[pai]?.pai
    return ids
}

private fun filtrarArvore(nos: List<NoPagina>, termo: String): List<NoPagina> = nos.mapNotNull { no ->
    if (no.pagina.titulo.contains(termo, ignoreCase = true)) no
    else filtrarArvore(no.filhos, termo).takeIf { it.isNotEmpty() }?.let { NoPagina(no.pagina, it) }
}

private fun achatar(nos: List<NoPagina>, nivel: Int, abertos: Set<String>, expandirTudo: Boolean, saida: MutableList<Pair<NoPagina, Int>>) {
    nos.forEach { no ->
        saida += no to nivel
        if (no.filhos.isNotEmpty() && (expandirTudo || no.pagina.id in abertos)) achatar(no.filhos, nivel + 1, abertos, expandirTudo, saida)
    }
}
