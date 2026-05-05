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
    fun onCallConnected() {}
    fun onCallFailed(error: String) {}
    fun onCallEndedForAccount(accountKey: String) {}
}