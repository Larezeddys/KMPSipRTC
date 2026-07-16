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
 * Motivo de la ultima desconexion de la sala LiveKit. El SFU siempre envia
 * este motivo (protobuf DisconnectReason via RoomEvent.Disconnected en
 * Android, o LeaveRequest.reason en el signaling manual de Desktop), pero
 * antes de este modelo se descartaba en ambas plataformas y la UI solo veia
 * "DISCONNECTED" sin poder explicarle al usuario por que salio de la sala.
 */
enum class LkDisconnectReason {
    UNKNOWN,
    CLIENT_INITIATED,
    DUPLICATE_IDENTITY,
    SERVER_SHUTDOWN,
    PARTICIPANT_REMOVED,
    ROOM_DELETED,
    ROOM_CLOSED,
    STATE_MISMATCH,
    JOIN_FAILURE,
    SIGNAL_CLOSE,
    CONNECTION_TIMEOUT,
    MEDIA_FAILURE,
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
