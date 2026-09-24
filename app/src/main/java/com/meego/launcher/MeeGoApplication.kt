package com.meego.launcher

import android.app.Application

class MeeGoApplication : Application() {
    companion object {
        lateinit var instance: MeeGoApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
