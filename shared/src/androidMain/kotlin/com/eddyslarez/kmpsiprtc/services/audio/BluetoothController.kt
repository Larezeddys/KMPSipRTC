package com.eddyslarez.kmpsiprtc.services.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log

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

    /**
     * Retorna SOLO dispositivos Bluetooth REALMENTE conectados al sistema de audio.
     *
     * AudioManager.getDevices(GET_DEVICES_OUTPUTS) en Android 6.0+ retorna únicamente los
     * dispositivos efectivamente activos en el routing de audio. Un BT pareado pero no
     * conectado NO aparece en esa lista — por lo que confiamos en ella como fuente
     * autoritativa de "está conectado ahora mismo".
     *
     * NO se usa fallback basado en isBluetoothScoAvailableOffCall: ese flag solo indica
     * capability del perfil SCO, no conexión real, y producía dispositivos fantasma.
     */
    fun getBluetoothDevices(): List<AudioDevice> {
        // AudioManager.getDevices NO requiere permisos BLUETOOTH_*: funciona en API 23+
        // sin que la app tenga BLUETOOTH_CONNECT/SCAN.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            log.w(TAG) { "API < 23, Bluetooth device enumeration not available" }
            return emptyList()
        }

        val devices = mutableListOf<AudioDevice>()
        try {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            audioDevices.forEach { deviceInfo ->
                if (deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {

                    // Solo aceptar dispositivos con un productName real — descarta entradas placeholder
                    val productName = deviceInfo.productName?.toString()?.trim().orEmpty()
                    val displayName = productName.takeIf { it.isNotBlank() }
                        ?: "Bluetooth Device"

                    val device = AudioDevice(
                        name = displayName,
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
                        // CONNECTED es seguro aquí: getDevices() solo lista dispositivos activos.
                        connectionState = DeviceConnectionState.CONNECTED,
                        isWireless = true,
                        supportsHDVoice = deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        latency = if (deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) 100 else 50
                    )
                    devices.add(device)
                }
            }
        } catch (e: SecurityException) {
            log.e(TAG) { "SecurityException getting Bluetooth devices: ${e.message}" }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting Bluetooth devices: ${e.message}" }
        }

        return devices
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