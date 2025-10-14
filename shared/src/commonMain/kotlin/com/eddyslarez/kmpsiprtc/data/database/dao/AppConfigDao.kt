package com.eddyslarez.kmpsiprtc.data.database.dao

import androidx.room.Query
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update
import com.eddyslarez.kmpsiprtc.data.database.entities.AppConfigEntity

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

@Dao
interface AppConfigDao {

    @Query("SELECT * FROM app_configuration WHERE id = :configId LIMIT 1")
    suspend fun getConfig(configId: String = "default_config"): AppConfigEntity?

    @Query("SELECT * FROM app_configuration WHERE id = :configId LIMIT 1")
    fun getConfigFlow(configId: String = "default_config"): Flow<AppConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: AppConfigEntity)

    @Update
    suspend fun updateConfig(config: AppConfigEntity)

    @Query("UPDATE app_configuration SET incomingRingtoneUri = :uri, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateIncomingRingtoneUri(
        uri: String?,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET outgoingRingtoneUri = :uri, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateOutgoingRingtoneUri(
        uri: String?,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET defaultDomain = :domain, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateDefaultDomain(
        domain: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET webSocketUrl = :url, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateWebSocketUrl(
        url: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET userAgent = :userAgent, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateUserAgent(
        userAgent: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET enableLogs = :enable, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateEnableLogs(
        enable: Boolean,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET enableAutoReconnect = :enable, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updateEnableAutoReconnect(
        enable: Boolean,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("UPDATE app_configuration SET pingIntervalMs = :interval, updatedAt = :timestamp WHERE id = :configId")
    suspend fun updatePingInterval(
        interval: Long,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
        configId: String = "default_config"
    )

    @Query("DELETE FROM app_configuration WHERE id = :configId")
    suspend fun deleteConfig(configId: String = "default_config")

    @Query("SELECT COUNT(*) FROM app_configuration")
    suspend fun getConfigCount(): Int
}
