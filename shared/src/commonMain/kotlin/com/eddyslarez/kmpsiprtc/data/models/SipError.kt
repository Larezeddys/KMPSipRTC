package com.eddyslarez.kmpsiprtc.data.models

/**
 * Jerarquia tipada de errores para KmpSipRtc.
 *
 * Permite a los consumidores usar `when` exhaustivo para manejar
 * cada tipo de error de forma especifica en vez de parsear strings.
 *
 * ```kotlin
 * when (error) {
 *     is SipError.NotInitialized -> showSetupScreen()
 *     is SipError.Configuration -> showConfigError(error.field, error.reason)
 *     is SipError.Network -> showRetryDialog()
 *     is SipError.Protocol -> handleSipCode(error.sipCode)
 *     is SipError.Authentication -> navigateToLogin()
 *     is SipError.Media -> handleMediaError(error.type)
 *     is SipError.Matrix -> handleMatrixError()
 *     is SipError.CallNotFound -> showNoCallMessage()
 *     is SipError.Unknown -> showGenericError(error.message)
 * }
 * ```
 */
sealed class SipError(
    open val message: String,
    open val cause: Throwable? = null
) {
    /** La libreria no fue inicializada. Llama initialize() primero. */
    data class NotInitialized(
        override val message: String = "Library not initialized. Call initialize() first."
    ) : SipError(message)

    /** Configuracion invalida en SipConfig. */
    data class Configuration(
        val field: String,
        val reason: String,
        override val message: String = "Invalid configuration '$field': $reason"
    ) : SipError(message)

    /** Error de red (sin conectividad, timeout, etc). */
    data class Network(
        val code: Int? = null,
        override val message: String,
        override val cause: Throwable? = null
    ) : SipError(message, cause)

    /** Error de protocolo SIP (403, 486, 503, etc). */
    data class Protocol(
        val sipCode: Int,
        val sipReason: String,
        override val message: String = "SIP $sipCode: $sipReason"
    ) : SipError(message)

    /** Error de autenticacion SIP. */
    data class Authentication(
        val username: String,
        val domain: String,
        override val message: String = "Authentication failed for $username@$domain"
    ) : SipError(message)

    /** Error de media/audio (microfono, speaker, WebRTC, grabacion). */
    data class Media(
        val type: MediaErrorType,
        override val message: String,
        override val cause: Throwable? = null
    ) : SipError(message, cause)

    /** Error especifico de Matrix. */
    data class Matrix(
        override val message: String,
        override val cause: Throwable? = null
    ) : SipError(message, cause)

    /** No se encontro la llamada con el callId especificado. */
    data class CallNotFound(
        val callId: String,
        override val message: String = "Call not found: $callId"
    ) : SipError(message)

    /** Error generico/desconocido. */
    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : SipError(message, cause)

    /** Convierte a Exception para interoperabilidad con codigo existente. */
    fun toException(): Exception = Exception(message, cause)

    companion object {
        /** Crea SipError desde un codigo SIP. */
        fun fromSipCode(sipCode: Int, reason: String = ""): SipError {
            return when (sipCode) {
                401, 407 -> Authentication("", "", "SIP $sipCode: ${reason.ifEmpty { "Authentication Required" }}")
                403 -> Protocol(sipCode, reason.ifEmpty { "Forbidden" })
                404 -> Protocol(sipCode, reason.ifEmpty { "Not Found" })
                408 -> Protocol(sipCode, reason.ifEmpty { "Request Timeout" })
                480 -> Protocol(sipCode, reason.ifEmpty { "Temporarily Unavailable" })
                486 -> Protocol(sipCode, reason.ifEmpty { "Busy Here" })
                603 -> Protocol(sipCode, reason.ifEmpty { "Decline" })
                in 500..599 -> Protocol(sipCode, reason.ifEmpty { "Server Error" })
                else -> Protocol(sipCode, reason.ifEmpty { "Unknown SIP Error" })
            }
        }

        /** Crea SipError desde un CallErrorReason existente. */
        fun fromCallErrorReason(reason: CallErrorReason, sipCode: Int? = null): SipError {
            return when (reason) {
                CallErrorReason.NONE -> Unknown("No error")
                CallErrorReason.BUSY -> Protocol(sipCode ?: 486, "Busy Here")
                CallErrorReason.NO_ANSWER -> Protocol(sipCode ?: 408, "No Answer")
                CallErrorReason.REJECTED -> Protocol(sipCode ?: 603, "Rejected")
                CallErrorReason.TEMPORARILY_UNAVAILABLE -> Protocol(sipCode ?: 480, "Temporarily Unavailable")
                CallErrorReason.NOT_FOUND -> Protocol(sipCode ?: 404, "Not Found")
                CallErrorReason.FORBIDDEN -> Protocol(sipCode ?: 403, "Forbidden")
                CallErrorReason.NETWORK_ERROR -> Network(message = "Network error")
                CallErrorReason.AUTHENTICATION_FAILED -> Authentication("", "", "Authentication failed")
                CallErrorReason.SERVER_ERROR -> Protocol(sipCode ?: 500, "Server Error")
                CallErrorReason.MEDIA_ERROR -> Media(MediaErrorType.UNKNOWN, "Media error")
                CallErrorReason.UNKNOWN -> Unknown("Unknown error")
            }
        }

        /** Crea SipError desde una Exception generica. */
        fun from(e: Throwable): SipError {
            return Unknown(e.message ?: "Unknown error", e)
        }
    }
}

/**
 * Tipos de error de media/audio.
 */
enum class MediaErrorType {
    MICROPHONE_UNAVAILABLE,
    SPEAKER_UNAVAILABLE,
    CODEC_UNSUPPORTED,
    WEBRTC_FAILURE,
    RECORDING_FAILURE,
    DEVICE_NOT_FOUND,
    PERMISSION_DENIED,
    UNKNOWN
}
