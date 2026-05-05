package com.eddyslarez.kmpsiprtc.data.models

import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

@Serializable
enum class PushMode {
    FOREGROUND,
    PUSH,
//    TRANSITIONING
}

@Serializable
enum class PushModeStrategy {
    AUTOMATIC,
    MANUAL
}

@Serializable
data class PushModeConfig(
    val strategy: PushModeStrategy = PushModeStrategy.AUTOMATIC,
    val autoTransitionDelay: Long = 2000L,
    val forceReregisterOnIncomingCall: Boolean = true,
    val returnToPushAfterCallEnd: Boolean = true,
    val enablePushNotifications: Boolean = true
)

@Serializable
data class PushModeState @OptIn(ExperimentalTime::class) constructor(
    val currentMode: PushMode,
    val previousMode: PushMode? = null,
    val timestamp: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    val reason: String = "",
    val accountsInPushMode: Set<String> = emptySet(),
    val wasInPushBeforeCall: Boolean = false,
    val specificAccountInForeground: String? = null
)

object PushModeReasons {
    const val APP_BACKGROUNDED = "App moved to background"
    const val APP_FOREGROUNDED = "App moved to foreground"
    const val INCOMING_CALL_RECEIVED = "Incoming call received"
    const val CALL_ENDED = "Call ended"
    const val MANUAL_SWITCH = "Manual mode switch"
    const val REGISTRATION_REQUIRED = "Registration required"
    const val NETWORK_RECONNECTION = "Network reconnection"
    const val PUSH_NOTIFICATION_RECEIVED = "Push notification received"
    const val RECONNECTION_RESTORED = "Reconnection restored"
}
