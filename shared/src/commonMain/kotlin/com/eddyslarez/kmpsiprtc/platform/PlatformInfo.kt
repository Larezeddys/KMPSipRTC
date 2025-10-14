package com.eddyslarez.kmpsiprtc.platform

interface PlatformInfo {
    fun getPlatform(): String
    fun getVersionName(): String
    fun getOSVersion(): String
    fun getABI(): String
    fun isDarkThemeEnabled(): Boolean
    fun getDeviceIdentifier(): String
    fun getDeviceModel(): String
    fun getDeviceName(): String
}

expect fun createPlatformInfo(): PlatformInfo
