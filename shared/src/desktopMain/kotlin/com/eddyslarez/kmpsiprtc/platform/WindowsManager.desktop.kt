package com.eddyslarez.kmpsiprtc.platform

import androidx.compose.ui.awt.ComposeWindow
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.util.Timer
import java.util.TimerTask
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString


actual fun createWindowManager(): WindowManager = DesktopWindowManager()


class DesktopWindowManager : WindowManager {

    private var window: Frame? = null
    private var trayIcon: TrayIcon? = null
    private var dialog: JDialog? = null

    override fun registerComposeWindow(window: Any) {
        if (window is ComposeWindow) {
            this.window = window
        }
    }

    override fun bringToFront() {
        SwingUtilities.invokeLater {
            window?.apply {
                isVisible = true
                toFront()
                requestFocus()
            }
        }
    }

    override fun showNotification(title: String, message: String, iconPath: String?) {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        if (trayIcon == null) {
            val icon = Toolkit.getDefaultToolkit().getImage(iconPath ?: "default_icon.png")
            trayIcon = TrayIcon(icon, title)
            trayIcon!!.isImageAutoSize = true
            tray.add(trayIcon)
        }
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    override fun incomingCall(callerName: String, callerNumber: String) {
        if (window == null) return
        SwingUtilities.invokeLater {
            val parentWindow = window!!
            dialog = JDialog(parentWindow, "Incoming Call", true).apply {
                setSize(300, 150)
                setLocationRelativeTo(parentWindow)
                layout = BorderLayout()

                val label = JLabel("Incoming call from $callerName ($callerNumber)", JLabel.CENTER)
                val button = JButton("Answer").apply {
                    addActionListener {
                        dispose()
                        bringToFront()
                    }
                }

                add(label, BorderLayout.CENTER)
                add(button, BorderLayout.SOUTH)
                isVisible = true
            }
        }
    }

    override fun cleanup() {
        trayIcon?.let {
            SystemTray.getSystemTray().remove(it)
            trayIcon = null
        }
    }
}
