package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallStateInfo
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

object MultiCallManager {

    // Scope para corrutinas
    private val scope = CoroutineScope(Dispatchers.IO)

    // Mapa de llamadas activas por callId
    private val _activeCalls = MutableStateFlow<Map<String, CallData>>(emptyMap())
    val activeCallsFlow: StateFlow<Map<String, CallData>> = _activeCalls.asStateFlow()

    // Estados de cada llamada
    private val _callStates = MutableStateFlow<Map<String, CallStateInfo>>(emptyMap())
    val callStatesFlow: StateFlow<Map<String, CallStateInfo>> = _callStates.asStateFlow()

    /**
     * Determina si un estado de llamada es considerado "activo"
     */
    private fun isActiveCallState(state: CallState): Boolean {
        return when (state) {
            CallState.IDLE,
            CallState.ENDED,
            CallState.ERROR -> false
            else -> true
        }
    }

    /**
     * Añade una nueva llamada al gestor
     */
    fun addCall(callData: CallData) {
        val currentCalls = _activeCalls.value.toMutableMap()
        currentCalls[callData.callId] = callData
        _activeCalls.value = currentCalls

        // Inicializar estado de la llamada
        val initialState = if (callData.direction == CallDirections.INCOMING) {
            CallState.INCOMING_RECEIVED
        } else {
            CallState.OUTGOING_INIT
        }

        updateCallState(callData.callId, initialState)

        log.d(tag = "MultiCallManager") { "Call added: ${callData.callId} (${callData.direction})" }
    }

    /**
     * Remueve una llamada del gestor
     */
    fun removeCall(callId: String) {
        val currentCalls = _activeCalls.value.toMutableMap()
        val removedCall = currentCalls.remove(callId)
        _activeCalls.value = currentCalls

        val currentStates = _callStates.value.toMutableMap()
        currentStates.remove(callId)
        _callStates.value = currentStates

        if (removedCall != null) {
            log.d(tag = "MultiCallManager") { "Call removed: $callId" }
        }
    }

    /**
     * Actualiza el estado de una llamada específica
     */
    fun updateCallState(callId: String, newState: CallState, errorReason: CallErrorReason = CallErrorReason.NONE) {
        val currentStates = _callStates.value.toMutableMap()
        val previousState = currentStates[callId]

        val newStateInfo = CallStateInfo(
            state = newState,
            previousState = previousState?.state,
            errorReason = errorReason,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            callId = callId,
            direction = getCall(callId)?.direction ?: CallDirections.OUTGOING
        )

        currentStates[callId] = newStateInfo
        _callStates.value = currentStates

        // Si la llamada terminó, programar su eliminación
        if (newState == CallState.ENDED || newState == CallState.ERROR) {
            scope.launch {
                delay(1000) // Esperar 1 segundo
                removeCall(callId)
            }
        }

        log.d(tag = "MultiCallManager") { "Call state updated: $callId -> $newState" }
    }

    /**
     * Obtiene una llamada específica
     */
    fun getCall(callId: String): CallData? {
        return _activeCalls.value[callId]
    }

    /**
     * Obtiene el estado de una llamada específica
     */
    fun getCallState(callId: String): CallStateInfo? {
        return _callStates.value[callId]
    }

    /**
     * Obtiene todas las llamadas activas (filtradas por estado)
     * Si hay una sola llamada y está terminada, retorna lista vacía
     * Si hay múltiples llamadas, filtra las que no están activas
     */
    fun getAllCalls(): List<CallData> {
        val allCalls = _activeCalls.value.values.toList()

        // Si no hay llamadas, retornar lista vacía
        if (allCalls.isEmpty()) {
            return emptyList()
        }

        // Si hay una sola llamada
        if (allCalls.size == 1) {
            val singleCall = allCalls.first()
            val callState = getCallState(singleCall.callId)

            // Si la llamada está terminada o en error, retornar lista vacía
            return if (callState != null && !isActiveCallState(callState.state)) {
                // Remover inmediatamente la llamada terminada
                removeCall(singleCall.callId)
                emptyList()
            } else {
                allCalls
            }
        }

        // Si hay múltiples llamadas, filtrar las activas
        val activeCalls = allCalls.filter { callData ->
            val callState = getCallState(callData.callId)
            val isActive = callState != null && isActiveCallState(callState.state)

            // Si la llamada no está activa, programar su eliminación
            if (!isActive) {
                scope.launch {
                    delay(100) // Pequeño delay para evitar conflictos
                    removeCall(callData.callId)
                }
            }

            isActive
        }

        return activeCalls
    }

    /**
     * Obtiene solo las llamadas realmente activas (sin estados terminales)
     */
    fun getActiveCalls(): List<CallData> {
        return _activeCalls.value.values.filter { callData ->
            val callState = getCallState(callData.callId)
            callState != null && isActiveCallState(callState.state)
        }
    }

    /**
     * Obtiene llamadas terminadas que aún están en memoria
     */
    fun getTerminatedCalls(): List<CallData> {
        return _activeCalls.value.values.filter { callData ->
            val callState = getCallState(callData.callId)
            callState != null && !isActiveCallState(callState.state)
        }
    }

    /**
     * Limpia inmediatamente las llamadas terminadas
     */
    fun cleanupTerminatedCalls() {
        val terminatedCalls = getTerminatedCalls()
        terminatedCalls.forEach { callData ->
            removeCall(callData.callId)
        }
        log.d(tag = "MultiCallManager") { "Cleaned up ${terminatedCalls.size} terminated calls" }
    }

    /**
     * Obtiene todos los estados de llamadas
     */
    fun getAllCallStates(): Map<String, CallStateInfo> {
        return _callStates.value
    }

    /**
     * Verifica si hay llamadas activas
     */
    fun hasActiveCalls(): Boolean {
        return getActiveCalls().isNotEmpty()
    }

    /**
     * Obtiene la llamada actual (primera llamada activa)
     */
    fun getCurrentCall(): CallData? {
        return getActiveCalls().firstOrNull()
    }

    /**
     * Obtiene llamadas por estado
     */
    fun getCallsByState(state: CallState): List<CallData> {
        val callsInState = _callStates.value.filter { it.value.state == state }.keys
        return _activeCalls.value.filter { it.key in callsInState }.values.toList()
    }

    /**
     * Obtiene llamadas entrantes
     */
    fun getIncomingCalls(): List<CallData> {
        return getActiveCalls().filter {
            it.direction == CallDirections.INCOMING
        }
    }

    /**
     * Obtiene llamadas salientes
     */
    fun getOutgoingCalls(): List<CallData> {
        return getActiveCalls().filter {
            it.direction == CallDirections.OUTGOING
        }
    }

    /**
     * Obtiene llamadas conectadas
     */
    fun getConnectedCalls(): List<CallData> {
        return getCallsByState(CallState.CONNECTED) + getCallsByState(CallState.STREAMS_RUNNING)
    }

    /**
     * Obtiene llamadas en espera
     */
    fun getHeldCalls(): List<CallData> {
        return getCallsByState(CallState.PAUSED)
    }

    /**
     * Limpia todas las llamadas
     */
    fun clearAllCalls() {
        _activeCalls.value = emptyMap()
        _callStates.value = emptyMap()
        log.d(tag = "MultiCallManager") { "All calls cleared" }
    }

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val calls = _activeCalls.value
        val states = _callStates.value
        val activeCalls = getActiveCalls()
        val terminatedCalls = getTerminatedCalls()

        return buildString {
            appendLine("=== MULTI CALL MANAGER DIAGNOSTIC ===")
            appendLine("Total calls in memory: ${calls.size}")
            appendLine("Active calls: ${activeCalls.size}")
            appendLine("Terminated calls: ${terminatedCalls.size}")
            appendLine("Call states: ${states.size}")

            appendLine("\n--- Active Calls ---")
            activeCalls.forEach { callData ->
                val state = states[callData.callId]?.state ?: "UNKNOWN"
                appendLine("${callData.callId}: ${callData.from} -> ${callData.to} ($state)")
            }

            if (terminatedCalls.isNotEmpty()) {
                appendLine("\n--- Terminated Calls (pending cleanup) ---")
                terminatedCalls.forEach { callData ->
                    val state = states[callData.callId]?.state ?: "UNKNOWN"
                    appendLine("${callData.callId}: ${callData.from} -> ${callData.to} ($state)")
                }
            }

            appendLine("\n--- Call States ---")
            states.forEach { (callId, stateInfo) ->
                appendLine("$callId: ${stateInfo.previousState} -> ${stateInfo.state}")
            }
        }
    }
}