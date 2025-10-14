package com.eddyslarez.kmpsiprtc.services.audio

interface ResourceUtils {
    fun getDefaultIncomingRingtonePath(): String?
    fun getDefaultOutgoingRingtonePath(): String?
}

expect fun createResourceUtils(): ResourceUtils
