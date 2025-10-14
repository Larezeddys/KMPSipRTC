package com.eddyslarez.kmpsiprtc

import android.app.Application
import android.content.Context
import com.eddyslarez.kmpsiprtc.platform.AndroidContext


class MainApplication : Application() {

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        AndroidContext.initialize(this)
    }

    companion object {
        private var instance: MainApplication? = null

        fun getApplicationContext(): Context {
            return instance!!.applicationContext
        }
    }
}
