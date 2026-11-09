package com.eddyslarez.kmpsiprtc.data.security

/** Cifrado en reposo de secretos guardados en la base de datos (contraseña SIP). */
internal expect object SecretProtector {
    fun protect(plain: String): String

    /** Devuelve el texto plano; un valor sin cifrar se devuelve tal cual. */
    fun reveal(stored: String): String

    /** `false` si el valor sigue en claro y debe migrarse. */
    fun isProtected(stored: String): Boolean
}
