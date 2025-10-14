package com.eddyslarez.kmpsiprtc.data.models

data class SipConfig(
    val defaultDomain: String = "",
    val webSocketUrl: String = "",
    val userAgent: String = "",
    val enableLogs: Boolean = true,
    val enableAutoReconnect: Boolean = true,
    val pingIntervalMs: Long = 30000L,
    val pushModeConfig: PushModeConfig = PushModeConfig(),
    val incomingRingtoneUri: String? = null,
    val outgoingRingtoneUri: String? = null
)