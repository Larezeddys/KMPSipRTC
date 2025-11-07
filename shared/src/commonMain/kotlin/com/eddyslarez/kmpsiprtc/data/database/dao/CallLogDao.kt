package com.eddyslarez.kmpsiprtc.data.database.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.eddyslarez.kmpsiprtc.data.database.entities.CallDataEntity
import com.eddyslarez.kmpsiprtc.data.database.entities.CallLogEntity
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import kotlin.time.ExperimentalTime

@Dao
interface CallLogDao {

    // === OPERACIONES BÁSICAS ===

    @Query("SELECT * FROM call_logs ORDER BY startTime DESC")
    fun getAllCallLogs(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs ORDER BY startTime DESC LIMIT :limit")
    fun getRecentCallLogs(limit: Int = 50): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE id = :callLogId")
    suspend fun getCallLogById(callLogId: String): CallLogEntity?

    @Query("SELECT * FROM call_logs WHERE accountId = :accountId ORDER BY startTime DESC")
    fun getCallLogsByAccount(accountId: String): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: CallLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLogs(callLogs: List<CallLogEntity>)

    @Update
    suspend fun updateCallLog(callLog: CallLogEntity)

    @Delete
    suspend fun deleteCallLog(callLog: CallLogEntity)

    @Query("DELETE FROM call_logs WHERE id = :callLogId")
    suspend fun deleteCallLogById(callLogId: String)

    // === FILTROS POR TIPO ===

    @Query("SELECT * FROM call_logs WHERE callType = :callType ORDER BY startTime DESC")
    fun getCallLogsByType(callType: CallTypes): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE direction = :direction ORDER BY startTime DESC")
    fun getCallLogsByDirection(direction: CallDirections): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE callType = 'MISSED' ORDER BY startTime DESC")
    fun getMissedCalls(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE callType = 'SUCCESS' ORDER BY startTime DESC")
    fun getSuccessfulCalls(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE direction = 'INCOMING' ORDER BY startTime DESC")
    fun getIncomingCalls(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE direction = 'OUTGOING' ORDER BY startTime DESC")
    fun getOutgoingCalls(): Flow<List<CallLogEntity>>

    // === FILTROS POR CONTACTO ===

    @Query("SELECT * FROM call_logs WHERE phoneNumber = :phoneNumber ORDER BY startTime DESC")
    fun getCallLogsForNumber(phoneNumber: String): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE displayName = :displayName ORDER BY startTime DESC")
    fun getCallLogsForContact(displayName: String): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE phoneNumber LIKE '%' || :query || '%' OR displayName LIKE '%' || :query || '%' ORDER BY startTime DESC")
    fun searchCallLogs(query: String): Flow<List<CallLogEntity>>

    // === FILTROS POR FECHA ===

    @Query("SELECT * FROM call_logs WHERE startTime >= :startTime AND startTime <= :endTime ORDER BY startTime DESC")
    fun getCallLogsByDateRange(startTime: Long, endTime: Long): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE startTime >= :timestamp ORDER BY startTime DESC")
    fun getCallLogsSince(timestamp: Long): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE DATE(startTime/1000, 'unixepoch') = DATE(:timestamp/1000, 'unixepoch') ORDER BY startTime DESC")
    fun getCallLogsForDay(timestamp: Long): Flow<List<CallLogEntity>>

    // === ESTADÍSTICAS ===

    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun getTotalCallCount(): Int

    @Query("SELECT COUNT(*) FROM call_logs WHERE callType = :callType")
    suspend fun getCallCountByType(callType: CallTypes): Int

    @Query("SELECT COUNT(*) FROM call_logs WHERE direction = :direction")
    suspend fun getCallCountByDirection(direction: CallDirections): Int

    @Query("SELECT COUNT(*) FROM call_logs WHERE accountId = :accountId")
    suspend fun getCallCountByAccount(accountId: String): Int

    @Query("SELECT SUM(duration) FROM call_logs WHERE callType = 'SUCCESS'")
    suspend fun getTotalCallDuration(): Long?

    @Query("SELECT AVG(duration) FROM call_logs WHERE callType = 'SUCCESS' AND duration > 0")
    suspend fun getAverageCallDuration(): Double?

    @Query("SELECT phoneNumber, COUNT(*) as count FROM call_logs GROUP BY phoneNumber ORDER BY count DESC LIMIT :limit")
    suspend fun getMostCalledNumbers(limit: Int = 10): List<PhoneNumberCount>

    @Query("SELECT COUNT(*) FROM call_logs WHERE isRead = 0")
    suspend fun getUnreadCallCount(): Int

    @Query("SELECT COUNT(*) FROM call_logs WHERE callType = 'MISSED' AND isRead = 0")
    suspend fun getUnreadMissedCallCount(): Int

    // === OPERACIONES DE LECTURA ===

    @OptIn(ExperimentalTime::class)
    @Query("UPDATE call_logs SET isRead = 1, updatedAt = :timestamp WHERE id = :callLogId")
    suspend fun markAsRead(callLogId: String, timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds())

    @OptIn(ExperimentalTime::class)
    @Query("UPDATE call_logs SET isRead = 1, updatedAt = :timestamp WHERE phoneNumber = :phoneNumber")
    suspend fun markAllAsReadForNumber(phoneNumber: String, timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds())

    @OptIn(ExperimentalTime::class)
    @Query("UPDATE call_logs SET isRead = 1, updatedAt = :timestamp")
    suspend fun markAllAsRead(timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds())

    // === OPERACIONES DE NOTAS ===

    @OptIn(ExperimentalTime::class)
    @Query("UPDATE call_logs SET notes = :notes, updatedAt = :timestamp WHERE id = :callLogId")
    suspend fun updateNotes(callLogId: String, notes: String?, timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds())

    // === LIMPIEZA ===

    @Query("DELETE FROM call_logs")
    suspend fun deleteAllCallLogs()

    @Query("DELETE FROM call_logs WHERE accountId = :accountId")
    suspend fun deleteCallLogsByAccount(accountId: String)

    @Query("DELETE FROM call_logs WHERE startTime < :timestamp")
    suspend fun deleteCallLogsOlderThan(timestamp: Long)

    @Query("DELETE FROM call_logs WHERE phoneNumber = :phoneNumber")
    suspend fun deleteCallLogsForNumber(phoneNumber: String)

    @Query("DELETE FROM call_logs WHERE callType = :callType")
    suspend fun deleteCallLogsByType(callType: CallTypes)

    // Mantener solo los N registros más recientes
    @Query("DELETE FROM call_logs WHERE id NOT IN (SELECT id FROM call_logs ORDER BY startTime DESC LIMIT :limit)")
    suspend fun keepOnlyRecentCallLogs(limit: Int)

    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun getCallLogCount(): Int
    // === CONSULTAS AVANZADAS ===

    @Query("""
        SELECT phoneNumber, 
               COUNT(*) as totalCalls,
               SUM(CASE WHEN callType = 'SUCCESS' THEN 1 ELSE 0 END) as successfulCalls,
               SUM(CASE WHEN callType = 'MISSED' THEN 1 ELSE 0 END) as missedCalls,
               SUM(CASE WHEN direction = 'INCOMING' THEN 1 ELSE 0 END) as incomingCalls,
               SUM(CASE WHEN direction = 'OUTGOING' THEN 1 ELSE 0 END) as outgoingCalls,
               SUM(duration) as totalDuration,
               MAX(startTime) as lastCallTime
        FROM call_logs 
        WHERE phoneNumber = :phoneNumber
        GROUP BY phoneNumber
    """)
    suspend fun getCallStatisticsForNumber(phoneNumber: String): CallStatistics?

    @Query("""
        SELECT DATE(startTime/1000, 'unixepoch') as date,
               COUNT(*) as callCount,
               SUM(duration) as totalDuration
        FROM call_logs 
        WHERE startTime >= :startTime
        GROUP BY DATE(startTime/1000, 'unixepoch')
        ORDER BY date DESC
    """)
    suspend fun getDailyCallStatistics(startTime: Long): List<DailyCallStats>
}

/**
 * Clases de datos para estadísticas
 */
data class PhoneNumberCount(
    val phoneNumber: String,
    val count: Int
)

data class CallStatistics(
    val phoneNumber: String,
    val totalCalls: Int,
    val successfulCalls: Int,
    val missedCalls: Int,
    val incomingCalls: Int,
    val outgoingCalls: Int,
    val totalDuration: Long,
    val lastCallTime: Long
)

data class DailyCallStats(
    val date: String,
    val callCount: Int,
    val totalDuration: Long
)