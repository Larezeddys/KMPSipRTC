package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager

class CallHoldManager(private val webRtcManager: WebRtcManager) {

    private var isCallOnHold = false
    private var originalLocalSdp: String? = null

    /**
     * Puts a call on hold by modifying the SDP.
     * @return el SDP modificado para hold, o null en caso de error.
     */
    suspend fun holdCall(): String? {
        try {
            if (isCallOnHold) return originalLocalSdp

            val currentSdp = webRtcManager.getLocalDescription() ?: return null
            originalLocalSdp = currentSdp

            val holdSdp = modifySdpForHold(currentSdp)
            val result = webRtcManager.applyModifiedSdp(holdSdp)

            if (result) {
                webRtcManager.setAudioEnabled(false)
                isCallOnHold = true
                logInfo("Call placed on hold successfully")
                return holdSdp
            }
            return null
        } catch (e: Exception) {
            logError("Error putting call on hold: ${e.message}")
            return null
        }
    }

    /**
     * Resumes a call that was previously on hold.
     * @return el SDP modificado para resume, o null en caso de error.
     */
    suspend fun resumeCall(): String? {
        try {
            if (!isCallOnHold) return originalLocalSdp

            val baseSdp = originalLocalSdp ?: webRtcManager.getLocalDescription() ?: return null
            val resumeSdp = modifySdpForResume(baseSdp)
            val result = webRtcManager.applyModifiedSdp(resumeSdp)

            if (result) {
                webRtcManager.setAudioEnabled(true)
                isCallOnHold = false
                logInfo("Call resumed successfully")
                return resumeSdp
            }
            return null
        } catch (e: Exception) {
            logError("Error resuming call: ${e.message}")
            return null
        }
    }

    /**
     * Modifies SDP to put a call on hold (a=sendrecv → a=sendonly)
     */
    private fun modifySdpForHold(sdp: String): String {
        return sdp.replace(Regex("a=sendrecv"), "a=sendonly")
            .replace(Regex("a=recvonly"), "a=inactive")
    }

    /**
     * Modifies SDP to resume a call (a=sendonly → a=sendrecv)
     */
    private fun modifySdpForResume(sdp: String): String {
        return sdp.replace(Regex("a=sendonly"), "a=sendrecv")
            .replace(Regex("a=inactive"), "a=recvonly")
    }

    private fun logInfo(message: String) {
        log.d(tag = "CallHoldManager") { message }
    }

    private fun logError(message: String) {
        log.e(tag = "CallHoldManager") { message }
    }
}