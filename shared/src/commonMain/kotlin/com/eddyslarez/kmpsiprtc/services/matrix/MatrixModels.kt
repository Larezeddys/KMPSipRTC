package com.eddyslarez.kmpsiprtc.services.matrix

import kotlinx.coroutines.flow.StateFlow

data class MatrixRoom(
    val id: String,
    val name: String,
    /**
     * URL HTTPS resuelta del avatar de la room (incluye `?access_token=`).
     * null si el room no tiene avatar configurado. Se resuelve a partir del
     * `avatar_url` en `mxc://...` del state event m.room.avatar (o, para DMs
     * sin avatar de room, del avatar del hero).
     */
    val avatarUrl: String?,
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    val unreadCount: Int,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val members: List<String> = emptyList(),
)

/**
 * Estado de una llamada Matrix.
 *
 * @deprecated Las llamadas reales del producto NO usan Matrix VoIP — van por el
 * módulo de conferencias / LiveKit. Este tipo se mantiene únicamente para
 * compatibilidad con código heredado y nunca debe llegar a la UI en producción.
 * Cualquier nuevo desarrollo de llamadas debe usar `conference/` no este modelo.
 */
@Deprecated(
    message = "Matrix calls están deshabilitadas. Las llamadas usan conference/LiveKit.",
    level = DeprecationLevel.WARNING
)
data class MatrixCall(
    val callId: String,
    val roomId: String,
    val isVideo: Boolean,
    val state: MatrixCallState,
    val remoteSdp: String? = null,
    val localSdp: String? = null,
    val participants: List<String> = emptyList()
)

@Deprecated(
    message = "Matrix calls están deshabilitadas. Las llamadas usan conference/LiveKit.",
    level = DeprecationLevel.WARNING
)
enum class MatrixCallState {
    IDLE,
    INVITING,
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    ERROR
}

sealed class MatrixConnectionState {
    object Disconnected : MatrixConnectionState()
    object Initialized : MatrixConnectionState()
    object Connecting : MatrixConnectionState()
    object Connected : MatrixConnectionState()
    data class Error(val message: String) : MatrixConnectionState()
}

data class MatrixMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val senderDisplayName: String?,
    val content: String,
    val timestamp: Long,
    val type: MessageType,
    /**
     * URL HTTPS resuelta de la media (si es un mensaje IMAGE/VIDEO/AUDIO/FILE).
     * Apunta al endpoint `/_matrix/media/v3/download/...` con el access token
     * adjunto (`?access_token=...`). Para tipo TEXT siempre es null.
     */
    val mediaUrl: String? = null,
    /**
     * Nombre original del archivo (sólo para tipos no-TEXT). Útil para mostrar
     * "📎 informe.pdf" o como caption de la imagen.
     */
    val fileName: String? = null,
)

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,

    /** @deprecated Matrix calls deshabilitadas. Las llamadas usan conference/LiveKit. */
    @Deprecated("Matrix calls deshabilitadas. Las llamadas usan conference/LiveKit.")
    CALL_INVITE,

    /** @deprecated Matrix calls deshabilitadas. Las llamadas usan conference/LiveKit. */
    @Deprecated("Matrix calls deshabilitadas. Las llamadas usan conference/LiveKit.")
    CALL_ANSWER,

    /** @deprecated Matrix calls deshabilitadas. Las llamadas usan conference/LiveKit. */
    @Deprecated("Matrix calls deshabilitadas. Las llamadas usan conference/LiveKit.")
    CALL_HANGUP
}
