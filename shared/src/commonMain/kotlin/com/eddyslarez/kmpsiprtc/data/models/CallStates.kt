package com.eddyslarez.kmpsiprtc.data.models

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

@Serializable
enum class CallState {
    // Estados iniciales
    IDLE,

    // Estados de llamada saliente
    OUTGOING_INIT,
    OUTGOING_PROGRESS,
    OUTGOING_RINGING,

    // Estados de llamada entrante
    INCOMING_RECEIVED,

    // Estados conectados
    CONNECTED,
    STREAMS_RUNNING,

    // Estados de pausa/hold
    PAUSING,
    PAUSED,
    RESUMING,

    // Estados de finalización
    ENDING,
    ENDED,

    // Estados de error
    ERROR
}

@Serializable
enum class CallErrorReason {
    NONE,
    BUSY,
    NO_ANSWER,
    REJECTED,
    TEMPORARILY_UNAVAILABLE,
    NOT_FOUND,
    FORBIDDEN,
    NETWORK_ERROR,
    AUTHENTICATION_FAILED,
    SERVER_ERROR,
    UNKNOWN
}

@Serializable
data class CallStateInfo(
    val state: CallState,
    val previousState: CallState? = null,
    val errorReason: CallErrorReason = CallErrorReason.NONE,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val sipCode: Int? = null,
    val sipReason: String? = null,
    val callId: String = "",
    val direction: CallDirections = CallDirections.OUTGOING
) {
    fun isOutgoingCall(): Boolean = direction == CallDirections.OUTGOING
    fun isIncomingCall(): Boolean = direction == CallDirections.INCOMING
    fun isConnected(): Boolean = state == CallState.CONNECTED || state == CallState.STREAMS_RUNNING
    fun isActive(): Boolean = state != CallState.IDLE && state != CallState.ENDED && state != CallState.ERROR
    fun hasError(): Boolean = state == CallState.ERROR || errorReason != CallErrorReason.NONE
    fun isOnHold(): Boolean = state == CallState.PAUSED
    fun isTransitioning(): Boolean =
        state == CallState.PAUSING || state == CallState.RESUMING || state == CallState.ENDING
}

/**
 * Mapeo de códigos SIP a razones de error
 */
object SipErrorMapper {
    fun mapSipCodeToErrorReason(sipCode: Int): CallErrorReason {
        return when (sipCode) {
            486 -> CallErrorReason.BUSY
            408 -> CallErrorReason.NO_ANSWER
            603 -> CallErrorReason.REJECTED
            480 -> CallErrorReason.TEMPORARILY_UNAVAILABLE
            404 -> CallErrorReason.NOT_FOUND
            403 -> CallErrorReason.FORBIDDEN
            401, 407 -> CallErrorReason.AUTHENTICATION_FAILED
            in 500..599 -> CallErrorReason.SERVER_ERROR
            else -> CallErrorReason.UNKNOWN
        }
    }

    fun getErrorDescription(reason: CallErrorReason): String {
        return when (reason) {
            CallErrorReason.NONE -> "Sin error"
            CallErrorReason.BUSY -> "Ocupado"
            CallErrorReason.NO_ANSWER -> "Sin respuesta"
            CallErrorReason.REJECTED -> "Rechazada"
            CallErrorReason.TEMPORARILY_UNAVAILABLE -> "Temporalmente no disponible"
            CallErrorReason.NOT_FOUND -> "Usuario no encontrado"
            CallErrorReason.FORBIDDEN -> "Prohibido"
            CallErrorReason.NETWORK_ERROR -> "Error de red"
            CallErrorReason.AUTHENTICATION_FAILED -> "Error de autenticación"
            CallErrorReason.SERVER_ERROR -> "Error del servidor"
            CallErrorReason.UNKNOWN -> "Error desconocido"
        }
    }
}

/**
 * Validador de transiciones de estado
 */
object CallStateTransitionValidator {

    private val validOutgoingTransitions = mapOf(
        CallState.IDLE to setOf(CallState.OUTGOING_INIT),
        CallState.OUTGOING_INIT to setOf(CallState.OUTGOING_PROGRESS, CallState.CONNECTED, CallState.OUTGOING_RINGING, CallState.ERROR, CallState.ENDING),
        CallState.OUTGOING_PROGRESS to setOf(CallState.OUTGOING_RINGING, CallState.CONNECTED, CallState.ERROR, CallState.ENDING),
        CallState.OUTGOING_RINGING to setOf(CallState.CONNECTED, CallState.ERROR, CallState.ENDING),
        CallState.CONNECTED to setOf(CallState.STREAMS_RUNNING, CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.STREAMS_RUNNING to setOf(CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.PAUSING to setOf(CallState.PAUSED, CallState.ERROR),
        CallState.PAUSED to setOf(CallState.RESUMING, CallState.ENDING, CallState.ERROR),
        CallState.RESUMING to setOf(CallState.STREAMS_RUNNING, CallState.ERROR),
        CallState.ENDING to setOf(CallState.ENDED),
        CallState.ENDED to setOf(CallState.IDLE),
        CallState.ERROR to setOf(CallState.ENDED, CallState.IDLE)
    )

    private val validIncomingTransitions = mapOf(
        CallState.IDLE to setOf(CallState.INCOMING_RECEIVED),
       CallState.INCOMING_RECEIVED to setOf(CallState.CONNECTED, CallState.ERROR, CallState.ENDING),
        CallState.CONNECTED to setOf(CallState.STREAMS_RUNNING, CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.STREAMS_RUNNING to setOf(CallState.PAUSING, CallState.ENDING, CallState.ERROR),
        CallState.PAUSING to setOf(CallState.PAUSED, CallState.ERROR),
        CallState.PAUSED to setOf(CallState.RESUMING, CallState.ENDING, CallState.ERROR),
        CallState.RESUMING to setOf(CallState.STREAMS_RUNNING, CallState.ERROR),
        CallState.ENDING to setOf(CallState.ENDED),
        CallState.ENDED to setOf(CallState.IDLE),
        CallState.ERROR to setOf(CallState.ENDED, CallState.IDLE)
    )

    fun isValidTransition(
        from: CallState,
        to: CallState,
        direction: CallDirections
    ): Boolean {
        val validTransitions = if (direction == CallDirections.OUTGOING) {
            validOutgoingTransitions
        } else {
            validIncomingTransitions
        }

        return validTransitions[from]?.contains(to) ?: false
    }

    fun getValidNextStates(
        currentState: CallState,
        direction: CallDirections
    ): Set<CallState> {
        val validTransitions = if (direction == CallDirections.OUTGOING) {
            validOutgoingTransitions
        } else {
            validIncomingTransitions
        }

        return validTransitions[currentState] ?: emptySet()
    }
}