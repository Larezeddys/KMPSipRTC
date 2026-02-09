package com.eddyslarez.kmpsiprtc.services.webSocket


interface MultiplatformWebSocket {
    fun connect()
    fun send(message: String)
    fun close(code: Int = 1000, reason: String = "")
    fun isConnected(): Boolean

    fun sendPing()
    fun startPingTimer(intervalMs: Long = 60_000)
    fun stopPingTimer()

    fun startRegistrationRenewalTimer(
        checkIntervalMs: Long = 300_000,
        renewBeforeExpirationMs: Long = 60_000
    )
    fun stopRegistrationRenewalTimer()

    fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long)
    fun renewRegistration(accountKey: String)

    fun setListener(listener: Listener)

    interface Listener {
        fun onOpen()
        fun onMessage(message: String)
        fun onClose(code: Int, reason: String)
        fun onError(error: Exception)
        fun onPong(timeMs: Long)
        fun onRegistrationRenewalRequired(accountKey: String)

        /** Conexion degradada despues de multiples intentos fallidos */
        fun onConnectionDegraded(attemptCount: Int, lastError: Exception?) {}
        /** Conexion restaurada despues de un periodo de desconexion */
        fun onConnectionRestored(downTimeMs: Long) {}
    }
}

expect fun createWebSocket(url: String, headers: Map<String, String>): MultiplatformWebSocket