package com.eddyslarez.kmpsiprtc.data.models

internal interface SipCallbacks {
    fun onCallTerminated() {}
    fun onRegistrationStateChanged(state: RegistrationState) {}
    fun onAccountRegistrationStateChanged(
        username: String,
        domain: String,
        state: RegistrationState
    ) {
    }

    fun onIncomingCall(callerNumber: String, callerName: String?) {}

    /**
     * El llamante colgo antes de que se respondiera el INVITE entrante.
     *
     * Se emite solo para llamadas aun sonando: un CANCEL sobre una llamada ya
     * establecida no la tumba y no llega aqui.
     */
    fun onIncomingCallCancelled(
        callId: String,
        callerNumber: String,
        callerName: String?,
        targetAccount: String
    ) {
    }
    fun onCallConnected() {}
    fun onCallFailed(error: String) {}
    fun onCallEndedForAccount(accountKey: String) {}
}