package br.com.korvon.nekonote

import android.content.Context
import br.com.korvon.nekonote.dados.Configuracao
import br.com.korvon.nekonote.dados.PedidoProcessamento
import br.com.korvon.nekonote.dados.Preferencias
import br.com.korvon.nekonote.dados.api.ApiNekoHttp
import br.com.korvon.nekonote.dados.api.ApiNeko
import br.com.korvon.nekonote.dados.auth.RepositorioAuth
import br.com.korvon.nekonote.dados.auth.RepositorioAuthSupabase
import br.com.korvon.nekonote.dados.fotos.ArmazemImagens
import br.com.korvon.nekonote.dados.fotos.SessaoFotos
import br.com.korvon.nekonote.dados.fotos.PreviaTexto
import br.com.korvon.nekonote.dados.processamento.ClienteProcessamento
import br.com.korvon.nekonote.dados.processamento.ClienteProcessamentoWs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ContainerApp(context: Context) {
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Preferencias(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val httpWs = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    val auth: RepositorioAuth = RepositorioAuthSupabase(http, Configuracao.urlAuth, context.applicationContext)
    val api: ApiNeko = ApiNekoHttp(http, auth, Configuracao.urlApi)
    val processamento: ClienteProcessamento = ClienteProcessamentoWs(httpWs, auth)

    val fotos = SessaoFotos(ArmazemImagens(context), PreviaTexto(), escopo)

    val retornoNotion = MutableStateFlow<String?>(null)

    var pedido: PedidoProcessamento? = null
}
