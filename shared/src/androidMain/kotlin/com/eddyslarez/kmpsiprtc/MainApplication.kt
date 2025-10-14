package com.eddyslarez.kmpsiprtc

import android.app.Application
import android.content.Context


class MainApplication : Application() {

    init {
        instance = this
    }
    companion object{
        private var instance: MainApplication? = null

        fun getApplicationContext(): Context {
            return instance!!.applicationContext
        }
    }
    override fun onCreate() {
        super.onCreate()

    }

}