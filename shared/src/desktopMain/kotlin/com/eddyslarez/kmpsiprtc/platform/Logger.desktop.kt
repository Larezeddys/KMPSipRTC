package com.eddyslarez.kmpsiprtc.platform

actual fun getPlatformLogger(): Logger = DesktopLogger()

class DesktopLogger : Logger {
    override fun d(tag: String, message: () -> String) {
        val msg = message()
        println("DEBUG [$tag]: $msg")
        LibraryLogBridge.onLog("DEBUG", tag, msg)
    }

    override fun i(tag: String, message: () -> String) {
        val msg = message()
        println("INFO  [$tag]: $msg")
        LibraryLogBridge.onLog("INFO", tag, msg)
    }

    override fun w(tag: String, message: () -> String) {
        val msg = message()
        println("WARN  [$tag]: $msg")
        LibraryLogBridge.onLog("WARN", tag, msg)
    }

    override fun e(tag: String, message: () -> String) {
        val msg = message()
        println("ERROR [$tag]: $msg")
        LibraryLogBridge.onLog("ERROR", tag, msg)
    }
}
