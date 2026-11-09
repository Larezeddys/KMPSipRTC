package com.eddyslarez.kmpsiprtc.data.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/** Fallo al arrancar el motor de medios (WebRTC) de la plataforma. */
sealed class SipEngineInitError(
    open val message: String,
    open val cause: Throwable? = null
) {
    /** No se pudo cargar la librería nativa (falta el classifier del SO/arquitectura, arquitectura no soportada...). */
    data class NativeLibraryUnavailable(
        val platform: String,
        override val message: String,
        override val cause: Throwable? = null
    ) : SipEngineInitError(message, cause)

    /** El módulo de audio (ADM) no arrancó. */
    data class AudioDeviceModuleFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : SipEngineInitError(message, cause)

    /** El resto de fallos al crear la factoría de conexiones. */
    data class FactoryCreationFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : SipEngineInitError(message, cause)
}

/** Canal interno por el que el motor de cada plataforma informa de su fallo de arranque. */
internal object SipEngineErrors {
    private val _lastError = MutableStateFlow<SipEngineInitError?>(null)
    val lastError: StateFlow<SipEngineInitError?> = _lastError.asStateFlow()

    @Volatile
    var listener: ((SipEngineInitError) -> Unit)? = null

    fun report(error: SipEngineInitError) {
        _lastError.value = error
        runCatching { listener?.invoke(error) }
    }

    fun clear() {
        _lastError.value = null
    }
}
