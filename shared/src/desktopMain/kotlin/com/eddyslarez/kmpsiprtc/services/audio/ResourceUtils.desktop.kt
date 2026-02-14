package com.eddyslarez.kmpsiprtc.services.audio


actual fun createResourceUtils(): ResourceUtils =  DesktopResourceUtils()

class DesktopResourceUtils():ResourceUtils {
    override fun getDefaultIncomingRingtonePath(): String? {
        // Preferir WAV (soportado nativamente por Java AudioSystem) sobre MP3
        val resource = this::class.java.getResource("/call.wav")
            ?: this::class.java.getResource("/call.mp3")
        return resource?.toString() ?: run {
            println("Warning: call.wav/call.mp3 not found in resources")
            null
        }
    }

    override fun getDefaultOutgoingRingtonePath(): String? {
        val resource = this::class.java.getResource("/ringback.wav")
            ?: this::class.java.getResource("/ringback.mp3")
        return resource?.toString() ?: run {
            println("Warning: ringback.wav/ringback.mp3 not found in resources")
            null
        }
    }
}