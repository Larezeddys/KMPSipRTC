package com.eddyslarez.kmpsiprtc.platform

import platform.Foundation.NSLog

actual fun getPlatformLogger(): Logger = IosLogger()

class IosLogger : Logger {
    override fun d(tag: String, message: () -> String) {
        val msg = message()
        NSLog("DEBUG [$tag]: $msg")
        LibraryLogBridge.onLog("DEBUG", tag, msg)
    }

    override fun i(tag: String, message: () -> String) {
        val msg = message()
        NSLog("INFO  [$tag]: $msg")
        LibraryLogBridge.onLog("INFO", tag, msg)
    }

    override fun w(tag: String, message: () -> String) {
        val msg = message()
        NSLog("WARN  [$tag]: $msg")
        LibraryLogBridge.onLog("WARN", tag, msg)
    }

    override fun e(tag: String, message: () -> String) {
        val msg = message()
        NSLog("ERROR [$tag]: $msg")
        LibraryLogBridge.onLog("ERROR", tag, msg)
    }
}