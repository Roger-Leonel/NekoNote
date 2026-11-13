package br.com.korvon.nekonote.dados.fotos

import android.graphics.BitmapFactory
import android.util.Log
import br.com.korvon.nekonote.dados.MIN_CARACTERES_LEGIVEL
import br.com.korvon.nekonote.dados.aguardar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PreviaTexto {
    private val reconhecedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun analisar(arquivo: File): Boolean? = withContext(Dispatchers.Default) {
        try {
            val opcoes = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bitmap = BitmapFactory.decodeFile(arquivo.path, opcoes) ?: return@withContext null
            val texto = try {
                reconhecedor.process(InputImage.fromBitmap(bitmap, 0)).aguardar().text
            } finally {
                bitmap.recycle()
            }
            texto.count { !it.isWhitespace() } >= MIN_CARACTERES_LEGIVEL
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("NekoOcr", "ML Kit falhou ao analisar a foto", e)
            null
        }
    }
}
