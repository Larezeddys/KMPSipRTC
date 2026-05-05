package com.eddyslarez.kmpsiprtc.services.webrtc

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState

/**
 * Listener compuesto que permite registrar multiples WebRtcEventListener
 * y delega cada callback a todos los listeners registrados.
 * Resuelve el problema de que WebRtcManager.setListener() solo acepta UN listener.
 */
internal class CompositeWebRtcEventListener : WebRtcEventListener {

    private val listeners = mutableListOf<WebRtcEventListener>()

    fun addListener(listener: WebRtcEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: WebRtcEventListener) {
        listeners.remove(listener)
    }

    override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        listeners.forEach { it.onIceCandidate(candidate, sdpMid, sdpMLineIndex) }
    }

    override fun onConnectionStateChange(state: WebRtcConnectionState) {
        listeners.forEach { it.onConnectionStateChange(state) }
    }

    override fun onRemoteAudioTrack() {
        listeners.forEach { it.onRemoteAudioTrack() }
    }

    override fun onAudioDeviceChanged(device: AudioDevice?) {
        listeners.forEach { it.onAudioDeviceChanged(device) }
    }
}
