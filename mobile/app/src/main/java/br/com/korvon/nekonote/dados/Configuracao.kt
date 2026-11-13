package br.com.korvon.nekonote.dados

import br.com.korvon.nekonote.BuildConfig

object Configuracao {
    val urlApi: String = BuildConfig.URL_API
    val urlWebSocket: String = urlApi.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/ocr"
    val urlPrivacidade get() = "$urlApi/privacidade"
    val urlTermos get() = "$urlApi/termos"

    val urlAuth: String = BuildConfig.URL_AUTH

    const val LADO_MAXIMO_PX = 2000
    const val QUALIDADE_JPEG = 85
    const val ORCAMENTO_BASE64_BYTES = 12L * 1024 * 1024
}
