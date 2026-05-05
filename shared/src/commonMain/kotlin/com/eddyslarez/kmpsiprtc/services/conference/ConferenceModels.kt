package com.eddyslarez.kmpsiprtc.services.conference

/**
 * Modelos para el sistema de conferencias LiveKit.
 * Estos modelos son independientes de la plataforma y se exponen al modulo consumer (app KMP).
 */

/**
 * Participante en una conferencia LiveKit.
 */
data class LkParticipant(
    val identity: String,
    val name: String,
    val sid: String = "",
    val isLocal: Boolean = false,
    val isSpeaking: Boolean = false,
    val isAudioEnabled: Boolean = false,
    val isVideoEnabled: Boolean = false,
    val isScreenSharing: Boolean = false,
    val isHandRaised: Boolean = false,
    val handRaisedAt: Long? = null,
    val videoTrackSid: String? = null,
    val screenShareTrackSid: String? = null,
)

/**
 * Estado de conexion a la conferencia.
 */
enum class LkConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    ERROR
}

/**
 * Estado de los medios locales (mic, camera, screen share).
 */
data class LkMediaState(
    val microphoneEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val screenShareEnabled: Boolean = false,
)

/**
 * Dispositivo de audio/video disponible.
 */
data class LkDevice(
    val id: String,
    val name: String,
)

/**
 * Contenedor de dispositivos disponibles.
 */
data class LkDevices(
    val cameras: List<LkDevice> = emptyList(),
    val microphones: List<LkDevice> = emptyList(),
    val speakers: List<LkDevice> = emptyList(),
    val screenShareSources: List<LkDevice> = emptyList(),
    val selectedCameraId: String? = null,
    val selectedMicrophoneId: String? = null,
    val selectedSpeakerId: String? = null,
    val selectedScreenShareSourceId: String? = null,
)

/**
 * Mensaje de chat en la conferencia LiveKit (via Data Channel).
 */
data class LkChatMessage(
    val id: String,
    val senderIdentity: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isLocal: Boolean = false,
    val isSystem: Boolean = false,
)

/**
 * Handle opaco para un video track.
 * Cada plataforma lo resuelve a su tipo nativo (ej: LiveKit VideoTrack en Android,
 * RTCVideoTrack en iOS, VideoSink en Desktop).
 */
data class LkVideoTrackHandle(
    val participantIdentity: String,
    val trackSid: String,
    val nativeTrack: Any?,
    val isScreenShare: Boolean = false,
)
