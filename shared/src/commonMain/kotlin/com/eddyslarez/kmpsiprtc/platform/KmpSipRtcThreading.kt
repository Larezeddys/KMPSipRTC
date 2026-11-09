package com.eddyslarez.kmpsiprtc.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.concurrent.Volatile

/**
 * Dispatcher del trabajo interno de [com.eddyslarez.kmpsiprtc.KmpSipRtc] y del que entrega sus
 * callbacks (`onComplete`, listeners). Por defecto: `Dispatchers.Main` en Android/iOS y
 * `Dispatchers.Default` en escritorio, para no ocupar el hilo de eventos de Swing.
 *
 * Se lee al crear la instancia: hay que fijarlo antes del primer `KmpSipRtc.getInstance()`.
 */
object KmpSipRtcThreading {
    @Volatile
    var dispatcherOverride: CoroutineDispatcher? = null

    val dispatcher: CoroutineDispatcher
        get() = dispatcherOverride ?: platformDefaultSipDispatcher()
}

internal expect fun platformDefaultSipDispatcher(): CoroutineDispatcher

/** `true` donde los gestores de registro y reconexión comparten el NetworkManager del núcleo. */
internal expect fun platformSharesNetworkManager(): Boolean
