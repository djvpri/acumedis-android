package com.acumedis.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(this)
    }
}
