package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.ExperimentalTime


@Entity(tableName = "app_configuration")
data class AppConfigEntity @OptIn(ExperimentalTime::class) constructor(
    @PrimaryKey
    val id: String = "default_config",
    val incomingRingtoneUri: String? = null,
    val outgoingRingtoneUri: String? = null,
    val defaultDomain: String = "",
    val webSocketUrl: String = "",
    val userAgent: String = "",
    val enableLogs: Boolean = true,
    val enableAutoReconnect: Boolean = true,
    val pingIntervalMs: Long = 30000L,
    val createdAt: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
)
