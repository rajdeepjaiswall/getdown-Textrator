package com.getdown.texttractor

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Backend.start()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
