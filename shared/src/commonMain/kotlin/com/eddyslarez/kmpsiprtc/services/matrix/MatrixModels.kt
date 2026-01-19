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

data class MatrixCall(
    val callId: String,
    val roomId: String,
    val isVideo: Boolean,
    val state: MatrixCallState,
    val remoteSdp: String? = null,
    val localSdp: String? = null,
    val participants: List<String> = emptyList()
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
    CALL_INVITE,
    CALL_ANSWER,
    CALL_HANGUP
}
