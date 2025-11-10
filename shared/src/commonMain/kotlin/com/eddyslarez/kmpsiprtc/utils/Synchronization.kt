package com.eddyslarez.kmpsiprtc.utils

/**
 * Abstracción multiplataforma para sincronización de threads
 */
expect class Lock() {
    /**
     * Ejecuta el bloque de código de forma sincronizada
     */
    fun <T> withLock(action: () -> T): T
}

/**
 * Función de utilidad para sincronización más natural
 */
inline fun <T> synchronized(lock: Lock, noinline block: () -> T): T {
    return lock.withLock(block)}