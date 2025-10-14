package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState

@Entity(
    tableName = "call_state_history",
    foreignKeys = [
        ForeignKey(
            entity = CallDataEntity::class,
            parentColumns = ["callId"],
            childColumns = ["callId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["callId"]),
        Index(value = ["timestamp"]),
        Index(value = ["state"]),
        Index(value = ["hasError"])
    ]
)
data class CallStateHistoryEntity(
    @PrimaryKey
    val id: String,
    val callId: String,
    val state: CallState,
    val previousState: CallState? = null,
    val timestamp: Long,
    val errorReason: CallErrorReason = CallErrorReason.NONE,
    val sipCode: Int? = null,
    val sipReason: String? = null,
    val hasError: Boolean = false,
    val duration: Long = 0L, // Duración en este estado
    val additionalInfo: String? = null
) {
    fun isErrorState(): Boolean = hasError || errorReason != CallErrorReason.NONE
    fun isTerminalState(): Boolean = state == CallState.ENDED || state == CallState.ERROR
    fun isActiveState(): Boolean = !isTerminalState() && state != CallState.IDLE
}