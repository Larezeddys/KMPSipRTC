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
import com.eddyslarez.kmpsiprtc.utils.Lock
import com.eddyslarez.kmpsiprtc.utils.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime

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

    private val TAG = "DatabaseManager"

    companion object {
        @Volatile
        private var INSTANCE: DatabaseManager? = null
        private val LOCK =  Lock()

        fun getInstance(): DatabaseManager {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: DatabaseManager().also {
                    INSTANCE = it
                    it.ensureInitialized()
                }
            }
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            scope.launch {
                try {
                    // Forzar apertura de la base de datos
                    val stats = getGeneralStatistics()
                    log.d(tag = TAG) { "Database initialized with ${stats.totalAccounts} accounts" }
                    isInitialized = true
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error initializing database: ${e.message}" }
                    e.printStackTrace()
                }
            }
        }
    }


    // === OPERACIONES DE CUENTAS SIP ===

    /**
     * Obtiene cuentas que deberían estar registradas pero pueden estar desconectadas
     */
    @OptIn(ExperimentalTime::class)
    suspend fun getAccountsForRecovery(): List<SipAccountEntity> {
        return try {
            val activeAccounts = getActiveSipAccounts().first()
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

            // Filtrar cuentas que:
            // 1. Están activas
            // 2. No han expirado (si tienen expiry)
            // 3. O su último registro fue reciente (menos de 2 horas)
            activeAccounts.filter { account ->
                account.isActive &&
                        (account.registrationExpiry == 0L || account.registrationExpiry > now) &&
                        (now - account.updatedAt) < (2 * 60 * 60 * 1000) // 2 horas
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting accounts for recovery: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Marca una cuenta como necesitando reconexión
     */
    suspend fun markAccountForReconnection(accountId: String, reason: String? = null) {
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

    /**
     * Obtiene estadísticas de conectividad de cuentas
     */
    @OptIn(ExperimentalTime::class)
    suspend fun getAccountConnectivityStats(): Map<String, Any> {
        return try {
            val activeAccounts = getActiveSipAccounts().first()
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

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

    /**
     * Crea o actualiza una cuenta SIP
     */
    suspend fun createOrUpdateSipAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        pushToken: String? = null,
        pushProvider: String? = null
    ): SipAccountEntity {
        return repository.createOrUpdateAccount(
            username = username,
            password = password,
            domain = domain,
            displayName = displayName,
            pushToken = pushToken,
            pushProvider = pushProvider
        )
    }

    /**
     * Obtiene todas las cuentas activas
     */
    fun getActiveSipAccounts(): Flow<List<SipAccountEntity>> {
        return repository.getActiveAccounts()
    }

    /**
     * Obtiene cuentas registradas
     */
    fun getRegisteredSipAccounts(): Flow<List<SipAccountEntity>> {
        return repository.getRegisteredAccounts()
    }

    /**
     * Obtiene cuenta por credenciales
     */
    suspend fun getSipAccountByCredentials(username: String, domain: String): SipAccountEntity? {
        return repository.getAccountByCredentials(username, domain)
    }

    /**
     * Actualiza estado de registro de cuenta
     */
    suspend fun updateSipAccountRegistrationState(
        accountId: String,
        state: RegistrationState,
        expiry: Long? = null
    ) {
        repository.updateRegistrationState(accountId, state, expiry)
    }

    /**
     * Elimina cuenta SIP
     */
    suspend fun deleteSipAccount(accountId: String) {
        repository.deleteAccount(accountId)
    }

    // === OPERACIONES DE HISTORIAL DE LLAMADAS ===

    /**
     * Obtiene historial de llamadas reciente
     */
    fun getRecentCallLogs(limit: Int = 50): Flow<List<CallLogWithContact>> {
        return repository.getRecentCallLogs(limit)
    }

    /**
     * Obtiene llamadas perdidas
     */
    fun getMissedCallLogs(): Flow<List<CallLogWithContact>> {
        return repository.getMissedCalls()
    }

    /**
     * Busca en historial de llamadas
     */
    fun searchCallLogs(query: String): Flow<List<CallLogWithContact>> {
        return repository.searchCallLogs(query)
    }

    /**
     * Crea entrada en historial de llamadas
     */
    suspend fun createCallLog(
        accountId: String,
        callData: CallData,
        callType: CallTypes,
        endTime: Long? = null,
        sipCode: Int? = null,
        sipReason: String? = null
    ): CallLogEntity {
        return repository.createCallLog(
            accountId = accountId,
            callData = callData,
            callType = callType,
            endTime = endTime,
            sipCode = sipCode,
            sipReason = sipReason
        )
    }

    /**
     * Limpia todo el historial de llamadas
     */
    suspend fun clearAllCallLogs() {
        repository.clearCallLogs()
    }

    // === OPERACIONES DE DATOS DE LLAMADAS ACTIVAS ===

    /**
     * Obtiene llamadas activas
     */
    fun getActiveCallData(): Flow<List<CallDataEntity>> {
        return repository.getActiveCalls()
    }

    /**
     * Crea datos de llamada activa
     */
    suspend fun createCallData(accountId: String, callData: CallData): CallDataEntity {
        return repository.createCallData(accountId, callData)
    }

    /**
     * Actualiza estado de llamada
     */
    suspend fun updateCallState(
        callId: String,
        newState: CallState,
        errorReason: CallErrorReason = CallErrorReason.NONE,
        sipCode: Int? = null,
        sipReason: String? = null
    ) {
        repository.updateCallState(callId, newState, errorReason, sipCode, sipReason)
    }

    /**
     * Finaliza llamada
     */
    @OptIn(ExperimentalTime::class)
    suspend fun endCall(callId: String, endTime: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()) {
        repository.endCall(callId, endTime)
    }

    // === OPERACIONES DE CONTACTOS ===

    /**
     * Obtiene todos los contactos
     */
    fun getAllContacts(): Flow<List<ContactEntity>> {
        return repository.getAllContacts()
    }

    /**
     * Busca contactos
     */
    fun searchContacts(query: String): Flow<List<ContactEntity>> {
        return repository.searchContacts(query)
    }

    /**
     * Crea o actualiza contacto
     */
    suspend fun createOrUpdateContact(
        phoneNumber: String,
        displayName: String,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
        company: String? = null
    ): ContactEntity {
        return repository.createOrUpdateContact(
            phoneNumber = phoneNumber,
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            email = email,
            company = company
        )
    }

    /**
     * Verifica si un número está bloqueado
     */
    suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
        return repository.isPhoneNumberBlocked(phoneNumber)
    }

    // === ESTADÍSTICAS ===

    /**
     * Obtiene estadísticas generales
     */
    suspend fun getGeneralStatistics(): GeneralStatistics {
        return repository.getGeneralStatistics()
    }

    /**
     * Obtiene estadísticas de llamadas para un número específico
     */
    suspend fun getCallStatisticsForNumber(phoneNumber: String): CallStatistics? {
        return repository.getCallStatisticsForNumber(phoneNumber)
    }

    // === OPERACIONES DE MANTENIMIENTO ===

    /**
     * Limpia datos antiguos
     */
    fun cleanupOldData(daysToKeep: Int = 30) {
        scope.launch {
            try {
                repository.cleanupOldData(daysToKeep)
                log.d(tag = TAG) { "Old data cleanup completed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during cleanup: ${e.message}" }
            }
        }
    }

    /**
     * Mantiene solo los registros más recientes
     */
    fun keepOnlyRecentData(
        callLogsLimit: Int = 1000,
        stateHistoryLimit: Int = 5000
    ) {
        scope.launch {
            try {
                repository.keepOnlyRecentData(callLogsLimit, stateHistoryLimit)
                log.d(tag = TAG) { "Recent data maintenance completed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during data maintenance: ${e.message}" }
            }
        }
    }

    /**
     * Optimiza la base de datos
     */
    fun optimizeDatabase() {
        scope.launch {
            try {
                // VACUUM debe ejecutarse fuera de una transacción
                database.useWriterConnection { transactor ->
                    transactor.execSQL("VACUUM")
                }
                log.d(tag = TAG) { "Database optimization completed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during database optimization: ${e.message}" }
            }
        }
    }

    init {
        log.d(tag = TAG) { "DatabaseManager instance created" }
    }


    private suspend fun recoverDatabase() {
        try {
            log.d(tag = TAG) { "Attempting database recovery" }

            val tablesExist: Boolean = try {
                database.useReaderConnection { transactor ->
                    transactor.deferredTransaction {
                        usePrepared("SELECT COUNT(*) FROM sqlite_master WHERE type='table'") { statement ->
                            if (statement.step()) {
                                val count = statement.getLong(0)
                                count > 0
                            } else {
                                false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error checking database tables: ${e.message}" }
                false
            }

            if (!tablesExist) {
                log.w(tag = TAG) { "Database tables missing, recreating..." }
                // Aquí podrías llamar a tu función para recrear las tablas
            }

            isInitialized = true
            log.d(tag = TAG) { "Database recovery completed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Database recovery failed: ${e.message}" }
        }
    }

    /**
     * Obtiene información de diagnóstico de la base de datos
     */
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

    /**
     * Cierra la base de datos (útil para testing)
     */
    fun closeDatabase() {
        scope.launch {
            try {
                log.d(tag = TAG) { "Closing database safely" }

                // Cancelar operaciones pendientes
                scope.cancel()

                // Forzar commit de transacciones pendientes
                try {
                    database.useWriterConnection { transactor ->
                        transactor.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    }
                } catch (e: Exception) {
                    log.w(tag = TAG) { "Error during WAL checkpoint: ${e.message}" }
                }

                // Cerrar la base de datos
                database.close()

                synchronized(LOCK ) {
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

    /**
     * Obtiene la configuración de la aplicación
     */
    suspend fun getAppConfig(): AppConfigEntity? {
        return repository.getAppConfig()
    }

    /**
     * Flow para observar cambios en la configuración
     */
    fun getAppConfigFlow(): Flow<AppConfigEntity?> {
        return repository.getAppConfigFlow()
    }

    /**
     * Crea o actualiza configuración completa
     */
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
        return repository.createOrUpdateAppConfig(
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

    /**
     * Actualiza URI del ringtone de entrada
     */
    suspend fun updateIncomingRingtoneUri(uri: String?) {
        repository.updateIncomingRingtoneUri(uri)
    }

    /**
     * Actualiza URI del ringtone de salida
     */
    suspend fun updateOutgoingRingtoneUri(uri: String?) {
        repository.updateOutgoingRingtoneUri(uri)
    }

    /**
     * Actualiza ambas URIs de ringtones
     */
    suspend fun updateRingtoneUris(incomingUri: String?, outgoingUri: String?) {
        repository.updateRingtoneUris(incomingUri, outgoingUri)
    }

    /**
     * Carga configuración inicial o crea una por defecto
     */
    suspend fun loadOrCreateDefaultConfig(): AppConfigEntity {
        return getAppConfig() ?: createOrUpdateAppConfig()
    }
}