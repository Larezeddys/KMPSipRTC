package com.eddyslarez.kmpsiprtc.platform

import com.eddyslarez.kmpsiprtc.services.webSocket.MultiplatformWebSocket

/**
 * Interfaz de logging simple multiplataforma
 */
interface Logger {
    fun d(tag: String = "", message: () -> String)
    fun i(tag: String = "", message: () -> String)
    fun w(tag: String = "", message: () -> String)
    fun e(tag: String = "", message: () -> String)
}

/**
 * Expect declaration para obtener el logger según la plataforma
 */
expect fun getPlatformLogger(): Logger

// Instancia global (usable en todo el código común)
val log: Logger = getPlatformLogger()