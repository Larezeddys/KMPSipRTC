package com.eddyslarez.kmpsiprtc.services.unified

enum class CallType {
    SIP_EXTERNAL,    // Llamadas SIP externas
    MATRIX_INTERNAL  // Llamadas Matrix internas
}

data class UnifiedCallInfo(
    val callId: String,
    val type: CallType,
    val phoneNumber: String,
    val displayName: String?,
    val roomId: String? = null // Para llamadas Matrix
)
