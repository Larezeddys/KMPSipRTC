package com.eddyslarez.kmpsiprtc.services.matrix

/**
 * Configuración del cliente Matrix.
 *
 * Librería de propósito general: funciona contra **cualquier homeserver** Matrix.
 * El [homeserverUrl] puede ser una URL completa (`https://matrix.org`) o un
 * dominio a descubrir vía `.well-known/matrix/client`. El login resuelve el
 * baseUrl real antes de autenticar.
 *
 * **E2EE**: deshabilitado por ahora. No hay key management/device verification
 * implementado; si una room está cifrada la UI debe señalarlo. El camino queda
 * abierto para una iteración futura (Trixnity soporta Olm/Megolm).
 *
 * **Llamadas**: el VoIP nativo de Matrix (`m.call.*`) está habilitado por
 * defecto y convive con LiveKit. El `UnifiedCallRouter` decide la ruta por
 * destino. Poner [enableVoip]/[enableVideo] en false desactiva el VoIP nativo
 * (las llamadas irían sólo por LiveKit/conferencias).
 */
data class MatrixConfig(
    /**
     * Homeserver. URL completa o dominio. Si es un dominio (sin esquema o sin
     * endpoint conocido) se intenta descubrir vía `.well-known/matrix/client`.
     */
    val homeserverUrl: String = "https://matrix.org",
    val deviceDisplayName: String = "KMP SIP RTC",
    /**
     * Resolución automática del baseUrl vía `.well-known/matrix/client`.
     * Útil cuando el usuario escribe sólo su dominio (`@user:dominio.com`).
     */
    val enableWellKnownDiscovery: Boolean = true,
    /**
     * E2EE: deshabilitado. Ver doc de clase.
     */
    val enableEncryption: Boolean = false,
    val syncTimeout: Long = 30000L,
    /** VoIP nativo Matrix (audio). Convive con LiveKit. */
    val enableVoip: Boolean = true,
    /**
     * Video nativo Matrix 1:1: DESACTIVADO por decisión de producto — el stack
     * WebRTC 1:1 es solo-audio; el video real va por conferencias LiveKit
     * (la app redirige el botón de video a una conferencia).
     */
    val enableVideo: Boolean = false,
    val enableFileTransfer: Boolean = true,
    val maxFileUploadSize: Long = 100 * 1024 * 1024, // 100MB
    /** Si true, marca automáticamente como leído el último mensaje al recibirlo. */
    val autoMarkRead: Boolean = false,
    /** Si true, publica y observa presencia (online/offline). */
    val presenceEnabled: Boolean = true,
    /** Timeout del indicador de "escribiendo" enviado al servidor (ms). */
    val typingTimeoutMs: Long = 15000L,
)
