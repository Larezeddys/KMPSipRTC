package com.eddyslarez.kmpsiprtc.services.livekit

/**
 * Configuracion para conectar a LiveKit SFU via el conference server.
 *
 * @param sfuServiceUrl URL del endpoint /sfu/get del conference server (ej: "http://server:3001/sfu/get")
 * @param autoPublishAudio Publicar audio automaticamente al unirse
 * @param autoSubscribe Suscribirse automaticamente a tracks remotos
 */
data class LiveKitConfig(
    val sfuServiceUrl: String,
    val autoPublishAudio: Boolean = true,
    val autoSubscribe: Boolean = true
)
