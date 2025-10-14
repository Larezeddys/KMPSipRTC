package com.eddyslarez.kmpsiprtc.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.eddyslarez.kmpsiprtc.data.database.entities.CallDataEntity
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallState
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

@Dao
interface CallDataDao {

    // === OPERACIONES BÁSICAS ===

    @Query("SELECT * FROM call_data ORDER BY startTime DESC")
    fun getAllCallData(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE isActive = 1 ORDER BY startTime DESC")
    fun getActiveCallData(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE callId = :callId")
    suspend fun getCallDataById(callId: String): CallDataEntity?

    @Query("SELECT * FROM call_data WHERE callId = :callId")
    fun getCallDataByIdFlow(callId: String): Flow<CallDataEntity?>

    @Query("SELECT * FROM call_data WHERE accountId = :accountId ORDER BY startTime DESC")
    fun getCallDataByAccount(accountId: String): Flow<List<CallDataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallData(callData: CallDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallDataList(callDataList: List<CallDataEntity>)

    @Update
    suspend fun updateCallData(callData: CallDataEntity)

    @Delete
    suspend fun deleteCallData(callData: CallDataEntity)

    @Query("DELETE FROM call_data WHERE callId = :callId")
    suspend fun deleteCallDataById(callId: String)

    // === OPERACIONES DE ESTADO ===

    @Query("SELECT * FROM call_data WHERE currentState = :state ORDER BY startTime DESC")
    fun getCallDataByState(state: CallState): Flow<List<CallDataEntity>>

    @Query("UPDATE call_data SET currentState = :state, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateCallState(callId: String, state: CallState, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET connectTime = :connectTime, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateConnectTime(callId: String, connectTime: Long, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET endTime = :endTime, isActive = 0, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun endCall(callId: String, endTime: Long, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET isActive = :isActive, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun setCallActive(callId: String, isActive: Boolean, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    // === OPERACIONES DE HOLD/MUTE ===

    @Query("UPDATE call_data SET isOnHold = :isOnHold, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateHoldState(callId: String, isOnHold: Boolean, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET isMuted = :isMuted, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateMuteState(callId: String, isMuted: Boolean, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET isRecording = :isRecording, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateRecordingState(callId: String, isRecording: Boolean, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    // === OPERACIONES SDP ===

    @Query("UPDATE call_data SET localSdp = :sdp, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateLocalSdp(callId: String, sdp: String, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET remoteSdp = :sdp, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateRemoteSdp(callId: String, sdp: String, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET iceUfrag = :ufrag, icePwd = :pwd, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateIceCredentials(callId: String, ufrag: String?, pwd: String?, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET dtlsFingerprint = :fingerprint, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateDtlsFingerprint(callId: String, fingerprint: String?, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    // === OPERACIONES DE TAGS ===

    @Query("UPDATE call_data SET fromTag = :fromTag, toTag = :toTag, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateTags(callId: String, fromTag: String?, toTag: String?, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET inviteFromTag = :fromTag, inviteToTag = :toTag, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateInviteTags(callId: String, fromTag: String, toTag: String, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    // === OPERACIONES DE CONTACTO REMOTO ===

    @Query("UPDATE call_data SET remoteContactUri = :uri, remoteDisplayName = :displayName, remoteUserAgent = :userAgent, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateRemoteContact(callId: String, uri: String?, displayName: String, userAgent: String?, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    // === OPERACIONES DE CALIDAD ===

    @Query("UPDATE call_data SET audioCodec = :codec, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateAudioCodec(callId: String, codec: String?, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    @Query("UPDATE call_data SET packetsSent = :sent, packetsReceived = :received, bytesTransferred = :bytes, updatedAt = :timestamp WHERE callId = :callId")
    suspend fun updateCallStatistics(callId: String, sent: Long, received: Long, bytes: Long, timestamp: Long = Clock.System.now().toEpochMilliseconds())

    // === CONSULTAS ESPECÍFICAS ===

    @Query("SELECT * FROM call_data WHERE direction = :direction AND isActive = 1 ORDER BY startTime DESC")
    fun getActiveCallsByDirection(direction: CallDirections): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE isOnHold = 1 AND isActive = 1")
    fun getCallsOnHold(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE isMuted = 1 AND isActive = 1")
    fun getMutedCalls(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE isRecording = 1 AND isActive = 1")
    fun getRecordingCalls(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE fromNumber = :phoneNumber OR toNumber = :phoneNumber ORDER BY startTime DESC")
    fun getCallDataForNumber(phoneNumber: String): Flow<List<CallDataEntity>>

    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun getCallLogCount(): Int
    // === ESTADÍSTICAS ===

    @Query("SELECT COUNT(*) FROM call_data WHERE isActive = 1")
    suspend fun getActiveCallCount(): Int

    @Query("SELECT COUNT(*) FROM call_data WHERE currentState = :state")
    suspend fun getCallCountByState(state: CallState): Int

    @Query("SELECT COUNT(*) FROM call_data WHERE direction = :direction AND isActive = 1")
    suspend fun getActiveCallCountByDirection(direction: CallDirections): Int

    @Query("SELECT AVG(CASE WHEN connectTime IS NOT NULL AND endTime IS NOT NULL THEN endTime - connectTime ELSE 0 END) FROM call_data WHERE connectTime IS NOT NULL AND endTime IS NOT NULL")
    suspend fun getAverageCallDuration(): Double?

    // === LIMPIEZA ===

    @Query("DELETE FROM call_data WHERE isActive = 0 AND endTime < :threshold")
    suspend fun deleteInactiveCallsOlderThan(threshold: Long)

    @Query("DELETE FROM call_data WHERE isActive = 0")
    suspend fun deleteAllInactiveCalls()

    @Query("DELETE FROM call_data")
    suspend fun deleteAllCallData()

    @Query("DELETE FROM call_data WHERE accountId = :accountId")
    suspend fun deleteCallDataByAccount(accountId: String)

    // === OPERACIONES COMPLEJAS ===

    @Transaction
    suspend fun endCallWithCleanup(callId: String, endTime: Long) {
        endCall(callId, endTime)
        // Aquí podrías agregar lógica adicional de limpieza
    }

    @Query("SELECT * FROM call_data WHERE currentState IN ('CONNECTED', 'STREAMS_RUNNING') AND isActive = 1")
    fun getConnectedCalls(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE currentState IN ('INCOMING_RECEIVED') AND isActive = 1")
    fun getIncomingCalls(): Flow<List<CallDataEntity>>

    @Query("SELECT * FROM call_data WHERE currentState IN ('OUTGOING_INIT', 'OUTGOING_PROGRESS', 'OUTGOING_RINGING') AND isActive = 1")
    fun getOutgoingCalls(): Flow<List<CallDataEntity>>
}