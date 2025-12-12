package com.eddyslarez.kmpsiprtc.utils

import platform.Foundation.NSLock

actual class Lock {
    private val lock = NSLock()

    actual fun <T> withLock(action: () -> T): T {
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}