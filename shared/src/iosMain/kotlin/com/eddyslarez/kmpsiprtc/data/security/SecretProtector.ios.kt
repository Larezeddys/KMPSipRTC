package com.eddyslarez.kmpsiprtc.data.security

internal actual object SecretProtector {
    actual fun protect(plain: String): String = plain

    actual fun reveal(stored: String): String = stored

    actual fun isProtected(stored: String): Boolean = true
}
