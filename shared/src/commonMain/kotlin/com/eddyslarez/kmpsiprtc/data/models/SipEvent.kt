package com.eddyslarez.kmpsiprtc.data.models

/**
 * Jerarquía unificada de eventos SIP.
 * Reemplaza los 4 listener interfaces fragmentados (SipEventListener, RegistrationListener,
 * CallListener, IncomingCallListener) con un único sealed class observable via Flow.
 *
 * Uso:
 * ```kotlin
 * KmpSipRtc.getInstance().observeEvents().collect { event ->
 *     when (event) {
 *         is SipEvent.Registration.StateChanged -> ...
 *         is SipEvent.Call.Incoming -> ...
 *         is SipEvent.Call.Connected -> ...
 *         is SipEvent.Call.Ended -> ...
 *         ...
 *     }
 * }
 * ```
 */
sealed class SipEvent {

    /** Timestamp del evento */
    abstract val timestamp: Long

    // ==================== REGISTRATION ====================

    sealed class Registration : SipEvent() {
        abstract val username: String
        abstract val domain: String

        data class StateChanged(
            val state: RegistrationState,
            override val username: String,
            override val domain: String,
            override val timestamp: Long = currentTimeMs()
        ) : Registration()

        data class Failed(
            override val username: String,
            override val domain: String,
            val error: String,
            override val timestamp: Long = currentTimeMs()
        ) : Registration()

        data class Expiring(
            override val username: String,
            override val domain: String,
            val expiresInMs: Long,
            override val timestamp: Long = currentTimeMs()
        ) : Registration()
    }

    // ==================== CALL ====================

    sealed class Call : SipEvent() {
        abstract val callId: String

        data class StateChanged(
            override val callId: String,
            val stateInfo: CallStateInfo,
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class Incoming(
            override val callId: String,
            val callerNumber: String,
            val callerName: String?,
            val targetAccount: String,
            val headers: Map<String, String> = emptyMap(),
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class IncomingCancelled(
            override val callId: String,
            val callerNumber: String,
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class Connected(
            override val callId: String,
            val phoneNumber: String,
            val direction: CallDirections,
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class Ended(
            override val callId: String,
            val phoneNumber: String,
            val direction: CallDirections,
            val reason: CallEndReason,
            val durationMs: Long = 0,
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class Failed(
            override val callId: String,
            val error: String,
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class HoldChanged(
            override val callId: String,
            val isOnHold: Boolean,
            override val timestamp: Long = currentTimeMs()
        ) : Call()

        data class MuteChanged(
            override val callId: String,
            val isMuted: Boolean,
            override val timestamp: Long = currentTimeMs()
        ) : Call()
    }

    // ==================== NETWORK/AUDIO ====================

    data class NetworkChanged(
        val isConnected: Boolean,
        override val timestamp: Long = currentTimeMs()
    ) : SipEvent()

    data class AudioDeviceChanged(
        val deviceName: String,
        override val timestamp: Long = currentTimeMs()
    ) : SipEvent()
}

/**
 * Razones de finalización de llamada
 */
enum class CallEndReason {
    NORMAL_HANGUP,
    BUSY,
    NO_ANSWER,
    REJECTED,
    NETWORK_ERROR,
    CANCELLED,
    TIMEOUT,
    ERROR
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
