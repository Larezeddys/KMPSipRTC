package com.eddyslarez.kmpsiprtc.platform

import android.util.Log

actual fun getPlatformLogger(): Logger = AndroidLogger()

class AndroidLogger : Logger {
    override fun d(tag: String, message: () -> String) {
        val msg = message()
        Log.d(tag, msg)
        LibraryLogBridge.onLog("DEBUG", tag, msg)
    }

    override fun i(tag: String, message: () -> String) {
        val msg = message()
        Log.i(tag, msg)
        LibraryLogBridge.onLog("INFO", tag, msg)
    }

    override fun w(tag: String, message: () -> String) {
        val msg = message()
        Log.w(tag, msg)
        LibraryLogBridge.onLog("WARN", tag, msg)
    }

    override fun e(tag: String, message: () -> String) {
        val msg = message()
        Log.e(tag, msg)
        LibraryLogBridge.onLog("ERROR", tag, msg)
    }
}
