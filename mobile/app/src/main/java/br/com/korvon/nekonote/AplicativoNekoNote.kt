package br.com.korvon.nekonote

import android.app.Application

class AplicativoNekoNote : Application() {
    lateinit var conteiner: ContainerApp
        private set

    override fun onCreate() {
        super.onCreate()
        conteiner = ContainerApp(this)
    }
}
