package br.com.korvon.nekonote.dados

import android.content.Context

class Preferencias(context: Context) {
    private val sp = context.getSharedPreferences("nekonote", Context.MODE_PRIVATE)

    fun onboardingConcluido(idUsuario: String) = sp.getBoolean("onboarding_$idUsuario", false)
    fun marcarOnboarding(idUsuario: String, concluido: Boolean = true) = sp.edit().putBoolean("onboarding_$idUsuario", concluido).apply()

    fun limparUsuario(idUsuario: String) {
        sp.edit().remove("onboarding_$idUsuario").apply()
    }
}
