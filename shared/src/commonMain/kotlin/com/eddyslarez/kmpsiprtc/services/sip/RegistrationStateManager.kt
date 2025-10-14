package com.eddyslarez.kmpsiprtc.services.sip

import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.collections.component1
import kotlin.collections.component2

object RegistrationStateManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "RegistrationStateManager"

    data class RegistrationStateInfo(
        val state: RegistrationState,
        val previousState: RegistrationState?,
        val timestamp: Long,
        val accountKey: String = "",
        val errorMessage: String? = null,
        val retryCount: Int = 0,
        val lastSuccessfulRegistration: Long = 0L,
        val isNetworkConnected: Boolean = true,
        val registrationExpiry: Long = 0L,
        val consecutiveFailures: Int = 0
    ) {
        fun isExpired(): Boolean {
            return registrationExpiry > 0 && Clock.System.now().toEpochMilliseconds() > registrationExpiry
        }

        fun needsRenewal(): Boolean {
            val renewalThreshold = registrationExpiry - (60 * 1000) // 1 minuto antes
            return registrationExpiry > 0 && Clock.System.now().toEpochMilliseconds() > renewalThreshold
        }

        fun isHealthy(): Boolean {
            return state == RegistrationState.OK &&
                    !isExpired() &&
                    isNetworkConnected &&
                    consecutiveFailures == 0
        }
    }

    private val _accountStatesFlow = MutableStateFlow<Map<String, RegistrationStateInfo>>(emptyMap())
    val accountStatesFlow: StateFlow<Map<String, RegistrationStateInfo>> = _accountStatesFlow.asStateFlow()

    private val _globalStateFlow = MutableStateFlow(RegistrationState.NONE)
    val globalStateFlow: StateFlow<RegistrationState> = _globalStateFlow.asStateFlow()

    private val _networkStateFlow = MutableStateFlow(true)
    val networkStateFlow: StateFlow<Boolean> = _networkStateFlow.asStateFlow()

    private val _stateHistoryFlow = MutableStateFlow<List<RegistrationStateInfo>>(emptyList())
    val stateHistoryFlow: StateFlow<List<RegistrationStateInfo>> = _stateHistoryFlow.asStateFlow()

    private var onStateChangeCallback: ((String, RegistrationState, RegistrationState?) -> Unit)? = null
    private var onNetworkStateChangeCallback: ((Boolean) -> Unit)? = null
    private var onRegistrationExpiredCallback: ((String) -> Unit)? = null

    private val reconnectionJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val renewalJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Configura callbacks para notificaciones
     */
    fun setCallbacks(
        onStateChange: ((String, RegistrationState, RegistrationState?) -> Unit)? = null,
        onNetworkStateChange: ((Boolean) -> Unit)? = null,
        onRegistrationExpired: ((String) -> Unit)? = null
    ) {
        this.onStateChangeCallback = onStateChange
        this.onNetworkStateChangeCallback = onNetworkStateChange
        this.onRegistrationExpiredCallback = onRegistrationExpired
    }

    /**
     * Actualiza el estado de registro para una cuenta específica con validaciones mejoradas
     */
    fun updateAccountState(
        accountKey: String,
        newState: RegistrationState,
        errorMessage: String? = null,
        registrationExpiry: Long = 0L
    ): Boolean {
        val currentStates = _accountStatesFlow.value
        val currentStateInfo = currentStates[accountKey]
        val currentNetworkState = _networkStateFlow.value

        // Validar transición de estado
        if (!isValidStateTransition(currentStateInfo?.state, newState)) {
            log.w(tag = TAG) {
                "Invalid state transition for $accountKey: ${currentStateInfo?.state} -> $newState"
            }
            // Permitir solo ciertas transiciones críticas
            if (newState != RegistrationState.FAILED && newState != RegistrationState.NONE) {
                return false
            }
        }

        // Prevenir duplicados innecesarios pero permitir actualizaciones importantes
        if (currentStateInfo?.state == newState &&
            currentStateInfo.errorMessage == errorMessage &&
            !shouldForceUpdate(currentStateInfo, newState, currentNetworkState)) {
            log.d(tag = TAG) {
                "Duplicate state transition prevented: $newState for account $accountKey"
            }
            return false
        }

        // Calcular nuevos valores
        val retryCount = if (newState == RegistrationState.FAILED) {
            (currentStateInfo?.retryCount ?: 0) + 1
        } else if (newState == RegistrationState.OK) {
            0 // Reset retry count on success
        } else {
            currentStateInfo?.retryCount ?: 0
        }

        val consecutiveFailures = if (newState == RegistrationState.FAILED) {
            (currentStateInfo?.consecutiveFailures ?: 0) + 1
        } else if (newState == RegistrationState.OK) {
            0 // Reset on success
        } else {
            currentStateInfo?.consecutiveFailures ?: 0
        }

        val lastSuccessfulRegistration = if (newState == RegistrationState.OK) {
            Clock.System.now().toEpochMilliseconds()
        } else {
            currentStateInfo?.lastSuccessfulRegistration ?: 0L
        }

        // Crear nueva información de estado
        val newStateInfo = RegistrationStateInfo(
            state = newState,
            previousState = currentStateInfo?.state,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            accountKey = accountKey,
            errorMessage = errorMessage,
            retryCount = retryCount,
            lastSuccessfulRegistration = lastSuccessfulRegistration,
            isNetworkConnected = currentNetworkState,
            registrationExpiry = if (registrationExpiry > 0) registrationExpiry else currentStateInfo?.registrationExpiry ?: 0L,
            consecutiveFailures = consecutiveFailures
        )

        // Actualizar estados por cuenta
        val updatedStates = currentStates.toMutableMap()
        updatedStates[accountKey] = newStateInfo
        _accountStatesFlow.value = updatedStates

        // Actualizar estado global
        updateGlobalState(updatedStates)

        // Añadir al historial
        addToHistory(newStateInfo)

        // Manejar lógica específica del estado
        handleStateSpecificLogic(accountKey, newStateInfo)

        // Notificar callback
        onStateChangeCallback?.invoke(accountKey, newState, currentStateInfo?.state)

        log.d(tag = TAG) {
            "State updated for $accountKey: ${currentStateInfo?.state} -> $newState" +
                    " (retries: $retryCount, failures: $consecutiveFailures, network: $currentNetworkState)"
        }

        return true
    }

    /**
     * Actualiza el estado de conectividad de red
     */
    fun updateNetworkState(isConnected: Boolean) {
        val previousState = _networkStateFlow.value

        if (previousState == isConnected) {
            return // No hay cambio
        }

        _networkStateFlow.value = isConnected

        log.d(tag = TAG) { "Network state changed: $previousState -> $isConnected" }

        // Actualizar estado de red en todas las cuentas
        val currentStates = _accountStatesFlow.value
        val updatedStates = currentStates.mapValues { (_, stateInfo) ->
            stateInfo.copy(isNetworkConnected = isConnected)
        }
        _accountStatesFlow.value = updatedStates

        // Manejar reconexión automática
        if (isConnected && !previousState) {
            handleNetworkReconnection()
        } else if (!isConnected) {
            handleNetworkDisconnection()
        }

        // Notificar callback
        onNetworkStateChangeCallback?.invoke(isConnected)
    }

    /**
     * Valida si una transición de estado es válida
     */
    private fun isValidStateTransition(currentState: RegistrationState?, newState: RegistrationState): Boolean {
        if (currentState == null) return true

        return when (currentState) {
            RegistrationState.NONE -> true // Desde NONE se puede ir a cualquier estado
            RegistrationState.IN_PROGRESS -> newState != RegistrationState.PROGRESS // Evitar bucles
            RegistrationState.PROGRESS -> newState != RegistrationState.IN_PROGRESS // Evitar bucles
            RegistrationState.OK -> newState != RegistrationState.IN_PROGRESS || newState != RegistrationState.PROGRESS
            RegistrationState.FAILED -> true // Desde FAILED se puede ir a cualquier estado
            RegistrationState.CLEARED -> newState == RegistrationState.NONE || newState == RegistrationState.IN_PROGRESS
        }
    }

    /**
     * Determina si se debe forzar una actualización de estado
     */
    private fun shouldForceUpdate(
        currentStateInfo: RegistrationStateInfo,
        newState: RegistrationState,
        networkConnected: Boolean
    ): Boolean {
        // Forzar actualización si cambió el estado de red
        if (currentStateInfo.isNetworkConnected != networkConnected) {
            return true
        }

        // Forzar si el registro expiró
        if (currentStateInfo.isExpired() && newState == RegistrationState.FAILED) {
            return true
        }

        // Forzar si hay muchos fallos consecutivos
        if (currentStateInfo.consecutiveFailures >= 3 && newState == RegistrationState.OK) {
            return true
        }

        return false
    }

    /**
     * Actualiza el estado global basado en los estados individuales
     */
    private fun updateGlobalState(accountStates: Map<String, RegistrationStateInfo>) {
        val globalState = when {
            accountStates.isEmpty() -> RegistrationState.NONE
            accountStates.values.any { it.state == RegistrationState.OK && it.isHealthy() } -> RegistrationState.OK
            accountStates.values.any { it.state == RegistrationState.IN_PROGRESS || it.state == RegistrationState.PROGRESS } -> RegistrationState.IN_PROGRESS
            accountStates.values.all { it.state == RegistrationState.FAILED } -> RegistrationState.FAILED
            accountStates.values.all { it.state == RegistrationState.CLEARED } -> RegistrationState.CLEARED
            else -> RegistrationState.NONE
        }

        val previousGlobalState = _globalStateFlow.value
        if (previousGlobalState != globalState) {
            _globalStateFlow.value = globalState
            log.d(tag = TAG) { "Global state updated: $previousGlobalState -> $globalState" }
        }
    }

    /**
     * Maneja lógica específica según el estado
     */
    private fun handleStateSpecificLogic(accountKey: String, stateInfo: RegistrationStateInfo) {
        when (stateInfo.state) {
            RegistrationState.OK -> {
                // Cancelar reconexión si estaba en progreso
                cancelReconnection(accountKey)

                // Programar renovación si hay tiempo de expiración
                if (stateInfo.registrationExpiry > 0) {
                    scheduleRenewal(accountKey, stateInfo.registrationExpiry)
                }
            }

            RegistrationState.FAILED -> {
                // Programar reconexión automática si la red está disponible
                if (stateInfo.isNetworkConnected && stateInfo.consecutiveFailures < 5) {
                    scheduleReconnection(accountKey, stateInfo.consecutiveFailures)
                }
            }

            RegistrationState.CLEARED -> {
                // Limpiar jobs de reconexión y renovación
                cancelReconnection(accountKey)
                cancelRenewal(accountKey)
            }

            else -> {
                // Para otros estados, no hacer nada especial
            }
        }
    }

    /**
     * Programa reconexión automática con backoff exponencial
     */
    private fun scheduleReconnection(accountKey: String, failureCount: Int) {
        cancelReconnection(accountKey) // Cancelar reconexión anterior

        val baseDelay = 2000L // 2 segundos base
        val maxDelay = 60000L // 60 segundos máximo
        val delay = minOf(baseDelay * (1 shl failureCount), maxDelay) // Backoff exponencial

        log.d(tag = TAG) { "Scheduling reconnection for $accountKey in ${delay}ms (attempt ${failureCount + 1})" }

        reconnectionJobs[accountKey] = scope.launch {
            try {
                delay(delay)

                // Verificar que aún necesitamos reconectar
                val currentState = getAccountState(accountKey)
                if (currentState == RegistrationState.FAILED && _networkStateFlow.value) {
                    log.d(tag = TAG) { "Attempting automatic reconnection for $accountKey" }
                    updateAccountState(accountKey, RegistrationState.IN_PROGRESS, "Automatic reconnection")
                    // El SipCoreManager debería detectar este cambio y intentar registrar
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in reconnection for $accountKey: ${e.message}" }
            }
        }
    }

    /**
     * Programa renovación de registro
     */
    private fun scheduleRenewal(accountKey: String, expiryTime: Long) {
        cancelRenewal(accountKey) // Cancelar renovación anterior

        val renewalTime = expiryTime - (60 * 1000) // 1 minuto antes de expirar
        val delay = renewalTime - Clock.System.now().toEpochMilliseconds()

        if (delay <= 0) {
            log.w(tag = TAG) { "Registration for $accountKey already expired or expires too soon" }
            onRegistrationExpiredCallback?.invoke(accountKey)
            return
        }

        log.d(tag = TAG) { "Scheduling renewal for $accountKey in ${delay}ms" }

        renewalJobs[accountKey] = scope.launch {
            try {
                delay(delay)

                // Verificar que aún estamos registrados
                val currentState = getAccountState(accountKey)
                if (currentState == RegistrationState.OK) {
                    log.d(tag = TAG) { "Registration renewal required for $accountKey" }
                    onRegistrationExpiredCallback?.invoke(accountKey)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in renewal for $accountKey: ${e.message}" }
            }
        }
    }

    /**
     * Maneja reconexión cuando la red se restaura
     */
    private fun handleNetworkReconnection() {
        log.d(tag = TAG) { "Network reconnected, checking failed registrations" }

        val currentStates = _accountStatesFlow.value
        val failedAccounts = currentStates.filter { (_, stateInfo) ->
            stateInfo.state == RegistrationState.FAILED ||
                    stateInfo.isExpired() ||
                    !stateInfo.isHealthy()
        }

        failedAccounts.forEach { (accountKey, stateInfo) ->
            log.d(tag = TAG) { "Attempting to recover registration for $accountKey" }

            // Resetear contador de fallos consecutivos en reconexión de red
            val updatedStateInfo = stateInfo.copy(
                consecutiveFailures = 0,
                isNetworkConnected = true
            )

            val updatedStates = _accountStatesFlow.value.toMutableMap()
            updatedStates[accountKey] = updatedStateInfo
            _accountStatesFlow.value = updatedStates

            // Programar reconexión inmediata
            scheduleReconnection(accountKey, 0)
        }
    }

    /**
     * Maneja desconexión de red
     */
    private fun handleNetworkDisconnection() {
        log.d(tag = TAG) { "Network disconnected, updating account states" }

        // Cancelar todas las reconexiones y renovaciones pendientes
        reconnectionJobs.values.forEach { it.cancel() }
        reconnectionJobs.clear()

        renewalJobs.values.forEach { it.cancel() }
        renewalJobs.clear()

        // Marcar todas las cuentas como desconectadas de red
        val currentStates = _accountStatesFlow.value
        val updatedStates = currentStates.mapValues { (_, stateInfo) ->
            stateInfo.copy(isNetworkConnected = false)
        }
        _accountStatesFlow.value = updatedStates

        updateGlobalState(updatedStates)
    }

    /**
     * Cancela reconexión para una cuenta
     */
    private fun cancelReconnection(accountKey: String) {
        reconnectionJobs[accountKey]?.cancel()
        reconnectionJobs.remove(accountKey)
    }

    /**
     * Cancela renovación para una cuenta
     */
    private fun cancelRenewal(accountKey: String) {
        renewalJobs[accountKey]?.cancel()
        renewalJobs.remove(accountKey)
    }

    /**
     * Añade entrada al historial con límite
     */
    private fun addToHistory(stateInfo: RegistrationStateInfo) {
        val currentHistory = _stateHistoryFlow.value.toMutableList()
        currentHistory.add(stateInfo)

        // Mantener solo los últimos 50 estados para evitar memory leaks
        if (currentHistory.size > 50) {
            currentHistory.removeAt(0)
        }
        _stateHistoryFlow.value = currentHistory
    }

    /**
     * Remueve una cuenta del gestor de estados
     */
    fun removeAccount(accountKey: String) {
        // Cancelar jobs pendientes
        cancelReconnection(accountKey)
        cancelRenewal(accountKey)

        // Remover del estado
        val currentStates = _accountStatesFlow.value.toMutableMap()
        currentStates.remove(accountKey)
        _accountStatesFlow.value = currentStates

        // Recalcular estado global
        updateGlobalState(currentStates)

        log.d(tag = TAG) { "Account $accountKey removed from state manager" }
    }

    /**
     * Limpia todos los estados
     */
    fun clearAllStates() {
        // Cancelar todos los jobs
        reconnectionJobs.values.forEach { it.cancel() }
        reconnectionJobs.clear()

        renewalJobs.values.forEach { it.cancel() }
        renewalJobs.clear()

        // Limpiar estados
        _accountStatesFlow.value = emptyMap()
        _globalStateFlow.value = RegistrationState.CLEARED
        _stateHistoryFlow.value = emptyList()

        log.d(tag = TAG) { "All registration states cleared" }
    }

    // === MÉTODOS DE CONSULTA MEJORADOS ===

    fun getAccountState(accountKey: String): RegistrationState {
        return _accountStatesFlow.value[accountKey]?.state ?: RegistrationState.NONE
    }

    fun getAccountStateInfo(accountKey: String): RegistrationStateInfo? {
        return _accountStatesFlow.value[accountKey]
    }

    fun getAllAccountStates(): Map<String, RegistrationState> {
        return _accountStatesFlow.value.mapValues { it.value.state }
    }

    fun getAllAccountStateInfos(): Map<String, RegistrationStateInfo> {
        return _accountStatesFlow.value
    }

    fun getGlobalState(): RegistrationState = _globalStateFlow.value

    fun getNetworkState(): Boolean = _networkStateFlow.value

    fun getRegisteredAccounts(): List<String> {
        return _accountStatesFlow.value.filter {
            it.value.state == RegistrationState.OK && it.value.isHealthy()
        }.keys.toList()
    }

    fun getHealthyAccounts(): List<String> {
        return _accountStatesFlow.value.filter { it.value.isHealthy() }.keys.toList()
    }

    fun hasRegisteredAccounts(): Boolean {
        return _accountStatesFlow.value.values.any {
            it.state == RegistrationState.OK && it.isHealthy()
        }
    }

    fun getFailedAccounts(): List<String> {
        return _accountStatesFlow.value.filter { it.value.state == RegistrationState.FAILED }.keys.toList()
    }

    fun getAccountsNeedingRenewal(): List<String> {
        return _accountStatesFlow.value.filter { it.value.needsRenewal() }.keys.toList()
    }

    fun getExpiredAccounts(): List<String> {
        return _accountStatesFlow.value.filter { it.value.isExpired() }.keys.toList()
    }

    fun getStateHistory(): List<RegistrationStateInfo> = _stateHistoryFlow.value

    fun clearHistory() {
        _stateHistoryFlow.value = emptyList()
    }

    /**
     * Diagnóstico mejorado del estado actual
     */
    fun getDiagnosticInfo(): String {
        val accountStates = _accountStatesFlow.value
        val globalState = _globalStateFlow.value
        val networkState = _networkStateFlow.value
        val history = getStateHistory()

        return buildString {
            appendLine("=== REGISTRATION STATE DIAGNOSTIC ===")
            appendLine("Global State: $globalState")
            appendLine("Network Connected: $networkState")
            appendLine("Total Accounts: ${accountStates.size}")
            appendLine("Registered Accounts: ${getRegisteredAccounts().size}")
            appendLine("Healthy Accounts: ${getHealthyAccounts().size}")
            appendLine("Failed Accounts: ${getFailedAccounts().size}")
            appendLine("Accounts Needing Renewal: ${getAccountsNeedingRenewal().size}")
            appendLine("Expired Accounts: ${getExpiredAccounts().size}")
            appendLine("Active Reconnection Jobs: ${reconnectionJobs.size}")
            appendLine("Active Renewal Jobs: ${renewalJobs.size}")
            appendLine("History Count: ${history.size}")

            appendLine("\n--- Account States ---")
            accountStates.forEach { (accountKey, stateInfo) ->
                appendLine("$accountKey:")
                appendLine("  State: ${stateInfo.state}")
                appendLine("  Healthy: ${stateInfo.isHealthy()}")
                appendLine("  Network: ${stateInfo.isNetworkConnected}")
                appendLine("  Retries: ${stateInfo.retryCount}")
                appendLine("  Consecutive Failures: ${stateInfo.consecutiveFailures}")
                appendLine("  Last Success: ${if (stateInfo.lastSuccessfulRegistration > 0) stateInfo.lastSuccessfulRegistration else "Never"}")
                appendLine("  Expires: ${if (stateInfo.registrationExpiry > 0) stateInfo.registrationExpiry else "No expiry"}")
                appendLine("  Error: ${stateInfo.errorMessage ?: "None"}")
                appendLine()
            }

            if (history.isNotEmpty()) {
                appendLine("--- Recent State History ---")
                history.takeLast(10).forEach { stateInfo ->
                    appendLine("${stateInfo.timestamp}: ${stateInfo.accountKey} -> ${stateInfo.previousState} -> ${stateInfo.state}")
                }
            }
        }
    }

    /**
     * Fuerza verificación de salud de todas las cuentas
     */
    fun performHealthCheck() {
        log.d(tag = TAG) { "Performing health check on all accounts" }

        val currentStates = _accountStatesFlow.value
        var needsUpdate = false
        val updatedStates = currentStates.toMutableMap()

        currentStates.forEach { (accountKey, stateInfo) ->
            val wasHealthy = stateInfo.isHealthy()
            val currentTime = Clock.System.now().toEpochMilliseconds()

            // Verificar expiración
            if (stateInfo.isExpired() && stateInfo.state == RegistrationState.OK) {
                log.w(tag = TAG) { "Account $accountKey registration expired" }
                updatedStates[accountKey] = stateInfo.copy(
                    state = RegistrationState.FAILED,
                    errorMessage = "Registration expired",
                    timestamp = currentTime
                )
                needsUpdate = true
                onRegistrationExpiredCallback?.invoke(accountKey)
            }

            // Verificar si necesita renovación
            else if (stateInfo.needsRenewal() && stateInfo.state == RegistrationState.OK) {
                log.d(tag = TAG) { "Account $accountKey needs renewal" }
                onRegistrationExpiredCallback?.invoke(accountKey)
            }

            // Verificar si cambió el estado de salud
            if (wasHealthy != stateInfo.isHealthy()) {
                needsUpdate = true
            }
        }

        if (needsUpdate) {
            _accountStatesFlow.value = updatedStates
            updateGlobalState(updatedStates)
        }
    }
}