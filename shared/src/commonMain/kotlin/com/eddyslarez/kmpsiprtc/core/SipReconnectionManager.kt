package com.eddyslarez.kmpsiprtc.core

import com.eddyslarez.kmpsiprtc.data.database.DatabaseManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.sip.SipMessageHandler
import com.eddyslarez.kmpsiprtc.utils.AccountRecoveryCounter
import com.eddyslarez.kmpsiprtc.utils.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min
import kotlin.random.Random
import kotlin.text.get
import kotlin.text.set
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class SipReconnectionManager(
    private val messageHandler: SipMessageHandler,
    private val sipCoreManager: SipCoreManager
) {
    companion object {
        private const val TAG = "SipReconnectionManager"
        private const val MAX_RECONNECTION_ATTEMPTS = 5
        private const val RECONNECTION_BASE_DELAY = 2000L
        private const val RECONNECTION_MAX_DELAY = 30000L
        private const val NETWORK_STABILITY_CHECK_DELAY = 3000L
        private const val ACCOUNT_RECOVERY_TIMEOUT = 10000L
    }
    private val networkManager = createNetworkManager()
    private var reconnectionJob: Job? = null
    private var networkStabilityJob: Job? = null
    private var isNetworkAvailable = false
    private var wasDisconnectedDueToNetwork = false
    private val reconnectionAttempts = mutableMapOf<String, Int>()
    private val accountRecoveryAttempts = AccountRecoveryCounter()
    private val cachedAccounts = ConcurrentMap<String, AccountInfo>()
    private var lastAccountSync = 0L
    private var reconnectionListener: ReconnectionListener? = null

    // Callback para notificar reconexion exitosa al PushModeManager
    private var onReconnectionSuccessCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null

    fun setPushModeCallbacks(
        onReconnectionSuccess: (() -> Unit)? = null,
        onDisconnected: (() -> Unit)? = null
    ) {
        this.onReconnectionSuccessCallback = onReconnectionSuccess
        this.onDisconnectedCallback = onDisconnected
    }

    suspend fun initialize() {
        log.d(tag = TAG) { "Initializing SipReconnectionManager" }
        setupNetworkListener()
        checkInitialNetworkState()
        syncAccountsFromCoreManager()
        startPeriodicAccountSync()
    }

    suspend fun forceReconnection(accounts: List<AccountInfo>) {
        log.d(tag = TAG) { "🔧 FORCING MANUAL RECONNECTION" }

        // Verificar y forzar estado actual en NetworkManager
        networkManager.forceNetworkCheck()
        isNetworkAvailable = networkManager.isNetworkAvailable()

        if (!isNetworkAvailable) {
            log.w(tag = TAG) { "🌐 Cannot force reconnection - no connectivity" }
            return
        }

        // Resetear contadores de intentos
        accountRecoveryAttempts.reset() // suspend
        reconnectionAttempts.clear()

        // Sincronizar cuentas
        syncAccountsFromCoreManager() // suspend

        // Marcar como desconectado para forzar reconexión
        wasDisconnectedDueToNetwork = true

        startReconnectionProcess(accounts) // suspend
    }

    suspend fun verifyAndFixConnectivity(accounts: List<AccountInfo>) {
        val accountsToCheck = if (accounts.isNotEmpty()) accounts else cachedAccounts.values() // suspend
        for (accountInfo in accountsToCheck) {
            reconnectAccountWithRetry(accountInfo) // suspend
        }
    }

    suspend fun getConnectivityStatus(): Map<String, Any> {
        val cachedKeys = cachedAccounts.keys() // suspend
        val reconnectionAttemptsCopy = reconnectionAttempts.toMap() // ya OK

        return mapOf(
            "networkAvailable" to isNetworkAvailable,
            "wasDisconnectedDueToNetwork" to wasDisconnectedDueToNetwork,
            "reconnectionInProgress" to (reconnectionJob?.isActive == true),
            "networkStabilityJobActive" to (networkStabilityJob?.isActive == true),
            "reconnectionAttempts" to reconnectionAttemptsCopy,
            "cachedAccountsCount" to cachedAccounts.size(),
            "accountRecoveryAttempts" to accountRecoveryAttempts.get(),
            "lastAccountSync" to lastAccountSync,
            "cachedAccountsKeys" to cachedKeys
        )
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun syncAccountsFromCoreManager() {
        try {
            val activeAccounts = sipCoreManager.activeAccounts
            cachedAccounts.clear()
            for ((key, value) in activeAccounts) {
                cachedAccounts.put(key, value)
            }
            lastAccountSync = kotlin.time.Clock.System.now().toEpochMilliseconds()
            log.d(tag = TAG) { "Synced ${cachedAccounts} accounts from SipCoreManager" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error syncing accounts from SipCoreManager: ${e.message}" }
        }
    }

    private fun startPeriodicAccountSync() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    delay(30.seconds)
                    syncAccountsFromCoreManager()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in periodic account sync: ${e.message}" }
                }
            }
        }
    }

    private fun setupNetworkListener() {
        networkManager.setConnectivityListener(object : NetworkConnectivityListener {
            override fun onNetworkLost() = CoroutineScope(Dispatchers.IO).launch { handleNetworkLost() }
            override fun onNetworkRestored() = handleNetworkRestored()
            override fun onReconnectionStarted() {
                reconnectionListener?.onReconnectionStarted()
            }
            override fun onReconnectionCompleted(successful: Boolean) {
                reconnectionListener?.onReconnectionCompleted(successful)
            }
        })

        isNetworkAvailable = networkManager.isNetworkAvailable()
    }

    private fun checkInitialNetworkState() {
        try {
            isNetworkAvailable = networkManager.isNetworkAvailable()
            if (!isNetworkAvailable) wasDisconnectedDueToNetwork = true
        } catch (e: Exception) {
            isNetworkAvailable = false
            wasDisconnectedDueToNetwork = true
        }
    }

    private suspend fun handleNetworkLost() {
        networkStabilityJob?.cancel()

        val networkInfo = networkManager.getNetworkInfo()
        val isStillConnected = networkInfo["isAvailable"] as? Boolean ?: false

        if (isStillConnected) {
            networkStabilityJob = CoroutineScope(Dispatchers.IO).launch {
                delay(1000L)
                val recheckInfo = networkManager.getNetworkInfo()
                val stillConnected = recheckInfo["isAvailable"] as? Boolean ?: false
                if (!stillConnected) processNetworkLoss()
            }
            return
        }

        processNetworkLoss()
    }

    private suspend fun processNetworkLoss() {
        isNetworkAvailable = false
        wasDisconnectedDueToNetwork = true
        reconnectionJob?.cancel()

        syncAccountsFromCoreManager()

        cachedAccounts.values().forEach { account ->
            account.isRegistered.value = false
            val accountKey = "${account.username}@${account.domain}"
            try { sipCoreManager.updateRegistrationState(accountKey, RegistrationState.NONE) } catch (_: Exception) {}
        }

        reconnectionListener?.onNetworkLost()
        onDisconnectedCallback?.invoke()
    }

    private fun handleNetworkRestored() {
        networkStabilityJob?.cancel()
        val wasDisconnected = !isNetworkAvailable || wasDisconnectedDueToNetwork
        isNetworkAvailable = true
        if (wasDisconnected) {
            wasDisconnectedDueToNetwork = false
            reconnectionListener?.onNetworkRestored()
            networkStabilityJob = CoroutineScope(Dispatchers.IO).launch {
                delay(NETWORK_STABILITY_CHECK_DELAY)
                if (networkManager.isNetworkAvailable()) startReconnectionProcess()
                else handleNetworkLost()
            }
        }
    }

    fun startReconnectionProcess(accountsToReconnect: List<AccountInfo>? = null) {
        reconnectionJob?.cancel()
        reconnectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = getAccountsForReconnection(accountsToReconnect)
                if (accounts.isEmpty()) {
                    val recoveredAccounts = recoverAccountsFromDatabase()
                    if (recoveredAccounts.isNotEmpty()) reconnectAccounts(recoveredAccounts)
                } else reconnectAccounts(accounts)
            } catch (_: Exception) {}
        }
    }

    private suspend fun getAccountsForReconnection(providedAccounts: List<AccountInfo>?): List<AccountInfo> {
        return when {
            !providedAccounts.isNullOrEmpty() -> providedAccounts
            cachedAccounts.size() > 0 -> cachedAccounts.values()
            else -> {
                syncAccountsFromCoreManager()
                if (cachedAccounts.size() > 0) cachedAccounts.values()
                else recoverAccountsFromDatabase()
            }
        }
    }

    private suspend fun recoverAccountsFromDatabase(): List<AccountInfo> {
        return try {
            val attempt = accountRecoveryAttempts.increment()
            withTimeout(ACCOUNT_RECOVERY_TIMEOUT) {
                val registeredAccounts = DatabaseManager.getInstance().getRegisteredSipAccounts().first()
                registeredAccounts.mapNotNull { dbAccount ->
                    try {
                        val accountInfo = AccountInfo(
                            username = dbAccount.username,
                            password = dbAccount.password,
                            domain = dbAccount.domain
                        ).apply { isRegistered.value = false }

                        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                        cachedAccounts.put(accountKey, accountInfo)
                        sipCoreManager.activeAccounts[accountKey] = accountInfo
                        accountInfo
                    } catch (_: Exception) { null }
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun reconnectAccounts(accounts: List<AccountInfo>) = coroutineScope {
        reconnectionListener?.onReconnectionStarted()
        val jobs = accounts.map { account ->
            async { reconnectAccountWithRetry(account) }
        }
        jobs.awaitAll()
        reconnectionListener?.onReconnectionCompleted(true)
        onReconnectionSuccessCallback?.invoke()
    }

    suspend fun reconnectAccountWithRetry(accountInfo: AccountInfo): Boolean {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        if (!networkManager.isNetworkAvailable()) return false

        var attempts = reconnectionAttempts[accountKey] ?: 0

        while (attempts < MAX_RECONNECTION_ATTEMPTS && !accountInfo.isRegistered.value) {
            attempts++
            reconnectionAttempts[accountKey] = attempts

            try {
                val success = sipCoreManager.sharedWebSocketManager.registerAccount(
                    accountInfo,
                    sipCoreManager.isAppInBackground
                )

                if (success && waitForReconnectionResult(accountInfo, 15_000L)) {
                    reconnectionAttempts.remove(accountKey)
                    return true
                }

                delay(calculateReconnectionDelay(attempts))

            } catch (e: Exception) {
                delay(calculateReconnectionDelay(attempts))
            }
        }

        return false
    }


    @OptIn(ExperimentalTime::class)
    private suspend fun waitForReconnectionResult(accountInfo: AccountInfo, timeoutMs: Long): Boolean {
        val start = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val checkInterval = 250L
        while (kotlin.time.Clock.System.now().toEpochMilliseconds() - start < timeoutMs) {
            if (accountInfo.isRegistered.value) return true
            if (!networkManager.isNetworkAvailable()) return false
            delay(checkInterval)
        }
        return false
    }

    private fun calculateReconnectionDelay(attempt: Int): Long {
        val delay = RECONNECTION_BASE_DELAY * (1 shl (attempt - 1))
        val capped = min(delay, RECONNECTION_MAX_DELAY)
        val jitter = (capped * 0.1 * Random.nextDouble()).toLong()
        return capped + jitter
    }

    fun setReconnectionListener(listener: ReconnectionListener?) { reconnectionListener = listener }
    fun isNetworkAvailable(): Boolean = isNetworkAvailable

    suspend fun getCachedAccounts(): Map<String, AccountInfo> = cachedAccounts.snapshot()

    suspend fun dispose() {
        reconnectionJob?.cancel()
        networkStabilityJob?.cancel()
        reconnectionAttempts.clear()
        cachedAccounts.clear()
        reconnectionListener = null
        isNetworkAvailable = false
        wasDisconnectedDueToNetwork = false
        lastAccountSync = 0L
        accountRecoveryAttempts.reset()
    }
}
/**
 * Interface para escuchar eventos de reconexión (mejorada)
 */
interface ReconnectionListener {
    fun onNetworkLost()
    fun onNetworkRestored()
    fun onReconnectionStarted()
    fun onReconnectionCompleted(successful: Boolean)
    fun onReconnectionAttempt(accountKey: String, attempt: Int)
    fun onReconnectAccount(accountInfo: AccountInfo): Boolean
    fun onAccountReconnected(accountKey: String, successful: Boolean)
    fun onReconnectionFailed(accountKey: String)
}