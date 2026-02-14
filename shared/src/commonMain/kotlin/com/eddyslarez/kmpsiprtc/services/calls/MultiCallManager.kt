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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ExperimentalTime

internal object MultiCallManager {

    // Scope para corrutinas con SupervisorJob para que un fallo no cancele todo
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Mutex para proteger mutaciones de CallData compartidos
    private val callDataMutex = Mutex()

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
     * Añade una nueva llamada manteniendo la MISMA referencia.
     * Mutaciones de campos protegidas por callDataMutex para thread-safety.
     */
    fun addCall(callData: CallData) {
        val existingCall = _activeCalls.value[callData.callId]

        if (existingCall != null) {
            log.d(tag = "MultiCallManager") {
                "[WARN] Call ${callData.callId} already exists"
            }

            // Proteger mutaciones de campos con Mutex
            scope.launch {
                callDataMutex.withLock {
                    if (callData.remoteSdp.isNotBlank() && existingCall.remoteSdp.isBlank()) {
                        existingCall.remoteSdp = callData.remoteSdp
                    }
                    if (callData.localSdp.isNotBlank() && existingCall.localSdp.isBlank()) {
                        existingCall.localSdp = callData.localSdp
                    }
                    if (callData.originalInviteMessage.isNotBlank() && existingCall.originalInviteMessage.isBlank()) {
                        existingCall.originalInviteMessage = callData.originalInviteMessage
                    }
                }
            }

            log.d(tag = "MultiCallManager") {
                "[OK] Preserved data (remoteSdp: ${existingCall.remoteSdp.length} chars)"
            }
            return
        }

        // Nueva llamada - operacion atomica via StateFlow.update
        _activeCalls.update { current -> current + (callData.callId to callData) }

        log.d(tag = "MultiCallManager") {
            "[OK] New call added: ${callData.callId}, remoteSdp: ${callData.remoteSdp.length} chars"
        }

        val initialState = if (callData.direction == CallDirections.INCOMING) {
            CallState.INCOMING_RECEIVED
        } else {
            CallState.OUTGOING_INIT
        }

        updateCallState(callData.callId, initialState)
    }


    /**
     * Remueve una llamada del gestor
     */
    fun removeCall(callId: String) {
        val existed = _activeCalls.value.containsKey(callId)
        _activeCalls.update { it - callId }
        _callStates.update { it - callId }

        if (existed) {
            log.d(tag = "MultiCallManager") { "Call removed: $callId" }
        }
    }

    /**
     * Actualiza el estado de una llamada específica
     */
    @OptIn(ExperimentalTime::class)
    fun updateCallState(callId: String, newState: CallState, errorReason: CallErrorReason = CallErrorReason.NONE) {
        val previousState = _callStates.value[callId]
        val direction = getCall(callId)?.direction ?: CallDirections.OUTGOING

        val newStateInfo = CallStateInfo(
            state = newState,
            previousState = previousState?.state,
            errorReason = errorReason,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            callId = callId,
            direction = direction
        )

        _callStates.update { current -> current + (callId to newStateInfo) }

        // Si la llamada terminó, programar su eliminación
        if (newState == CallState.ENDED || newState == CallState.ERROR) {
            scope.launch {
                delay(1000)
                removeCall(callId)
            }
        }

        log.d(tag = "MultiCallManager") { "Call state updated: $callId -> $newState" }
    }

    /**
     * [OK] CORREGIDO: Obtiene la MISMA referencia de CallData
     */
    fun getCall(callId: String): CallData? {
        val call = _activeCalls.value[callId]
        if (call != null) {
            log.d(tag = "MultiCallManager") {
                "getCall($callId): remoteSdp ${call.remoteSdp.length} chars"
            }
        }
        return call
    }

    /**
     * Obtiene el estado de una llamada específica
     */
    fun getCallState(callId: String): CallStateInfo? {
        return _callStates.value[callId]
    }

    /**
     * [OK] CORREGIDO: Obtiene todas las llamadas con verificación de SDP
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
     * Sincroniza CallData desde AccountInfo.
     * Mutaciones protegidas por callDataMutex para thread-safety.
     */
    fun syncCallDataFromAccountInfo(callId: String, accountInfoCallData: CallData) {
        val currentCall = getCall(callId)
        if (currentCall != null) {
            log.d(tag = "MultiCallManager") {
                "Syncing CallData from AccountInfo for: $callId"
            }

            // Proteger mutaciones de campos con Mutex
            scope.launch {
                callDataMutex.withLock {
                    currentCall.remoteSdp = accountInfoCallData.remoteSdp
                    currentCall.localSdp = accountInfoCallData.localSdp
                    currentCall.originalInviteMessage = accountInfoCallData.originalInviteMessage
                }
            }

            log.d(tag = "MultiCallManager") {
                "[OK] Synced: remoteSdp ${currentCall.remoteSdp.length} chars"
            }
        }
    }

    /**
     * Información de diagnóstico mejorada
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
                appendLine("  - Direction: ${callData.direction}")
                appendLine("  - RemoteSDP: ${callData.remoteSdp.length} chars")
                appendLine("  - LocalSDP: ${callData.localSdp.length} chars")
                appendLine("  - OriginalInvite: ${callData.originalInviteMessage.length} chars")
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