package com.eddyslarez.kmpsiprtc.platform

import javax.swing.UIManager

actual fun createPlatformInfo(): PlatformInfo = DesktopPlatformInfo()

class DesktopPlatformInfo : PlatformInfo {

    override fun getDeviceIdentifier(): String {
        return System.getProperty("os.name") +
                System.getProperty("os.version") +
                System.getProperty("user.name")
    }

    override fun getPlatform(): String {
        return "Desktop"
    }

    override fun getVersionName(): String {
        return System.getProperty("os.version") ?: "Unknown"
    }

    override fun getOSVersion(): String {
        return "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    }

    override fun getABI(): String {
        return System.getProperty("os.arch") ?: "Unknown"
    }

    override fun isDarkThemeEnabled(): Boolean {
        val lookAndFeel = UIManager.getLookAndFeel()
        return lookAndFeel.name.contains("Dark", ignoreCase = true)
    }

    override fun getDeviceModel(): String {
        return System.getProperty("os.arch")
    }

    override fun getDeviceName(): String {
        return System.getProperty("user.name")
    }
}

