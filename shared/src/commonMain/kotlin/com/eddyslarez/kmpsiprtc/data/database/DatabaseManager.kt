package com.eddyslarez.kmpsiprtc.data.database

import androidx.room.deferredTransaction
import androidx.room.execSQL
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import com.eddyslarez.kmpsiprtc.data.database.dao.CallStatistics
import com.eddyslarez.kmpsiprtc.data.database.entities.AppConfigEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.CallDataEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.CallLogEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.ContactEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.SipAccountEntity
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.repository.CallLogWithContact
import com.eddyslarez.kmpsiprtc.repository.GeneralStatistics
import com.eddyslarez.kmpsiprtc.repository.SipRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class DatabaseManager private constructor() {

    private val database: SipDatabase by lazy {
        buildSipDatabase(getDatabaseBuilder())
    }

    private val repository: SipRepository by lazy {
        SipRepository(database)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isInitialized = false

    private val initMutex = Mutex()
    private val TAG = "DatabaseManager"

    companion object {
        @Volatile
        private var INSTANCE: DatabaseManager? = null
        private val LOCK = SynchronizedObject()

        fun getInstance(): DatabaseManager {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: DatabaseManager().also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Obtiene la instancia Y espera a que esté inicializada
         * USA ESTE MÉTODO en lugar de getInstance() directamente
         */
        suspend fun getInitializedInstance(timeoutMs: Long = 10000): DatabaseManager {
            val instance = getInstance()
            if (!instance.waitForInitialization(timeoutMs)) {
                throw IllegalStateException("Database initialization timeout after ${timeoutMs}ms")
            }
            return instance
        }

        fun hasInstance(): Boolean = INSTANCE != null
        fun getExistingInstance(): DatabaseManager? = INSTANCE
    }

    init {
        log.d(tag = TAG) { "DatabaseManager instance created" }
        // Inicializar de forma asíncrona
        scope.launch {
            initializeDatabase()
        }
    }

    private suspend fun initializeDatabase() {
        initMutex.withLock {
            if (isInitialized) return

            try {
                log.d(tag = TAG) { "Starting database initialization..." }

                // Forzar inicialización de la base de datos
                val config = repository.getAppConfig()
                if (config == null) {
                    repository.createOrUpdateAppConfig()
                    log.d(tag = TAG) { "Default configuration created" }
                }

                // Verificar estadísticas
                val stats = getGeneralStatistics()
                log.d(tag = TAG) {
                    "Database initialized: ${stats.totalAccounts} accounts, ${stats.totalCalls} calls"
                }

                isInitialized = true

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error initializing database: ${e.message}" }
                recoverDatabase()
            }
        }
    }

    private suspend fun recoverDatabase() {
        try {
            log.d(tag = TAG) { "Attempting database recovery..." }

            val tablesExist = try {
                database.useReaderConnection { transactor ->
                    transactor.deferredTransaction {
                        var exists = false
                        usePrepared("SELECT name FROM sqlite_master WHERE type='table'") { statement ->
                            val tables = mutableListOf<String>()
                            while (statement.step()) {
                                tables.add(statement.getText(0))
                            }
                            exists = tables.isNotEmpty()
                        }
                        exists
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error checking tables: ${e.message}" }
                false
            }

            if (!tablesExist) {
                log.w(tag = TAG) { "Database tables missing, will be created on first use" }
            }

            isInitialized = true
            log.d(tag = TAG) { "Database recovery completed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Database recovery failed: ${e.message}" }
        }
    }

    /**
     * Espera a que la base de datos esté completamente inicializada
     */
    suspend fun waitForInitialization(timeoutMs: Long = 10000): Boolean {
        val startTime = Clock.System.now().toEpochMilliseconds()

        while (!isInitialized) {
            if (Clock.System.now().toEpochMilliseconds() - startTime > timeoutMs) {
                log.e(tag = TAG) { "Database initialization timeout" }
                return false
            }
            delay(100)
        }

        return true
    }

    /**
     * HELPER: Asegura que la BD esté lista antes de ejecutar operaciones
     */
    private suspend fun <T> ensureInitializedAndRun(block: suspend () -> T): T {
        if (!isInitialized) {
            waitForInitialization()
        }
        return block()
    }

    // === OPERACIONES DE CUENTAS SIP ===

    suspend fun getAccountsForRecovery(): List<SipAccountEntity> {
        return ensureInitializedAndRun {
            try {
                val activeAccounts = getActiveSipAccounts().first()
                val now = Clock.System.now().toEpochMilliseconds()

                activeAccounts.filter { account ->
                    account.isActive &&
                            (account.registrationExpiry == 0L || account.registrationExpiry > now) &&
                            (now - account.updatedAt) < (2 * 60 * 60 * 1000)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting accounts for recovery: ${e.message}" }
                emptyList()
            }
        }
    }

    suspend fun markAccountForReconnection(accountId: String, reason: String? = null) {
        ensureInitializedAndRun {
            try {
                repository.updateRegistrationState(
                    accountId = accountId,
                    state = RegistrationState.FAILED,
                    expiry = null
                )
                log.d(tag = TAG) { "Account marked for reconnection: $accountId, reason: $reason" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error marking account for reconnection: ${e.message}" }
            }
        }
    }

    suspend fun getAccountConnectivityStats(): Map<String, Any> {
        return ensureInitializedAndRun {
            try {
                val activeAccounts = getActiveSipAccounts().first()
                val now = Clock.System.now().toEpochMilliseconds()

                val stats = activeAccounts.groupBy { account ->
                    when {
                        account.registrationState == RegistrationState.OK &&
                                (account.registrationExpiry == 0L || account.registrationExpiry > now) -> "registered"
                        account.registrationState == RegistrationState.IN_PROGRESS -> "connecting"
                        account.registrationState == RegistrationState.FAILED -> "failed"
                        else -> "disconnected"
                    }
                }

                mapOf(
                    "total" to activeAccounts.size,
                    "registered" to (stats["registered"]?.size ?: 0),
                    "connecting" to (stats["connecting"]?.size ?: 0),
                    "failed" to (stats["failed"]?.size ?: 0),
                    "disconnected" to (stats["disconnected"]?.size ?: 0),
                    "lastUpdate" to now
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error getting connectivity stats: ${e.message}" }
                mapOf("error" to (e.message ?: "Unknown error"))
            }
        }
    }

    suspend fun createOrUpdateSipAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        pushToken: String? = null,
        pushProvider: String? = null
    ): SipAccountEntity {
        return ensureInitializedAndRun {
            repository.createOrUpdateAccount(
                username = username,
                password = password,
                domain = domain,
                displayName = displayName,
                pushToken = pushToken,
                pushProvider = pushProvider
            )
        }
    }

    fun getActiveSipAccounts(): Flow<List<SipAccountEntity>> {
        return repository.getActiveAccounts()
    }

    fun getRegisteredSipAccounts(): Flow<List<SipAccountEntity>> {
        return repository.getRegisteredAccounts()
    }

    suspend fun getSipAccountByCredentials(username: String, domain: String): SipAccountEntity? {
        return ensureInitializedAndRun {
            repository.getAccountByCredentials(username, domain)
        }
    }

    suspend fun updateSipAccountRegistrationState(
        accountId: String,
        state: RegistrationState,
        expiry: Long? = null
    ) {
        ensureInitializedAndRun {
            repository.updateRegistrationState(accountId, state, expiry)
        }
    }

    suspend fun deleteSipAccount(accountId: String) {
        ensureInitializedAndRun {
            repository.deleteAccount(accountId)
        }
    }

    // === OPERACIONES DE HISTORIAL DE LLAMADAS ===

    fun getRecentCallLogs(limit: Int = 50): Flow<List<CallLogWithContact>> {
        return repository.getRecentCallLogs(limit)
    }

    fun getMissedCallLogs(): Flow<List<CallLogWithContact>> {
        return repository.getMissedCalls()
    }

    fun searchCallLogs(query: String): Flow<List<CallLogWithContact>> {
        return repository.searchCallLogs(query)
    }

    suspend fun createCallLog(
        accountId: String,
        callData: CallData,
        callType: CallTypes,
        endTime: Long? = null,
        sipCode: Int? = null,
        sipReason: String? = null
    ): CallLogEntity {
        return ensureInitializedAndRun {
            repository.createCallLog(
                accountId = accountId,
                callData = callData,
                callType = callType,
                endTime = endTime,
                sipCode = sipCode,
                sipReason = sipReason
            )
        }
    }

    suspend fun clearAllCallLogs() {
        ensureInitializedAndRun {
            repository.clearCallLogs()
        }
    }

    // === OPERACIONES DE DATOS DE LLAMADAS ACTIVAS ===

    fun getActiveCallData(): Flow<List<CallDataEntity>> {
        return repository.getActiveCalls()
    }

    suspend fun createCallData(accountId: String, callData: CallData): CallDataEntity {
        return ensureInitializedAndRun {
            repository.createCallData(accountId, callData)
        }
    }

    suspend fun updateCallState(
        callId: String,
        newState: CallState,
        errorReason: CallErrorReason = CallErrorReason.NONE,
        sipCode: Int? = null,
        sipReason: String? = null
    ) {
        ensureInitializedAndRun {
            repository.updateCallState(callId, newState, errorReason, sipCode, sipReason)
        }
    }

    suspend fun endCall(callId: String, endTime: Long = Clock.System.now().toEpochMilliseconds()) {
        ensureInitializedAndRun {
            repository.endCall(callId, endTime)
        }
    }

    // === OPERACIONES DE CONTACTOS ===

    fun getAllContacts(): Flow<List<ContactEntity>> {
        return repository.getAllContacts()
    }

    fun searchContacts(query: String): Flow<List<ContactEntity>> {
        return repository.searchContacts(query)
    }

    suspend fun createOrUpdateContact(
        phoneNumber: String,
        displayName: String,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
        company: String? = null
    ): ContactEntity {
        return ensureInitializedAndRun {
            repository.createOrUpdateContact(
                phoneNumber = phoneNumber,
                displayName = displayName,
                firstName = firstName,
                lastName = lastName,
                email = email,
                company = company
            )
        }
    }

    suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
        return ensureInitializedAndRun {
            repository.isPhoneNumberBlocked(phoneNumber)
        }
    }

    // === ESTADÍSTICAS ===

    suspend fun getGeneralStatistics(): GeneralStatistics {
        return ensureInitializedAndRun {
            repository.getGeneralStatistics()
        }
    }

    suspend fun getCallStatisticsForNumber(phoneNumber: String): CallStatistics? {
        return ensureInitializedAndRun {
            repository.getCallStatisticsForNumber(phoneNumber)
        }
    }

    // === OPERACIONES DE MANTENIMIENTO ===

    fun cleanupOldData(daysToKeep: Int = 30) {
        scope.launch {
            ensureInitializedAndRun {
                try {
                    repository.cleanupOldData(daysToKeep)
                    log.d(tag = TAG) { "Old data cleanup completed" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error during cleanup: ${e.message}" }
                }
            }
        }
    }

    fun keepOnlyRecentData(
        callLogsLimit: Int = 1000,
        stateHistoryLimit: Int = 5000
    ) {
        scope.launch {
            ensureInitializedAndRun {
                try {
                    repository.keepOnlyRecentData(callLogsLimit, stateHistoryLimit)
                    log.d(tag = TAG) { "Recent data maintenance completed" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error during data maintenance: ${e.message}" }
                }
            }
        }
    }

    fun optimizeDatabase() {
        scope.launch {
            ensureInitializedAndRun {
                try {
                    database.useWriterConnection { transactor ->
                        transactor.execSQL("VACUUM")
                    }
                    log.d(tag = TAG) { "Database optimization completed" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error during database optimization: ${e.message}" }
                }
            }
        }
    }

    // === MÉTODOS DE UTILIDAD ===

    suspend fun getDatabaseDiagnosticInfo(): String {
        return try {
            val stats = getGeneralStatistics()
            buildString {
                appendLine("=== DATABASE DIAGNOSTIC INFO ===")
                appendLine("Total Accounts: ${stats.totalAccounts}")
                appendLine("Registered Accounts: ${stats.registeredAccounts}")
                appendLine("Total Calls: ${stats.totalCalls}")
                appendLine("Missed Calls: ${stats.missedCalls}")
                appendLine("Total Contacts: ${stats.totalContacts}")
                appendLine("Active Calls: ${stats.activeCalls}")
                appendLine("Initialized: $isInitialized")
            }
        } catch (e: Exception) {
            "Error getting diagnostic info: ${e.message}"
        }
    }

    fun closeDatabase() {
        scope.launch {
            try {
                log.d(tag = TAG) { "Closing database safely" }

                scope.cancel()

                try {
                    database.useWriterConnection { transactor ->
                        transactor.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    }
                } catch (e: Exception) {
                    log.w(tag = TAG) { "Error during WAL checkpoint: ${e.message}" }
                }

                database.close()

                synchronized(LOCK) {
                    INSTANCE = null
                    isInitialized = false
                }

                log.d(tag = TAG) { "Database closed successfully" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error closing database: ${e.message}" }
            }
        }
    }

    // === OPERACIONES DE CONFIGURACIÓN ===

    suspend fun getAppConfig(): AppConfigEntity? {
        return ensureInitializedAndRun {
            repository.getAppConfig()
        }
    }

    fun getAppConfigFlow(): Flow<AppConfigEntity?> {
        return repository.getAppConfigFlow()
    }

    suspend fun createOrUpdateAppConfig(
        incomingRingtoneUri: String? = null,
        outgoingRingtoneUri: String? = null,
        defaultDomain: String? = null,
        webSocketUrl: String? = null,
        userAgent: String? = null,
        enableLogs: Boolean? = null,
        enableAutoReconnect: Boolean? = null,
        pingIntervalMs: Long? = null
    ): AppConfigEntity {
        return ensureInitializedAndRun {
            repository.createOrUpdateAppConfig(
                incomingRingtoneUri = incomingRingtoneUri,
                outgoingRingtoneUri = outgoingRingtoneUri,
                defaultDomain = defaultDomain,
                webSocketUrl = webSocketUrl,
                userAgent = userAgent,
                enableLogs = enableLogs,
                enableAutoReconnect = enableAutoReconnect,
                pingIntervalMs = pingIntervalMs
            )
        }
    }

    suspend fun updateIncomingRingtoneUri(uri: String?) {
        ensureInitializedAndRun {
            repository.updateIncomingRingtoneUri(uri)
        }
    }

    suspend fun updateOutgoingRingtoneUri(uri: String?) {
        ensureInitializedAndRun {
            repository.updateOutgoingRingtoneUri(uri)
        }
    }

    suspend fun updateRingtoneUris(incomingUri: String?, outgoingUri: String?) {
        ensureInitializedAndRun {
            repository.updateRingtoneUris(incomingUri, outgoingUri)
        }
    }

    suspend fun loadOrCreateDefaultConfig(): AppConfigEntity {
        return ensureInitializedAndRun {
            getAppConfig() ?: createOrUpdateAppConfig()
        }
    }
}