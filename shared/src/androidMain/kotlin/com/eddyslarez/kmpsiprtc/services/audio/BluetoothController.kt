package com.eddyslarez.kmpsiprtc.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import android.media.AudioManager

class BluetoothController(private val context: Context) {
    private val TAG = "BluetoothController"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ✅ NUEVO: Verificación de permisos mejorada
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Para versiones anteriores, algunas operaciones pueden funcionar sin permisos
            true
        }
    }

    // ✅ NUEVO: Detección de dispositivos Bluetooth usando AudioManager (no requiere permisos explícitos)
    fun getBluetoothDevices(): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()

        if (!hasBluetoothPermissions()) {
            log.w(TAG) { "No Bluetooth permissions, using fallback detection" }
            // Fallback: usar métodos que no requieren permisos
            return getBluetoothDevicesFallback()
        }

        try {
            // Método principal con permisos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                audioDevices.forEach { deviceInfo ->
                    if (deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {

                        val device = AudioDevice(
                            name = deviceInfo.productName.toString().takeIf { it.isNotBlank() } ?: "Bluetooth Device",
                            descriptor = "bluetooth_${deviceInfo.id}",
                            nativeDevice = deviceInfo,
                            isOutput = true,
                            audioUnit = AudioUnit(
                                type = if (deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                                    AudioUnitTypes.BLUETOOTHA2DP else AudioUnitTypes.BLUETOOTH,
                                capability = AudioUnitCompatibilities.ALL,
                                isCurrent = false,
                                isDefault = false
                            ),
                            connectionState = DeviceConnectionState.CONNECTED,
                            isWireless = true,
                            supportsHDVoice = deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                            latency = if (deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) 100 else 50
                        )
                        devices.add(device)
                    }
                }
            }
        } catch (e: SecurityException) {
            log.e(TAG) { "SecurityException getting Bluetooth devices: ${e.message}" }
            return getBluetoothDevicesFallback()
        } catch (e: Exception) {
            log.e(TAG) { "Error getting Bluetooth devices: ${e.message}" }
        }

        return devices
    }

    // ✅ NUEVO: Fallback que no requiere permisos explícitos
    private fun getBluetoothDevicesFallback(): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()

        try {
            // Detectar Bluetooth SCO disponible (para llamadas)
            if (audioManager.isBluetoothScoAvailableOffCall) {
                devices.add(createGenericBluetoothDevice(AudioUnitTypes.BLUETOOTH, "Bluetooth Headset"))
            }

//            // Detectar Bluetooth A2DP disponible (para música)
//            if (isBluetoothA2dpConnected()) {
//                devices.add(createGenericBluetoothDevice(AudioUnitTypes.BLUETOOTHA2DP, "Bluetooth Speaker"))
//            }

        } catch (e: Exception) {
            log.e(TAG) { "Error in fallback Bluetooth detection: ${e.message}" }
        }

        return devices
    }

    // ✅ NUEVO: Detectar si A2DP está conectado (método indirecto)
    private fun isBluetoothA2dpConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            } else {
                // Para versiones anteriores, usar estado de audio
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION &&
                        !audioManager.isSpeakerphoneOn &&
                        !audioManager.isWiredHeadsetOn
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun createGenericBluetoothDevice(type: AudioUnitTypes, name: String): AudioDevice {
        return AudioDevice(
            name = name,
            descriptor = "bluetooth_generic",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = type,
                capability = AudioUnitCompatibilities.ALL,
                isCurrent = false,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = true,
            supportsHDVoice = type == AudioUnitTypes.BLUETOOTH,
            latency = if (type == AudioUnitTypes.BLUETOOTHA2DP) 100 else 50
        )
    }

    fun hasConnectedDevices(): Boolean {
        return getBluetoothDevices().isNotEmpty()
    }

    fun onConnectionChanged(isConnected: Boolean) {
        log.d(TAG) { "Bluetooth connection changed: $isConnected" }
    }

    fun initialize() {
        log.d(TAG) { "BluetoothController initialized" }
    }

    fun dispose() {
        log.d(TAG) { "BluetoothController disposed" }
    }

    fun diagnose(): String {
        val devices = getBluetoothDevices()
        return buildString {
            appendLine("Bluetooth Devices: ${devices.size}")
            devices.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type})")
            }
            appendLine("Has Permissions: ${hasBluetoothPermissions()}")
            appendLine("SCO Available: ${audioManager.isBluetoothScoAvailableOffCall}")
            appendLine("SCO On: ${audioManager.isBluetoothScoOn}")
        }
    }
}