package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        private const val QUICK_RETRY_DELAY = 3_000L
        private const val NORMAL_RETRY_DELAY = 10_000L
        private const val MAX_RETRY_DELAY = 60_000L
        private const val REGISTRATION_TIMEOUT = 15_000L
    }

    private val networkManager = createNetworkManager()
    private var healthCheckJob: Job? = null
    private val accountMonitorJobs = ConcurrentMap<String, Job>()
    private val retryCounters = ConcurrentMap<String, Int>()
    private val lastRetryTimestamp = ConcurrentMap<String, Long>()
    private val registrationInProgress = ConcurrentMap<String, Boolean>()
    private var isActive = false

    fun initialize() {
        log.d(tag = TAG) { "🛡️ Initializing Registration Guardian" }
        networkManager.initialize()
        isActive = true
        startHealthCheckMonitor()
        startAccountRecoveryFromDatabase()
    }

    private fun startHealthCheckMonitor() {
        healthCheckJob = CoroutineScope(Dispatchers.IO).launch {
            log.d(tag = TAG) { "🔍 Health check monitor started" }
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
            log.w(tag = TAG) { "⚠️ Network unavailable, skipping health check" }
            return
        }

        log.d(tag = TAG) { "🏥 Performing health check..." }
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
                    log.d(tag = TAG) { "📥 Recovered account from DB: $accountKey" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error loading accounts from DB: ${e.message}" }
        }

        return accounts
    }

    private fun checkAndFixAccountHealth(accountKey: String, accountInfo: AccountInfo) {
        val currentState = sipCoreManager.getRegistrationState(accountKey)
        val isWebSocketHealthy = sipCoreManager.sharedWebSocketManager.isWebSocketHealthy()

        log.d(tag = TAG) {
            "🔍 Health check for $accountKey: state=$currentState, registered=${accountInfo.isRegistered.value}"
        }

        // ✅ CRÍTICO: Si está registrado Y el estado es OK, NO hacer nada
        if (accountInfo.isRegistered.value && currentState == RegistrationState.OK && isWebSocketHealthy) {
            log.d(tag = TAG) { "✅ Account $accountKey is healthy, skipping action" }
            return
        }

        val needsAction = when {
            currentState == RegistrationState.FAILED -> {
                log.w(tag = TAG) { "❌ Account $accountKey is FAILED - needs recovery" }
                true
            }
            currentState == RegistrationState.NONE -> {
                log.w(tag = TAG) { "⚪ Account $accountKey is NONE - needs registration" }
                true
            }
            currentState == RegistrationState.IN_PROGRESS && isStuckInProgress(accountKey) -> {
                log.w(tag = TAG) { "⏳ Account $accountKey stuck IN_PROGRESS" }
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
        CoroutineScope(Dispatchers.IO).launch {
            if (registrationInProgress.get(accountKey) == true) {
                log.d(tag = TAG) { "⏸️ Recovery already in progress for $accountKey" }
                return@launch
            }

            accountMonitorJobs.get(accountKey)?.cancel()
            registrationInProgress.put(accountKey, true)

            val job = launch {
                log.d(tag = TAG) { "🚀 Starting infinite recovery for $accountKey" }
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

            if (!networkManager.isNetworkAvailable()) {
                delay(NORMAL_RETRY_DELAY)
                continue
            }

            try {
                // ✅ NO limpiar estado - mantener sesión SIP
                // cleanupAccountStateOnly(accountInfo) // ❌ REMOVER ESTO

                // ✅ SOLO actualizar estado de registro
                accountInfo.isRegistered.value = false

                // ⏱️ Pequeño delay para estabilidad
                delay(1000)

                sipCoreManager.updateRegistrationState(
                    accountKey,
                    RegistrationState.IN_PROGRESS
                )

                // ✅ Re-registrar manteniendo el mismo diálogo SIP
                // El CSeq se incrementará automáticamente
                val success = attemptSingleAccountRegistration(accountInfo)

                if (success) {
                    val confirmed = waitForRegistrationConfirmation(accountInfo)
                    if (confirmed) {
                        log.d(tag = TAG) {
                            "✅ Account $accountKey recovered successfully"
                        }
                        sipCoreManager.updateRegistrationState(
                            accountKey,
                            RegistrationState.OK
                        )
                        retryCounters.remove(accountKey)
                        return
                    } else {
                        sipCoreManager.updateRegistrationState(
                            accountKey,
                            RegistrationState.FAILED
                        )
                    }
                } else {
                    sipCoreManager.updateRegistrationState(
                        accountKey,
                        RegistrationState.FAILED
                    )
                }
            } catch (e: Exception) {
                log.e(tag = TAG) {
                    "💥 Error in recovery attempt for $accountKey: ${e.message}"
                }
                sipCoreManager.updateRegistrationState(
                    accountKey,
                    RegistrationState.FAILED
                )
            }

            delay(calculateRetryDelay(attemptNumber))
        }
    }
//    @OptIn(ExperimentalTime::class)
//    private suspend fun recoverAccountWithInfiniteRetries(accountKey: String, accountInfo: AccountInfo) {
//        var attemptNumber = retryCounters.get(accountKey) ?: 0
//
//        while (isActive && !accountInfo.isRegistered.value) {
//            attemptNumber++
//            retryCounters.put(accountKey, attemptNumber)
//            lastRetryTimestamp.put(accountKey, kotlin.time.Clock.System.now().toEpochMilliseconds())
//
//            if (!networkManager.isNetworkAvailable()) {
//                delay(NORMAL_RETRY_DELAY)
//                continue
//            }
//
//            try {
//                // ✅ FIX: NO desconectar WebSocket compartido
//                // Solo limpiar estado de ESTA cuenta
//                cleanupAccountStateOnly(accountInfo)
//                delay(1000)
//
//                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
//
//                // ✅ FIX: Usar método que NO cierra WebSocket de otras cuentas
//                val success = attemptSingleAccountRegistration(accountInfo)
//
//                if (success) {
//                    val confirmed = waitForRegistrationConfirmation(accountInfo)
//                    if (confirmed) {
//                        log.d(tag = TAG) { "✅ Account $accountKey recovered successfully" }
//                        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.OK)
//                        retryCounters.remove(accountKey)
//                        return
//                    } else {
//                        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
//                    }
//                } else {
//                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
//                }
//            } catch (e: Exception) {
//                log.e(tag = TAG) { "💥 Error in recovery attempt for $accountKey: ${e.message}" }
//                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
//            }
//
//            delay(calculateRetryDelay(attemptNumber))
//        }
//    }

    private suspend fun cleanupAccountStateOnly(accountInfo: AccountInfo) {
        try {
            accountInfo.isRegistered.value = false

            log.d(tag = TAG) {
                "🧹 Cleaned up state for ${accountInfo.username}@${accountInfo.domain}" +
                        " (keeping SIP session data intact)"
            }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error cleaning up state: ${e.message}" }
        }
    }

    // ✅ FIX: NUEVO método que registra sin afectar otras cuentas
    private suspend fun attemptSingleAccountRegistration(accountInfo: AccountInfo): Boolean {
        return withTimeoutOrNull(REGISTRATION_TIMEOUT) {
            try {
                // ✅ Verificar que WebSocket esté conectado
                if (!sipCoreManager.sharedWebSocketManager.isConnected()) {
                    log.d(tag = TAG) { "🔌 WebSocket not connected, attempting connection..." }
                    sipCoreManager.sharedWebSocketManager.connect()
                    delay(2000) // Esperar conexión
                }

                // ✅ Registrar SOLO esta cuenta sin afectar otras
                sipCoreManager.sharedWebSocketManager.registerAccount(
                    accountInfo,
                    sipCoreManager.isAppInBackground
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in registration: ${e.message}" }
                false
            }
        } ?: false
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun waitForRegistrationConfirmation(accountInfo: AccountInfo): Boolean {
        val startTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val checkInterval = 500L

        while (kotlin.time.Clock.System.now().toEpochMilliseconds() - startTime < REGISTRATION_TIMEOUT) {
            if (accountInfo.isRegistered.value) {
                return true
            }
            if (!networkManager.isNetworkAvailable()) {
                return false
            }
            delay(checkInterval)
        }
        return false
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
        CoroutineScope(Dispatchers.IO).launch {
            delay(5000)
            try {
                log.d(tag = TAG) { "🔍 Starting initial account recovery from database..." }
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
                        log.d(tag = TAG) { "📥 Added account from DB: $accountKey" }
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
        log.d(tag = TAG) { "🔧 FORCING IMMEDIATE RECOVERY OF ALL ACCOUNTS" }
        val report = StringBuilder()
        report.appendLine("=== FORCED RECOVERY REPORT ===")

        accountMonitorJobs.values().forEach { it.cancel() }
        accountMonitorJobs.clear()
        registrationInProgress.clear()
        retryCounters.clear()

        val allAccounts = getAllAccountsFromAllSources()
        report.appendLine("Total accounts: ${allAccounts.size}")

        allAccounts.forEach { (accountKey, accountInfo) ->
            report.appendLine("\nStarting recovery for: $accountKey")
            startAccountRecovery(accountKey, accountInfo)
        }

        report.appendLine("\n✅ Recovery processes initiated for all accounts")
        return report.toString()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun getDiagnosticInfo(): String {
        val retrySnapshot = retryCounters.snapshot()
        val lastRetrySnapshot = lastRetryTimestamp.snapshot()
        val activeAccounts = sipCoreManager.activeAccounts

        return buildString {
            appendLine("=== REGISTRATION GUARDIAN STATUS ===")
            appendLine("Active: $isActive")
            appendLine("Health check job active: ${healthCheckJob?.isActive ?: false}")
            appendLine("Account monitor jobs: ${accountMonitorJobs.size()}")
            appendLine("Accounts in recovery: ${registrationInProgress.size()}")

            appendLine("\n--- Retry Counters ---")
            retrySnapshot.forEach { (account, count) ->
                val lastRetryTime = lastRetrySnapshot[account] ?: 0L
                val timeSinceLastRetry = kotlin.time.Clock.System.now().toEpochMilliseconds() - lastRetryTime
                appendLine("$account: attempt #$count (${timeSinceLastRetry}ms ago)")
            }

            appendLine("\n--- Account States ---")
            activeAccounts.forEach { (accountKey, accountInfo) ->
                val state = sipCoreManager.getRegistrationState(accountKey)
                val wsHealthy = sipCoreManager.sharedWebSocketManager.isWebSocketHealthy()
                appendLine("$accountKey: state=$state, registered=${accountInfo.isRegistered.value}, wsHealthy=$wsHealthy")
            }
        }
    }

    fun dispose() {
        log.d(tag = TAG) { "🛡️ Disposing Registration Guardian" }
        isActive = false
        healthCheckJob?.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            accountMonitorJobs.forEach { _, job -> job.cancel() }
            accountMonitorJobs.clear()
            registrationInProgress.clear()
            retryCounters.clear()
            lastRetryTimestamp.clear()
        }

        networkManager.dispose()
    }
}
