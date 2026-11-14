package br.com.korvon.nekonote.dados.fotos

import android.net.Uri
import br.com.korvon.nekonote.dados.MAX_FOTOS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

data class Foto(
    val id: String,
    val arquivo: File,
    val selecionada: Boolean = true,
    val legivel: Boolean? = null,
    val daCamera: Boolean = false,
)

data class ResultadoImportacao(val adicionadas: Int = 0, val falhas: Int = 0)

class SessaoFotos(
    private val store: ArmazemImagens,
    private val preview: PreviaTexto,
    private val escopo: CoroutineScope,
) {
    private val _fotos = MutableStateFlow(store.carregarFotos())
    val fotos: StateFlow<List<Foto>> = _fotos

    private val _ocupadas = MutableStateFlow(0)
    val ocupadas: StateFlow<Int> = _ocupadas

    val selecionadas: List<Foto> get() = _fotos.value.filter { it.selecionada }

    init {
        escopo.launch(Dispatchers.IO) {
            _fotos.drop(1).collect { store.salvarFotos(it) }
        }
        _fotos.value.filter { it.legivel == null }.forEach { analisar(it) }
    }

    fun novoArquivoTemporario(): File = store.novoArquivo()

    suspend fun adicionarCapturada(bruto: File): Boolean {
        _ocupadas.update { it + 1 }
        try {
            val arquivo = store.normalizar(bruto) ?: return false
            return incluir(arquivo, daCamera = true)
        } finally {
            _ocupadas.update { it - 1 }
        }
    }

    suspend fun adicionarDaGaleria(uris: List<Uri>): ResultadoImportacao {
        _ocupadas.update { it + 1 }
        try {
            var adicionadas = 0
            var falhas = 0
            for (uri in uris) {
                val arquivo = store.normalizar(uri)
                if (arquivo == null) {
                    falhas++
                } else {
                    incluir(arquivo)
                    adicionadas++
                }
            }
            return ResultadoImportacao(adicionadas, falhas)
        } finally {
            _ocupadas.update { it - 1 }
        }
    }

    private fun incluir(arquivo: File, daCamera: Boolean = false): Boolean {
        val foto = Foto(UUID.randomUUID().toString(), arquivo, daCamera = daCamera)
        _fotos.update { lista -> lista + foto.copy(selecionada = lista.count { it.selecionada } < MAX_FOTOS) }
        analisar(foto)
        return true
    }

    private fun analisar(foto: Foto) {
        escopo.launch {
            // a prévia só avisa de pouco texto: se o ML Kit falhar ou demorar, não trava a foto girando
            // (null é "analisando"), considera legível e deixa o OCR do servidor decidir
            val legivel = withTimeoutOrNull(20_000) { preview.analisar(foto.arquivo) } ?: true
            _fotos.update { lista -> lista.map { if (it.id == foto.id) it.copy(legivel = legivel) else it } }
        }
    }

    fun alternar(id: String): Boolean {
        var ok = true
        _fotos.update { lista ->
            val foto = lista.firstOrNull { it.id == id } ?: return@update lista
            if (!foto.selecionada && lista.count { it.selecionada } >= MAX_FOTOS) {
                ok = false
                return@update lista
            }
            lista.map { if (it.id == id) it.copy(selecionada = !it.selecionada) else it }
        }
        return ok
    }

    fun selecionarTodas(selecionada: Boolean) {
        _fotos.update { lista ->
            if (!selecionada) return@update lista.map { it.copy(selecionada = false) }
            var marcadas = lista.count { it.selecionada }
            lista.map { foto ->
                if (!foto.selecionada && marcadas < MAX_FOTOS) {
                    marcadas++
                    foto.copy(selecionada = true)
                } else {
                    foto
                }
            }
        }
    }

    fun remover(id: String) {
        val foto = _fotos.value.firstOrNull { it.id == id } ?: return
        _fotos.update { lista -> lista.filter { it.id != id } }
        foto.arquivo.delete()
    }

    fun removerDesmarcadas() {
        val (manter, descartar) = _fotos.value.partition { it.selecionada }
        descartar.forEach { it.arquivo.delete() }
        _fotos.value = manter
    }

    fun limpar() {
        _fotos.value = emptyList()
        store.limpar()
    }
}
