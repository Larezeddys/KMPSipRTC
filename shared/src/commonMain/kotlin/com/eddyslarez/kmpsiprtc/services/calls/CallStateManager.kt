package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallStateInfo
import com.eddyslarez.kmpsiprtc.data.models.CallStateTransitionValidator
import com.eddyslarez.kmpsiprtc.data.models.SipErrorMapper
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

object CallStateManager {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _callStateFlow = MutableStateFlow(
        CallStateInfo(
            state = CallState.IDLE,
            previousState = null,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
    )
    val callStateFlow: StateFlow<CallStateInfo> = _callStateFlow.asStateFlow()

    private val _callHistoryFlow = MutableStateFlow<List<CallStateInfo>>(emptyList())
    val callHistoryFlow: StateFlow<List<CallStateInfo>> = _callHistoryFlow.asStateFlow()

    // Estado interno
    private var currentCallId: String = ""
    private var currentDirection: CallDirections = CallDirections.OUTGOING
    private var currentCallerNumber: String = ""
    private var isInitialized = false
    private var lastStateUpdate = 0L

    // Constantes para prevenir spam de estados
    private const val MIN_STATE_UPDATE_INTERVAL = 100L

    /**
     * Inicialización del gestor (llamar solo una vez)
     */
    fun initialize() {
        if (!isInitialized) {
            isInitialized = true
            log.d(tag = "CallStateManager") { "CallStateManager initialized" }
        }
    }

    /**
     * Actualiza el estado de la llamada con validaciones estrictas
     */
    internal fun updateCallState(
        newState: CallState,
        callId: String = currentCallId,
        direction: CallDirections = currentDirection,
        sipCode: Int? = null,
        sipReason: String? = null,
        errorReason: CallErrorReason = CallErrorReason.NONE,
        forceUpdate: Boolean = false
    ): Boolean {

        // Verificar si está inicializado
        if (!isInitialized) {
            log.w(tag = "CallStateManager") {
                "State update attempted before initialization: $newState"
            }
            return false
        }

        val currentTime = Clock.System.now().toEpochMilliseconds()
        val currentStateInfo = _callStateFlow.value

        // Prevenir actualizaciones muy frecuentes (spam)
        if (!forceUpdate && currentTime - lastStateUpdate < MIN_STATE_UPDATE_INTERVAL) {
            log.d(tag = "CallStateManager") {
                "State update too frequent, skipping: $newState"
            }
            return false
        }

        // Validación estricta para prevenir estados duplicados
        if (currentStateInfo.state == newState &&
            currentStateInfo.callId == callId &&
            currentStateInfo.errorReason == errorReason &&
            !forceUpdate
        ) {
            log.d(tag = "CallStateManager") {
                "Duplicate state transition prevented: $newState for call $callId"
            }
            return false
        }

        // Validar que tenemos un callId válido para estados activos
        if (newState != CallState.IDLE &&
            newState != CallState.ERROR &&
            callId.isEmpty()
        ) {
            log.w(tag = "CallStateManager") {
                "Invalid callId for active state: $newState"
            }
            return false
        }

        // Validar transición
        if (!forceUpdate && !CallStateTransitionValidator.isValidTransition(
                currentStateInfo.state,
                newState,
                direction
            )
        ) {
            log.w(tag = "CallStateManager") {
                "Invalid state transition: ${currentStateInfo.state} -> $newState for $direction call"
            }

            // Permitir solo transiciones críticas de emergencia
            if (newState != CallState.ERROR &&
                newState != CallState.ENDED &&
                newState != CallState.IDLE
            ) {
                return false
            }
        }

        // Crear nueva información de estado
        val newStateInfo = CallStateInfo(
            state = newState,
            previousState = currentStateInfo.state,
            errorReason = errorReason,
            timestamp = currentTime,
            sipCode = sipCode,
            sipReason = sipReason,
            callId = callId,
            direction = direction
        )

        // Actualizar estado actual
        _callStateFlow.value = newStateInfo
        lastStateUpdate = currentTime

        // Actualizar en MultiCallManager también
        MultiCallManager.updateCallState(callId, newState, errorReason)

        // Añadir al historial
        addToHistory(newStateInfo)

        // Actualizar información de llamada actual
        updateCurrentCallInfo(newState, callId, direction)

        log.d(tag = "CallStateManager") {
            "✓ State transition: ${currentStateInfo.state} -> $newState for call $callId (${direction.name})"
        }

        return true
    }

    /**
     * Añadir al historial con límite
     */
    private fun addToHistory(stateInfo: CallStateInfo) {
        val currentHistory = _callHistoryFlow.value.toMutableList()
        currentHistory.add(stateInfo)

        // Mantener solo los últimos 30 estados para evitar memory leaks
        if (currentHistory.size > 30) {
            currentHistory.removeAt(0)
        }
        _callHistoryFlow.value = currentHistory
    }

    /**
     * Actualizar información de llamada actual
     */
    private fun updateCurrentCallInfo(newState: CallState, callId: String, direction: CallDirections) {
        when (newState) {
            CallState.IDLE, CallState.ENDED, CallState.ERROR -> {
                // Solo limpiar si es la llamada actual
                if (currentCallId == callId || callId.isEmpty()) {
                    currentCallId = ""
                    currentCallerNumber = ""
                }
            }
            else -> {
                if (callId.isNotEmpty()) {
                    currentCallId = callId
                    currentDirection = direction
                }
            }
        }
    }

    // === MÉTODOS MEJORADOS PARA TRANSICIONES ===

    fun startOutgoingCall(callId: String, phoneNumber: String) {
        if (!isInitialized) return

        currentCallerNumber = phoneNumber

        val callData = CallData(
            callId = callId,
            to = phoneNumber,
            from = "",
            direction = CallDirections.OUTGOING,
            startTime = Clock.System.now().toEpochMilliseconds()
        )
        MultiCallManager.addCall(callData)

        updateCallState(
            newState = CallState.OUTGOING_INIT,
            callId = callId,
            direction = CallDirections.OUTGOING,
            forceUpdate = true
        )
    }

    fun incomingCallReceived(callId: String, callerNumber: String) {
        if (!isInitialized) return

        currentCallerNumber = callerNumber

        val callData = CallData(
            callId = callId,
            to = "",
            from = callerNumber,
            direction = CallDirections.INCOMING,
            startTime = Clock.System.now().toEpochMilliseconds()
        )
        MultiCallManager.addCall(callData)

        updateCallState(
            newState = CallState.INCOMING_RECEIVED,
            callId = callId,
            direction = CallDirections.INCOMING,
            forceUpdate = true
        )
    }

    fun callConnected(callId: String, sipCode: Int = 200) {
        if (!isInitialized) return

        updateCallState(
            newState = CallState.CONNECTED,
            callId = callId,
            sipCode = sipCode,
            sipReason = "OK"
        )
    }

    fun streamsRunning(callId: String) {
        if (!isInitialized) return

        updateCallState(
            newState = CallState.STREAMS_RUNNING,
            callId = callId
        )
    }

    fun startEnding(callId: String) {
        if (!isInitialized) return

        updateCallState(
            newState = CallState.ENDING,
            callId = callId
        )
    }

    fun callEnded(callId: String, sipCode: Int? = null, sipReason: String? = null) {
        if (!isInitialized) return

        updateCallState(
            newState = CallState.ENDED,
            callId = callId,
            sipCode = sipCode,
            sipReason = sipReason,
            forceUpdate = true
        )

        // Programar limpieza de la llamada
        scope.launch {
            kotlinx.coroutines.delay(1000)
            cleanupCall(callId)
        }
    }

    fun callError(
        callId: String,
        sipCode: Int? = null,
        sipReason: String? = null,
        errorReason: CallErrorReason = CallErrorReason.UNKNOWN
    ) {
        if (!isInitialized) return

        val mappedError = if (sipCode != null) {
            SipErrorMapper.mapSipCodeToErrorReason(sipCode)
        } else {
            errorReason
        }

        updateCallState(
            newState = CallState.ERROR,
            callId = callId,
            sipCode = sipCode,
            sipReason = sipReason,
            errorReason = mappedError,
            forceUpdate = true
        )

        // Programar limpieza
        scope.launch {
            kotlinx.coroutines.delay(2000)
            cleanupCall(callId)
        }
    }

    /**
     * Limpieza completa de una llamada
     */
    private fun cleanupCall(callId: String) {
        MultiCallManager.removeCall(callId)

        // Si es la llamada actual, resetear a IDLE
        if (currentCallId == callId) {
            forceResetToIdle()
        }
    }

    /**
     * Reset forzado a IDLE
     */
    fun forceResetToIdle() {
        MultiCallManager.clearAllCalls()

        updateCallState(
            newState = CallState.IDLE,
            callId = "",
            direction = CallDirections.OUTGOING,
            forceUpdate = true
        )

        currentCallerNumber = ""
        currentCallId = ""
    }

    /**
     * Reset completo (solo usar en shutdown)
     */
    fun resetToIdle() {
        if (!isInitialized) return
        forceResetToIdle()
    }

    // === MÉTODOS DE CONSULTA ===

    fun getCurrentState(): CallStateInfo = _callStateFlow.value
    fun getCurrentCallId(): String = currentCallId
    fun getCurrentCallerNumber(): String = currentCallerNumber
    fun isCallActive(): Boolean = _callStateFlow.value.isActive()
    fun isCallConnected(): Boolean = _callStateFlow.value.isConnected()
    fun hasError(): Boolean = _callStateFlow.value.hasError()

    fun getStateHistory(): List<CallStateInfo> = _callHistoryFlow.value
    fun clearHistory() {
        _callHistoryFlow.value = emptyList()
    }

    fun getStateForCall(callId: String): CallStateInfo? {
        return MultiCallManager.getCallState(callId)
    }

    // === MÉTODOS AUXILIARES PARA COMPATIBILIDAD ===

    fun callerNumber(number: String) {
        currentCallerNumber = number
    }

    fun callId(id: String) {
        currentCallId = id
    }

    fun outgoingCallProgress(callId: String, sipCode: Int = 183) {
        if (!isInitialized) return
        updateCallState(
            newState = CallState.OUTGOING_PROGRESS,
            callId = callId,
            sipCode = sipCode,
            sipReason = "Session Progress"
        )
    }

    fun outgoingCallRinging(callId: String, sipCode: Int = 180) {
        if (!isInitialized) return
        updateCallState(
            newState = CallState.OUTGOING_RINGING,
            callId = callId,
            sipCode = sipCode,
            sipReason = "Ringing"
        )
    }

    fun startHold(callId: String) {
        if (!isInitialized) return

        val currentState = getCurrentState()
        if (currentState.state != CallState.STREAMS_RUNNING && currentState.state != CallState.CONNECTED) {
            log.w(tag = "CallStateManager") { "Cannot hold call in state: ${currentState.state}" }
            return
        }

        updateCallState(
            newState = CallState.PAUSING,
            callId = callId
        )
    }

    fun callOnHold(callId: String) {
        if (!isInitialized) return

        val currentState = getCurrentState()
        if (currentState.state != CallState.PAUSING) {
            log.w(tag = "CallStateManager") { "Invalid transition to PAUSED from: ${currentState.state}" }
        }

        updateCallState(
            newState = CallState.PAUSED,
            callId = callId
        )
    }

    fun startResume(callId: String) {
        if (!isInitialized) return

        val currentState = getCurrentState()
        if (currentState.state != CallState.PAUSED) {
            log.w(tag = "CallStateManager") { "Cannot resume call in state: ${currentState.state}" }
            return
        }

        updateCallState(
            newState = CallState.RESUMING,
            callId = callId
        )
    }
    /**
     * NUEVO: Método para completar resume
     */
    fun callResumed(callId: String) {
        if (!isInitialized) return

        val currentState = getCurrentState()
        if (currentState.state != CallState.RESUMING) {
            log.w(tag = "CallStateManager") { "Invalid transition to STREAMS_RUNNING from: ${currentState.state}" }
        }

        updateCallState(
            newState = CallState.STREAMS_RUNNING,
            callId = callId
        )
    }


    /**
     * Diagnóstico mejorado
     */
    fun getDiagnosticInfo(): String {
        val current = getCurrentState()
        val history = getStateHistory()

        return buildString {
            appendLine("=== CALL STATE DIAGNOSTIC ===")
            appendLine("Initialized: $isInitialized")
            appendLine("Current State: ${current.state}")
            appendLine("Previous State: ${current.previousState}")
            appendLine("Call ID: ${current.callId}")
            appendLine("Direction: ${current.direction}")
            appendLine("Caller Number: $currentCallerNumber")
            appendLine("Error Reason: ${current.errorReason}")
            appendLine("SIP Code: ${current.sipCode}")
            appendLine("SIP Reason: ${current.sipReason}")
            appendLine("Timestamp: ${current.timestamp}")
            appendLine("Last Update: $lastStateUpdate")
            appendLine("Is Active: ${current.isActive()}")
            appendLine("Is Connected: ${current.isConnected()}")
            appendLine("Has Error: ${current.hasError()}")
            appendLine("History Count: ${history.size}")

            if (history.isNotEmpty()) {
                appendLine("\n--- Recent State History ---")
                history.takeLast(5).forEach { state ->
                    appendLine("${state.timestamp}: ${state.previousState} -> ${state.state} (${state.callId})")
                }
            }
        }
    }
}
