package com.eddyslarez.kmpsiprtc.platform

/**
 * Puente de logs entre la librería KMPSipRTC y la app consumidora.
 * La app puede configurar un listener para recibir todos los logs internos
 * del SIP stack y mostrarlos en su LogWindowManager.
 */
object LibraryLogBridge {
    var listener: ((level: String, tag: String, message: String) -> Unit)? = null

    internal fun onLog(level: String, tag: String, message: String) {
        listener?.invoke(level, tag, message)
    }
}
