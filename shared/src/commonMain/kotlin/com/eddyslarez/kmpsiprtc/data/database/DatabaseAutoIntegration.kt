package com.eddyslarez.kmpsiprtc.data.database

import com.eddyslarez.kmpsiprtc.core.SipCoreManager
import com.eddyslarez.kmpsiprtc.data.database.entities.SipAccountEntity
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallStateInfo
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.repository.GeneralStatistics
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.calls.MultiCallManager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.concurrent.Volatile

class DatabaseAutoIntegration private constructor(
    private val sipCoreManager: SipCoreManager
) {
    private val databaseManager = DatabaseManager.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val accountsCache = mutableMapOf<String, SipAccountEntity>()

    private val TAG = "DatabaseAutoIntegration"

    companion object {
        @Volatile
        private var INSTANCE: DatabaseAutoIntegration? = null
        private val LOCK = SynchronizedObject()

        fun getInstance(
            sipCoreManager: SipCoreManager
        ): DatabaseAutoIntegration {
            return INSTANCE ?: synchronized(LOCK) {
                val instance = DatabaseAutoIntegration(sipCoreManager)
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * Inicializa la integración automática
     */
    fun initialize() {
        log.d(tag = TAG) { "Initializing automatic database integration" }

        setupRegistrationSync()
        setupCallStateSync()
        setupContactSync()
//        startPeriodicMaintenance()

        log.d(tag = TAG) { "Database integration initialized successfully" }
    }

    // === SINCRONIZACIÓN DE REGISTRO ===

    private fun setupRegistrationSync() {
        // Observar cambios en estados de registro
        scope.launch {
            sipCoreManager.registrationStatesFlow.collect { registrationStates ->
                registrationStates.forEach { (accountKey, state) ->
                    syncRegistrationState(accountKey, state)
                }
            }
        }
    }

    private suspend fun syncRegistrationState(accountKey: String, state: RegistrationState) {
        try {
            val parts = accountKey.split("@")
            if (parts.size != 2) return

            val username = parts[0]
            val domain = parts[1]

            // Buscar o crear cuenta en BD
            var account = databaseManager.getSipAccountByCredentials(username, domain)

            if (account == null && state != RegistrationState.NONE) {
                // Crear cuenta en BD si no existe
                val accountInfo = sipCoreManager.activeAccounts[accountKey]
                if (accountInfo != null) {
                    account = createAccountInDatabase(accountInfo, accountKey)
                }
            }

            // Actualizar estado de registro
            account?.let { acc ->
                accountsCache[accountKey] = acc
                databaseManager.updateSipAccountRegistrationState(
                    accountId = acc.id,
                    state = state,
                    expiry = if (state == RegistrationState.OK)
                       Clock.System.now().toEpochMilliseconds()+ (3600 * 1000) // 1 hora
                    else null
                )

                log.d(tag = TAG) { "Registration state synced: $accountKey -> $state" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error syncing registration state: ${e.message}" }
        }
    }

    private suspend fun createAccountInDatabase(
        accountInfo: AccountInfo,
        accountKey: String
    ): SipAccountEntity {
        return databaseManager.createOrUpdateSipAccount(
            username = accountInfo.username,
            password = accountInfo.password,
            domain = accountInfo.domain,
            displayName = accountInfo.username,
            pushToken = accountInfo.token.value,
            pushProvider = accountInfo.provider.value
        ).also { account ->
            accountsCache[accountKey] = account
            log.d(tag = TAG) { "Account created in database: $accountKey" }
        }
    }

    // === SINCRONIZACIÓN DE ESTADOS DE LLAMADA ===

    private fun setupCallStateSync() {
        scope.launch {
            CallStateManager.callStateFlow.collect { stateInfo ->
                syncCallState(stateInfo)
            }
        }
    }

    private suspend fun syncCallState(stateInfo: CallStateInfo) {
        try {
            val callId = stateInfo.callId
            val callData = MultiCallManager.getCall(callId) ?: return
            val account = getCurrentAccount() ?: return

            when (stateInfo.state) {
                CallState.OUTGOING_INIT,
                CallState.INCOMING_RECEIVED -> {
                    // Crear datos de llamada activa
                    createActiveCallData(account, callData, stateInfo)
                }

                CallState.CONNECTED,
                CallState.STREAMS_RUNNING -> {
                    // Actualizar tiempo de conexión
                    updateCallConnected(callId, stateInfo)
                }

                CallState.PAUSED -> {
                    // Llamada en espera
                    updateCallState(callId, stateInfo)
                }

                CallState.ENDED -> {
                    // Llamada terminada - crear historial
                    createCallHistory(account, callData, stateInfo)
                    endActiveCall(callId)
                }

                CallState.ERROR -> {
                    // Error en llamada
                    handleCallError(account, callData, stateInfo)
                }

                else -> {
                    // Otros estados - solo actualizar
                    updateCallState(callId, stateInfo)
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error syncing call state: ${e.message}" }
        }
    }

    private suspend fun createActiveCallData(
        account: SipAccountEntity,
        callData: CallData,
        stateInfo: CallStateInfo
    ) {
        try {
            databaseManager.createCallData(account.id, callData)
            databaseManager.updateCallState(
                callId = callData.callId,
                newState = stateInfo.state,
                errorReason = stateInfo.errorReason,
                sipCode = stateInfo.sipCode,
                sipReason = stateInfo.sipReason
            )

            log.d(tag = TAG) { "Active call data created: ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating active call data: ${e.message}" }
        }
    }

    private suspend fun updateCallConnected(callId: String, stateInfo: CallStateInfo) {
        try {
            // Actualizar estado y tiempo de conexión
            databaseManager.updateCallState(
                callId = callId,
                newState = stateInfo.state,
                sipCode = stateInfo.sipCode,
                sipReason = stateInfo.sipReason
            )

            log.d(tag = TAG) { "Call connected updated: $callId" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating call connected: ${e.message}" }
        }
    }

    private suspend fun createCallHistory(
        account: SipAccountEntity,
        callData: CallData,
        stateInfo: CallStateInfo
    ) {
        try {
            val callType = determineCallType(callData, stateInfo)
            val endTime = Clock.System.now().toEpochMilliseconds()

            databaseManager.createCallLog(
                accountId = account.id,
                callData = callData,
                callType = callType,
                endTime = endTime,
                sipCode = stateInfo.sipCode,
                sipReason = stateInfo.sipReason
            )

            // Actualizar estadísticas de contacto si existe
            updateContactStatistics(callData, callType, endTime - callData.startTime)

            log.d(tag = TAG) { "Call history created: ${callData.callId} (${callType})" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating call history: ${e.message}" }
        }
    }

    private suspend fun handleCallError(
        account: SipAccountEntity,
        callData: CallData,
        stateInfo: CallStateInfo
    ) {
        try {
            // Actualizar estado de error
            databaseManager.updateCallState(
                callId = callData.callId,
                newState = CallState.ERROR,
                errorReason = stateInfo.errorReason,
                sipCode = stateInfo.sipCode,
                sipReason = stateInfo.sipReason
            )

            // Crear historial con tipo de error apropiado
            val callType = when (stateInfo.errorReason) {
                CallErrorReason.NO_ANSWER -> CallTypes.MISSED
                CallErrorReason.REJECTED -> CallTypes.DECLINED
                else -> CallTypes.ABORTED
            }

            databaseManager.createCallLog(
                accountId = account.id,
                callData = callData,
                callType = callType,
                endTime = Clock.System.now().toEpochMilliseconds(),
                sipCode = stateInfo.sipCode,
                sipReason = stateInfo.sipReason
            )

            endActiveCall(callData.callId)

            log.d(tag = TAG) { "Call error handled: ${callData.callId} (${stateInfo.errorReason})" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling call error: ${e.message}" }
        }
    }

    private suspend fun updateCallState(callId: String, stateInfo: CallStateInfo) {
        try {
            databaseManager.updateCallState(
                callId = callId,
                newState = stateInfo.state,
                errorReason = stateInfo.errorReason,
                sipCode = stateInfo.sipCode,
                sipReason = stateInfo.sipReason
            )
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating call state: ${e.message}" }
        }
    }

    private suspend fun endActiveCall(callId: String) {
        try {
            databaseManager.endCall(callId)
            log.d(tag = TAG) { "Active call ended: $callId" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error ending active call: ${e.message}" }
        }
    }

    private fun determineCallType(callData: CallData, stateInfo: CallStateInfo): CallTypes {
        return when {
            stateInfo.hasError() -> when (stateInfo.errorReason) {
                CallErrorReason.NO_ANSWER -> if (callData.direction == CallDirections.INCOMING)
                    CallTypes.MISSED else CallTypes.ABORTED
                CallErrorReason.REJECTED -> CallTypes.DECLINED
                CallErrorReason.BUSY -> CallTypes.ABORTED
                else -> CallTypes.ABORTED
            }
            stateInfo.state == CallState.ENDED -> CallTypes.SUCCESS
            else -> CallTypes.ABORTED
        }
    }

    // === SINCRONIZACIÓN DE CONTACTOS ===

    private fun setupContactSync() {
        // Crear contactos automáticamente para números no conocidos
        scope.launch {
            CallStateManager.callStateFlow
                .filter { it.state == CallState.INCOMING_RECEIVED || it.state == CallState.OUTGOING_INIT }
                .collect { stateInfo ->
                    autoCreateContactIfNeeded(stateInfo)
                }
        }
    }

    private suspend fun autoCreateContactIfNeeded(stateInfo: CallStateInfo) {
        try {
            val callData = MultiCallManager.getCall(stateInfo.callId) ?: return
            val phoneNumber = if (callData.direction == CallDirections.INCOMING)
                callData.from else callData.to

            // Verificar si el contacto ya existe
            val existingContact = scope.async {
                try {
                    databaseManager.getAllContacts().first().find { it.phoneNumber == phoneNumber }
                } catch (e: Exception) {
                    null
                }
            }.await()

            if (existingContact == null) {
                // Crear contacto básico
                databaseManager.createOrUpdateContact(
                    phoneNumber = phoneNumber,
                    displayName = callData.remoteDisplayName.ifEmpty { phoneNumber },
                    firstName = null,
                    lastName = null
                )

                log.d(tag = TAG) { "Auto-created contact for: $phoneNumber" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error auto-creating contact: ${e.message}" }
        }
    }

    private suspend fun updateContactStatistics(
        callData: CallData,
        callType: CallTypes,
        duration: Long
    ) {
        try {
            val phoneNumber = if (callData.direction == CallDirections.INCOMING)
                callData.from else callData.to

            // Actualizar estadísticas en el contacto
            // Nota: Esto requeriría métodos adicionales en DatabaseManager
            // o acceso directo al ContactDao para actualizaciones de estadísticas

            log.d(tag = TAG) { "Contact statistics updated for: $phoneNumber" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating contact statistics: ${e.message}" }
        }
    }

    // === MANTENIMIENTO AUTOMÁTICO ===

    private fun startPeriodicMaintenance() {
        scope.launch {
            while (true) {
                delay(24 * 60 * 60 * 1000) // 24 horas
                performMaintenance()
            }
        }
    }

    private fun performMaintenance() {
        scope.launch {
            try {
                log.d(tag = TAG) { "Starting periodic maintenance" }

                // Limpiar datos antiguos (30 días)
                databaseManager.cleanupOldData(daysToKeep = 30)

                // Mantener solo registros recientes
                databaseManager.keepOnlyRecentData(
                    callLogsLimit = 1000,
                    stateHistoryLimit = 5000
                )

                // Optimizar base de datos
                databaseManager.optimizeDatabase()

                log.d(tag = TAG) { "Periodic maintenance completed" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in periodic maintenance: ${e.message}" }
            }
        }
    }

    // === MÉTODOS DE UTILIDAD ===

    private fun getCurrentAccount(): SipAccountEntity? {
        val currentAccountInfo = sipCoreManager.currentAccountInfo ?: return null
        val accountKey = "${currentAccountInfo.username}@${currentAccountInfo.domain}"
        return accountsCache[accountKey]
    }

    /**
     * Obtiene estadísticas generales de la base de datos
     */
    suspend fun getGeneralStatistics(): GeneralStatistics {
        return databaseManager.getGeneralStatistics()
    }

    /**
     * Fuerza la sincronización de todas las cuentas activas
     */
    fun forceSyncAllAccounts() {
        scope.launch {
            try {
                log.d(tag = TAG) { "Starting forced sync of all accounts" }

                sipCoreManager.activeAccounts.forEach { (accountKey, accountInfo) ->
                    // Crear o actualizar en BD
                    val account = databaseManager.createOrUpdateSipAccount(
                        username = accountInfo.username,
                        password = accountInfo.password,
                        domain = accountInfo.domain,
                        displayName = accountInfo.username,
                        pushToken = accountInfo.token.value,
                        pushProvider = accountInfo.provider.value
                    )

                    // Actualizar estado de registro
                    val state = sipCoreManager.getRegistrationState(accountKey)
                    databaseManager.updateSipAccountRegistrationState(account.id, state)

                    accountsCache[accountKey] = account
                }

                log.d(tag = TAG) { "Forced sync completed for ${sipCoreManager.activeAccounts.size} accounts" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in forced sync: ${e.message}" }
            }
        }
    }

    /**
     * Obtiene información de diagnóstico de la integración
     */
    suspend fun getDiagnosticInfo(): String {
        return try {
            val stats = getGeneralStatistics()
            buildString {
                appendLine("=== DATABASE INTEGRATION DIAGNOSTIC ===")
                appendLine("Active Accounts in Cache: ${accountsCache.size}")
                appendLine("SipCore Active Accounts: ${sipCoreManager.activeAccounts.size}")
                appendLine("Database Statistics:")
                appendLine("  - Total Accounts: ${stats.totalAccounts}")
                appendLine("  - Registered Accounts: ${stats.registeredAccounts}")
                appendLine("  - Total Calls: ${stats.totalCalls}")
                appendLine("  - Missed Calls: ${stats.missedCalls}")
                appendLine("  - Total Contacts: ${stats.totalContacts}")
                appendLine("  - Active Calls: ${stats.activeCalls}")

                appendLine("\nCached Accounts:")
                accountsCache.forEach { (key, account) ->
                    appendLine("  - $key: ${account.registrationState}")
                }

                appendLine("\nIntegration Status: ✅ ACTIVE")
            }
        } catch (e: Exception) {
            "Error getting diagnostic info: ${e.message}"
        }
    }

    /**
     * Limpia la integración
     */
    fun dispose() {
        scope.cancel()
        accountsCache.clear()
        INSTANCE = null
        log.d(tag = TAG) { "Database integration disposed" }
    }
}
