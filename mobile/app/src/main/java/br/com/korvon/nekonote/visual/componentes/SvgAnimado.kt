package br.com.korvon.nekonote.visual.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs

class SvgAnimado private constructor(
    private val raiz: Grupo,
    val tamanho: Float,
    val duracaoSegundos: Float,
) {
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrizTemp = Matrix()

    fun desenhar(canvas: Canvas, lado: Float, tempoSegundos: Float) {
        val tempo = ((tempoSegundos % duracaoSegundos) + duracaoSegundos) % duracaoSegundos
        canvas.save()
        val escala = lado / tamanho
        canvas.scale(escala, escala)
        desenharNo(canvas, raiz, tempo)
        canvas.restore()
    }

    private fun desenharNo(canvas: Canvas, no: No, tempo: Float) {
        when (no) {
            is Grupo -> {
                if (no.opacidade <= 0f) return
                canvas.save()
                val giro = no.rotacao
                if (giro != null) {
                    matrizTemp.setRotate(giro.valorEm(tempo)[0])
                    canvas.concat(matrizTemp)
                } else {
                    canvas.concat(no.base)
                }
                for (filho in no.filhos) desenharNo(canvas, filho, tempo)
                canvas.restore()
            }
            is Tracado -> desenharTracado(canvas, no, tempo)
        }
    }

    private fun desenharTracado(canvas: Canvas, t: Tracado, tempo: Float) {
        val morph = t.morph
        val caminho = if (morph != null) morph.caminhoEm(tempo) else t.fixo!!

        t.preenchimento?.let {
            tinta.reset()
            tinta.isAntiAlias = true
            tinta.style = Paint.Style.FILL
            tinta.color = it
            canvas.drawPath(caminho, tinta)
        }
        val cor = t.traco ?: return
        tinta.reset()
        tinta.isAntiAlias = true
        tinta.style = Paint.Style.STROKE
        tinta.color = cor
        tinta.strokeWidth = t.larguraTraco
        tinta.strokeCap = t.ponta
        tinta.strokeJoin = t.juncao
        tinta.strokeMiter = t.limiteJuncao

        val tracejado = t.animTracejado?.valorEm(tempo) ?: t.tracejado
        val medida = t.pathLength
        if (tracejado != null && tracejado.size >= 2 && medida != null && t.comprimento > 0f) {
            val k = t.comprimento / medida
            val traco = tracejado[0] * k
            val vazio = tracejado[1] * k
            val periodo = traco + vazio
            if (periodo > 0f) {
                val deslocamento = (t.animDeslocamento?.valorEm(tempo)?.get(0) ?: t.deslocamento) * k
                val fase = ((deslocamento % periodo) + periodo) % periodo
                tinta.pathEffect = DashPathEffect(floatArrayOf(traco, vazio), fase)
            }
        }
        canvas.drawPath(caminho, tinta)
    }

    internal sealed class No

    internal class Grupo : No() {
        var base = Matrix()
        var rotacao: Animacao? = null
        var opacidade = 1f
        val filhos = mutableListOf<No>()
    }

    internal class Tracado : No() {
        var fixo: Path? = null
        var morph: Morph? = null
        var traco: Int? = null
        var preenchimento: Int? = null
        var larguraTraco = 1f
        var ponta: Paint.Cap = Paint.Cap.BUTT
        var juncao: Paint.Join = Paint.Join.MITER
        var limiteJuncao = 4f
        var pathLength: Float? = null
        var tracejado: FloatArray? = null
        var deslocamento = 0f
        var animTracejado: Animacao? = null
        var animDeslocamento: Animacao? = null
        var comprimento = 0f
    }

    internal class Animacao(
        private val duracao: Float,
        private val instantes: FloatArray,
        private val valores: List<FloatArray>,
        private val curvas: List<Curva>?,
    ) {
        fun valorEm(tempoSegundos: Float): FloatArray {
            val t = ((tempoSegundos % duracao) / duracao).coerceIn(0f, 1f)
            val n = instantes.size
            if (n == 1 || t >= instantes[n - 1]) return valores[n - 1]
            var i = 0
            while (i < n - 2 && t >= instantes[i + 1]) i++
            val largura = instantes[i + 1] - instantes[i]
            var u = if (largura <= 0f) 1f else ((t - instantes[i]) / largura).coerceIn(0f, 1f)
            curvas?.get(i)?.let { u = it.resolver(u) }
            val de = valores[i]
            val ate = valores[i + 1]
            return FloatArray(de.size) { de[it] + (ate[it] - de[it]) * u }
        }
    }

    internal class Curva(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
        private val linear = x1 == y1 && x2 == y2

        fun resolver(x: Float): Float {
            if (linear) return x
            var baixo = 0f
            var alto = 1f
            var t = x
            repeat(24) {
                val xt = ponto(t, x1, x2)
                if (abs(xt - x) < 1e-5f) return ponto(t, y1, y2)
                if (xt < x) baixo = t else alto = t
                t = (baixo + alto) / 2
            }
            return ponto(t, y1, y2)
        }

        private fun ponto(t: Float, a: Float, b: Float): Float {
            val m = 1 - t
            return 3 * m * m * t * a + 3 * m * t * t * b + t * t * t
        }
    }

    internal class Forma(val comandos: CharArray, val coordenadas: FloatArray) {
        fun paraCaminho(destino: Path = Path(), coords: FloatArray = coordenadas): Path {
            destino.rewind()
            var k = 0
            for (c in comandos) when (c) {
                'M' -> { destino.moveTo(coords[k], coords[k + 1]); k += 2 }
                'C' -> { destino.cubicTo(coords[k], coords[k + 1], coords[k + 2], coords[k + 3], coords[k + 4], coords[k + 5]); k += 6 }
                'Z' -> destino.close()
            }
            return destino
        }
    }

    internal class Morph(private val animacao: Animacao, private val modelo: Forma) {
        private val caminho = Path()
        fun caminhoEm(tempo: Float): Path = modelo.paraCaminho(caminho, animacao.valorEm(tempo))
    }

    companion object {
        fun carregar(contexto: Context, recurso: Int): SvgAnimado =
            contexto.resources.openRawResource(recurso).use { Leitor(Xml.newPullParser().also { p -> p.setInput(it, null) }).ler() }
    }

    private class Leitor(private val xml: XmlPullParser) {
        private val numero = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        private var tamanho = 1080f
        private var duracao = 1f

        fun ler(): SvgAnimado {
            val raiz = Grupo()
            val pilha = ArrayDeque<No>()
            pilha.addLast(raiz)
            var evento = xml.eventType
            while (evento != XmlPullParser.END_DOCUMENT) {
                if (evento == XmlPullParser.START_TAG) when (xml.name) {
                    "svg" -> xml.getAttributeValue(null, "viewBox")?.split(' ', ',')?.filter { it.isNotBlank() }
                        ?.getOrNull(2)?.toFloatOrNull()?.let { tamanho = it }
                    "g" -> {
                        val g = Grupo()
                        g.base = transformacao(xml.getAttributeValue(null, "transform"))
                        g.opacidade = xml.getAttributeValue(null, "opacity")?.toFloatOrNull() ?: 1f
                        (pilha.last() as Grupo).filhos += g
                        pilha.addLast(g)
                    }
                    "path" -> {
                        val t = tracado()
                        (pilha.last() as Grupo).filhos += t
                        pilha.addLast(t)
                    }
                    "animateTransform" -> (pilha.last() as? Grupo)?.let { g ->
                        if (xml.getAttributeValue(null, "type") == "rotate") g.rotacao = animacao()
                    }
                    "animate" -> (pilha.last() as? Tracado)?.let { aplicarAnimate(it) }
                }
                if (evento == XmlPullParser.END_TAG && (xml.name == "g" || xml.name == "path")) pilha.removeLast()
                evento = xml.next()
            }
            return SvgAnimado(raiz, tamanho, duracao)
        }

        private fun tracado(): Tracado {
            val t = Tracado()
            fun atributo(nome: String) = xml.getAttributeValue(null, nome)
            val d = atributo("d")
            if (d != null) {
                val forma = forma(d)
                t.fixo = forma.paraCaminho()
                t.comprimento = comprimentoTotal(t.fixo!!)
            }
            t.traco = cor(atributo("stroke"))
            t.preenchimento = cor(atributo("fill"))
            atributo("stroke-width")?.toFloatOrNull()?.let { t.larguraTraco = it }
            if (atributo("stroke-linecap") == "round") t.ponta = Paint.Cap.ROUND
            if (atributo("stroke-linejoin") == "round") t.juncao = Paint.Join.ROUND
            atributo("stroke-miterlimit")?.toFloatOrNull()?.let { t.limiteJuncao = it }
            t.pathLength = atributo("pathLength")?.toFloatOrNull()
            t.tracejado = atributo("stroke-dasharray")?.let(::numeros)
            atributo("stroke-dashoffset")?.toFloatOrNull()?.let { t.deslocamento = it }
            return t
        }

        private fun aplicarAnimate(t: Tracado) {
            when (xml.getAttributeValue(null, "attributeName")) {
                "stroke-dasharray" -> t.animTracejado = animacao()
                "stroke-dashoffset" -> t.animDeslocamento = animacao()
                "d" -> {
                    val formas = xml.getAttributeValue(null, "values").split(';').map { forma(it) }
                    val base = formas.first()
                    t.morph = Morph(animacao(formas.map { it.coordenadas }), base)
                }
            }
        }

        private fun animacao(valoresProntos: List<FloatArray>? = null): Animacao {
            fun atributo(nome: String) = xml.getAttributeValue(null, nome)
            val dur = atributo("dur")!!.removeSuffix("s").toFloat()
            duracao = dur
            val valores = valoresProntos ?: atributo("values")!!.split(';').map { numeros(it) }
            val instantes = atributo("keyTimes")!!.split(';').map { it.trim().toFloat() }.toFloatArray()
            val curvas = if (atributo("calcMode") == "spline") {
                atributo("keySplines")!!.split(';').map { numeros(it) }.map { Curva(it[0], it[1], it[2], it[3]) }
            } else null
            return Animacao(dur, instantes, valores, curvas)
        }

        private fun numeros(texto: String): FloatArray = numero.findAll(texto).map { it.value.toFloat() }.toList().toFloatArray()

        private fun cor(valor: String?): Int? {
            if (valor == null || valor == "none") return null
            val hex = valor.removePrefix("#")
            return when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                3 -> hex.map { "$it$it" }.joinToString("").let { (0xFF000000 or it.toLong(16)).toInt() }
                else -> null
            }
        }

        private fun comprimentoTotal(caminho: Path): Float {
            val medidor = PathMeasure(caminho, false)
            var total = medidor.length
            while (medidor.nextContour()) total += medidor.length
            return total
        }

        private fun forma(d: String): Forma {
            val comandos = ArrayList<Char>()
            val coords = ArrayList<Float>()
            var i = 0
            while (i < d.length) {
                val c = d[i]
                if (c == 'M' || c == 'C' || c == 'Z') {
                    val inicio = i + 1
                    var fim = inicio
                    while (fim < d.length && d[fim] !in "MCZ") fim++
                    val nums = numeros(d.substring(inicio, fim))
                    when (c) {
                        'Z' -> comandos += 'Z'
                        'M' -> { comandos += 'M'; coords += nums[0]; coords += nums[1] }
                        'C' -> for (p in nums.indices step 6) {
                            comandos += 'C'
                            for (j in 0 until 6) coords += nums[p + j]
                        }
                    }
                    i = fim
                } else i++
            }
            return Forma(comandos.toCharArray(), coords.toFloatArray())
        }

        private fun transformacao(texto: String?): Matrix {
            val m = Matrix()
            if (texto == null) return m
            Regex("(\\w+)\\(([^)]*)\\)").findAll(texto).forEach { achado ->
                val n = numeros(achado.groupValues[2])
                val op = Matrix()
                when (achado.groupValues[1]) {
                    "translate" -> op.setTranslate(n[0], n.getOrElse(1) { 0f })
                    "scale" -> op.setScale(n[0], n.getOrElse(1) { n[0] })
                    "rotate" -> if (n.size >= 3) op.setRotate(n[0], n[1], n[2]) else op.setRotate(n[0])
                    "matrix" -> op.setValues(floatArrayOf(n[0], n[2], n[4], n[1], n[3], n[5], 0f, 0f, 1f))
                }
                m.preConcat(op)
            }
            return m
        }
    }
}
