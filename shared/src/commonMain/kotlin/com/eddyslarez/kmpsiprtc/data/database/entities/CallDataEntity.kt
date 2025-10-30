package com.eddyslarez.kmpsiprtc.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallState
import kotlinx.datetime.Clock
@Entity(
    tableName = "call_data",
    indices = [
        Index(value = ["callId"], unique = true),
        Index(value = ["accountId"]),
        Index(value = ["isActive"])
    ]
)
data class CallDataEntity(
    @PrimaryKey
    val callId: String,
    val accountId: String,
    val fromNumber: String,
    val toNumber: String,
    val direction: CallDirections,
    val currentState: CallState,
    val startTime: Long,
    val connectTime: Long? = null,
    val endTime: Long? = null,
    val isActive: Boolean = true,

    // Tags SIP
    val fromTag: String? = null,
    val toTag: String? = null,
    val inviteFromTag: String = "",
    val inviteToTag: String = "",

    // Información de contacto remoto
    val remoteContactUri: String? = null,
    val remoteDisplayName: String = "",
    val remoteUserAgent: String? = null,

    // SDP y WebRTC
    val localSdp: String = "",
    val remoteSdp: String = "",
    val iceUfrag: String? = null,
    val icePwd: String? = null,
    val dtlsFingerprint: String? = null,

    // Headers SIP
    val viaHeader: String = "",
    val inviteViaBranch: String = "",
    val lastCSeqValue: Int = 0,

    // Estado de la llamada
    val isOnHold: Boolean = false,
    val isMuted: Boolean = false,
    val isRecording: Boolean = false,

    // Mensajes originales
    val originalInviteMessage: String = "",
    val originalCallInviteMessage: String = "",

    // Información de calidad
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val bandwidth: Int = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesTransferred: Long = 0,

    // Metadatos
    val md5Hash: String = "",
    val sipName: String = "",
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds()
)