package com.eddyslarez.kmpsiprtc.services.audio

interface AudioManager {
    fun setVibrationPattern(patternName: String)
    fun setIncomingRingtone(path: String)
    fun setOutgoingRingtone(path: String)
    fun playRingtone(syncVibration: Boolean = true)
    fun playOutgoingRingtone()
    fun stopRingtone()
    fun stopOutgoingRingtone()
    fun stopAllRingtones()
    fun isRingtonePlaying(): Boolean
    fun isIncomingRingtonePlaying(): Boolean
    fun isOutgoingRingtonePlaying(): Boolean
    fun isVibrating(): Boolean
    fun getDiagnosticInfo(): String
    fun cleanup()
}
expect fun createAudioManager(): AudioManager

