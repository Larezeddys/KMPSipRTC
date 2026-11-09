package com.eddyslarez.kmpsiprtc.platform

import kotlin.concurrent.Volatile

enum class SipLogLevel(internal val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    NONE(4)
}

/**
 * Configuración de logs de la librería. La aplica el logger de escritorio; los loggers de
 * Android/iOS mantienen su comportamiento.
 *
 * Por defecto solo se emiten WARN y ERROR, sin mensajes SIP completos. La app sube el nivel en
 * builds de depuración o cuando el usuario activa la grabación de logs SIP.
 */
object SipLogConfig {
    @Volatile
    var minLevel: SipLogLevel = SipLogLevel.WARN

    /** Con `false` un mensaje SIP completo se reduce a método/estado, CSeq y hash del Call-ID. */
    @Volatile
    var logFullSipMessages: Boolean = false

    fun isEnabled(level: SipLogLevel): Boolean =
        level != SipLogLevel.NONE && level.priority >= minLevel.priority
}
