package br.com.korvon.nekonote.dados.fotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import br.com.korvon.nekonote.dados.Configuracao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

class ArmazemImagens(private val context: Context) {
    private val pasta = File(context.filesDir, "fotos").apply { mkdirs() }
    private val indice = File(context.filesDir, "fotos_indice.json")

    fun novoArquivo(): File = File(pasta, "${UUID.randomUUID()}.jpg")

    suspend fun normalizar(origem: File): File? = withContext(Dispatchers.IO) {
        val saida = normalizarDe { origem.inputStream() }
        origem.delete()
        saida
    }

    suspend fun normalizar(uri: Uri): File? = withContext(Dispatchers.IO) {
        normalizarDe { context.contentResolver.openInputStream(uri) }
    }

    fun limpar() {
        pasta.listFiles()?.forEach { it.delete() }
        indice.delete()
    }

    fun carregarFotos(): List<Foto> {
        val fotos = try {
            val arr = JSONArray(indice.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val arquivo = File(pasta, o.getString("arquivo"))
                if (!arquivo.exists()) return@mapNotNull null
                Foto(
                    id = o.getString("id"),
                    arquivo = arquivo,
                    selecionada = o.optBoolean("selecionada", true),
                    legivel = if (o.isNull("legivel")) null else o.getBoolean("legivel"),
                    daCamera = o.optBoolean("daCamera", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        val nomes = fotos.mapTo(HashSet()) { it.arquivo.name }
        pasta.listFiles()?.filter { it.name !in nomes }?.forEach { it.delete() }
        return fotos
    }

    fun salvarFotos(fotos: List<Foto>) {
        val arr = JSONArray()
        fotos.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("arquivo", it.arquivo.name).put("selecionada", it.selecionada)
                    .put("legivel", it.legivel ?: JSONObject.NULL).put("daCamera", it.daCamera),
            )
        }
        val temporario = File(indice.parentFile, "fotos_indice.tmp")
        temporario.writeText(arr.toString())
        if (!temporario.renameTo(indice)) {
            indice.delete()
            temporario.renameTo(indice)
        }
    }

    private fun normalizarDe(abrir: () -> InputStream?): File? {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (abrir() ?: return null).use { BitmapFactory.decodeStream(it, null, limites) }
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return null

        var amostra = 1
        val maior = maxOf(limites.outWidth, limites.outHeight)
        while (maior / (amostra * 2) >= Configuracao.LADO_MAXIMO_PX) amostra *= 2

        val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
        val bruto = abrir()?.use { BitmapFactory.decodeStream(it, null, opcoes) } ?: return null

        val rotacao = abrir()?.use { rotacaoExif(it) } ?: 0
        val escala = Configuracao.LADO_MAXIMO_PX.toFloat() / maxOf(bruto.width, bruto.height)
        val matriz = Matrix().apply {
            if (escala < 1f) postScale(escala, escala)
            if (rotacao != 0) postRotate(rotacao.toFloat())
        }
        val final = if (matriz.isIdentity) bruto else Bitmap.createBitmap(bruto, 0, 0, bruto.width, bruto.height, matriz, true)
        if (final !== bruto) bruto.recycle()

        val saida = novoArquivo()
        saida.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, Configuracao.QUALIDADE_JPEG, it) }
        final.recycle()
        return saida
    }

    private fun rotacaoExif(stream: InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
