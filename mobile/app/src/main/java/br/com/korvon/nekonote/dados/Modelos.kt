package br.com.korvon.nekonote.dados

import java.io.File

const val MAX_FOTOS = br.com.korvon.nekonote.BuildConfig.MAX_IMAGENS
const val MIN_CARACTERES_LEGIVEL = 20

data class UsuarioAuth(val idUsuario: String, val email: String, val nomeGoogle: String = "") {
    val idPublico: String
        get() {
            var h = 1125899906842597L
            for (c in idUsuario) h = 31 * h + c.code
            return (h and Long.MAX_VALUE).toString().padStart(11, '0').take(11)
        }

    val nome: String get() = nomeGoogle.ifBlank { email.substringBefore('@') }
    val primeiroNome: String get() = nome.trim().substringBefore(' ')
}

enum class NotionEstado { CONECTADO, DESCONECTADO, RECONECTAR }

data class NotionStatus(
    val estado: NotionEstado = NotionEstado.DESCONECTADO,
    val espacoNome: String? = null,
    val espacoIcone: String? = null,
    val paginasLiberadas: Int = 0,
) {
    val conectado get() = estado == NotionEstado.CONECTADO
}

data class Conta(val email: String, val idPublico: String, val notion: NotionStatus, val cadastroConcluido: Boolean = false)

data class PaginaNotion(val id: String, val titulo: String, val icone: String? = null, val pai: String? = null)

data class NoPagina(val pagina: PaginaNotion, val filhos: List<NoPagina>) {
    val total: Int get() = 1 + filhos.sumOf { it.total }
}

fun montarArvore(paginas: List<PaginaNotion>): List<NoPagina> {
    val ids = paginas.mapTo(HashSet()) { it.id }
    val filhosPorPai = paginas.filter { it.pai != null && it.pai in ids && it.pai != it.id }.groupBy { it.pai!! }
    val vistos = HashSet<String>()

    fun no(p: PaginaNotion): NoPagina {
        vistos += p.id
        return NoPagina(p, filhosPorPai[p.id].orEmpty().filter { it.id !in vistos }.map(::no))
    }

    val raizes = paginas.filter { it.pai == null || it.pai !in ids || it.pai == it.id }.map(::no)
    val sobras = paginas.filter { it.id !in vistos }.map(::no)
    return raizes + sobras
}

data class Modalidades(
    val topicos: Boolean = true,
    val resumo: Boolean = true,
    val assunto: Boolean = true,
    val apresentacao: Boolean = false,
) {
    val algumaLigada get() = topicos || resumo || assunto || apresentacao
}

enum class Temperamento(val valor: String, val rotulo: String, val emoji: String, val descricao: String) {
    EQUILIBRADO("equilibrado", "Equilibrado", "⚖️", "Claro e natural, sem exageros"),
    DIRETO("direto", "Direto", "⚡", "Curto e objetivo, só o essencial"),
    DIVERTIDO("divertido", "Divertido", "😸", "Leve e com personalidade"),
    TECNICO("tecnico", "Técnico", "🛠️", "Termos precisos e mais detalhe"),
    DIDATICO("didatico", "Didático", "🎓", "Explica passo a passo, como numa aula"),
    ;

    companion object {
        fun deValor(valor: String?): Temperamento = entries.firstOrNull { it.valor == valor } ?: EQUILIBRADO
    }
}

data class PedidoProcessamento(
    val idPagina: String,
    val modalidades: Modalidades,
    val imagens: List<File>,
    val temperamento: Temperamento = Temperamento.EQUILIBRADO,
)

sealed interface EventoProcessamento {
    data class Inicio(val total: Int) : EventoProcessamento
    data class Progresso(val percentual: Int, val atual: Int, val total: Int, val mensagem: String) : EventoProcessamento
    data class Enviando(val mensagem: String, val percentual: Int = 95) : EventoProcessamento
    data class Reconectando(val tentativa: Int) : EventoProcessamento
    data class Resultado(val urlPagina: String?, val mensagem: String) : EventoProcessamento
    data class Erro(val mensagem: String) : EventoProcessamento
}

class ExcecaoNeko(
    mensagem: String,
    val tipo: Tipo = Tipo.GERAL,
    cause: Throwable? = null,
) : Exception(mensagem, cause) {
    enum class Tipo { GERAL, REDE, NAO_AUTENTICADO, NOTION_RECONECTAR, CANCELADO }
}
