package com.eddyslarez.kmpsiprtc.platform

import platform.Foundation.NSLog

actual fun getPlatformLogger(): Logger = IosLogger()

class IosLogger : Logger {
    override fun d(tag: String, message: () -> String) = NSLog("DEBUG [$tag]: ${message()}")
    override fun i(tag: String, message: () -> String) = NSLog("INFO  [$tag]: ${message()}")
    override fun w(tag: String, message: () -> String) = NSLog("WARN  [$tag]: ${message()}")
    override fun e(tag: String, message: () -> String) = NSLog("ERROR [$tag]: ${message()}")
}