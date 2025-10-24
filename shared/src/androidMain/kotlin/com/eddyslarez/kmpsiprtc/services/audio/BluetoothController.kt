package com.eddyslarez.kmpsiprtc.services.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log

class BluetoothController(private val context: Context) {
    private val TAG = "BluetoothController"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private val bluetoothDevices = mutableListOf<AudioDevice>()

    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
                scanBluetoothDevices()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                bluetoothDevices.clear()
            }
        }
    }

    fun initialize() {
        log.d(TAG) { "Initializing BluetoothController" }

        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            bluetoothAdapter?.getProfileProxy(
                context,
                bluetoothProfileListener,
                BluetoothProfile.HEADSET
            )
            log.d(TAG) { "✅ Bluetooth setup initiated" }
        } catch (e: Exception) {
            log.w(TAG) { "⚠️ Bluetooth not available: ${e.message}" }
        }
    }

    fun dispose() {
        bluetoothHeadset?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
        bluetoothDevices.clear()
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothDevices(activeRoute: AudioUnitTypes?): List<AudioDevice> {
        scanBluetoothDevices()
        return bluetoothDevices.map { device ->
            device.copy(
                audioUnit = device.audioUnit.copy(
                    isCurrent = activeRoute == AudioUnitTypes.BLUETOOTH
                )
            )
        }
    }

    fun hasConnectedDevices(): Boolean = bluetoothDevices.isNotEmpty()

    fun onConnectionChanged(isConnected: Boolean) {
        if (isConnected) {
            mainHandler.postDelayed({
                scanBluetoothDevices()
            }, 300)
        } else {
            bluetoothDevices.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanBluetoothDevices() {
        bluetoothDevices.clear()

        bluetoothHeadset?.let { headset ->
            try {
                val connectedDevices = headset.connectedDevices
                connectedDevices.forEach { device ->
                    val audioDevice = AudioDevice(
                        name = device.name ?: "Dispositivo Bluetooth",
                        descriptor = device.address,
                        nativeDevice = device,
                        isOutput = true,
                        audioUnit = AudioUnit(
                            type = AudioUnitTypes.BLUETOOTH,
                            capability = AudioUnitCompatibilities.ALL,
                            isCurrent = false,
                            isDefault = false
                        ),
                        connectionState = DeviceConnectionState.CONNECTED,
                        isWireless = true,
                        supportsHDVoice = true,
                        latency = 50
                    )
                    bluetoothDevices.add(audioDevice)
                }
                log.d(TAG) { "Found ${bluetoothDevices.size} Bluetooth devices" }
            } catch (e: Exception) {
                log.e(TAG) { "Error scanning Bluetooth: ${e.message}" }
            }
        }
    }

    fun diagnose(): String {
        return buildString {
            appendLine("Adapter: ${bluetoothAdapter != null}")
            appendLine("Headset: ${bluetoothHeadset != null}")
            appendLine("Connected Devices: ${bluetoothDevices.size}")
            bluetoothDevices.forEach {
                appendLine("  - ${it.name}")
            }
        }
    }
}