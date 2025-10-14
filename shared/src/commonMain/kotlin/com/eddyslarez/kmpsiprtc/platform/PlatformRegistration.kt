package com.eddyslarez.kmpsiprtc.platform

enum class AppLifecycleEvent {
    EnterBackground,
    FinishedLaunching,
    EnterForeground,
    WillTerminate,
    ProtectedDataAvailable,           // en Android lo mapearemos a USER_PRESENT
    ProtectedDataWillBecomeUnavailable // en Android lo mapearemos a SCREEN_OFF
}

interface AppLifecycleListener {
    fun onEvent(event: AppLifecycleEvent)
}

expect class PlatformRegistration() {
    fun setupNotificationObservers(listener: AppLifecycleListener)
}