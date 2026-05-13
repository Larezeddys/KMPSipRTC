package com.eddyslarez.kmpsiprtc.services.matrix

/**
 * Configuración del cliente Matrix.
 *
 * Esta integración es **chat-only**. Las llamadas Matrix (`m.call.*`) y la
 * videollamada **están deshabilitadas por defecto** y nunca deben activarse
 * desde la app: las llamadas reales del producto van por el módulo de
 * conferencias / LiveKit, no por Matrix / Element Call.
 *
 * E2EE también está deshabilitado por ahora. El homeserver de MCN además
 * tiene `io.element.e2ee.force_disable = true` en su `.well-known`, así que
 * no hay cifrado posible incluso si se activara aquí.
 *
 * Los flags `enableVoip`, `enableVideo` y `enableEncryption` se conservan
 * para futura compatibilidad pero el código del MatrixManager los respeta
 * estrictamente: cualquier intento de iniciar/contestar una llamada con
 * los flags en `false` retorna sin efecto.
 */
data class MatrixConfig(
    /** Homeserver de MCN por defecto. Puede sobreescribirse para staging/dev. */
    val homeserverUrl: String = "https://matrix.m.mcn.hu",
    val deviceDisplayName: String = "MCN Softphone",
    /**
     * E2EE: deshabilitado. El homeserver lo fuerza off; además no hay UI ni
     * key management implementado. Si una room está cifrada, la UI lo señala
     * como "no soportado" y bloquea envío/lectura del contenido cifrado.
     */
    val enableEncryption: Boolean = false,
    val syncTimeout: Long = 30000L,
    /**
     * VoIP Matrix: deshabilitado. Ver doc de clase. NO activar — las llamadas
     * van por el módulo de conferencias.
     */
    val enableVoip: Boolean = false,
    /** Video Matrix: deshabilitado. Ver doc de clase. NO activar. */
    val enableVideo: Boolean = false,
    val enableFileTransfer: Boolean = true,
    val maxFileUploadSize: Long = 100 * 1024 * 1024 // 100MB
)
