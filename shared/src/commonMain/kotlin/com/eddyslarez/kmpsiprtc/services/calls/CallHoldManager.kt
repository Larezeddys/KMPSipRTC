package com.eddyslarez.kmpsiprtc.services.calls

import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager

internal class CallHoldManager(private val webRtcManager: WebRtcManager) {

    private var isCallOnHold = false
    private var originalLocalSdp: String? = null

    /**
     * Puts a call on hold by modifying the SDP.
     * @param fallbackSdp SDP de respaldo si WebRTC no tiene localDescription (típico en desktop).
     * @return el SDP modificado para hold, o null en caso de error.
     */
    suspend fun holdCall(fallbackSdp: String? = null): String? {
        try {
            if (isCallOnHold) return originalLocalSdp

            // Preferir fallbackSdp (localSdp almacenado por CallManager) sobre
            // getLocalDescription(), ya que en multi-llamada la PC activa puede
            // pertenecer a otra llamada y getLocalDescription() retornaría su SDP.
            val currentSdp = fallbackSdp ?: webRtcManager.getLocalDescription() ?: return null
            originalLocalSdp = currentSdp

            val holdSdp = modifySdpForHold(currentSdp)

            // Intentar aplicar SDP modificado en WebRTC (no crítico: el RE-INVITE SIP es
            // lo que Asterisk usa para activar hold music). NO llamamos setAudioEnabled porque
            // con multi-llamada un único WebRtcManager compartido apagaría el audio de todas
            // las llamadas simultáneamente.
            webRtcManager.applyModifiedSdp(holdSdp)

            isCallOnHold = true
            logInfo("Call placed on hold successfully")
            return holdSdp
        } catch (e: Exception) {
            logError("Error putting call on hold: ${e.message}")
            return null
        }
    }

    /**
     * Resumes a call that was previously on hold.
     * @param fallbackSdp SDP de respaldo si originalLocalSdp y WebRTC no tienen SDP.
     * @return el SDP modificado para resume, o null en caso de error.
     */
    suspend fun resumeCall(fallbackSdp: String? = null): String? {
        try {
            if (!isCallOnHold) return originalLocalSdp

            val baseSdp = originalLocalSdp ?: webRtcManager.getLocalDescription() ?: fallbackSdp ?: return null
            val resumeSdp = modifySdpForResume(baseSdp)

            // Intentar aplicar SDP modificado en WebRTC. No llamamos setAudioEnabled para
            // no interferir con otras llamadas activas que comparten el mismo WebRtcManager.
            webRtcManager.applyModifiedSdp(resumeSdp)

            isCallOnHold = false
            logInfo("Call resumed successfully")
            return resumeSdp
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
//
//class CallHoldManager(private val webRtcManager: WebRtcManager) {
//
//    private var isCallOnHold = false
//
//    fun holdCall(): String? {
//        try {
//            if (isCallOnHold) return null
//
//            // [OK] Usar transceivers en lugar de modificar SDP
//            webRtcManager.setAudioEnabled(false)
//
//            // Obtener el SDP actualizado
//            val holdSdp = webRtcManager.getLocalDescription()
//
//            if (holdSdp != null) {
//                isCallOnHold = true
//                log.d(tag = "CallHoldManager",{"Call placed on hold successfully"})
//                return holdSdp
//            }
//            return null
//        } catch (e: Exception) {
//            log.d(tag = "CallHoldManager",{"Error putting call on hold: ${e.message}"})
//            return null
//        }
//    }
//
//    fun resumeCall(): String? {
//        try {
//            if (!isCallOnHold) return null
//
//            // [OK] Habilitar audio nuevamente
//            webRtcManager.setAudioEnabled(true)
//
//            val resumeSdp = webRtcManager.getLocalDescription()
//
//            if (resumeSdp != null) {
//                isCallOnHold = false
//                log.d(tag = "CallHoldManager",{"Call resumed successfully"})
//                return resumeSdp
//            }
//            return null
//        } catch (e: Exception) {
//            log.d(tag = "CallHoldManager",{ "Error resuming call: ${e.message}" })
//            return null
//        }
//    }
//}