package com.eddyslarez.kmpsiprtc.utils

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as ktWithLock

actual class Lock {
    private val lock = ReentrantLock()

    actual fun <T> withLock(action: () -> T): T {
        return lock.ktWithLock(action)
    }
}