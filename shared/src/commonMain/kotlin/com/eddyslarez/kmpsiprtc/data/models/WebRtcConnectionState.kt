package com.eddyslarez.kmpsiprtc.data.models

enum class SdpType {
    OFFER,
    ANSWER
}

enum class WebRtcConnectionState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}

