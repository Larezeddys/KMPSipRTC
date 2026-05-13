package com.eddyslarez.kmpsiprtc.services.matrix

import kotlinx.coroutines.flow.StateFlow

data class MatrixRoom(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val isDirect: Boolean,
    val isEncrypted: Boolean,
    val unreadCount: Int,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    val members: List<String> = emptyList()
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
    val type: MessageType
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
