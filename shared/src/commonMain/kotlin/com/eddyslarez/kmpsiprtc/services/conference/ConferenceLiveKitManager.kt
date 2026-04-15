package com.eddyslarez.kmpsiprtc.services.conference

import kotlinx.coroutines.flow.StateFlow

/**
 * Manager principal para conferencias LiveKit.
 * Abstrae la conexion, publicacion/suscripcion de tracks,
 * gestion de participantes y dispositivos.
 *
 * Cada plataforma implementa esto usando:
 * - Android: io.livekit:livekit-android SDK oficial
 * - iOS: LiveKitClient CocoaPod (ObjC interop)
 * - Desktop: Signaling custom (LiveKitSignalingClient) + webrtc-java
 */
expect class ConferenceLiveKitManager() {

    // --- Estado reactivo ---

    /** Lista de participantes actuales en la conferencia */
    val participants: StateFlow<List<LkParticipant>>

    /** Estado de conexion a la conferencia */
    val connectionState: StateFlow<LkConnectionState>

    /** Estado de medios locales (mic, camera, screen share) */
    val mediaState: StateFlow<LkMediaState>

    /** Video tracks disponibles para rendering */
    val videoTracks: StateFlow<List<LkVideoTrackHandle>>

    /** Mensajes de chat recibidos via Data Channel */
    val chatMessages: StateFlow<List<LkChatMessage>>

    // --- Conexion ---

    /**
     * Conecta a una sala LiveKit.
     * @param url URL del servidor LiveKit (wss://...)
     * @param token JWT token generado por el conference server
     * @param participantName Nombre visible del participante local
     */
    suspend fun connect(url: String, token: String, participantName: String)

    /**
     * Desconecta de la sala LiveKit y limpia recursos.
     */
    suspend fun disconnect()

    // --- Control de medios ---

    /** Habilita/deshabilita el microfono */
    suspend fun setMicrophoneEnabled(enabled: Boolean)

    /** Habilita/deshabilita la camara */
    suspend fun setCameraEnabled(enabled: Boolean)

    /** Habilita/deshabilita screen share */
    suspend fun setScreenShareEnabled(enabled: Boolean)

    // --- Dispositivos ---

    /** Carga y retorna los dispositivos disponibles */
    suspend fun loadDevices(): LkDevices

    /** Selecciona una camara por ID */
    suspend fun selectCamera(deviceId: String)

    /** Selecciona un microfono por ID */
    suspend fun selectMicrophone(deviceId: String)

    /** Selecciona un speaker por ID */
    suspend fun selectSpeaker(deviceId: String)

    // --- Video track handles para rendering ---

    /**
     * Obtiene el handle del video track de un participante.
     * El nativeTrack dentro puede ser casteado al tipo de la plataforma
     * para rendering (ej: io.livekit.android.room.track.VideoTrack en Android).
     */
    fun getVideoTrackHandle(participantIdentity: String): LkVideoTrackHandle?

    /**
     * Obtiene el handle del screen share track de un participante.
     */
    fun getScreenShareTrackHandle(participantIdentity: String): LkVideoTrackHandle?

    /** Envia un mensaje de chat a todos los participantes via Data Channel */
    suspend fun sendChatMessage(text: String)
}
