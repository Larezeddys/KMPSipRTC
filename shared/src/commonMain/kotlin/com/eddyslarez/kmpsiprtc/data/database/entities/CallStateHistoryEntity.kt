package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eddyslarez.kmpsiprtc.data.models.CallErrorReason
import com.eddyslarez.kmpsiprtc.data.models.CallState
@Entity(
    tableName = "call_state_history",
    indices = [
        Index(value = ["callId"]),
        Index(value = ["timestamp"])
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
    val duration: Long = 0L,
    val additionalInfo: String? = null
)