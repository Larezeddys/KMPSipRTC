package com.eddyslarez.kmpsiprtc.services.audio

interface AudioManager {
    fun setVibrationPattern(patternName: String)
    fun setIncomingRingtone(path: String)
    fun setOutgoingRingtone(path: String)
    fun playRingtone(syncVibration: Boolean = true)
    /**
     * Aviso de llamada en espera. Android conserva su comportamiento actual; iOS delega el aviso
     * a CallKit y Desktop lo sustituye por un beep corto y atenuado.
     */
    fun playCallWaitingTone() {
        playRingtone(syncVibration = false)
    }
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

