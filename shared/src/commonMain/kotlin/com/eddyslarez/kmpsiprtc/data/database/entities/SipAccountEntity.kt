package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eddyslarez.kmpsiprtc.data.models.RegistrationState
import kotlinx.datetime.Clock


@Entity(
    tableName = "sip_accounts",
    indices = [
        Index(value = ["username", "domain"], unique = true),
        Index(value = ["isActive"])
    ]
)
data class SipAccountEntity(
    @PrimaryKey
    val id: String,
    val username: String,
    val password: String,
    val domain: String,
    val displayName: String? = null,
    val userAgent: String? = null,
    val pushToken: String? = null,
    val pushProvider: String? = null,
    val registrationState: RegistrationState = RegistrationState.NONE,
    val lastRegistrationTime: Long = 0L,
    val registrationExpiry: Long = 0L,
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),

    // Configuración avanzada
    val autoRegister: Boolean = true,
    val enablePush: Boolean = true,
    val registrationTimeout: Int = 30000,
    val keepAliveInterval: Int = 60000,

    // Estadísticas
    val totalCalls: Int = 0,
    val successfulCalls: Int = 0,
    val failedCalls: Int = 0,
    val lastCallTime: Long = 0L,

    // Información de conexión
    val lastErrorMessage: String? = null,
    val connectionQuality: Float = 0.0f,
    val averageLatency: Int = 0
) {
    fun getAccountKey(): String = "$username@$domain"

    fun isRegistered(): Boolean = registrationState == RegistrationState.OK

    fun isExpired(): Boolean {
        return registrationExpiry > 0 && Clock.System.now().toEpochMilliseconds() > registrationExpiry
    }
}