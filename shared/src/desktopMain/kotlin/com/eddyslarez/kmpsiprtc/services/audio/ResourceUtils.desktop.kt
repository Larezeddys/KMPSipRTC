package com.eddyslarez.kmpsiprtc.services.audio


actual fun createResourceUtils(): ResourceUtils =  DesktopResourceUtils()

class DesktopResourceUtils():ResourceUtils {
    override fun getDefaultIncomingRingtonePath(): String? {
        // Intentar cargar desde recursos del classpath
        val resource = this::class.java.getResource("/call.mp3")
        return resource?.toString() ?: run {
            println("Warning: call.mp3 not found in resources")
            null
        }
    }

    override fun getDefaultOutgoingRingtonePath(): String? {
        val resource = this::class.java.getResource("/ringback.mp3")
        return resource?.toString() ?: run {
            println("Warning: ringback.mp3 not found in resources")
            null
        }
    }
}