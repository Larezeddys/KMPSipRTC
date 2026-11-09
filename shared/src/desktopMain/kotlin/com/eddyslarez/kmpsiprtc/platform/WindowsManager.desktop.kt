package com.eddyslarez.kmpsiprtc.platform

import java.awt.Frame
import javax.swing.SwingUtilities

actual fun createWindowManager(): WindowManager = DesktopWindowManager()

/**
 * Implementación mínima: la app de escritorio gestiona su ventana, bandeja y notificaciones.
 * Solo se conserva traer al frente la ventana registrada.
 */
class DesktopWindowManager : WindowManager {

    @Volatile
    private var window: Frame? = null

    override fun registerComposeWindow(window: Any) {
        this.window = window as? Frame
    }

    override fun bringToFront() {
        val target = window ?: return
        SwingUtilities.invokeLater {
            target.isVisible = true
            target.toFront()
            target.requestFocus()
        }
    }

    override fun showNotification(title: String, message: String, iconPath: String?) = Unit

    override fun incomingCall(callerName: String, callerNumber: String) = Unit

    override fun cleanup() {
        window = null
    }
}
