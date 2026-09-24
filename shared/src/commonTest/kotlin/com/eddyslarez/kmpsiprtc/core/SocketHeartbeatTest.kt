package com.eddyslarez.kmpsiprtc.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocketHeartbeatTest {
    @Test
    fun reconnectAfterBackgroundDoesNotInheritExpiredPong() {
        val heartbeat = SocketHeartbeat()
        heartbeat.onPong(1_000L)
        assertFalse(heartbeat.isFresh(1_331_000L, 30_000L))
        // Reconexion voluntaria, sin onClose/onError del transporte anterior.
        heartbeat.reset()
        assertTrue(heartbeat.isFresh(1_332_000L, 30_000L))
        heartbeat.onPong(1_333_000L)
        assertTrue(heartbeat.isFresh(1_362_000L, 30_000L))
        assertFalse(heartbeat.isFresh(1_393_000L, 30_000L))
    }
}
