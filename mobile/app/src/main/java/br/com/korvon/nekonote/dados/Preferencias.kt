package br.com.korvon.nekonote.dados

import android.content.Context

class Preferencias(context: Context) {
    private val sp = context.getSharedPreferences("nekonote", Context.MODE_PRIVATE)

    fun onboardingConcluido(idUsuario: String) = sp.getBoolean("onboarding_$idUsuario", false)
    fun marcarOnboarding(idUsuario: String, concluido: Boolean = true) = sp.edit().putBoolean("onboarding_$idUsuario", concluido).apply()

    fun ultimaPagina(idUsuario: String): String? = sp.getString("pagina_$idUsuario", null)
    fun salvarUltimaPagina(idUsuario: String, id: String) = sp.edit().putString("pagina_$idUsuario", id).apply()

    fun temperamento(idUsuario: String): Temperamento = Temperamento.deValor(sp.getString("temperamento_$idUsuario", null))
    fun salvarTemperamento(idUsuario: String, t: Temperamento) = sp.edit().putString("temperamento_$idUsuario", t.valor).apply()

    fun limparUsuario(idUsuario: String) {
        sp.edit().remove("onboarding_$idUsuario").remove("pagina_$idUsuario").remove("temperamento_$idUsuario").apply()
    }
}
