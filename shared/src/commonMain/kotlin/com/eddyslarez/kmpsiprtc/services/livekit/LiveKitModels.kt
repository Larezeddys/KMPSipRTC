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
    val participantName: String = "",
    val otherParticipants: List<LiveKitParticipantInfo> = emptyList(),
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

/** Informacion de un participante en el protocolo LiveKit */
data class LiveKitParticipantInfo(
    val sid: String = "",
    val identity: String = "",
    val name: String = "",
    val state: Int = 0, // 0=JOINING, 1=JOINED, 2=ACTIVE, 3=DISCONNECTED
    val isPublisher: Boolean = false,
    /** metadata (ParticipantInfo field 5) — puede contener {"handRaised":..} como Element/web */
    val metadata: String = "",
    /** attributes (ParticipantInfo field 15, map<string,string>) — p.ej. handRaised="true" */
    val attributes: Map<String, String> = emptyMap(),
    /** tracks publicados (ParticipantInfo field 4) — se usa para detectar screen share remoto */
    val tracks: List<LiveKitTrackInfo> = emptyList(),
)

/**
 * Informacion de un track publicado (livekit.TrackInfo).
 * Campos relevantes: sid=1, type=2, name=3, source=9.
 */
data class LiveKitTrackInfo(
    val sid: String = "",
    val type: Int = 0,      // livekit.TrackType: 0=AUDIO, 1=VIDEO, 2=DATA
    val name: String = "",
    val source: Int = 0,    // livekit.TrackSource: 1=CAMERA, 2=MIC, 3=SCREEN_SHARE, 4=SCREEN_SHARE_AUDIO
)

/**
 * Paquete de datos de usuario decodificado del data channel (livekit.DataPacket + UserPacket).
 * El SFU rellena [senderIdentity] con la identity del participante que lo envió.
 */
data class LiveKitDataPacket(
    val senderIdentity: String = "",
    val payload: ByteArray = ByteArray(0),
    val topic: String? = null,
)

/** Update de participantes (join/leave/update) */
data class LiveKitParticipantUpdate(
    val participants: List<LiveKitParticipantInfo>
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

/**
 * Valores del enum livekit.TrackType (livekit_models.proto).
 * IMPORTANTE: deben coincidir EXACTAMENTE con el proto oficial, no hay UNKNOWN:
 *   AUDIO = 0, VIDEO = 1, DATA = 2
 * (Antes estaba corrido en +1 — VIDEO=2 — lo que rompía el AddTrackRequest.type.)
 */
enum class LiveKitTrackType(val value: Int) {
    AUDIO(0),
    VIDEO(1),
    DATA(2)
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
    data class ParticipantUpdated(val update: LiveKitParticipantUpdate) : LiveKitSignalMessage()
    data class TrackPublished(val published: LiveKitTrackPublished) : LiveKitSignalMessage()
    data class Leave(val canReconnect: Boolean, val reason: Int) : LiveKitSignalMessage()
    object Pong : LiveKitSignalMessage()
    data class Unknown(val fieldNumber: Int) : LiveKitSignalMessage()
}
