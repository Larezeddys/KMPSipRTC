package com.eddyslarez.kmpsiprtc.platform

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build

actual fun createPlatformInfo(): PlatformInfo = AndroidPlatformInfo()


class AndroidPlatformInfo : PlatformInfo {

    override fun getDeviceIdentifier(): String {
        return "${Build.MANUFACTURER}${Build.MODEL}"
    }

    override fun getPlatform(): String {
        return "Android"
    }

    override fun getVersionName(): String {
        return Build.VERSION.RELEASE
    }

    override fun getOSVersion(): String {
        return "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
    }

    override fun getABI(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }

    override fun isDarkThemeEnabled(): Boolean {
        val configuration = Resources.getSystem().configuration.uiMode
        return (configuration and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun getDeviceModel(): String {
        return Build.MODEL
    }

    override fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
