package com.eddyslarez.kmpsiprtc.services.unified

import com.eddyslarez.kmpsiprtc.core.CallManager
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.matrix.MatrixManager
import com.eddyslarez.kmpsiprtc.services.livekit.LiveKitCallManager
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


/**
 * Router que decide si usar SIP, Matrix P2P o LiveKit SFU para una llamada
 */
class UnifiedCallRouter(
    private val sipCallManager: CallManager,
    private val matrixManager: MatrixManager,
    private val livekitCallManager: LiveKitCallManager? = null
) {
    private val TAG = "UnifiedCallRouter"

    /**
     * Determina el tipo de llamada basandose en el destino.
     * Si hay LiveKitCallManager configurado, las llamadas Matrix van por LiveKit SFU.
     * Si no, van por P2P (comportamiento original).
     */
    fun determineCallType(destination: String): CallType {
        return when {
            // Si es un Matrix ID (@user:domain) o room ID (!room:domain)
            (destination.startsWith("@") || destination.startsWith("!")) && destination.contains(":") -> {
                if (livekitCallManager != null) {
                    log.d { "Routing to LiveKit SFU for: $destination" }
                    CallType.LIVEKIT_SFU
                } else {
                    log.d { "Routing to Matrix P2P for: $destination" }
                    CallType.MATRIX_INTERNAL
                }
            }
            // Todo lo demas va por SIP
            else -> {
                log.d { "Routing to SIP for: $destination" }
                CallType.SIP_EXTERNAL
            }
        }
    }

    /**
     * Realiza llamada usando el protocolo correcto
     */
    @OptIn(ExperimentalTime::class)
    suspend fun makeCall(
        destination: String,
        isVideo: Boolean = false,
        accountInfo: AccountInfo? = null
    ): Result<UnifiedCallInfo> {
        val callType = determineCallType(destination)

        return when (callType) {
            CallType.LIVEKIT_SFU -> {
                // Llamada via LiveKit SFU (el audio pasa por el servidor para grabacion)
                try {
                    val lkManager = livekitCallManager
                        ?: return Result.failure(Exception("LiveKit not configured"))

                    // Primero crear la sala Matrix si es un @user
                    val roomId = if (destination.startsWith("@")) {
                        // Crear/obtener sala directa con el usuario
                        val matrixCall = matrixManager.startVoiceCall(destination).getOrThrow()
                        matrixCall.roomId
                    } else {
                        destination // Ya es un room ID
                    }

                    // Obtener userId de Matrix
                    val userId = matrixManager.getUserId() ?: "@unknown:localhost"

                    // Unirse a LiveKit SFU
                    lkManager.joinCall(roomId, userId).getOrThrow()

                    val callId = "lk-${roomId.hashCode().toString(16)}-${Clock.System.now().toEpochMilliseconds()}"

                    Result.success(
                        UnifiedCallInfo(
                            callId = callId,
                            type = CallType.LIVEKIT_SFU,
                            phoneNumber = destination,
                            displayName = null,
                            roomId = roomId
                        )
                    )
                } catch (e: Exception) {
                    log.e { "Error en llamada LiveKit SFU: ${e.message}" }
                    Result.failure(e)
                }
            }
            CallType.MATRIX_INTERNAL -> {
                // Llamada Matrix P2P (sin grabacion en servidor)
                if (isVideo) {
                    matrixManager.startVideoCall(destination).map { matrixCall ->
                        UnifiedCallInfo(
                            callId = matrixCall.callId,
                            type = CallType.MATRIX_INTERNAL,
                            phoneNumber = destination,
                            displayName = null,
                            roomId = matrixCall.roomId
                        )
                    }
                } else {
                    matrixManager.startVoiceCall(destination).map { matrixCall ->
                        UnifiedCallInfo(
                            callId = matrixCall.callId,
                            type = CallType.MATRIX_INTERNAL,
                            phoneNumber = destination,
                            displayName = null,
                            roomId = matrixCall.roomId
                        )
                    }
                }
            }
            CallType.SIP_EXTERNAL -> {
                // Llamada SIP
                try {
                    requireNotNull(accountInfo) { "SIP account info required for external calls" }
                    sipCallManager.makeCall(destination, accountInfo)
                    Result.success(
                        UnifiedCallInfo(
                            callId = accountInfo.currentCallData.value?.callId ?: "",
                            type = CallType.SIP_EXTERNAL,
                            phoneNumber = destination,
                            displayName = null
                        )
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * Responder llamada Matrix
     */
    suspend fun answerMatrixCall(callId: String): Result<Unit> {
        return matrixManager.answerCall(callId)
    }

    /**
     * Colgar llamada (Matrix P2P o LiveKit SFU)
     */
    suspend fun hangupMatrixCall(callId: String): Result<Unit> {
        // Si hay una llamada LiveKit activa, desconectar de LiveKit
        if (livekitCallManager?.isConnected() == true) {
            livekitCallManager.leaveCall()
        }
        return matrixManager.hangupCall(callId)
    }

    /**
     * Colgar llamada LiveKit SFU
     */
    suspend fun hangupLiveKitCall(): Result<Unit> {
        return try {
            livekitCallManager?.leaveCall()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Verificar si hay una llamada Matrix activa
     */
    fun hasActiveMatrixCall(): Boolean {
        return matrixManager.activeCall.value != null
    }

    /**
     * Verificar si hay una llamada LiveKit activa
     */
    fun hasActiveLiveKitCall(): Boolean {
        return livekitCallManager?.isConnected() == true
    }
}
