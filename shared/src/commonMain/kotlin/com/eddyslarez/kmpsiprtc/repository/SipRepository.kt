package com.eddyslarez.kmpsiprtc.repository

import com.eddyslarez.kmpsiprtc.data.database.dao.CallStatistics
import com.eddyslarez.kmpsiprtc.data.database.entities.AppConfigEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.CallDataEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.CallLogEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.CallStateHistoryEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.ContactEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.SipAccountEntity
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import com.eddyslarez.kmpsiprtc.utils.generateId
import kotlinx.coroutines.flow.map
import com.eddyslarez.kmpsiprtc.data.database.SipDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

class SipRepository(private val database: SipDatabase) {

    private val appConfigDao = database.appConfigDao()
    private val sipAccountDao = database.sipAccountDao()
    private val callLogDao = database.callLogDao()
    private val callDataDao = database.callDataDao()
    private val contactDao = database.contactDao()
    private val callStateHistoryDao = database.callStateDao()

    // === OPERACIONES DE CONFIGURACIÓN ===

    suspend fun getAppConfig(): AppConfigEntity? {
        return appConfigDao.getConfig()
    }

    fun getAppConfigFlow(): Flow<AppConfigEntity?> {
        return appConfigDao.getConfigFlow()
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
        val existingConfig = appConfigDao.getConfig()

        val config = if (existingConfig != null) {
            existingConfig.copy(
                incomingRingtoneUri = incomingRingtoneUri ?: existingConfig.incomingRingtoneUri,
                outgoingRingtoneUri = outgoingRingtoneUri ?: existingConfig.outgoingRingtoneUri,
                defaultDomain = defaultDomain ?: existingConfig.defaultDomain,
                webSocketUrl = webSocketUrl ?: existingConfig.webSocketUrl,
                userAgent = userAgent ?: existingConfig.userAgent,
                enableLogs = enableLogs ?: existingConfig.enableLogs,
                enableAutoReconnect = enableAutoReconnect ?: existingConfig.enableAutoReconnect,
                pingIntervalMs = pingIntervalMs ?: existingConfig.pingIntervalMs,
                updatedAt = Clock.System.now().toEpochMilliseconds()
            )
        } else {
            AppConfigEntity(
                incomingRingtoneUri = incomingRingtoneUri,
                outgoingRingtoneUri = outgoingRingtoneUri,
                defaultDomain = defaultDomain ?: "",
                webSocketUrl = webSocketUrl ?: "",
                userAgent = userAgent ?: "",
                enableLogs = enableLogs ?: true,
                enableAutoReconnect = enableAutoReconnect ?: true,
                pingIntervalMs = pingIntervalMs ?: 30000L
            )
        }

        appConfigDao.insertConfig(config)
        return config
    }

    suspend fun updateIncomingRingtoneUri(uri: String?) {
        appConfigDao.updateIncomingRingtoneUri(uri)
    }

    suspend fun updateOutgoingRingtoneUri(uri: String?) {
        appConfigDao.updateOutgoingRingtoneUri(uri)
    }

    suspend fun updateRingtoneUris(incomingUri: String?, outgoingUri: String?) {
        val existingConfig = appConfigDao.getConfig() ?: AppConfigEntity()
        val updatedConfig = existingConfig.copy(
            incomingRingtoneUri = incomingUri,
            outgoingRingtoneUri = outgoingUri,
            updatedAt = Clock.System.now().toEpochMilliseconds()
        )
        appConfigDao.insertConfig(updatedConfig)
    }

    // === OPERACIONES DE CUENTAS SIP ===

    fun getActiveAccounts(): Flow<List<SipAccountEntity>> {
        return sipAccountDao.getActiveAccounts()
    }

    fun getRegisteredAccounts(): Flow<List<SipAccountEntity>> {
        return sipAccountDao.getRegisteredAccounts()
    }

    suspend fun getAccountByCredentials(username: String, domain: String): SipAccountEntity? {
        return sipAccountDao.getAccountByCredentials(username, domain)
    }

    suspend fun createOrUpdateAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        pushToken: String? = null,
        pushProvider: String? = null
    ): SipAccountEntity {
        val existingAccount = getAccountByCredentials(username, domain)

        val account = if (existingAccount != null) {
            existingAccount.copy(
                password = password,
                displayName = displayName ?: existingAccount.displayName,
                pushToken = pushToken ?: existingAccount.pushToken,
                pushProvider = pushProvider ?: existingAccount.pushProvider,
                updatedAt = Clock.System.now().toEpochMilliseconds()
            )
        } else {
            SipAccountEntity(
                id = generateId(),
                username = username,
                password = password,
                domain = domain,
                displayName = displayName ?: username,
                pushToken = pushToken,
                pushProvider = pushProvider
            )
        }

        sipAccountDao.insertAccount(account)
        return account
    }

    suspend fun updateRegistrationState(
        accountId: String,
        state: RegistrationState,
        expiry: Long? = null
    ) {
        if (expiry != null) {
            sipAccountDao.updateRegistrationWithExpiry(accountId, state, expiry)
        } else {
            sipAccountDao.updateRegistrationState(accountId, state)
        }
    }

    suspend fun deleteAccount(accountId: String) {
        sipAccountDao.deleteAccountById(accountId)
    }

    // === OPERACIONES DE HISTORIAL DE LLAMADAS ===

    fun getRecentCallLogs(limit: Int = 50): Flow<List<CallLogWithContact>> {
        return callLogDao.getRecentCallLogs(limit).map { callLogs ->
            callLogs.map { callLog ->
                val contact = contactDao.getContactByPhoneNumber(callLog.phoneNumber)
                CallLogWithContact(callLog, contact)
            }
        }
    }

    fun getMissedCalls(): Flow<List<CallLogWithContact>> {
        return callLogDao.getMissedCalls().map { callLogs ->
            callLogs.map { callLog ->
                val contact = contactDao.getContactByPhoneNumber(callLog.phoneNumber)
                CallLogWithContact(callLog, contact)
            }
        }
    }

    suspend fun createCallLog(
        accountId: String,
        callData: CallData,
        callType: CallTypes,
        endTime: Long? = null,
        sipCode: Int? = null,
        sipReason: String? = null
    ): CallLogEntity {
        val account = sipAccountDao.getAccountById(accountId)
        val localUsername = account?.username ?: ""

        val (phoneNumber, localAddress) = when (callData.direction) {
            CallDirections.OUTGOING -> {
                val remote = callData.to.takeIf { it.isNotEmpty() } ?: callData.getRemoteParty()
                val local = callData.from.takeIf { it.isNotEmpty() } ?: localUsername
                Pair(remote, local)
            }
            CallDirections.INCOMING -> {
                val remote = callData.from.takeIf { it.isNotEmpty() } ?: callData.getRemoteParty()
                val local = callData.to.takeIf { it.isNotEmpty() } ?: localUsername
                Pair(remote, local)
            }
        }

        val duration = if (endTime != null && callData.startTime > 0) {
            ((endTime - callData.startTime) / 1000).toInt()
        } else {
            0
        }

        val callLog = CallLogEntity(
            id = generateId(),
            accountId = accountId,
            callId = callData.callId,
            phoneNumber = phoneNumber,
            displayName = callData.remoteDisplayName.ifEmpty { phoneNumber },
            direction = callData.direction,
            callType = callType,
            startTime = callData.startTime,
            endTime = endTime,
            duration = duration,
            sipCode = sipCode,
            sipReason = sipReason,
            localAddress = localAddress
        )

        callLogDao.insertCallLog(callLog)
        updateContactStatistics(phoneNumber, callType, duration.toLong())
        updateAccountStatistics(accountId, callType)

        return callLog
    }

    fun searchCallLogs(query: String): Flow<List<CallLogWithContact>> {
        return callLogDao.searchCallLogs(query).map { callLogs ->
            callLogs.map { callLog ->
                val contact = contactDao.getContactByPhoneNumber(callLog.phoneNumber)
                CallLogWithContact(callLog, contact)
            }
        }
    }

    suspend fun clearCallLogs() {
        callLogDao.deleteAllCallLogs()
    }

    // === OPERACIONES DE DATOS DE LLAMADAS ===

    fun getActiveCalls(): Flow<List<CallDataEntity>> {
        return callDataDao.getActiveCallData()
    }

    suspend fun createCallData(
        accountId: String,
        callData: CallData
    ): CallDataEntity {
        val callDataEntity = CallDataEntity(
            callId = callData.callId,
            accountId = accountId,
            fromNumber = callData.from,
            toNumber = callData.to,
            direction = callData.direction,
            currentState = CallState.IDLE,
            startTime = callData.startTime,
            fromTag = callData.fromTag,
            toTag = callData.toTag,
            inviteFromTag = callData.inviteFromTag,
            inviteToTag = callData.inviteToTag,
            remoteContactUri = callData.remoteContactUri,
            remoteDisplayName = callData.remoteDisplayName,
            localSdp = callData.localSdp,
            remoteSdp = callData.remoteSdp,
            viaHeader = callData.via,
            inviteViaBranch = callData.inviteViaBranch,
            lastCSeqValue = callData.lastCSeqValue,
            originalInviteMessage = callData.originalInviteMessage,
            originalCallInviteMessage = callData.originalCallInviteMessage,
            md5Hash = callData.md5Hash,
            sipName = callData.sipName
        )

        callDataDao.insertCallData(callDataEntity)
        return callDataEntity
    }

    suspend fun updateCallState(
        callId: String,
        newState: CallState,
        errorReason: CallErrorReason = CallErrorReason.NONE,
        sipCode: Int? = null,
        sipReason: String? = null
    ) {
        val currentCallData = callDataDao.getCallDataById(callId)
        val previousState = currentCallData?.currentState

        callDataDao.updateCallState(callId, newState)

        val stateHistory = CallStateHistoryEntity(
            id = generateId(),
            callId = callId,
            state = newState,
            previousState = previousState,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            errorReason = errorReason,
            sipCode = sipCode,
            sipReason = sipReason,
            hasError = errorReason != CallErrorReason.NONE || newState == CallState.ERROR
        )

        callStateHistoryDao.insertStateHistory(stateHistory)
    }

    suspend fun endCall(callId: String, endTime: Long = Clock.System.now().toEpochMilliseconds()) {
        callDataDao.endCall(callId, endTime)
        updateCallState(callId, CallState.ENDED)
    }

    // === OPERACIONES DE CONTACTOS ===

    fun getAllContacts(): Flow<List<ContactEntity>> {
        return contactDao.getAllContacts()
    }

    fun searchContacts(query: String): Flow<List<ContactEntity>> {
        return contactDao.searchContacts(query)
    }

    suspend fun createOrUpdateContact(
        phoneNumber: String,
        displayName: String,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
        company: String? = null
    ): ContactEntity {
        val existingContact = contactDao.getContactByPhoneNumber(phoneNumber)

        val contact = if (existingContact != null) {
            existingContact.copy(
                displayName = displayName,
                firstName = firstName ?: existingContact.firstName,
                lastName = lastName ?: existingContact.lastName,
                email = email ?: existingContact.email,
                company = company ?: existingContact.company,
                updatedAt = Clock.System.now().toEpochMilliseconds()
            )
        } else {
            ContactEntity(
                id = generateId(),
                phoneNumber = phoneNumber,
                displayName = displayName,
                firstName = firstName,
                lastName = lastName,
                email = email,
                company = company
            )
        }

        contactDao.insertContact(contact)
        return contact
    }

    suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
        return contactDao.isPhoneNumberBlocked(phoneNumber) ?: false
    }

    // === ESTADÍSTICAS ===

    suspend fun getGeneralStatistics(): GeneralStatistics {
        val totalAccounts = sipAccountDao.getAccountCount()
        val registeredAccounts = sipAccountDao.getRegisteredAccountCount()
        val totalCalls = callLogDao.getTotalCallCount()
        val missedCalls = callLogDao.getCallCountByType(CallTypes.MISSED)
        val totalContacts = contactDao.getContactCount()
        val activeCalls = callDataDao.getActiveCallCount()

        return GeneralStatistics(
            totalAccounts = totalAccounts,
            registeredAccounts = registeredAccounts,
            totalCalls = totalCalls,
            missedCalls = missedCalls,
            totalContacts = totalContacts,
            activeCalls = activeCalls
        )
    }

    suspend fun getCallStatisticsForNumber(phoneNumber: String): CallStatistics? {
        return callLogDao.getCallStatisticsForNumber(phoneNumber)
    }

    // === LIMPIEZA ===

    suspend fun cleanupOldData(daysToKeep: Int = 30) {
        val threshold = Clock.System.now().toEpochMilliseconds() - (daysToKeep * 24 * 60 * 60 * 1000L)
        callLogDao.deleteCallLogsOlderThan(threshold)
        callStateHistoryDao.deleteStateHistoryOlderThan(threshold)
        callDataDao.deleteInactiveCallsOlderThan(threshold)
    }

    suspend fun keepOnlyRecentData(
        callLogsLimit: Int = 1000,
        stateHistoryLimit: Int = 5000
    ) {
        callLogDao.keepOnlyRecentCallLogs(callLogsLimit)
        callStateHistoryDao.keepOnlyRecentStateHistory(stateHistoryLimit)
    }

    // === MÉTODOS PRIVADOS ===

    private suspend fun updateContactStatistics(
        phoneNumber: String,
        callType: CallTypes,
        duration: Long
    ) {
        contactDao.incrementCallCount(phoneNumber)

        if (callType == CallTypes.SUCCESS && duration > 0) {
            contactDao.addCallDuration(phoneNumber, duration)
        }

        if (callType == CallTypes.MISSED) {
            contactDao.incrementMissedCalls(phoneNumber)
        }
    }

    private suspend fun updateAccountStatistics(accountId: String, callType: CallTypes) {
        sipAccountDao.incrementTotalCalls(accountId)

        when (callType) {
            CallTypes.SUCCESS -> sipAccountDao.incrementSuccessfulCalls(accountId)
            CallTypes.MISSED, CallTypes.DECLINED, CallTypes.ABORTED ->
                sipAccountDao.incrementFailedCalls(accountId)
        }
    }
}

// === CLASES DE DATOS ===

data class CallLogWithContact(
    val callLog: CallLogEntity,
    val contact: ContactEntity?
) {
    fun getDisplayName(): String {
        return contact?.displayName ?: callLog.displayName ?: callLog.phoneNumber
    }

    fun getAvatarUrl(): String? {
        return contact?.avatarUrl
    }

    fun isBlocked(): Boolean {
        return contact?.isBlocked ?: false
    }

    fun isFavorite(): Boolean {
        return contact?.isFavorite ?: false
    }
}

data class GeneralStatistics(
    val totalAccounts: Int,
    val registeredAccounts: Int,
    val totalCalls: Int,
    val missedCalls: Int,
    val totalContacts: Int,
    val activeCalls: Int
)