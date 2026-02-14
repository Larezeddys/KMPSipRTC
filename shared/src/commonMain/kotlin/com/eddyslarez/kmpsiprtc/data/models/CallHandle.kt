package com.eddyslarez.kmpsiprtc.data.models

/**
 * Value class que envuelve un call ID para type-safety.
 * Evita confundir un callId con otros strings (phoneNumber, domain, etc.).
 *
 * Uso:
 * ```kotlin
 * val handle: CallHandle = sdk.makeCall(CallTarget.Phone("123"))
 * sdk.endCall(handle)
 * sdk.holdCall(handle)
 * ```
 */
@JvmInline
value class CallHandle(val callId: String) {
    val isValid: Boolean get() = callId.isNotBlank()
    override fun toString(): String = "CallHandle($callId)"
}
