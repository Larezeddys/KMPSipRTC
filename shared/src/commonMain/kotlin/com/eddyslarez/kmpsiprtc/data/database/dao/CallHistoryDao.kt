package com.eddyslarez.kmpsiprtc.data.database.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eddyslarez.kmpsiprtc.data.database.entities.CallStateHistoryEntity
import com.eddyslarez.kmpsiprtc.data.models.CallState

@Dao
interface CallHistoryDao {

    // === OPERACIONES BÁSICAS ===

    @Query("SELECT * FROM call_state_history ORDER BY timestamp DESC")
    fun getAllStateHistory(): Flow<List<CallStateHistoryEntity>>

    @Query("SELECT * FROM call_state_history WHERE callId = :callId ORDER BY timestamp ASC")
    fun getStateHistoryForCall(callId: String): Flow<List<CallStateHistoryEntity>>

    @Query("SELECT * FROM call_state_history WHERE id = :historyId")
    suspend fun getStateHistoryById(historyId: String): CallStateHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStateHistory(stateHistory: CallStateHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStateHistoryList(stateHistoryList: List<CallStateHistoryEntity>)

    @Update
    suspend fun updateStateHistory(stateHistory: CallStateHistoryEntity)

    @Delete
    suspend fun deleteStateHistory(stateHistory: CallStateHistoryEntity)

    @Query("DELETE FROM call_state_history WHERE id = :historyId")
    suspend fun deleteStateHistoryById(historyId: String)

    // === CONSULTAS POR ESTADO ===

    @Query("SELECT * FROM call_state_history WHERE state = :state ORDER BY timestamp DESC")
    fun getStateHistoryByState(state: CallState): Flow<List<CallStateHistoryEntity>>

    @Query("SELECT * FROM call_state_history WHERE hasError = 1 ORDER BY timestamp DESC")
    fun getErrorStateHistory(): Flow<List<CallStateHistoryEntity>>

    @Query("SELECT * FROM call_state_history WHERE state IN ('ENDED', 'ERROR') ORDER BY timestamp DESC")
    fun getTerminalStateHistory(): Flow<List<CallStateHistoryEntity>>

    // === CONSULTAS POR FECHA ===

    @Query("SELECT * FROM call_state_history WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getStateHistoryByDateRange(
        startTime: Long,
        endTime: Long
    ): Flow<List<CallStateHistoryEntity>>

    @Query("SELECT * FROM call_state_history WHERE timestamp >= :timestamp ORDER BY timestamp DESC")
    fun getStateHistorySince(timestamp: Long): Flow<List<CallStateHistoryEntity>>

    @Query("SELECT * FROM call_state_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentStateHistory(limit: Int = 100): Flow<List<CallStateHistoryEntity>>

    // === ESTADÍSTICAS ===

    @Query("SELECT COUNT(*) FROM call_state_history")
    suspend fun getTotalStateHistoryCount(): Int

    @Query("SELECT COUNT(*) FROM call_state_history WHERE callId = :callId")
    suspend fun getStateHistoryCountForCall(callId: String): Int

    @Query("SELECT COUNT(*) FROM call_state_history WHERE state = :state")
    suspend fun getStateHistoryCountByState(state: CallState): Int

    @Query("SELECT COUNT(*) FROM call_state_history WHERE hasError = 1")
    suspend fun getErrorStateHistoryCount(): Int

    @Query("SELECT AVG(duration) FROM call_state_history WHERE duration > 0")
    suspend fun getAverageStateDuration(): Double?

    @Query("SELECT state, COUNT(*) as count FROM call_state_history GROUP BY state ORDER BY count DESC")
    suspend fun getStateDistribution(): List<StateCount>

    // === ANÁLISIS DE TRANSICIONES ===

    @Query("SELECT previousState, state, COUNT(*) as count FROM call_state_history WHERE previousState IS NOT NULL GROUP BY previousState, state ORDER BY count DESC")
    suspend fun getStateTransitions(): List<StateTransition>

    @Query("SELECT * FROM call_state_history WHERE callId = :callId AND state = :fromState AND previousState = :toState")
    suspend fun getSpecificTransition(
        callId: String,
        fromState: CallState,
        toState: CallState
    ): List<CallStateHistoryEntity>

    // === DURACIÓN EN ESTADOS ===

    @Query("SELECT SUM(duration) FROM call_state_history WHERE callId = :callId AND state = :state")
    suspend fun getTotalDurationInState(callId: String, state: CallState): Long?

    @Query("SELECT state, SUM(duration) as totalDuration FROM call_state_history WHERE callId = :callId GROUP BY state")
    suspend fun getDurationByStateForCall(callId: String): List<StateDuration>

    // === LIMPIEZA ===

    @Query("DELETE FROM call_state_history WHERE callId = :callId")
    suspend fun deleteStateHistoryForCall(callId: String)

    @Query("DELETE FROM call_state_history WHERE timestamp < :threshold")
    suspend fun deleteStateHistoryOlderThan(threshold: Long)

    @Query("DELETE FROM call_state_history")
    suspend fun deleteAllStateHistory()

    // Mantener solo los N registros más recientes por llamada
    @Query(
        """
        DELETE FROM call_state_history 
        WHERE callId = :callId 
        AND id NOT IN (
            SELECT id FROM call_state_history 
            WHERE callId = :callId 
            ORDER BY timestamp DESC 
            LIMIT :limit
        )
    """
    )
    suspend fun keepOnlyRecentStateHistoryForCall(callId: String, limit: Int)

    // Mantener solo los N registros más recientes globalmente
    @Query(
        """
        DELETE FROM call_state_history 
        WHERE id NOT IN (
            SELECT id FROM call_state_history 
            ORDER BY timestamp DESC 
            LIMIT :limit
        )
    """
    )
    suspend fun keepOnlyRecentStateHistory(limit: Int)

    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun getCallHistoryCount(): Int
    // === CONSULTAS AVANZADAS ===

    @Query(
        """
        SELECT callId, 
               MIN(timestamp) as firstState,
               MAX(timestamp) as lastState,
               COUNT(*) as stateCount,
               SUM(CASE WHEN hasError = 1 THEN 1 ELSE 0 END) as errorCount
        FROM call_state_history 
        WHERE callId = :callId
        GROUP BY callId
    """
    )
    suspend fun getCallSummary(callId: String): CallSummary?

    @Query(
        """
        SELECT * FROM call_state_history 
        WHERE callId = :callId 
        AND state = :state 
        ORDER BY timestamp DESC 
        LIMIT 1
    """
    )
    suspend fun getLastStateOccurrence(callId: String, state: CallState): CallStateHistoryEntity?

    @Query(
        """
        SELECT * FROM call_state_history 
        WHERE callId = :callId 
        AND state = :state 
        ORDER BY timestamp ASC 
        LIMIT 1
    """
    )
    suspend fun getFirstStateOccurrence(callId: String, state: CallState): CallStateHistoryEntity?
}

/**
 * Clases de datos para estadísticas
 */
data class StateCount(
    val state: CallState,
    val count: Int
)

data class StateTransition(
    val previousState: CallState?,
    val state: CallState,
    val count: Int
)

data class StateDuration(
    val state: CallState,
    val totalDuration: Long
)

data class CallSummary(
    val callId: String,
    val firstState: Long,
    val lastState: Long,
    val stateCount: Int,
    val errorCount: Int
)