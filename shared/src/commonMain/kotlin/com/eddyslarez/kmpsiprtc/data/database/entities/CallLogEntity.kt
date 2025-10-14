package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallTypes
import com.eddyslarez.kmpsiprtc.utils.formatDuration
import kotlinx.datetime.Clock

@Entity(
    tableName = "call_logs",
    foreignKeys = [
        ForeignKey(
            entity = SipAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["startTime"]),
        Index(value = ["phoneNumber"]),
        Index(value = ["callType"]),
        Index(value = ["direction"]),
        Index(value = ["isRead"])
    ]
)
data class CallLogEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val callId: String,
    val phoneNumber: String,
    val displayName: String? = null,
    val direction: CallDirections,
    val callType: CallTypes,
    val startTime: Long,
    val endTime: Long? = null,
    val duration: Int = 0,
    val isRead: Boolean = false,
    val notes: String? = null,

    // Información técnica
    val sipCode: Int? = null,
    val sipReason: String? = null,
    val localAddress: String? = null,
    val remoteAddress: String? = null,
    val userAgent: String? = null,

    // Calidad de llamada
    val audioQuality: Float = 0.0f,
    val networkLatency: Int = 0,
    val packetLoss: Float = 0.0f,
    val jitter: Int = 0,

    // Metadatos
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds()
) {
    fun getFormattedDuration(): String {
        return formatDuration(duration)
    }


    fun isMissedCall(): Boolean = callType == CallTypes.MISSED
    fun isSuccessfulCall(): Boolean = callType == CallTypes.SUCCESS
    fun isIncomingCall(): Boolean = direction == CallDirections.INCOMING
    fun isOutgoingCall(): Boolean = direction == CallDirections.OUTGOING
}