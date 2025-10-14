package com.eddyslarez.kmpsiprtc.platform

actual fun getPlatformLogger(): Logger = DesktopLogger()

class DesktopLogger : Logger {
    override fun d(tag: String, message: () -> String) = println("DEBUG [$tag]: ${message()}")
    override fun i(tag: String, message: () -> String) = println("INFO  [$tag]: ${message()}")
    override fun w(tag: String, message: () -> String) = println("WARN  [$tag]: ${message()}")
    override fun e(tag: String, message: () -> String) = println("ERROR [$tag]: ${message()}")
}