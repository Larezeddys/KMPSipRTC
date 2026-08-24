package com.eddyslarez.kmpsiprtc.services.conference

/**
 * En escritorio el marcador distingue el SO concreto (windows / mac / linux),
 * porque el cliente web pinta un logo distinto para cada uno.
 */
actual fun currentPlatformMarker(): String {
    val osName = (System.getProperty("os.name") ?: "").lowercase()
    return when {
        osName.contains("win") -> PLATFORM_MARKER_WINDOWS
        osName.contains("mac") -> PLATFORM_MARKER_MAC
        else -> PLATFORM_MARKER_LINUX
    }
}
