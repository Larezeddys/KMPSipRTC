package com.eddyslarez.kmpsiprtc.services.screencapture

import java.io.File

/**
 * Decide cómo capturar la pantalla en este equipo.
 *
 * En Linux el capturador nativo de webrtc-java no sirve bajo Wayland (está
 * compilado sin PipeWire y captura por X11, que devuelve negro), así que se usa
 * xdg-desktop-portal. En Windows y macOS el capturador nativo funciona y no se
 * toca.
 */
object DesktopScreenCaptureSupport {

    enum class Backend {
        /** xdg-desktop-portal + PipeWire (Linux). */
        PORTAL,
        /** Capturador nativo de webrtc-java (Windows, macOS y X11 sin portal). */
        WEBRTC_DESKTOP,
        /** No hay forma de capturar acá. */
        NONE,
    }

    private val osName: String get() = System.getProperty("os.name", "").lowercase()

    fun isLinux(): Boolean = osName.contains("linux")

    fun isWayland(): Boolean =
        !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() ||
            System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)

    fun isPortalAvailable(): Boolean {
        if (!isLinux()) return false
        val runtimeDir = System.getenv("XDG_RUNTIME_DIR") ?: return false
        // El portal habla por el bus de sesión y PipeWire por su socket propio.
        val hasBus = !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank() ||
            File(runtimeDir, "bus").exists()
        val hasPipeWire = File(runtimeDir, "pipewire-0").exists()
        return hasBus && hasPipeWire
    }

    fun isGStreamerAvailable(): Boolean = runCatching {
        Class.forName("org.freedesktop.gstreamer.Gst")
        true
    }.getOrDefault(false)

    fun backend(): Backend {
        val forced = System.getProperty("mcn.screenshare.backend")?.lowercase()
        when (forced) {
            "portal" -> return Backend.PORTAL
            "webrtc" -> return Backend.WEBRTC_DESKTOP
        }
        if (!isLinux()) return Backend.WEBRTC_DESKTOP
        if (isPortalAvailable() && isGStreamerAvailable()) return Backend.PORTAL
        // En X11 el capturador nativo todavía sirve; en Wayland daría negro.
        return if (isWayland()) Backend.NONE else Backend.WEBRTC_DESKTOP
    }

    fun isAvailable(): Boolean = backend() != Backend.NONE

    /** Motivo legible cuando no se puede capturar, o null si sí se puede. */
    fun unavailableReason(): String? {
        if (backend() != Backend.NONE) return null
        if (!isPortalAvailable()) return "El escritorio no expone xdg-desktop-portal con PipeWire"
        if (!isGStreamerAvailable()) return "Falta GStreamer con el plugin de PipeWire"
        return "La captura de pantalla no está disponible en este escritorio"
    }
}
