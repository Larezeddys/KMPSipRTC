package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.ConcurrentMap
import com.eddyslarez.kmpsiprtc.utils.generateNewCallId
import com.eddyslarez.kmpsiprtc.utils.generateNewFromTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.collections.containsKey
import kotlin.collections.set
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.get
import kotlin.text.set
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class RegistrationGuardianManager(
    private val sipCoreManager: SipCoreManager,
    private val databaseManager: DatabaseManager
) {
    companion object {
        private const val TAG = "RegistrationGuardian"
        private const val HEALTH_CHECK_INTERVAL = 60_000L
        private const val QUICK_RETRY_DELAY = 1_000L
        private const val NORMAL_RETRY_DELAY = 5_000L
        private const val MAX_RETRY_DELAY = 60_000L
        private const val REGISTRATION_TIMEOUT = 30_000L // â¬…ï¸ Aumentado a 30 segundos
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val networkManager = createNetworkManager()
    private var healthCheckJob: Job? = null
    private val accountMonitorJobs = ConcurrentMap<String, Job>()
    private val retryCounters = ConcurrentMap<String, Int>()
    private val lastRetryTimestamp = ConcurrentMap<String, Long>()
    private val registrationInProgress = ConcurrentMap<String, Boolean>()

    // âœ… NUEVO: Tracking de confirmaciones por cuenta
    private val registrationConfirmations = ConcurrentMap<String, CompletableDeferred<Boolean>>()

    private var isActive = false

    fun initialize() {
        log.d(tag = TAG) { "ðŸ›¡ï¸ Initializing Registration Guardian" }
        networkManager.initialize()
        isActive = true
        startHealthCheckMonitor()
        // Recuperacion inicial deshabilitada: el registro se maneja desde Main
        // despues de todas las verificaciones. El health check cada 60s sigue
        // funcionando como safety net para cuentas FAILED.
        // startAccountRecoveryFromDatabase()
    }

    private fun startHealthCheckMonitor() {
        healthCheckJob = scope.launch {
            log.d(tag = TAG) { "ðŸ” Health check monitor started" }
            while (isActive) {
                try {
                    delay(HEALTH_CHECK_INTERVAL)
                    performHealthCheck()
                } catch (e: CancellationException) {
                    log.d(tag = TAG) { "Health check monitor cancelled" }
                    break
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in health check: ${e.message}" }
                }
            }
        }
    }

    private suspend fun performHealthCheck() {
        if (!networkManager.isNetworkAvailable()) {
            log.w(tag = TAG) { "âš ï¸ Network unavailable, skipping health check" }
            return
        }

        log.d(tag = TAG) { "ðŸ¥ Performing health check..." }
        val allAccounts = getAllAccountsFromAllSources()
        log.d(tag = TAG) { "Found ${allAccounts.size} accounts to check" }

        allAccounts.forEach { (accountKey, accountInfo) ->
            checkAndFixAccountHealth(accountKey, accountInfo)
        }
    }

    private suspend fun getAllAccountsFromAllSources(): Map<String, AccountInfo> {
        val accounts = mutableMapOf<String, AccountInfo>()

        sipCoreManager.activeAccounts.forEach { (key, info) ->
            accounts[key] = info
        }

        try {
            val dbAccounts = databaseManager.getRegisteredSipAccounts().first()
            dbAccounts.forEach { dbAccount ->
                val accountKey = "${dbAccount.username}@${dbAccount.domain}"
                if (!accounts.containsKey(accountKey)) {
                    val accountInfo = AccountInfo(
                        username = dbAccount.username,
                        password = dbAccount.password,
                        domain = dbAccount.domain
                    ).apply {
                        token.value = dbAccount.pushToken ?: ""
                        provider.value = dbAccount.pushProvider ?: "fcm"
                        userAgent.value = sipCoreManager.userAgent()
                        isRegistered.value = false
                    }
                    accounts[accountKey] = accountInfo
                    sipCoreManager.activeAccounts[accountKey] = accountInfo
                    log.d(tag = TAG) { "ðŸ“¥ Recovered account from DB: $accountKey" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error loading accounts from DB: ${e.message}" }
        }

        return accounts
    }

    private suspend fun checkAndFixAccountHealth(accountKey: String, accountInfo: AccountInfo) {
        val currentState = sipCoreManager.getRegistrationState(accountKey)
        val isWebSocketHealthy = sipCoreManager.sharedWebSocketManager.isWebSocketHealthy()

        log.d(tag = TAG) {
            "ðŸ” Health check for $accountKey: state=$currentState, " +
                    "registered=${accountInfo.isRegistered.value}, wsHealthy=$isWebSocketHealthy"
        }

        // âœ… Si estÃ¡ registrado Y el estado es OK Y WebSocket estÃ¡ bien â†’ Cancelar monitoreo
        if (accountInfo.isRegistered.value && currentState == RegistrationState.OK && isWebSocketHealthy) {
            log.d(tag = TAG) { "âœ… Account $accountKey is healthy, canceling monitoring" }

            // âœ… NUEVO: Cancelar job de monitoreo si existe
            accountMonitorJobs.get(accountKey)?.cancel()
            accountMonitorJobs.remove(accountKey)
            registrationInProgress.remove(accountKey)
            retryCounters.remove(accountKey)

            return
        }

        val needsAction = when {
            currentState == RegistrationState.FAILED -> {
                log.w(tag = TAG) { "âŒ Account $accountKey is FAILED - needs recovery" }
                true
            }
            currentState == RegistrationState.NONE -> {
                log.w(tag = TAG) { "âšª Account $accountKey is NONE - needs registration" }
                true
            }
            currentState == RegistrationState.IN_PROGRESS && isStuckInProgress(accountKey) -> {
                log.w(tag = TAG) { "â³ Account $accountKey stuck IN_PROGRESS" }
                true
            }
            !isWebSocketHealthy && accountInfo.isRegistered.value -> {
                log.w(tag = TAG) { "ðŸ”Œ WebSocket unhealthy but account thinks it's registered" }
                true
            }
            else -> false
        }

        if (needsAction) {
            startAccountRecovery(accountKey, accountInfo)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun isStuckInProgress(accountKey: String): Boolean {
        val lastRetry = runBlocking { lastRetryTimestamp.get(accountKey) } ?: 0L
        val timeSinceLastRetry = kotlin.time.Clock.System.now().toEpochMilliseconds() - lastRetry
        return timeSinceLastRetry > 30_000L
    }

    private fun startAccountRecovery(accountKey: String, accountInfo: AccountInfo) {
        scope.launch {
            if (registrationInProgress.get(accountKey) == true) {
                log.d(tag = TAG) { "â¸ï¸ Recovery already in progress for $accountKey" }
                return@launch
            }

            accountMonitorJobs.get(accountKey)?.cancel()
            registrationInProgress.put(accountKey, true)

            val job = launch {
                log.d(tag = TAG) { "ðŸš€ Starting infinite recovery for $accountKey" }
                try {
                    recoverAccountWithInfiniteRetries(accountKey, accountInfo)
                } finally {
                    registrationInProgress.remove(accountKey)
                }
            }

            accountMonitorJobs.put(accountKey, job)
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun recoverAccountWithInfiniteRetries(
        accountKey: String,
        accountInfo: AccountInfo
    ) {
        var attemptNumber = retryCounters.get(accountKey) ?: 0

        while (isActive && !accountInfo.isRegistered.value) {
            attemptNumber++
            retryCounters.put(accountKey, attemptNumber)
            lastRetryTimestamp.put(accountKey, Clock.System.now().toEpochMilliseconds())

            log.d(tag = TAG) { "ðŸ”„ [$accountKey] Attempt #$attemptNumber starting..." }

            if (!networkManager.isNetworkAvailable()) {
                log.w(tag = TAG) { "ðŸŒ [$accountKey] Network unavailable, waiting..." }
                delay(NORMAL_RETRY_DELAY)
                continue
            }

            try {
                // âœ… Limpiar completamente el diÃ¡logo SIP antiguo
                cleanupAndResetSipDialog(accountInfo)

                // â±ï¸ Delay para asegurar que el servidor procese la limpieza
                delay(500)

                sipCoreManager.updateRegistrationState(
                    accountKey,
                    RegistrationState.IN_PROGRESS
                )

                // âœ… NUEVO: Crear CompletableDeferred ANTES del registro
                val confirmation = CompletableDeferred<Boolean>()
                registrationConfirmations.put(accountKey, confirmation)

                log.d(tag = TAG) { "ðŸ“ [$accountKey] Confirmation tracker created, attempting registration..." }

                // âœ… Registrar con NUEVO diÃ¡logo SIP completo
                val success = attemptFullRegistrationWithNewDialog(accountInfo)

                if (success) {
                    log.d(tag = TAG) { "ðŸ“¬ [$accountKey] REGISTER sent, waiting for confirmation..." }

                    // âœ… ESPERAR LA CONFIRMACIÃ“N O TIMEOUT
                    val confirmed = withTimeoutOrNull(REGISTRATION_TIMEOUT) {
                        confirmation.await()
                    } ?: false

                    if (confirmed) {
                        log.d(tag = TAG) {
                            "âœ… [$accountKey] Registration confirmed successfully (attempt #$attemptNumber)"
                        }
                        sipCoreManager.updateRegistrationState(
                            accountKey,
                            RegistrationState.OK
                        )
                        retryCounters.remove(accountKey)
                        registrationConfirmations.remove(accountKey)

                        // âœ… Cancelar el job de monitoreo
                        accountMonitorJobs.get(accountKey)?.cancel()
                        accountMonitorJobs.remove(accountKey)

                        return // â¬…ï¸ SALIR EXITOSAMENTE
                    } else {
                        log.w(tag = TAG) { "â±ï¸ [$accountKey] Registration confirmation timeout after ${REGISTRATION_TIMEOUT}ms" }
                        sipCoreManager.updateRegistrationState(
                            accountKey,
                            RegistrationState.FAILED
                        )
                    }
                } else {
                    log.w(tag = TAG) { "âŒ [$accountKey] Registration attempt failed" }
                    sipCoreManager.updateRegistrationState(
                        accountKey,
                        RegistrationState.FAILED
                    )
                }
            } catch (e: Exception) {
                log.e(tag = TAG) {
                    "ðŸ’¥ [$accountKey] Error in recovery attempt #$attemptNumber: ${e.message}"
                }
                sipCoreManager.updateRegistrationState(
                    accountKey,
                    RegistrationState.FAILED
                )
            } finally {
                // âœ… Limpiar confirmaciÃ³n si todavÃ­a existe
                registrationConfirmations.remove(accountKey)
            }

            val retryDelay = calculateRetryDelay(attemptNumber)
            log.d(tag = TAG) { "â³ [$accountKey] Waiting ${retryDelay}ms before next attempt" }
            delay(retryDelay)
        }

        log.d(tag = TAG) { "ðŸ›‘ [$accountKey] Recovery loop ended - registered=${accountInfo.isRegistered.value}" }
    }

    /**
     * âœ… NUEVO: MÃ©todo pÃºblico para confirmar registro desde fuera
     */
    fun confirmRegistration(accountKey: String, success: Boolean) {
        scope.launch {
            val confirmation = registrationConfirmations.get(accountKey)
            if (confirmation != null) {
                log.d(tag = TAG) { "âœ… [$accountKey] Confirming registration: $success" }
                confirmation.complete(success)
            } else {
                log.w(tag = TAG) { "âš ï¸ [$accountKey] No pending confirmation found" }
            }
        }
    }

    /**
     * âœ… Limpia completamente el diÃ¡logo SIP antiguo
     */
    private suspend fun cleanupAndResetSipDialog(accountInfo: AccountInfo) {
        try {
            // 1. Marcar como no registrado
            accountInfo.isRegistered.value = false

            // 2. Generar nuevo Call-ID
            accountInfo.callId.value = generateNewCallId()

            // 3. Generar nuevo From-Tag
            accountInfo.fromTag.value = generateNewFromTag()

            // 4. Resetear CSeq a 1
            accountInfo.resetCSeq()
            accountInfo.toTag =  MutableStateFlow("")

            // 5. Limpiar To-Tag
            accountInfo.toTag = MutableStateFlow("")

            // 6. Verificar que el token FCM estÃ© presente
            if (accountInfo.token.value.isEmpty()) {
                log.w(tag = TAG) { "âš ï¸ FCM token is empty, attempting to reload from DB" }
                try {
                    val dbAccounts = databaseManager.getRegisteredSipAccounts().first()
                    val dbAccount = dbAccounts.find {
                        it.username == accountInfo.username && it.domain == accountInfo.domain
                    }
                    if (dbAccount?.pushToken?.isNotEmpty() == true) {
                        accountInfo.token.value = dbAccount.pushToken!!
                        log.d(tag = TAG) { "âœ… Reloaded FCM token from DB" }
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Failed to reload token from DB: ${e.message}" }
                }
            }

            log.d(tag = TAG) {
                "ðŸ§¹ Reset SIP dialog for ${accountInfo.username}@${accountInfo.domain}: " +
                        "new Call-ID=${accountInfo.callId.value?.take(8)}..., " +
                        "new From-Tag=${accountInfo.fromTag.value?.take(8)}..., " +
                        "CSeq=1, " +
                        "has token=${accountInfo.token.value.isNotEmpty()}"
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error resetting SIP dialog: ${e.message}" }
            throw e
        }
    }

    /**
     * âœ… Registra con un diÃ¡logo SIP completamente nuevo
     */
    private suspend fun attemptFullRegistrationWithNewDialog(accountInfo: AccountInfo): Boolean {
        return withTimeoutOrNull(REGISTRATION_TIMEOUT) {
            try {
                log.d(tag = TAG) {
                    "ðŸ“¤ [${accountInfo.username}@${accountInfo.domain}] Starting registration attempt..."
                }

                // 1. Verificar/conectar WebSocket
                if (!sipCoreManager.sharedWebSocketManager.isConnected()) {
                    log.d(tag = TAG) {
                        "ðŸ”Œ [${accountInfo.username}@${accountInfo.domain}] WebSocket not connected, connecting..."
                    }
                    sipCoreManager.sharedWebSocketManager.connect()
                    delay(500)

                    if (!sipCoreManager.sharedWebSocketManager.isConnected()) {
                        log.e(tag = TAG) {
                            "âŒ [${accountInfo.username}@${accountInfo.domain}] WebSocket connection failed"
                        }
                        return@withTimeoutOrNull false
                    }
                }

                // 2. Registrar con el NUEVO diÃ¡logo
                log.d(tag = TAG) {
                    "ðŸ“ [${accountInfo.username}@${accountInfo.domain}] Sending REGISTER with new dialog..."
                }

                val success = sipCoreManager.sharedWebSocketManager.registerAccount(
                    accountInfo,
                    sipCoreManager.isAppInBackground
                )

                if (!success) {
                    log.e(tag = TAG) {
                        "âŒ [${accountInfo.username}@${accountInfo.domain}] registerAccount returned false"
                    }
                } else {
                    log.d(tag = TAG) {
                        "ðŸ“¬ [${accountInfo.username}@${accountInfo.domain}] REGISTER sent successfully, " +
                                "waiting for server response..."
                    }
                }

                success
            } catch (e: Exception) {
                log.e(tag = TAG) {
                    "ðŸ’¥ [${accountInfo.username}@${accountInfo.domain}] Registration error: ${e.message}"
                }
                false
            }
        } ?: false.also {
            log.e(tag = TAG) {
                "â±ï¸ [${accountInfo.username}@${accountInfo.domain}] Registration attempt timed out " +
                        "after ${REGISTRATION_TIMEOUT}ms"
            }
        }
    }

    private fun calculateRetryDelay(attemptNumber: Int): Long {
        return when {
            attemptNumber <= 3 -> QUICK_RETRY_DELAY
            attemptNumber <= 10 -> NORMAL_RETRY_DELAY
            else -> {
                val exponential = NORMAL_RETRY_DELAY * (1 shl minOf(attemptNumber - 10, 5))
                minOf(exponential, MAX_RETRY_DELAY)
            }
        }
    }

    private fun startAccountRecoveryFromDatabase() {
        scope.launch {
            delay(2000)
            try {
                log.d(tag = TAG) { "ðŸ” Starting initial account recovery from database..." }
                val dbAccounts = databaseManager.getRegisteredSipAccounts().first()
                log.d(tag = TAG) { "Found ${dbAccounts.size} accounts in database" }

                dbAccounts.forEach { dbAccount ->
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"
                    if (!sipCoreManager.activeAccounts.containsKey(accountKey)) {
                        val accountInfo = AccountInfo(
                            username = dbAccount.username,
                            password = dbAccount.password,
                            domain = dbAccount.domain
                        ).apply {
                            token.value = dbAccount.pushToken ?: ""
                            provider.value = dbAccount.pushProvider ?: "fcm"
                            userAgent.value = "${sipCoreManager.userAgent()} Push"
                            isRegistered.value = false
                        }
                        sipCoreManager.activeAccounts[accountKey] = accountInfo
                        log.d(tag = TAG) { "ðŸ“¥ Added account from DB: $accountKey" }
                    }

                    val accountInfo = sipCoreManager.activeAccounts[accountKey]
                    if (accountInfo != null && !accountInfo.isRegistered.value) {
                        startAccountRecovery(accountKey, accountInfo)
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in initial recovery: ${e.message}" }
            }
        }
    }

    suspend fun forceRecoverAllAccounts(): String {
        log.d(tag = TAG) { "ðŸ”§ FORCING IMMEDIATE RECOVERY OF ALL ACCOUNTS" }
        val report = StringBuilder()
        report.appendLine("=== FORCED RECOVERY REPORT ===")

        // Cancelar todos los jobs existentes
        accountMonitorJobs.values().forEach { it.cancel() }
        accountMonitorJobs.clear()
        registrationInProgress.clear()
        retryCounters.clear()
        registrationConfirmations.clear() // âœ… NUEVO: Limpiar confirmaciones

        val allAccounts = getAllAccountsFromAllSources()
        report.appendLine("Total accounts: ${allAccounts.size}")

        allAccounts.forEach { (accountKey, accountInfo) ->
            report.appendLine("\nStarting recovery for: $accountKey")
            startAccountRecovery(accountKey, accountInfo)
        }

        report.appendLine("\nâœ… Recovery processes initiated for all accounts")
        return report.toString()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun getDiagnosticInfo(): String {
        val retrySnapshot = retryCounters.snapshot()
        val lastRetrySnapshot = lastRetryTimestamp.snapshot()
        val activeAccounts = sipCoreManager.activeAccounts
        val confirmationsSnapshot = registrationConfirmations.snapshot() // âœ… NUEVO

        return buildString {
            appendLine("=== REGISTRATION GUARDIAN STATUS ===")
            appendLine("Active: $isActive")
            appendLine("Health check job active: ${healthCheckJob?.isActive ?: false}")
            appendLine("Account monitor jobs: ${accountMonitorJobs.size()}")
            appendLine("Accounts in recovery: ${registrationInProgress.size()}")
            appendLine("Pending confirmations: ${confirmationsSnapshot.size}") // âœ… NUEVO

            appendLine("\n--- Retry Counters ---")
            retrySnapshot.forEach { (account, count) ->
                val lastRetryTime = lastRetrySnapshot[account] ?: 0L
                val timeSinceLastRetry = kotlin.time.Clock.System.now().toEpochMilliseconds() - lastRetryTime
                appendLine("$account: attempt #$count (${timeSinceLastRetry}ms ago)")
            }

            // âœ… NUEVO: Mostrar confirmaciones pendientes
            appendLine("\n--- Pending Confirmations ---")
            confirmationsSnapshot.forEach { (account, deferred) ->
                appendLine("$account: isCompleted=${deferred.isCompleted}, " +
                        "isCancelled=${deferred.isCancelled}")
            }

            appendLine("\n--- Account States ---")
            activeAccounts.forEach { (accountKey, accountInfo) ->
                val state = sipCoreManager.getRegistrationState(accountKey)
                val wsHealthy = sipCoreManager.sharedWebSocketManager.isWebSocketHealthy()
                appendLine(
                    "$accountKey: state=$state, registered=${accountInfo.isRegistered.value}, " +
                            "wsHealthy=$wsHealthy, hasToken=${accountInfo.token.value.isNotEmpty()}, " +
                            "callId=${accountInfo.callId.value?.take(8)}..., cseq=${accountInfo.cseq}"
                )
            }
        }
    }

    suspend fun dispose() {
        log.d(tag = TAG) { "[CONN] Disposing Registration Guardian" }
        isActive = false
        healthCheckJob?.cancel()

        try {
            registrationConfirmations.forEach { _, deferred ->
                if (!deferred.isCompleted) {
                    deferred.cancel()
                }
            }
            registrationConfirmations.clear()

            accountMonitorJobs.forEach { _, job -> job.cancel() }
            accountMonitorJobs.clear()
            registrationInProgress.clear()
            retryCounters.clear()
            lastRetryTimestamp.clear()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during dispose cleanup: ${e.message}" }
        }

        networkManager.dispose()
        scope.cancel()
    }
}
