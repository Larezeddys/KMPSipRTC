package com.eddyslarez.kmpsiprtc.platform

actual fun getPlatformLogger(): Logger = DesktopLogger()

/**
 * Filtra por [SipLogConfig.minLevel] y pasa cada mensaje por [LogRedactor] antes de imprimirlo o
 * entregarlo a [LibraryLogBridge].
 */
class DesktopLogger : Logger {
    override fun d(tag: String, message: () -> String) = emit(SipLogLevel.DEBUG, "DEBUG", tag, message)

    override fun i(tag: String, message: () -> String) = emit(SipLogLevel.INFO, "INFO", tag, message)

    override fun w(tag: String, message: () -> String) = emit(SipLogLevel.WARN, "WARN", tag, message)

    override fun e(tag: String, message: () -> String) = emit(SipLogLevel.ERROR, "ERROR", tag, message)

    private fun emit(level: SipLogLevel, label: String, tag: String, message: () -> String) {
        if (!SipLogConfig.isEnabled(level)) return
        val msg = LogRedactor.redact(message())
        val line = "${label.padEnd(5)} [$tag]: $msg"
        if (level == SipLogLevel.ERROR) System.err.println(line) else println(line)
        LibraryLogBridge.onLog(label, tag, msg)
    }
}
