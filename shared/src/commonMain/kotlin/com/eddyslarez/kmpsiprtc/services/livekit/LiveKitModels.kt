package com.eddyslarez.kmpsiprtc.services.livekit

import kotlinx.serialization.Serializable

// --- Respuesta del conference server /sfu/get ---

@Serializable
data class SfuTokenResponse(
    val url: String,
    val jwt: String
)

// --- Modelos decodificados del protocolo LiveKit ---

data class LiveKitJoinResponse(
    val room: LiveKitRoom?,
    val participantSid: String,
    val participantIdentity: String,
    val iceServers: List<LiveKitIceServer>,
    val subscriberPrimary: Boolean,
    val serverVersion: String
)

data class LiveKitRoom(
    val sid: String,
    val name: String,
    val numParticipants: Int
)

data class LiveKitIceServer(
    val urls: List<String>,
    val username: String,
    val credential: String
)

data class LiveKitTrackPublished(
    val cid: String,
    val trackSid: String,
    val trackName: String
)

data class LiveKitSessionDescription(
    val type: String,  // "offer" o "answer"
    val sdp: String
)

data class LiveKitTrickle(
    val candidateInit: String,
    val target: Int  // 0 = PUBLISHER, 1 = SUBSCRIBER
)

enum class LiveKitSignalTarget(val value: Int) {
    PUBLISHER(0),
    SUBSCRIBER(1)
}

enum class LiveKitTrackType(val value: Int) {
    UNKNOWN(0),
    AUDIO(1),
    VIDEO(2),
    DATA(3)
}

enum class LiveKitTrackSource(val value: Int) {
    UNKNOWN(0),
    CAMERA(1),
    MICROPHONE(2),
    SCREEN_SHARE(3),
    SCREEN_SHARE_AUDIO(4)
}

// Estado de conexion a LiveKit
sealed class LiveKitConnectionState {
    object Disconnected : LiveKitConnectionState()
    object Connecting : LiveKitConnectionState()
    object Connected : LiveKitConnectionState()
    object Reconnecting : LiveKitConnectionState()
    data class Error(val message: String) : LiveKitConnectionState()

    override fun toString(): String = this::class.simpleName ?: "Unknown"
}

// Tipo de mensaje recibido del servidor
sealed class LiveKitSignalMessage {
    data class Join(val joinResponse: LiveKitJoinResponse) : LiveKitSignalMessage()
    data class Answer(val sdp: LiveKitSessionDescription) : LiveKitSignalMessage()
    data class Offer(val sdp: LiveKitSessionDescription) : LiveKitSignalMessage()
    data class Trickle(val trickle: LiveKitTrickle) : LiveKitSignalMessage()
    data class TrackPublished(val published: LiveKitTrackPublished) : LiveKitSignalMessage()
    data class Leave(val canReconnect: Boolean, val reason: Int) : LiveKitSignalMessage()
    data class Unknown(val fieldNumber: Int) : LiveKitSignalMessage()
}
