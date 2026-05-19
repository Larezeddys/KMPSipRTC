package com.eddyslarez.kmpsiprtc.platform

/**
 * Desktop no tiene un concepto puro de "background" como móvil: la app sigue corriendo
 * aunque la ventana esté minimizada o pierda el foco. Por eso NO emitimos
 * EnterBackground/EnterForeground — eso provocaría re-registros SIP innecesarios al
 * cambiar de ventana.
 *
 * Si una app desktop necesita reaccionar a iconify/deiconify o window focus, debe
 * registrar sus propios listeners de Swing/JavaFX y llamar a switchToPushMode/
 * switchToForegroundMode manualmente cuando aplique.
 */
actual class PlatformRegistration actual constructor() {
    actual fun setupNotificationObservers(listener: AppLifecycleListener) {
        // Solo emitimos FinishedLaunching para paridad con Android/iOS y para que cualquier
        // consumidor reactivo se entere del arranque del proceso.
        listener.onEvent(AppLifecycleEvent.FinishedLaunching)
    }
}
