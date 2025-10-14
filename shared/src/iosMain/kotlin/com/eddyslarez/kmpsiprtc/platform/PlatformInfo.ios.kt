package com.eddyslarez.kmpsiprtc.platform

import kotlinx.cinterop.*
import platform.Foundation.NSUUID
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.UIKit.UIDevice
import platform.UIKit.UITraitCollection
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.currentTraitCollection
import platform.darwin.sysctlbyname
import platform.posix.size_tVar

actual fun createPlatformInfo(): PlatformInfo = IOSPlatformInfo()

class IOSPlatformInfo : PlatformInfo {

    override fun getDeviceIdentifier(): String {
        return UIDevice.currentDevice.identifierForVendor?.UUIDString
            ?: NSUUID().UUIDString()
    }

    override fun getPlatform(): String = "iOS"

    override fun getVersionName(): String = UIDevice.currentDevice.systemVersion

    override fun getOSVersion(): String = UIDevice.currentDevice.systemVersion

    override fun getABI(): String {
        return getSystemInfoByName("hw.machine") ?: "unknown"
    }

    override fun isDarkThemeEnabled(): Boolean {
        return UITraitCollection.currentTraitCollection.userInterfaceStyle ==
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
    }

    override fun getDeviceModel(): String {
        val identifier = getMachineIdentifier()
        return identifier?.let { mapToReadableModel(it) }
            ?: UIDevice.currentDevice.model
    }

    override fun getDeviceName(): String = "Apple"

    // -----------------------------------------------------------
    // 🔧 Métodos auxiliares
    // -----------------------------------------------------------
    @OptIn(ExperimentalForeignApi::class)
    private fun getSystemInfoByName(name: String): String? {
        return memScoped {
            val size = alloc<size_tVar>()
            if (sysctlbyname(name, null, size.ptr, null, 0u) != 0) return@memScoped null

            val buffer = ByteArray(size.value.toInt())
            buffer.usePinned {
                if (sysctlbyname(name, it.addressOf(0), size.ptr, null, 0u) != 0) return@memScoped null
            }
            buffer.decodeToString().trim('\u0000')
        }
    }

    private fun getMachineIdentifier(): String? = getSystemInfoByName("hw.machine")

    private fun mapToReadableModel(identifier: String): String = when (identifier) {
        "iPhone15,4" -> "iPhone 15"
        "iPhone15,5" -> "iPhone 15 Plus"
        "iPhone16,1" -> "iPhone 15 Pro"
        "iPhone16,2" -> "iPhone 15 Pro Max"
        "iPhone14,7" -> "iPhone 14"
        "iPhone14,8" -> "iPhone 14 Plus"
        "iPhone15,2" -> "iPhone 14 Pro"
        "iPhone15,3" -> "iPhone 14 Pro Max"
        "iPhone14,5" -> "iPhone 13"
        "iPhone14,4" -> "iPhone 13 mini"
        "iPhone14,2" -> "iPhone 13 Pro"
        "iPhone14,3" -> "iPhone 13 Pro Max"
        "iPhone13,2" -> "iPhone 12"
        "iPhone13,1" -> "iPhone 12 mini"
        "iPhone13,3" -> "iPhone 12 Pro"
        "iPhone13,4" -> "iPhone 12 Pro Max"
        "iPhone12,1" -> "iPhone 11"
        "iPhone12,3" -> "iPhone 11 Pro"
        "iPhone12,5" -> "iPhone 11 Pro Max"
        "iPhone11,8" -> "iPhone XR"
        "iPhone11,2" -> "iPhone XS"
        "iPhone11,4", "iPhone11,6" -> "iPhone XS Max"
        "iPhone10,3", "iPhone10,6" -> "iPhone X"
        "iPhone10,1", "iPhone10,4" -> "iPhone 8"
        "iPhone10,2", "iPhone10,5" -> "iPhone 8 Plus"
        "iPhone9,1", "iPhone9,3" -> "iPhone 7"
        "iPhone9,2", "iPhone9,4" -> "iPhone 7 Plus"
        "iPhone8,1" -> "iPhone 6s"
        "iPhone8,2" -> "iPhone 6s Plus"
        "iPhone7,2" -> "iPhone 6"
        "iPhone7,1" -> "iPhone 6 Plus"
        "iPhone14,6" -> "iPhone SE (3rd generation)"
        "iPhone12,8" -> "iPhone SE (2nd generation)"
        "iPhone8,4" -> "iPhone SE (1st generation)"
        "iPhone6,1", "iPhone6,2" -> "iPhone 5s"
        "iPhone5,3", "iPhone5,4" -> "iPhone 5c"
        "iPhone5,1", "iPhone5,2" -> "iPhone 5"
        "iPhone4,1" -> "iPhone 4S"
        "iPhone3,1", "iPhone3,2", "iPhone3,3" -> "iPhone 4"
        "iPhone2,1" -> "iPhone 3GS"
        "iPhone1,2" -> "iPhone 3G"
        "iPhone1,1" -> "iPhone 2G"
        else -> identifier
    }
}

