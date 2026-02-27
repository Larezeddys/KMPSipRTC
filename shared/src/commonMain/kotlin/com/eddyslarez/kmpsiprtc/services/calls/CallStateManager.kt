package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallStateInfo
import com.eddyslarez.kmpsiprtc.data.models.CallStateTransitionValidator
import com.eddyslarez.kmpsiprtc.data.models.SipErrorMapper
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.Lock
import com.eddyslarez.kmpsiprtc.utils.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

internal object CallStateManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val updateLock = Lock()

    @OptIn(ExperimentalTime::class)
    private val _callStateFlow = MutableStateFlow(
        CallStateInfo(
            state = CallState.IDLE,
            previousState = null,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()
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
    @OptIn(ExperimentalTime::class)
    internal fun updateCallState(
        newState: CallState,
        callId: String = currentCallId,
        direction: CallDirections = currentDirection,
        sipCode: Int? = null,
        sipReason: String? = null,
        errorReason: CallErrorReason = CallErrorReason.NONE,
        forceUpdate: Boolean = false
    ): Boolean = synchronized(updateLock) {

        // Verificar si está inicializado
        if (!isInitialized) {
            log.w(tag = "CallStateManager") {
                "State update attempted before initialization: $newState"
            }
            return@synchronized false
        }

        val currentTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val currentStateInfo = _callStateFlow.value

        // Prevenir actualizaciones muy frecuentes (spam)
        if (!forceUpdate && currentTime - lastStateUpdate < MIN_STATE_UPDATE_INTERVAL) {
            log.d(tag = "CallStateManager") {
                "State update too frequent, skipping: $newState"
            }
            return@synchronized false
        }

        // En multi-línea, usar el estado PER-LLAMADA para validaciones.
        // El estado global puede ser de otra llamada (ej: PAUSING por hold de llamada 1)
        // mientras esta llamada específica tiene un estado diferente (INCOMING_RECEIVED).
        val perCallState = if (callId.isNotEmpty()) {
            MultiCallManager.getCallState(callId)?.state
        } else null
        val effectiveFromState = perCallState ?: currentStateInfo.state

        // Validación estricta para prevenir estados duplicados (usando estado per-llamada)
        if (effectiveFromState == newState &&
            currentStateInfo.callId == callId &&
            currentStateInfo.errorReason == errorReason &&
            !forceUpdate
        ) {
            log.d(tag = "CallStateManager") {
                "Duplicate state transition prevented: $newState for call $callId"
            }
            return@synchronized false
        }

        // Validar que tenemos un callId válido para estados activos
        if (newState != CallState.IDLE &&
            newState != CallState.ERROR &&
            callId.isEmpty()
        ) {
            log.w(tag = "CallStateManager") {
                "Invalid callId for active state: $newState"
            }
            return@synchronized false
        }

        // Validar transición usando estado per-llamada para soportar multi-línea correctamente
        if (!forceUpdate && !CallStateTransitionValidator.isValidTransition(
                effectiveFromState,
                newState,
                direction
            )
        ) {
            log.w(tag = "CallStateManager") {
                "Invalid state transition: $effectiveFromState -> $newState for $direction call (global: ${currentStateInfo.state})"
            }

            // Permitir solo transiciones críticas de emergencia
            if (newState != CallState.ERROR &&
                newState != CallState.ENDED &&
                newState != CallState.IDLE
            ) {
                return@synchronized false
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
            "State transition: ${currentStateInfo.state} -> $newState for call $callId (${direction.name})"
        }

        true
    }

    /**
     * Añadir al historial con límite
     */
    private fun addToHistory(stateInfo: CallStateInfo) {
        val currentHistory = _callHistoryFlow.value.toMutableList()

        // Solo agregar si es un estado diferente al último
        if (currentHistory.isEmpty() ||
            currentHistory.last().state != stateInfo.state ||
            currentHistory.last().callId != stateInfo.callId) {

            currentHistory.add(stateInfo)

            // Aumentar límite y usar criterio más inteligente
            if (currentHistory.size > 100) { // Aumentar de 30 a 100
                // Mantener primeros estados importantes y últimos estados
                val importantStates = currentHistory.filter {
                    it.state == CallState.IDLE ||
                            it.state == CallState.INCOMING_RECEIVED ||
                            it.state == CallState.OUTGOING_INIT ||
                            it.state == CallState.CONNECTED ||
                            it.state == CallState.ENDED ||
                            it.state == CallState.ERROR
                }

                if (importantStates.size > 20) {
                    // Mantener primeros 10 y últimos 10 estados importantes
                    val firstImportant = importantStates.take(10)
                    val lastImportant = importantStates.takeLast(10)
                    val combined = (firstImportant + lastImportant).distinctBy { it.timestamp }
                    currentHistory.clear()
                    currentHistory.addAll(combined.sortedBy { it.timestamp })
                } else {
                    currentHistory.removeAt(0) // Eliminar solo el más antiguo
                }
            }
            _callHistoryFlow.value = currentHistory
        }
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

    @OptIn(ExperimentalTime::class)
    fun startOutgoingCall(callId: String, phoneNumber: String, callData: CallData? = null) {
        if (!isInitialized) return

        currentCallerNumber = phoneNumber

        val finalCallData = callData ?: CallData(
            callId = callId,
            to = phoneNumber,
            from = "",
            direction = CallDirections.OUTGOING,
            startTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )
        MultiCallManager.addCall(finalCallData)

        updateCallState(
            newState = CallState.OUTGOING_INIT,
            callId = callId,
            direction = CallDirections.OUTGOING,
            forceUpdate = true
        )
    }

    @OptIn(ExperimentalTime::class)
    fun incomingCallReceived(callId: String, callerNumber: String, callData: CallData? = null) {
        if (!isInitialized) return

        currentCallerNumber = callerNumber

        val finalCallData = callData ?: CallData(
            callId = callId,
            to = "",
            from = callerNumber,
            direction = CallDirections.INCOMING,
            startTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )
        MultiCallManager.addCall(finalCallData)

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
     * Limpieza completa de una llamada.
     * Si quedan otras llamadas activas, transfiere el tracking a la siguiente en lugar de
     * resetear a IDLE (lo cual borraría todas las llamadas con clearAllCalls).
     */
    private fun cleanupCall(callId: String) {
        MultiCallManager.removeCall(callId)

        if (currentCallId == callId) {
            val remainingCalls = MultiCallManager.getActiveCalls()
            if (remainingCalls.isEmpty()) {
                forceResetToIdle()
            } else {
                // Quedan otras llamadas activas: transferir tracking sin borrarlas
                val nextCall = remainingCalls.first()
                currentCallId = nextCall.callId
                val nextState = MultiCallManager.getCallState(nextCall.callId)?.state
                    ?: CallState.STREAMS_RUNNING
                log.d(tag = "CallStateManager") {
                    "Switching tracking to remaining call: ${nextCall.callId} state: $nextState"
                }
                updateCallState(
                    newState = nextState,
                    callId = nextCall.callId,
                    direction = nextCall.direction,
                    forceUpdate = true
                )
            }
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
//        _callHistoryFlow.value = emptyList()
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

        // Usar estado específico de la llamada para soportar multi-línea
        val perCallState = MultiCallManager.getCallState(callId)?.state
        val effectiveState = perCallState ?: getCurrentState().state
        if (effectiveState != CallState.STREAMS_RUNNING && effectiveState != CallState.CONNECTED) {
            log.w(tag = "CallStateManager") { "Cannot hold call $callId in state: $effectiveState" }
            return
        }

        updateCallState(
            newState = CallState.PAUSING,
            callId = callId,
            forceUpdate = true
        )
    }

    fun callOnHold(callId: String) {
        if (!isInitialized) return

        // forceUpdate para permitir la transición aunque el estado global sea diferente (multi-línea)
        updateCallState(
            newState = CallState.PAUSED,
            callId = callId,
            forceUpdate = true
        )
    }

    fun startResume(callId: String) {
        if (!isInitialized) return

        // Usar estado específico de la llamada para soportar multi-línea
        val perCallState = MultiCallManager.getCallState(callId)?.state
        val effectiveState = perCallState ?: getCurrentState().state
        if (effectiveState != CallState.PAUSED) {
            log.w(tag = "CallStateManager") { "Cannot resume call $callId in state: $effectiveState" }
            return
        }

        updateCallState(
            newState = CallState.RESUMING,
            callId = callId,
            forceUpdate = true
        )
    }
    /**
     * NUEVO: Método para completar resume
     */
    fun callResumed(callId: String) {
        if (!isInitialized) return

        // forceUpdate para permitir la transición aunque el estado global sea diferente (multi-línea)
        updateCallState(
            newState = CallState.STREAMS_RUNNING,
            callId = callId,
            forceUpdate = true
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
