package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["phoneNumber"], unique = true)
    ]
)
data class ContactEntity(
    @PrimaryKey
    val id: String,
    val phoneNumber: String,
    val displayName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val company: String? = null,
    val notes: String? = null,
    val avatarUrl: String? = null,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val ringtoneUri: String? = null,

    // Estadísticas
    val totalCalls: Int = 0,
    val lastCallTime: Long = 0L,
    val totalCallDuration: Long = 0L,
    val missedCalls: Int = 0,

    // Metadatos
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val syncedAt: Long = 0L,
    val source: String = "manual"
)