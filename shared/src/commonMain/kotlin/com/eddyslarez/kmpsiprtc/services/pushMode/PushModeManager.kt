package com.eddyslarez.kmpsiprtc.services.pushMode

import com.eddyslarez.kmpsiprtc.data.models.PushMode
import com.eddyslarez.kmpsiprtc.data.models.PushModeConfig
import com.eddyslarez.kmpsiprtc.data.models.PushModeReasons
import com.eddyslarez.kmpsiprtc.data.models.PushModeState
import com.eddyslarez.kmpsiprtc.data.models.PushModeStrategy
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import com.eddyslarez.kmpsiprtc.utils.Lock
import com.eddyslarez.kmpsiprtc.utils.synchronized


class PushModeManager(
    private val config: PushModeConfig = PushModeConfig()
) {
    @OptIn(ExperimentalTime::class)
    val timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()

    // Estados
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "PushModeManager"
    private val TAG1 = "ProcesoPush"

    // Mutex para proteger operaciones criticas de estado
    private val callEndMutex = Mutex()
    private val transitionMutex = Mutex()

    // Estados
    @OptIn(ExperimentalTime::class)
    private val _pushModeStateFlow = MutableStateFlow(
        PushModeState(
            currentMode = PushMode.FOREGROUND,
            previousMode = null,
          timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            reason = "Initial state"
        )
    )
    val pushModeStateFlow: StateFlow<PushModeState> = _pushModeStateFlow.asStateFlow()

    // Flag para track de modo push antes de desconexion
    private var wasInPushBeforeDisconnect = false

    // Jobs para transiciones automaticas - POR CUENTA ESPECIFICA
    private var transitionJob: Job? = null
    private val callEndTransitionJobs = mutableMapOf<String, Job>()

    // Control de estado por cuenta especifica - protegidos por Lock
    private val accountPushStatesLock = Lock()
    private val accountPushStates = mutableMapOf<String, Boolean>()
    private val pendingReturns = mutableSetOf<String>()

    // Callbacks
    private var onModeChangeCallback: ((PushModeState) -> Unit)? = null
    private var onRegistrationRequiredCallback: ((Set<String>, PushMode) -> Unit)? = null

    // Callback para verificar salud de WebSocket antes de transiciones
    private var isWebSocketHealthyCallback: (() -> Boolean)? = null
    private var forceReconnectCallback: (() -> Unit)? = null

    // Estado interno
    private var isCallActive = false
    private var wasInPushBeforeCall = false

    /**
     * Configura callbacks para notificaciones de cambios
     */
    fun setCallbacks(
        onModeChange: ((PushModeState) -> Unit)? = null,
        onRegistrationRequired: ((Set<String>, PushMode) -> Unit)? = null
    ) {
        this.onModeChangeCallback = onModeChange
        this.onRegistrationRequiredCallback = onRegistrationRequired
    }

    /**
     * Configura callbacks para verificar WebSocket
     */
    fun setWebSocketCallbacks(
        isHealthy: (() -> Boolean)? = null,
        forceReconnect: (() -> Unit)? = null
    ) {
        this.isWebSocketHealthyCallback = isHealthy
        this.forceReconnectCallback = forceReconnect
    }

    /**
     * Notificar reconexion exitosa - verificar y restaurar modo push si corresponde
     */
    fun onReconnectionSuccessful(registeredAccounts: Set<String>) {
        log.d(tag = TAG1) { "Reconnection successful, wasInPushBeforeDisconnect: $wasInPushBeforeDisconnect" }

        if (wasInPushBeforeDisconnect && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG1) { "Restoring PUSH mode after reconnection for ${registeredAccounts.size} accounts" }
            wasInPushBeforeDisconnect = false
            transitionToPush(registeredAccounts, PushModeReasons.RECONNECTION_RESTORED)
        }
    }

    /**
     * Notificar desconexion - guardar estado actual
     */
    fun onDisconnected() {
        val currentMode = getCurrentMode()
        wasInPushBeforeDisconnect = (currentMode == PushMode.PUSH)
        log.d(tag = TAG1) { "Disconnected, saving push state: wasInPush=$wasInPushBeforeDisconnect" }
    }

    /**
     * Notifica que la aplicacion paso a segundo plano
     */
    fun onAppBackgrounded(registeredAccounts: Set<String>) {
        log.d(tag = TAG1) {
            "=== APP BACKGROUNDED EVENT ===" +
                    "\nRegistered accounts: ${registeredAccounts.size}" +
                    "\nAccounts: $registeredAccounts" +
                    "\nCurrent mode: ${getCurrentMode()}" +
                    "\nStrategy: ${config.strategy}" +
                    "\nIs call active: $isCallActive"
        }

        if (config.strategy == PushModeStrategy.AUTOMATIC && !isCallActive && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG1) { "Conditions met for transition to push mode" }
            scheduleTransitionToPush(registeredAccounts, PushModeReasons.APP_BACKGROUNDED)
        } else {
            log.w(tag = TAG1) {
                "Transition to push NOT scheduled:" +
                        "\n- Strategy: ${config.strategy}" +
                        "\n- Call active: $isCallActive" +
                        "\n- Accounts: ${registeredAccounts.size}"
            }
        }
    }

    /**
     * Notifica que la aplicacion paso a primer plano
     */
    fun onAppForegrounded(registeredAccounts: Set<String>) {
        log.d(tag = TAG1) {
            "=== APP FOREGROUNDED EVENT ===" +
                    "\nRegistered accounts: ${registeredAccounts.size}" +
                    "\nAccounts: $registeredAccounts" +
                    "\nCurrent mode: ${getCurrentMode()}" +
                    "\nStrategy: ${config.strategy}" +
                    "\nIs call active: $isCallActive"
        }

        // Cancelar transicion pendiente a push
        cancelPendingTransition()

        // NO cambiar a foreground si hay una llamada activa (ej: llamada entrante de push)
        // El modo se ajustara cuando la llamada termine, segun el estado de la app
        if (isCallActive) {
            log.d(tag = TAG1) { "Call active - suppressing foreground mode transition" }
            return
        }

        if (config.strategy == PushModeStrategy.AUTOMATIC && registeredAccounts.isNotEmpty()) {
            // Verificar WebSocket saludable antes de transicionar
            ensureWebSocketHealthyAndTransition(registeredAccounts, PushModeReasons.APP_FOREGROUNDED)
        } else {
            log.w(tag = TAG1) { "Transition to foreground NOT executed - strategy: ${config.strategy}" }
        }
    }

    /**
     * Marca explicitamente que hay una llamada activa (sin transicion de modo).
     * Usar cuando se recibe un push VoIP para bloquear transiciones durante la llamada.
     */
    fun setCallActive(active: Boolean) {
        isCallActive = active
        log.d(tag = TAG1) { "Call active set to: $active" }
    }

    /**
     * Verificar WebSocket y transicionar a foreground con timeout de seguridad
     */
    private fun ensureWebSocketHealthyAndTransition(accounts: Set<String>, reason: String) {
        val isHealthy = isWebSocketHealthyCallback?.invoke() ?: true

        if (!isHealthy) {
            log.w(tag = TAG1) { "WebSocket not healthy, forcing reconnect before foreground transition" }
            forceReconnectCallback?.invoke()

            // Programar transicion con timeout de seguridad de 10s
            scope.launch {
                var waited = 0L
                val timeout = 10000L
                while (waited < timeout) {
                    delay(500)
                    waited += 500
                    if (isWebSocketHealthyCallback?.invoke() == true) {
                        log.d(tag = TAG1) { "WebSocket healthy after ${waited}ms, transitioning to foreground" }
                        transitionToForeground(accounts, reason)
                        return@launch
                    }
                }
                // Timeout: transicionar de todas formas
                log.w(tag = TAG1) { "WebSocket not healthy after ${timeout}ms timeout, transitioning anyway" }
                transitionToForeground(accounts, reason)
            }
        } else {
            log.d(tag = TAG1) { "WebSocket healthy, transitioning to foreground" }
            transitionToForeground(accounts, reason)
        }
    }

    /**
     * Notifica que se recibio una llamada entrante
     */
    fun onIncomingCallReceived(registeredAccounts: Set<String>) {
        log.d(tag = TAG) { "Incoming call received, current mode: ${getCurrentMode()}" }

        val currentState = _pushModeStateFlow.value

        // Recordar si estabamos en modo push antes de la llamada
        if (currentState.currentMode == PushMode.PUSH) {
            wasInPushBeforeCall = true
        }

        isCallActive = true

        // Cancelar cualquier transicion pendiente
        cancelPendingTransition()
        cancelCallEndTransition()

        // Si estamos en modo push y se requiere reregistro automatico
        if (currentState.currentMode == PushMode.PUSH && config.forceReregisterOnIncomingCall) {
            transitionToForeground(registeredAccounts, PushModeReasons.INCOMING_CALL_RECEIVED)
        }
    }

    /**
     * Notifica que una llamada termino
     *
     * @param isAppInBackground true si la app esta en segundo plano cuando termina la llamada
     */
    fun onCallEnded(registeredAccounts: Set<String>, isAppInBackground: Boolean = true) {
        log.d(tag = TAG) { "Call ended, was in push before call: $wasInPushBeforeCall, isAppInBackground: $isAppInBackground" }

        isCallActive = false

        if (wasInPushBeforeCall && config.returnToPushAfterCallEnd) {
            if (!isAppInBackground) {
                // App en primer plano: cambiar a FOREGROUND
                log.d(tag = TAG) { "App in foreground after push call - switching to FOREGROUND" }
                transitionToForeground(registeredAccounts, PushModeReasons.APP_FOREGROUNDED)
            } else {
                // App en background: volver a PUSH
                scheduleReturnToPushAfterCall(registeredAccounts)
            }
        }

        // Resetear el flag solo aqui, despues de programar el retorno
        wasInPushBeforeCall = false
    }

    /**
     * Transicion manual a modo push
     */
    fun switchToPushMode(accountsToSwitch: Set<String>) {
        log.d(tag = TAG) { "Manual switch to push mode for accounts: $accountsToSwitch" }

        cancelPendingTransition()
        transitionToPush(accountsToSwitch, PushModeReasons.MANUAL_SWITCH)
    }

    /**
     * Transicion manual a modo foreground
     */
    fun switchToForegroundMode(accountsToSwitch: Set<String>) {
        log.d(tag = TAG) { "Manual switch to foreground mode for accounts: $accountsToSwitch" }

        cancelPendingTransition()
        transitionToForeground(accountsToSwitch, PushModeReasons.MANUAL_SWITCH)
    }

    /**
     * Notifica que se recibio una notificacion push.
     * RFC 8599 §4.1.3: El binding-refresh REGISTER debe incluir los mismos pn-* params
     * que se usaron al registrar en modo push. NO se transiciona a foreground.
     */
    fun onPushNotificationReceived(specificAccount: String? = null, allRegisteredAccounts: Set<String> = emptySet()) {
        log.d(tag = TAG1) { "Push notification received - sending binding-refresh in PUSH mode (RFC 8599)" }
        log.d(tag = TAG1) { "specificAccount : $specificAccount" }

        val currentState = _pushModeStateFlow.value
        log.d(tag = TAG1) { "currentState : $currentState" }

        if (specificAccount != null) {
            // Recordar estado push para manejo post-llamada
            synchronized(accountPushStatesLock) {
                accountPushStates[specificAccount] = true
            }
            log.d(tag = TAG1) { "Sending binding-refresh for $specificAccount in PUSH mode (keeping pn-prid)" }

            // RFC 8599: re-registrar con los mismos pn-* params, SIN transicionar a foreground
            onRegistrationRequiredCallback?.invoke(setOf(specificAccount), PushMode.PUSH)
        } else {
            synchronized(accountPushStatesLock) {
                allRegisteredAccounts.forEach { account ->
                    accountPushStates[account] = true
                }
            }
            log.d(tag = TAG1) { "Sending binding-refresh for all accounts in PUSH mode (keeping pn-prid)" }

            // RFC 8599: re-registrar con los mismos pn-* params, SIN transicionar a foreground
            onRegistrationRequiredCallback?.invoke(allRegisteredAccounts, PushMode.PUSH)
        }
    }

    /**
     * Transicion de una cuenta especifica a modo foreground
     */
    @OptIn(ExperimentalTime::class)
    private fun transitionSpecificAccountToForeground(accountKey: String, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG1) { "Transitioning specific account to FOREGROUND: $accountKey, reason: $reason" }

        // Crear nuevo estado manteniendo las otras cuentas en push
        val updatedAccountsInPush = currentState.accountsInPushMode.toMutableSet()
        updatedAccountsInPush.remove(accountKey)

        val newState = PushModeState(
            currentMode = if (updatedAccountsInPush.isEmpty()) PushMode.FOREGROUND else PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = updatedAccountsInPush,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall,
            specificAccountInForeground = accountKey
        )

        _pushModeStateFlow.value = newState

        // Notificar que se requiere reregistro solo para la cuenta especifica
        onRegistrationRequiredCallback?.invoke(setOf(accountKey), PushMode.FOREGROUND)
        onModeChangeCallback?.invoke(newState)
    }

    /**
     * Notifica que una llamada termino para una cuenta especifica
     * Protegido con Mutex para evitar race conditions
     *
     * @param isAppInBackground true si la app esta en segundo plano cuando termina la llamada.
     *        Si false (app en primer plano) y la llamada vino de push, se transiciona a FOREGROUND
     *        en lugar de volver a PUSH.
     */
    fun onCallEndedForAccount(
        accountKey: String,
        allRegisteredAccounts: Set<String>,
        isAppInBackground: Boolean = true
    ) {
        scope.launch {
            callEndMutex.withLock {
                // Prevenir llamadas duplicadas - thread-safe con mutex
                if (pendingReturns.contains(accountKey)) {
                    log.d(tag = TAG1) { "Call end already being processed for $accountKey, ignoring duplicate" }
                    return@withLock
                }

                val wasInPush = synchronized(accountPushStatesLock) {
                    accountPushStates[accountKey] ?: false
                }

                log.d(tag = TAG1) {
                    "Call ended for specific account: $accountKey" +
                            "\nwasInPushBeforeCall: $wasInPush" +
                            "\nisAppInBackground: $isAppInBackground"
                }

                isCallActive = false

                // Si estabamos en modo push antes de la llamada y esta configurado para volver
                if (wasInPush && config.returnToPushAfterCallEnd) {
                    if (!isAppInBackground) {
                        // App en primer plano: cambiar a FOREGROUND en lugar de volver a PUSH
                        log.d(tag = TAG1) { "App in foreground after push call - switching to FOREGROUND for $accountKey" }
                        transitionSpecificAccountToForeground(accountKey, PushModeReasons.APP_FOREGROUNDED)
                        cleanupAccountState(accountKey)
                    } else {
                        // App en segundo plano/bloqueada: volver a PUSH
                        log.d(tag = TAG1) { "Scheduling return to push mode for account: $accountKey" }

                        // Marcar como pendiente ANTES de programar
                        pendingReturns.add(accountKey)

                        // Cancelar job anterior ANTES de crear nuevo
                        callEndTransitionJobs[accountKey]?.cancel()

                        scheduleReturnToPushForSpecificAccount(accountKey)
                    }
                } else {
                    log.d(tag = TAG1) { "Not returning to push mode - wasInPushBeforeCall: $wasInPush, returnToPushAfterCallEnd: ${config.returnToPushAfterCallEnd}" }

                    // Limpiar estado si no va a retornar
                    cleanupAccountState(accountKey)
                }
            }
        }
    }

    /**
     * Programa retorno a modo push para una cuenta especifica despues de que termine una llamada
     */
    private fun scheduleReturnToPushForSpecificAccount(accountKey: String) {
        callEndTransitionJobs[accountKey] = scope.launch {
            try {
                val returnDelay = 500L
                log.d(tag = TAG1) { "Scheduling return to push for account $accountKey in ${returnDelay}ms after call end" }

                delay(returnDelay)

                // Verificar que no hay nueva llamada activa Y que aun esta pendiente el retorno
                if (!isCallActive && pendingReturns.contains(accountKey)) {
                    log.d(tag = TAG1) { "Executing return to push mode for account: $accountKey" }

                    // Ejecutar transicion
                    transitionSpecificAccountToPush(accountKey, PushModeReasons.CALL_ENDED)

                    // Limpiar estados DESPUES de completar la transicion
                    cleanupAccountState(accountKey)

                } else {
                    log.d(tag = TAG1) {
                        "Return to push cancelled for $accountKey - " +
                                "callActive: $isCallActive, pending: ${pendingReturns.contains(accountKey)}"
                    }

                    // Limpiar si se cancela
                    cleanupAccountState(accountKey)
                }

            } catch (e: CancellationException) {
                log.d(tag = TAG1) { "Return to push job cancelled for $accountKey" }
                cleanupAccountState(accountKey)
            } catch (e: Exception) {
                log.e(tag = TAG1) { "Error in call end transition for $accountKey: ${e.message}" }
                cleanupAccountState(accountKey)
            } finally {
                // Asegurar limpieza en cualquier caso
                callEndTransitionJobs.remove(accountKey)
            }
        }
    }

    /**
     * Limpieza de estado para una cuenta especifica - thread-safe
     */
    private fun cleanupAccountState(accountKey: String) {
        synchronized(accountPushStatesLock) {
            accountPushStates.remove(accountKey)
        }
        pendingReturns.remove(accountKey)
        log.d(tag = TAG1) { "Cleaned up state for account: $accountKey" }
    }

    /**
     * Transicion de una cuenta especifica a modo push
     */
    @OptIn(ExperimentalTime::class)
    private fun transitionSpecificAccountToPush(accountKey: String, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG1) { "Transitioning specific account to PUSH: $accountKey, reason: $reason" }

        // Agregar la cuenta especifica al conjunto de cuentas en push
        val updatedAccountsInPush = currentState.accountsInPushMode.toMutableSet()
        updatedAccountsInPush.add(accountKey)

        val newState = PushModeState(
            currentMode = PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = updatedAccountsInPush,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall,
            specificAccountInForeground = null
        )

        _pushModeStateFlow.value = newState

        // Notificar que se requiere reregistro en modo push para la cuenta especifica
        onRegistrationRequiredCallback?.invoke(setOf(accountKey), PushMode.PUSH)
        onModeChangeCallback?.invoke(newState)

        log.d(tag = TAG1) { "Push mode changed: PUSH ($reason)" }
        log.d(tag = TAG1) { "Account $accountKey successfully transitioned to push mode" }
    }

    /**
     * Programa transicion a modo push con delay y logging mejorado
     */
    private fun scheduleTransitionToPush(accounts: Set<String>, reason: String) {
        log.d(tag = TAG1) { "Scheduling transition to push mode..." }

        cancelPendingTransition()

        transitionJob = scope.launch {
            try {
                log.d(tag = TAG1) {
                    "Starting transition delay of ${config.autoTransitionDelay}ms" +
                            "\nReason: $reason" +
                            "\nAccounts to switch: $accounts"
                }

                delay(config.autoTransitionDelay)

                // Verificar que aun no hay llamada activa
                if (!isCallActive) {
                    log.d(tag = TAG1) { "Delay completed, executing transition to push" }
                    transitionToPush(accounts, reason)
                } else {
                    log.d(tag = TAG1) { "Transition to push cancelled - call became active during delay" }
                }
            } catch (e: CancellationException) {
                log.d(tag = TAG1) { "Transition to push was cancelled" }
            } catch (e: Exception) {
                log.e(tag = TAG1) { "Error in scheduled transition: ${e.message}" }
            }
        }
    }

    /**
     * Programa retorno a modo push despues de que termine una llamada
     */
    private fun scheduleReturnToPushAfterCall(accounts: Set<String>) {
        cancelCallEndTransition()

        scope.launch {
            try {
                val returnDelay = 500L
                log.d(tag = TAG) { "Scheduling return to push in ${returnDelay}ms after call end" }
                delay(returnDelay)

                // Verificar que no hay nueva llamada activa
                if (!isCallActive) {
                    transitionToPush(accounts, PushModeReasons.CALL_ENDED)
                } else {
                    log.d(tag = TAG) { "Return to push cancelled - new call is active" }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in call end transition: ${e.message}" }
            }
        }
    }


    /**
     * Transicion inmediata a modo push con logging detallado
     */
    @OptIn(ExperimentalTime::class)
    private fun transitionToPush(accounts: Set<String>, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG1) {
            "=== EXECUTING TRANSITION TO PUSH ===" +
                    "\nFrom mode: ${currentState.currentMode}" +
                    "\nReason: $reason" +
                    "\nAccounts: $accounts" +
                    "\nCallback set: ${onRegistrationRequiredCallback != null}" +
                    "\nMode change callback set: ${onModeChangeCallback != null}"
        }

        if (currentState.currentMode == PushMode.PUSH) {
            log.d(tag = TAG1) { "Already in push mode, ignoring transition" }
            return
        }

        val newState = PushModeState(
            currentMode = PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = accounts,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall
        )

        _pushModeStateFlow.value = newState

        log.d(tag = TAG1) { "Push mode state updated successfully" }

        // Notificar callbacks
        try {
            log.d(tag = TAG1) { "Calling registration required callback for ${accounts.size} accounts" }
            onRegistrationRequiredCallback?.invoke(accounts, PushMode.PUSH)

            log.d(tag = TAG1) { "Calling mode change callback" }
            onModeChangeCallback?.invoke(newState)

            log.d(tag = TAG1) { "=== TRANSITION TO PUSH COMPLETED ===" }
        } catch (e: Exception) {
            log.e(tag = TAG1) { "Error in transition callbacks: ${e.message}" }
        }
    }

    /**
     * Transicion inmediata a modo foreground con logging detallado
     */
    @OptIn(ExperimentalTime::class)
    private fun transitionToForeground(accounts: Set<String>, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG1) {
            "=== EXECUTING TRANSITION TO FOREGROUND ===" +
                    "\nFrom mode: ${currentState.currentMode}" +
                    "\nReason: $reason" +
                    "\nAccounts: $accounts"
        }

        if (currentState.currentMode == PushMode.FOREGROUND) {
            log.d(tag = TAG1) { "Already in foreground mode, ignoring transition" }
            return
        }

        val newState = PushModeState(
            currentMode = PushMode.FOREGROUND,
            previousMode = currentState.currentMode,
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = emptySet(),
            wasInPushBeforeCall = currentState.wasInPushBeforeCall
        )

        _pushModeStateFlow.value = newState

        log.d(tag = TAG1) { "Foreground mode state updated successfully" }

        // Notificar callbacks
        try {
            log.d(tag = TAG1) { "Calling registration required callback for ${accounts.size} accounts" }
            onRegistrationRequiredCallback?.invoke(accounts, PushMode.FOREGROUND)

            log.d(tag = TAG1) { "Calling mode change callback" }
            onModeChangeCallback?.invoke(newState)

            log.d(tag = TAG1) { "=== TRANSITION TO FOREGROUND COMPLETED ===" }
        } catch (e: Exception) {
            log.e(tag = TAG1) { "Error in transition callbacks: ${e.message}" }
        }
    }

    /**
     * Cancela transicion pendiente
     */
    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    /**
     * Cancela transicion de fin de llamada
     */
    private fun cancelCallEndTransition(accountKey: String? = null) {
        if (accountKey != null) {
            // Cancelar solo para cuenta especifica
            callEndTransitionJobs[accountKey]?.cancel()
            callEndTransitionJobs.remove(accountKey)
            pendingReturns.remove(accountKey)
            log.d(tag = TAG1) { "Cancelled call end transition for specific account: $accountKey" }
        } else {
            // Cancelar todas las transiciones (comportamiento original)
            callEndTransitionJobs.values.forEach { it.cancel() }
            callEndTransitionJobs.clear()
            pendingReturns.clear()
            log.d(tag = TAG1) { "Cancelled all call end transitions" }
        }
    }

// === METODOS DE CONSULTA ===

    fun getCurrentMode(): PushMode = _pushModeStateFlow.value.currentMode
    fun getCurrentState(): PushModeState = _pushModeStateFlow.value
    fun isInPushMode(): Boolean = getCurrentMode() == PushMode.PUSH
    fun isInForegroundMode(): Boolean = getCurrentMode() == PushMode.FOREGROUND
    fun getConfig(): PushModeConfig = config

    /**
     * Metodo para debugging del estado por cuenta
     */
    fun getAccountStates(): String {
        return buildString {
            appendLine("=== ACCOUNT PUSH STATES ===")
            appendLine("Accounts in push before call:")
            synchronized(accountPushStatesLock) {
                accountPushStates.forEach { (account, wasInPush) ->
                    appendLine("  $account: $wasInPush")
                }
            }
            appendLine("Pending returns:")
            pendingReturns.forEach { account ->
                appendLine("  $account")
            }
            appendLine("Active return jobs: ${callEndTransitionJobs.size}")
            callEndTransitionJobs.forEach { (account, job) ->
                appendLine("  $account: ${job.isActive}")
            }
            appendLine("Was in push before disconnect: $wasInPushBeforeDisconnect")
        }
    }

    /**
     * Informacion de diagnostico
     */
    fun getDiagnosticInfo(): String {
        val state = getCurrentState()

        return buildString {
            appendLine("=== PUSH MODE MANAGER DIAGNOSTIC ===")
            appendLine("Current Mode: ${state.currentMode}")
            appendLine("Previous Mode: ${state.previousMode}")
            appendLine("Strategy: ${config.strategy}")
            appendLine("Reason: ${state.reason}")
            appendLine("Timestamp: ${state.timestamp}")
            appendLine("Is Call Active: $isCallActive")
            appendLine("Accounts In Push Mode: ${state.accountsInPushMode}")
            appendLine("Specific Account In Foreground: ${state.specificAccountInForeground}")
            appendLine("Transition Job Active: ${transitionJob?.isActive}")
            appendLine("Call End Jobs Active: ${callEndTransitionJobs.size}")
            callEndTransitionJobs.forEach { (account, job) ->
                appendLine("  $account: ${job.isActive}")
            }
            appendLine("Auto Transition Delay: ${config.autoTransitionDelay}ms")
            appendLine("Force Reregister On Call: ${config.forceReregisterOnIncomingCall}")
            appendLine("Return To Push After Call: ${config.returnToPushAfterCallEnd}")
            appendLine("Was In Push Before Disconnect: $wasInPushBeforeDisconnect")

            // Estado por cuenta
            appendLine("\n${getAccountStates()}")
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        cancelPendingTransition()
        cancelCallEndTransition() // Cancela todos

        // Limpiar estados por cuenta
        synchronized(accountPushStatesLock) {
            accountPushStates.clear()
        }
        pendingReturns.clear()
        callEndTransitionJobs.clear()

        log.d(tag = TAG) { "PushModeManager disposed" }
    }
}
