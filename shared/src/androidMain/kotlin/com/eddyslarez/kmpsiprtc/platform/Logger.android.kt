package com.eddyslarez.kmpsiprtc.platform

import android.util.Log

actual fun getPlatformLogger(): Logger = AndroidLogger()

class AndroidLogger : Logger {
    override fun d(tag: String, message: () -> String) {
        Log.d(tag, message())
    }

    override fun i(tag: String, message: () -> String) {
        Log.i(tag, message())
    }

    override fun w(tag: String, message: () -> String) {
        Log.w(tag, message())
    }

    override fun e(tag: String, message: () -> String) {
        Log.e(tag, message())
    }
}