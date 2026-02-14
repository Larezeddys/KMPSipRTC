package com.eddyslarez.kmpsiprtc.services.unified

import kotlinx.serialization.Serializable

@Serializable
enum class CallType {
    SIP_EXTERNAL,    // Llamadas SIP externas
    MATRIX_INTERNAL, // Llamadas Matrix internas (P2P)
    LIVEKIT_SFU      // Llamadas Matrix via LiveKit SFU (grabacion en servidor)
}

data class UnifiedCallInfo(
    val callId: String,
    val type: CallType,
    val phoneNumber: String,
    val displayName: String?,
    val roomId: String? = null // Para llamadas Matrix
)
