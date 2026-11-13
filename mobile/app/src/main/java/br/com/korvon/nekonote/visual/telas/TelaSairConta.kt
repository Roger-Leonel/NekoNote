package br.com.korvon.nekonote.visual.telas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.korvon.nekonote.ContainerApp
import br.com.korvon.nekonote.dados.ExcecaoNeko
import br.com.korvon.nekonote.visual.componentes.AvatarGato
import br.com.korvon.nekonote.visual.componentes.Aviso
import br.com.korvon.nekonote.visual.obterModelo
import br.com.korvon.nekonote.visual.paraUsuario
import br.com.korvon.nekonote.visual.tema.Cores
import br.com.korvon.nekonote.visual.tema.Maven
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoSairConta(val ocupado: Boolean = false, val erro: String? = null)

class ModeloSairConta(private val c: ContainerApp) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoSairConta())
    val estado: StateFlow<EstadoSairConta> = _estado
    val usuario get() = c.auth.usuario.value

    fun trocarConta() {
        _estado.update { it.copy(ocupado = true, erro = null) }
        viewModelScope.launch {
            usuario?.idUsuario?.let { c.prefs.limparUsuario(it) }
            c.fotos.limpar()
            c.auth.sair()
        }
    }

    fun removerConta() {
        _estado.update { it.copy(ocupado = true, erro = null) }
        viewModelScope.launch {
            val idUsuario = usuario?.idUsuario
            try {
                c.api.excluirConta()
                idUsuario?.let { c.prefs.limparUsuario(it) }
                c.fotos.limpar()
                c.auth.sair()
            } catch (e: Exception) {
                val cancelado = (e as? ExcecaoNeko)?.tipo == ExcecaoNeko.Tipo.CANCELADO
                _estado.update { it.copy(ocupado = false, erro = if (cancelado) null else e.paraUsuario()) }
            }
        }
    }
}

@Composable
fun TelaSairConta(conteiner: ContainerApp, aoVoltar: () -> Unit) {
    val modelo = obterModelo({ ModeloSairConta(it) }, conteiner)
    val estado by modelo.estado.collectAsState()
    var confirmarRemocao by remember { mutableStateOf(false) }

    BackHandler(enabled = !estado.ocupado, onBack = aoVoltar)

    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Voltar", tint = Color.Black,
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !estado.ocupado, onClick = aoVoltar).padding(6.dp),
            )
        }

        Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AvatarGato(96.dp)
            Text(
                modelo.usuario?.email.orEmpty(), fontSize = 14.sp, color = Color(0xFF777777),
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "O que você quer fazer?", fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.Black,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp, bottom = 28.dp),
            )

            Opcao(
                titulo = "Trocar de conta",
                descricao = "Sai desta conta e volta para o login, onde você escolhe outra conta do Google.",
                fundo = Cores.Grafite, corTexto = Color.White,
                habilitado = !estado.ocupado, aoClicar = modelo::trocarConta,
            )
            Spacer(Modifier.height(16.dp))
            Opcao(
                titulo = "Remover conta",
                descricao = "Apaga sua conta e a conexão com o Notion. As páginas já criadas continuam no seu Notion.",
                fundo = Color.White, corTexto = Cores.Vermelho, borda = Cores.Vermelho,
                habilitado = !estado.ocupado, aoClicar = { confirmarRemocao = true },
            )

            estado.erro?.let { Aviso(it, Modifier.padding(top = 20.dp)) }
            if (estado.ocupado) CircularProgressIndicator(Modifier.padding(top = 24.dp).size(28.dp), strokeWidth = 3.dp)
        }
    }

    if (confirmarRemocao) {
        AlertDialog(
            onDismissRequest = { confirmarRemocao = false },
            title = { Text("Remover conta?") },
            text = { Text("Isso apaga sua conta e a conexão com o Notion. As páginas já criadas continuam no seu Notion.") },
            confirmButton = { TextButton({ confirmarRemocao = false; modelo.removerConta() }) { Text("Remover", color = Cores.Vermelho) } },
            dismissButton = { TextButton({ confirmarRemocao = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Opcao(
    titulo: String,
    descricao: String,
    fundo: Color,
    corTexto: Color,
    habilitado: Boolean,
    aoClicar: () -> Unit,
    borda: Color? = null,
) {
    val forma = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(forma).background(fundo)
            .then(if (borda != null) Modifier.border(2.dp, borda, forma) else Modifier)
            .clickable(enabled = habilitado, onClick = aoClicar)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(titulo, color = corTexto, fontFamily = Maven, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(descricao, color = corTexto.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}
