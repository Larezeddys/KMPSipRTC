package com.eddyslarez.kmpsiprtc.data.models

interface SipCallbacks {
    fun onCallTerminated() {}
    fun onRegistrationStateChanged(state: RegistrationState) {}
    fun onAccountRegistrationStateChanged(
        username: String,
        domain: String,
        state: RegistrationState
    ) {
    }
    /**
     * Called when remote audio data is captured
     * @param audioBytes Raw audio bytes from remote party
     */
    fun onRemoteAudioData(audioBytes: ByteArray) {}
    fun onIncomingCall(callerNumber: String, callerName: String?) {}
    fun onCallConnected() {}
    fun onCallFailed(error: String) {}
    fun onCallEndedForAccount(accountKey: String) {}
}