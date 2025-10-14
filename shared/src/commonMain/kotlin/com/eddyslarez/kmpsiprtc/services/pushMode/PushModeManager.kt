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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.datetime.Clock


class PushModeManager(
    private val config: PushModeConfig = PushModeConfig()
) {
    val timestamp = Clock.System.now().toEpochMilliseconds()

    // Estados
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "PushModeManager"
    private val TAG1 = "ProcesoPush"

    // Estados
    private val _pushModeStateFlow = MutableStateFlow(
        PushModeState(
            currentMode = PushMode.FOREGROUND,
            previousMode = null,
          timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = "Initial state"
        )
    )
    val pushModeStateFlow: StateFlow<PushModeState> = _pushModeStateFlow.asStateFlow()


    // Jobs para transiciones automáticas - POR CUENTA ESPECÍFICA
    private var transitionJob: Job? = null
    private val callEndTransitionJobs = mutableMapOf<String, Job>() // CAMBIO: Map por cuenta

    // Control de estado por cuenta específica
    private val accountPushStates = mutableMapOf<String, Boolean>() // wasInPushBeforeCall por cuenta
    private val pendingReturns = mutableSetOf<String>() // Cuentas con retorno pendiente

    // Callbacks
    private var onModeChangeCallback: ((PushModeState) -> Unit)? = null
    private var onRegistrationRequiredCallback: ((Set<String>, PushMode) -> Unit)? = null

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
     * Notifica que la aplicación pasó a segundo plano
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
    //    /**
//     * Notifica que la aplicación pasó a segundo plano
//     /
//    fun onAppBackgrounded(registeredAccounts: Set<String>) {
//        log.d(tag = TAG) { "App backgrounded with ${registeredAccounts.size} accounts" }
//
//        if (config.strategy == PushModeStrategy.AUTOMATIC && !isCallActive) {
//            scheduleTransitionToPush(registeredAccounts, PushModeReasons.APP_BACKGROUNDED)
//        }
//    }
    /*
    * Notifica que la aplicación pasó a primer plano
    */
    fun onAppForegrounded(registeredAccounts: Set<String>) {
        log.d(tag = TAG1) {
            "=== APP FOREGROUNDED EVENT ===" +
                    "\nRegistered accounts: ${registeredAccounts.size}" +
                    "\nAccounts: $registeredAccounts" +
                    "\nCurrent mode: ${getCurrentMode()}" +
                    "\nStrategy: ${config.strategy}"
        }

        // Cancelar transición pendiente a push
        cancelPendingTransition()

        if (config.strategy == PushModeStrategy.AUTOMATIC && registeredAccounts.isNotEmpty()) {
            log.d(tag = TAG1) { "Transitioning to foreground mode" }
            transitionToForeground(registeredAccounts, PushModeReasons.APP_FOREGROUNDED)
        } else {
            log.w(tag = TAG1) { "Transition to foreground NOT executed - strategy: ${config.strategy}" }
        }
    }
//    /**
//     * Notifica que la aplicación pasó a primer plano
//     */
//    fun onAppForegrounded(registeredAccounts: Set<String>) {
//        log.d(tag = TAG) { "App foregrounded with ${registeredAccounts.size} accounts" }
//
//        // Cancelar transición pendiente a push
//        cancelPendingTransition()
//
//        if (config.strategy == PushModeStrategy.AUTOMATIC) {
//            transitionToForeground(registeredAccounts, PushModeReasons.APP_FOREGROUNDED)
//        }
//    }

    /**
     * Notifica que se recibió una llamada entrante
     */
    fun onIncomingCallReceived(registeredAccounts: Set<String>) {
        log.d(tag = TAG) { "Incoming call received, current mode: ${getCurrentMode()}" }

        val currentState = _pushModeStateFlow.value

        // Recordar si estábamos en modo push antes de la llamada
        if (currentState.currentMode == PushMode.PUSH) {
            wasInPushBeforeCall = true
        }

        isCallActive = true

        // Cancelar cualquier transición pendiente
        cancelPendingTransition()
        cancelCallEndTransition()

        // Si estamos en modo push y se requiere reregistro automático
        if (currentState.currentMode == PushMode.PUSH && config.forceReregisterOnIncomingCall) {
            transitionToForeground(registeredAccounts, PushModeReasons.INCOMING_CALL_RECEIVED)
        }
    }

    /**
     * Notifica que una llamada terminó
     */
// Y modifica el método onCallEnded:
    fun onCallEnded(registeredAccounts: Set<String>) {
        log.d(tag = TAG) { "Call ended, was in push before call: $wasInPushBeforeCall" }

        isCallActive = false

        if (wasInPushBeforeCall && config.returnToPushAfterCallEnd) {
            scheduleReturnToPushAfterCall(registeredAccounts)
        }

        // Resetear el flag solo aquí, después de programar el retorno
        wasInPushBeforeCall = false
    }

    /**
     * Transición manual a modo push
     */
    fun switchToPushMode(accountsToSwitch: Set<String>) {
        log.d(tag = TAG) { "Manual switch to push mode for accounts: $accountsToSwitch" }

        cancelPendingTransition()
        transitionToPush(accountsToSwitch, PushModeReasons.MANUAL_SWITCH)
    }

    /**
     * Transición manual a modo foreground
     */
    fun switchToForegroundMode(accountsToSwitch: Set<String>) {
        log.d(tag = TAG) { "Manual switch to foreground mode for accounts: $accountsToSwitch" }

        cancelPendingTransition()
        transitionToForeground(accountsToSwitch, PushModeReasons.MANUAL_SWITCH)
    }

    /**
     * Notifica que se recibió una notificación push
     */
    fun onPushNotificationReceived(specificAccount: String? = null, allRegisteredAccounts: Set<String> = emptySet()) {
        log.d(tag = TAG1) { "Push notification received" }
        log.d(tag = TAG1) { "specificAccount : $specificAccount" }

        val currentState = _pushModeStateFlow.value
        log.d(tag = TAG1) { "currentState : $currentState" }

        // Si estamos en modo push, cambiar a foreground solo la cuenta específica o todas
        //if (currentState.currentMode == PushMode.PUSH) {

        if (specificAccount != null) {
            // CORREGIDO: Recordar estado push ANTES de cambiar
            accountPushStates[specificAccount] = true
            log.d(tag = TAG1) { "Recorded push state for $specificAccount before foreground transition" }

            // Solo cambiar la cuenta específica a foreground
            log.d(tag = TAG1) { "Switching specific account to foreground: $specificAccount" }
            transitionSpecificAccountToForeground(specificAccount, PushModeReasons.PUSH_NOTIFICATION_RECEIVED)
        } else {
            // Cambiar todas las cuentas (comportamiento anterior)
            allRegisteredAccounts.forEach { account ->
                accountPushStates[account] = true
            }
            transitionToForeground(allRegisteredAccounts, PushModeReasons.PUSH_NOTIFICATION_RECEIVED)
        }
        //   }
    }

    /**
     * Transición de una cuenta específica a modo foreground
     */
    private fun transitionSpecificAccountToForeground(accountKey: String, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG1) { "Transitioning specific account to FOREGROUND: $accountKey, reason: $reason" }

        // Crear nuevo estado manteniendo las otras cuentas en push
        val updatedAccountsInPush = currentState.accountsInPushMode.toMutableSet()
        updatedAccountsInPush.remove(accountKey)

        val newState = PushModeState(
            currentMode = if (updatedAccountsInPush.isEmpty()) PushMode.FOREGROUND else PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = updatedAccountsInPush,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall,
            specificAccountInForeground = accountKey
        )

        _pushModeStateFlow.value = newState

        // Notificar que se requiere reregistro solo para la cuenta específica
        onRegistrationRequiredCallback?.invoke(setOf(accountKey), PushMode.FOREGROUND)
        onModeChangeCallback?.invoke(newState)
    }

    /**
     * Notifica que una llamada terminó para una cuenta específica
     */
    fun onCallEndedForAccount(accountKey: String, allRegisteredAccounts: Set<String>) {
        // PREVENIR LLAMADAS DUPLICADAS
        if (pendingReturns.contains(accountKey)) {
            log.d(tag = TAG1) { "Call end already being processed for $accountKey, ignoring duplicate" }
            return
        }

        val wasInPush = accountPushStates[accountKey] ?: false

        log.d(tag = TAG1) { "Call ended for specific account: $accountKey, was in push before call: $wasInPush" }

        isCallActive = false

        // Si estábamos en modo push antes de la llamada y está configurado para volver
        if (wasInPush && config.returnToPushAfterCallEnd) {
            log.d(tag = TAG1) { "Scheduling return to push mode for account: $accountKey" }

            // Marcar como pendiente ANTES de programar
            pendingReturns.add(accountKey)

            scheduleReturnToPushForSpecificAccount(accountKey)
        } else {
            log.d(tag = TAG1) { "Not returning to push mode - wasInPushBeforeCall: $wasInPush, returnToPushAfterCallEnd: ${config.returnToPushAfterCallEnd}" }

            // Limpiar estado si no va a retornar
            accountPushStates.remove(accountKey)
        }
    }



    /**
     * Programa retorno a modo push para una cuenta específica después de que termine una llamada
     */
    private fun scheduleReturnToPushForSpecificAccount(accountKey: String) {
        // Cancelar job anterior SOLO para esta cuenta específica
        callEndTransitionJobs[accountKey]?.cancel()

        callEndTransitionJobs[accountKey] = scope.launch {
            try {
                val returnDelay = 2000L
                log.d(tag = TAG1) { "Scheduling return to push for account $accountKey in ${returnDelay}ms after call end" }

                delay(returnDelay)

                // Verificar que no hay nueva llamada activa Y que aún está pendiente el retorno
                if (!isCallActive && pendingReturns.contains(accountKey)) {
                    log.d(tag = TAG1) { "Executing return to push mode for account: $accountKey" }

                    // Ejecutar transición
                    transitionSpecificAccountToPush(accountKey, PushModeReasons.CALL_ENDED)

                    // Limpiar estados DESPUÉS de completar la transición
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
     * NUEVO: Limpieza de estado para una cuenta específica
     */
    private fun cleanupAccountState(accountKey: String) {
        accountPushStates.remove(accountKey)
        pendingReturns.remove(accountKey)
        log.d(tag = TAG1) { "Cleaned up state for account: $accountKey" }
    }

    /**
     * Transición de una cuenta específica a modo push
     */
    private fun transitionSpecificAccountToPush(accountKey: String, reason: String) {
        val currentState = _pushModeStateFlow.value

        log.d(tag = TAG1) { "Transitioning specific account to PUSH: $accountKey, reason: $reason" }

        // Agregar la cuenta específica al conjunto de cuentas en push
        val updatedAccountsInPush = currentState.accountsInPushMode.toMutableSet()
        updatedAccountsInPush.add(accountKey)

        val newState = PushModeState(
            currentMode = PushMode.PUSH,
            previousMode = currentState.currentMode,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            reason = reason,
            accountsInPushMode = updatedAccountsInPush,
            wasInPushBeforeCall = currentState.wasInPushBeforeCall,
            specificAccountInForeground = null
        )

        _pushModeStateFlow.value = newState

        // Notificar que se requiere reregistro en modo push para la cuenta específica
        onRegistrationRequiredCallback?.invoke(setOf(accountKey), PushMode.PUSH)
        onModeChangeCallback?.invoke(newState)

        log.d(tag = TAG1) { "Push mode changed: PUSH ($reason)" }
        log.d(tag = TAG1) { "Account $accountKey successfully transitioned to push mode" }
    }


    /**
     * Programa transición a modo push con delay
     */
//    private fun scheduleTransitionToPush(accounts: Set<String>, reason: String) {
//        cancelPendingTransition()
//
//        transitionJob = scope.launch {
//            try {
//                log.d(tag = TAG) { "Scheduling transition to push in ${config.autoTransitionDelay}ms" }
//                delay(config.autoTransitionDelay)
//
//                // Verificar que aún no hay llamada activa
//                if (!isCallActive) {
//                    transitionToPush(accounts, reason)
//                } else {
//                    log.d(tag = TAG) { "Transition to push cancelled - call is active" }
//                }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "Error in scheduled transition: ${e.message}" }
//            }
//        }
//    }
    /**
     * Programa transición a modo push con delay y logging mejorado
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

                // Verificar que aún no hay llamada activa
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
     * Programa retorno a modo push después de que termine una llamada
     */
    private fun scheduleReturnToPushAfterCall(accounts: Set<String>) {
        cancelCallEndTransition()

        //  callEndTransitionJob =
        scope.launch {
            try {
                // Delay más corto para retorno después de llamada
                val returnDelay = 2000L
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
     * Transición inmediata a modo push con logging detallado
     */
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
            timestamp = Clock.System.now().toEpochMilliseconds(),
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
//    private fun transitionToPush(accounts: Set<String>, reason: String) {
//        val currentState = _pushModeStateFlow.value
//
//        if (currentState.currentMode == PushMode.PUSH) {
//            log.d(tag = TAG) { "Already in push mode, ignoring transition" }
//            return
//        }
//
//        log.d(tag = TAG) { "Transitioning to PUSH mode: $reason" }
//
//        val newState = PushModeState(
//            currentMode = PushMode.PUSH,
//            previousMode = currentState.currentMode,
//            timestamp = Clock.System.now().toEpochMilliseconds(),
//            reason = reason,
//            accountsInPushMode = accounts,
//            wasInPushBeforeCall = currentState.wasInPushBeforeCall
//        )
//
//        _pushModeStateFlow.value = newState
//
//        // Notificar que se requiere reregistro en modo push
//        onRegistrationRequiredCallback?.invoke(accounts, PushMode.PUSH)
//        onModeChangeCallback?.invoke(newState)
//    }

    /**
     * Transición inmediata a modo foreground con logging detallado
     */
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
            timestamp = Clock.System.now().toEpochMilliseconds(),
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
     * Transición inmediata a modo foreground
     */
//    private fun transitionToForeground(accounts: Set<String>, reason: String) {
//        val currentState = _pushModeStateFlow.value
//
//        if (currentState.currentMode == PushMode.FOREGROUND) {
//            log.d(tag = TAG) { "Already in foreground mode, ignoring transition" }
//            return
//        }
//
//        log.d(tag = TAG) { "Transitioning to FOREGROUND mode: $reason" }
//
//        val newState = PushModeState(
//            currentMode = PushMode.FOREGROUND,
//            previousMode = currentState.currentMode,
//            timestamp = Clock.System.now().toEpochMilliseconds(),
//            reason = reason,
//            accountsInPushMode = emptySet(),
//            wasInPushBeforeCall = currentState.wasInPushBeforeCall
//        )
//
//        _pushModeStateFlow.value = newState
//
//        // Notificar que se requiere reregistro en modo foreground
//        onRegistrationRequiredCallback?.invoke(accounts, PushMode.FOREGROUND)
//        onModeChangeCallback?.invoke(newState)
//    }

    /**
     * Cancela transición pendiente
     */
    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    /**
     * Cancela transición de fin de llamada
     */
    private fun cancelCallEndTransition(accountKey: String? = null) {
        if (accountKey != null) {
            // Cancelar solo para cuenta específica
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

// === MÉTODOS DE CONSULTA ===

    fun getCurrentMode(): PushMode = _pushModeStateFlow.value.currentMode
    fun getCurrentState(): PushModeState = _pushModeStateFlow.value
    fun isInPushMode(): Boolean = getCurrentMode() == PushMode.PUSH
    fun isInForegroundMode(): Boolean = getCurrentMode() == PushMode.FOREGROUND
    fun getConfig(): PushModeConfig = config
    /**
     * NUEVO: Método para debugging del estado por cuenta
     */
    fun getAccountStates(): String {
        return buildString {
            appendLine("=== ACCOUNT PUSH STATES ===")
            appendLine("Accounts in push before call:")
            accountPushStates.forEach { (account, wasInPush) ->
                appendLine("  $account: $wasInPush")
            }
            appendLine("Pending returns:")
            pendingReturns.forEach { account ->
                appendLine("  $account")
            }
            appendLine("Active return jobs: ${callEndTransitionJobs.size}")
            callEndTransitionJobs.forEach { (account, job) ->
                appendLine("  $account: ${job.isActive}")
            }
        }
    }
    /**
     * Información de diagnóstico - ACTUALIZADA
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

            // NUEVO: Estado por cuenta
            appendLine("\n${getAccountStates()}")
        }
    }

    /**
     * Limpieza de recursos - MEJORADA
     */
    fun dispose() {
        cancelPendingTransition()
        cancelCallEndTransition() // Cancela todos

        // Limpiar estados por cuenta
        accountPushStates.clear()
        pendingReturns.clear()
        callEndTransitionJobs.clear()

        log.d(tag = TAG) { "PushModeManager disposed" }
    }
}