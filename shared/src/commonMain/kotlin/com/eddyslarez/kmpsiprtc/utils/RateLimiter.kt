package com.eddyslarez.kmpsiprtc.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ExperimentalTime

/**
 * Rate limiter simple basado en ventana deslizante.
 * Previene spam de operaciones (state updates, API calls, reconexiones).
 *
 * ```kotlin
 * private val callRateLimiter = RateLimiter(maxCalls = 5, windowMs = 10_000)
 *
 * fun makeCall(number: String) {
 *     if (!callRateLimiter.tryAcquire()) {
 *         log.w("Rate limited: too many calls in short period")
 *         return
 *     }
 *     // proceder con la llamada
 * }
 * ```
 */
internal class RateLimiter(
    private val maxCalls: Int,
    private val windowMs: Long
) {
    private val timestamps = ArrayDeque<Long>(maxCalls)
    private val mutex = Mutex()

    /**
     * Intenta adquirir un permiso.
     * @return true si se permite la operación, false si se excedió el límite
     */
    @OptIn(ExperimentalTime::class)
    suspend fun tryAcquire(): Boolean = mutex.withLock {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

        // Limpiar timestamps viejos fuera de la ventana
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }

        if (timestamps.size >= maxCalls) {
            return@withLock false
        }

        timestamps.addLast(now)
        true
    }

    /**
     * Versión no-suspend para contextos que no pueden suspender.
     * Usa la misma lógica pero sin Mutex (menos seguro, pero funcional).
     */
    @OptIn(ExperimentalTime::class)
    fun tryAcquireBlocking(): Boolean {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

        // Limpiar timestamps viejos
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }

        if (timestamps.size >= maxCalls) {
            return false
        }

        timestamps.addLast(now)
        return true
    }

    /**
     * Resetea el rate limiter
     */
    fun reset() {
        timestamps.clear()
    }

    /**
     * Cantidad de operaciones restantes en la ventana actual
     */
    @OptIn(ExperimentalTime::class)
    fun remainingCapacity(): Int {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val activeCount = timestamps.count { now - it <= windowMs }
        return (maxCalls - activeCount).coerceAtLeast(0)
    }
}
