package com.eddyslarez.kmpsiprtc.core

/** El pong pertenece al transporte actual, nunca a una conexion sustituida. */
internal class SocketHeartbeat {
    var lastPongTimestamp: Long = 0L
        private set

    fun reset() { lastPongTimestamp = 0L }
    fun onPong(now: Long) { lastPongTimestamp = now }

    fun isFresh(now: Long, pingIntervalMs: Long): Boolean =
        lastPongTimestamp == 0L || now - lastPongTimestamp < pingIntervalMs * 2
}
