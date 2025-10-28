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
import kotlinx.datetime.Clock
import kotlin.collections.containsKey
import kotlin.collections.set
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.get
import kotlin.text.set

class RegistrationGuardianManager(
    private val sipCoreManager: SipCoreManager,
    private val databaseManager: DatabaseManager
) {
    companion object {
        private const val TAG = "RegistrationGuardian"

        // Intervalos de verificación
        private const val HEALTH_CHECK_INTERVAL = 30_000L // 30 segundos
        private const val QUICK_RETRY_DELAY = 3_000L // 3 segundos
        private const val NORMAL_RETRY_DELAY = 10_000L // 10 segundos
        private const val MAX_RETRY_DELAY = 60_000L // 1 minuto

        // Timeouts
        private const val REGISTRATION_TIMEOUT = 15_000L // 15 segundos
        private const val WEBSOCKET_CONNECT_TIMEOUT = 10_000L // 10 segundos
    }
   private val networkManager = createNetworkManager()
    // Jobs de monitoreo
    private var healthCheckJob: Job? = null
    private val accountMonitorJobs = ConcurrentMap<String, Job>()
    private val retryCounters = ConcurrentMap<String, Int>()
    private val lastRetryTimestamp = ConcurrentMap<String, Long>()
    private val registrationInProgress = ConcurrentMap<String, Boolean>()

    // Estado del guardian
    private var isActive = false

    /**
     * Inicializar el guardián
     */
    fun initialize() {
        log.d(tag = TAG) { "🛡️ Initializing Registration Guardian" }

        // ✅ INICIALIZAR EL NETWORK MANAGER
        networkManager.initialize()

        isActive = true
        startHealthCheckMonitor()
        startAccountRecoveryFromDatabase()
    }
    /**
     * Monitor de salud continuo - El corazón del sistema
     */
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

    /**
     * Verificación de salud completa
     */
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

    /**
     * Obtener cuentas desde TODAS las fuentes
     */
    private suspend fun getAllAccountsFromAllSources(): Map<String, AccountInfo> {
        val accounts = mutableMapOf<String, AccountInfo>()

        // 1. Cuentas en memoria (SipCoreManager)
        sipCoreManager.activeAccounts.forEach { (key, info) ->
            accounts[key] = info
        }

        // 2. Cuentas en BD que no están en memoria
        try {
            val dbAccounts = databaseManager.getRegisteredSipAccounts().first()

            dbAccounts.forEach { dbAccount ->
                val accountKey = "${dbAccount.username}@${dbAccount.domain}"

                if (!accounts.containsKey(accountKey)) {
                    // Crear AccountInfo desde BD
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

                    // Agregar a SipCoreManager también
                    sipCoreManager.activeAccounts[accountKey] = accountInfo

                    log.d(tag = TAG) { "📥 Recovered account from DB: $accountKey" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error loading accounts from DB: ${e.message}" }
        }

        return accounts
    }

    /**
     * Verificar y corregir salud de una cuenta
     */
    private fun checkAndFixAccountHealth(accountKey: String, accountInfo: AccountInfo) {
        val currentState = sipCoreManager.getRegistrationState(accountKey)
        val isWebSocketHealthy = sipCoreManager.sharedWebSocketManager.isWebSocketHealthy()

        log.d(tag = TAG) {
            "🔍 Health check for $accountKey: state=$currentState, registered=${accountInfo.isRegistered.value}"
        }

        // Detectar problemas
        val needsAction = when {
            // Caso 1: Estado FAILED - SIEMPRE reintenta
            currentState == RegistrationState.FAILED -> {
                log.w(tag = TAG) { "❌ Account $accountKey is FAILED - needs recovery" }
                true
            }

            // Caso 2: Estado NONE - debe registrarse
            currentState == RegistrationState.NONE -> {
                log.w(tag = TAG) { "⚪ Account $accountKey is NONE - needs registration" }
                true
            }

            // Caso 3: Estado OK pero WebSocket no saludable
            currentState == RegistrationState.OK && !isWebSocketHealthy
                -> {
                log.w(tag = TAG) { "⚠️ Account $accountKey is OK but WebSocket unhealthy" }
                true
            }

            // Caso 4: Estado IN_PROGRESS por demasiado tiempo
            currentState == RegistrationState.IN_PROGRESS && isStuckInProgress(accountKey) -> {
                log.w(tag = TAG) { "⏳ Account $accountKey stuck IN_PROGRESS" }
                true
            }

            // Caso 5: Marcado como registrado pero estado no es OK
            accountInfo.isRegistered.value && currentState != RegistrationState.OK -> {
                log.w(tag = TAG) { "🔀 Account $accountKey has inconsistent state" }
                true
            }

            else -> false
        }

        if (needsAction) {
            // Iniciar proceso de recuperación
            startAccountRecovery(accountKey, accountInfo)
        }
    }

    /**
     * Verificar si una cuenta está atorada en IN_PROGRESS
     */
    private fun isStuckInProgress(accountKey: String): Boolean {
        val lastRetry = runBlocking { lastRetryTimestamp.get(accountKey) } ?: 0L
        val timeSinceLastRetry = Clock.System.now().toEpochMilliseconds() - lastRetry

        // Si lleva más de 30 segundos en IN_PROGRESS, está atorado
        return timeSinceLastRetry > 30_000L
    }

    /**
     * Iniciar recuperación de una cuenta
     */
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

    /**
     * NÚCLEO: Recuperación con reintentos infinitos
     */
    private suspend fun recoverAccountWithInfiniteRetries(accountKey: String, accountInfo: AccountInfo) {
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
                cleanupAccountState(accountInfo)
                delay(1000)

                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.IN_PROGRESS)
                val success = attemptSafeRegistration(accountInfo)

                if (success) {
                    val confirmed = waitForRegistrationConfirmation(accountInfo)
                    if (confirmed) {
                        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.OK)
                        retryCounters.remove(accountKey)
                        return
                    } else {
                        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                    }
                } else {
                    sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "💥 Error in recovery attempt for $accountKey: ${e.message}" }
                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
            }

            delay(calculateRetryDelay(attemptNumber))
        }
    }

    /**
     * Limpiar estado previo de la cuenta
     */
    private suspend fun cleanupAccountState(accountInfo: AccountInfo) {
        try {

            sipCoreManager.sharedWebSocketManager.disconnect()
            accountInfo.isRegistered.value = false

            log.d(tag = TAG) { "🧹 Cleaned up state for ${accountInfo.username}@${accountInfo.domain}" }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error cleaning up state: ${e.message}" }
        }
    }

    /**
     * Intento seguro de registro
     */
    private suspend fun attemptSafeRegistration(accountInfo: AccountInfo): Boolean {
        return withTimeoutOrNull(REGISTRATION_TIMEOUT) {
            try {
                // Usar el método seguro del SipCoreManager
                sipCoreManager.safeRegister(accountInfo, sipCoreManager.isAppInBackground)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in safe register: ${e.message}" }
                false
            }
        } ?: false
    }

    /**
     * Esperar confirmación de registro
     */
    private suspend fun waitForRegistrationConfirmation(accountInfo: AccountInfo): Boolean {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val checkInterval = 500L

        while (Clock.System.now().toEpochMilliseconds() - startTime < REGISTRATION_TIMEOUT) {
            // Verificar si ya está registrado
            if (accountInfo.isRegistered.value) {
                return true
            }

            // Verificar si perdimos red
            if (!networkManager.isNetworkAvailable()) {
                return false
            }

            delay(checkInterval)
        }

        return false
    }

    /**
     * Calcular delay para siguiente intento (backoff exponencial con límite)
     */
    private fun calculateRetryDelay(attemptNumber: Int): Long {
        return when {
            attemptNumber <= 3 -> QUICK_RETRY_DELAY // Primeros 3 intentos: rápido
            attemptNumber <= 10 -> NORMAL_RETRY_DELAY // Siguientes 7: normal
            else -> {
                // Después del intento 10: exponencial con límite
                val exponential = NORMAL_RETRY_DELAY * (1 shl minOf(attemptNumber - 10, 5))
                minOf(exponential, MAX_RETRY_DELAY)
            }
        }
    }

    /**
     * Recuperación inicial desde BD al arrancar
     */
    private fun startAccountRecoveryFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            delay(5000) // Esperar inicialización completa

            try {
                log.d(tag = TAG) { "🔍 Starting initial account recovery from database..." }

                val dbAccounts = databaseManager.getRegisteredSipAccounts().first()

                log.d(tag = TAG) { "Found ${dbAccounts.size} accounts in database" }

                dbAccounts.forEach { dbAccount ->
                    val accountKey = "${dbAccount.username}@${dbAccount.domain}"

                    // Si no está en memoria, crear
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

                    // Iniciar recuperación para todas las cuentas
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

    /**
     * Forzar recuperación inmediata de todas las cuentas
     */
    suspend fun forceRecoverAllAccounts(): String {
        log.d(tag = TAG) { "🔧 FORCING IMMEDIATE RECOVERY OF ALL ACCOUNTS" }

        val report = StringBuilder()
        report.appendLine("=== FORCED RECOVERY REPORT ===")

        // Cancelar todos los jobs existentes
        accountMonitorJobs.values().forEach { it.cancel() }
        accountMonitorJobs.clear()
        registrationInProgress.clear()
        retryCounters.clear()

        // Obtener todas las cuentas
        val allAccounts = getAllAccountsFromAllSources()
        report.appendLine("Total accounts: ${allAccounts.size}")

        // Iniciar recuperación para cada una
        allAccounts.forEach { (accountKey, accountInfo) ->
            report.appendLine("\nStarting recovery for: $accountKey")
            startAccountRecovery(accountKey, accountInfo)
        }

        report.appendLine("\n✅ Recovery processes initiated for all accounts")
        return report.toString()
    }

    /**
     * Obtener estado de diagnóstico
     */

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
                val timeSinceLastRetry = Clock.System.now().toEpochMilliseconds() - lastRetryTime
                appendLine("$account: attempt #$count (${timeSinceLastRetry}ms ago)")
            }

            appendLine("\n--- Account States ---")
            activeAccounts.forEach { (accountKey, accountInfo) ->
                val state = sipCoreManager.getRegistrationState(accountKey)
                val wsHealthy = !sipCoreManager.sharedWebSocketManager.isWebSocketHealthy()
                appendLine("$accountKey: state=$state, registered=${accountInfo.isRegistered}, wsHealthy=$wsHealthy")
            }
        }
    }


    /**
     * Detener el guardián
     */
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

        // ✅ LIMPIAR EL NETWORK MANAGER
        networkManager.dispose()
    }
}
