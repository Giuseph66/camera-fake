package br.com.neurelix.vcam

import android.app.Application
import br.com.neurelix.vcam.core.diagnostics.AppLog

class App : Application() {
    lateinit var controller: EngineController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        controller = EngineController(this)
        AppLog.i("app", "boot", "NEURELIX iniciado (pid=${android.os.Process.myPid()})")
    }

    companion object {
        lateinit var instance: App
            private set
        val controller: EngineController get() = instance.controller
    }
}
