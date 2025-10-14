package com.eddyslarez.kmpsiprtc.platform

// commonMain

interface WindowManager {
    fun registerComposeWindow(window: Any)
    fun bringToFront()
    fun showNotification(title: String, message: String, iconPath: String? = null)
    fun incomingCall(callerName: String, callerNumber: String)
    fun cleanup()
}

expect fun createWindowManager(): WindowManager
