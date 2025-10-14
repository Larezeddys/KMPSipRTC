package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AudioDeviceManager {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // Current devices with enhanced information
    private val _inputDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    private val _outputDevices = MutableStateFlow<List<AudioDevice>>(emptyList())

    // Selected devices
    private val _selectedInputDevice = MutableStateFlow<AudioDevice?>(null)
    private val _selectedOutputDevice = MutableStateFlow<AudioDevice?>(null)

    // Device monitoring
    private val _deviceConnectionStates = MutableStateFlow<Map<String, DeviceConnectionState>>(emptyMap())
    private val _preferredDeviceTypes = MutableStateFlow<Set<AudioUnitTypes>>(emptySet())

    // Public flows for observing device changes
    val inputDevices: StateFlow<List<AudioDevice>> = _inputDevices.asStateFlow()
    val outputDevices: StateFlow<List<AudioDevice>> = _outputDevices.asStateFlow()
    val selectedInputDevice: StateFlow<AudioDevice?> = _selectedInputDevice.asStateFlow()
    val selectedOutputDevice: StateFlow<AudioDevice?> = _selectedOutputDevice.asStateFlow()
    val deviceConnectionStates: StateFlow<Map<String, DeviceConnectionState>> = _deviceConnectionStates.asStateFlow()

    // Combined flows for convenience
    val availableInputDevices = combine(_inputDevices, _deviceConnectionStates) { devices, states ->
        devices.filter { device ->
            val state = states[device.descriptor] ?: DeviceConnectionState.AVAILABLE
            state == DeviceConnectionState.AVAILABLE || state == DeviceConnectionState.CONNECTED
        }
    }

    val availableOutputDevices = combine(_outputDevices, _deviceConnectionStates) { devices, states ->
        devices.filter { device ->
            val state = states[device.descriptor] ?: DeviceConnectionState.AVAILABLE
            state == DeviceConnectionState.AVAILABLE || state == DeviceConnectionState.CONNECTED
        }
    }

    val recommendedOutputDevice = combine(_outputDevices, _preferredDeviceTypes) { devices, preferredTypes ->
        devices
            .filter { it.connectionState == DeviceConnectionState.AVAILABLE || it.connectionState == DeviceConnectionState.CONNECTED }
            .sortedWith(compareByDescending<AudioDevice> { device ->
                // Prioritize preferred types
                if (preferredTypes.contains(device.audioUnit.type)) 2 else 0
            }.thenByDescending { it.qualityScore }
                .thenByDescending { if (it.audioUnit.isCurrent) 1 else 0 })
            .firstOrNull()
    }

    /**
     * Update available devices with enhanced information
     */
    fun updateDevices(inputs: List<AudioDevice>, outputs: List<AudioDevice>) {
        _inputDevices.value = inputs.sortedWith(getDeviceComparator())
        _outputDevices.value = outputs.sortedWith(getDeviceComparator())

        // Update connection states
        val newStates = (inputs + outputs).associate { device ->
            device.descriptor to device.connectionState
        }
        _deviceConnectionStates.value = newStates

        // Auto-select best devices if none selected or current selection is unavailable
        autoSelectOptimalDevices()
    }

    /**
     * Select an input device with validation
     */
    fun selectInputDevice(device: AudioDevice): Boolean {
        if (!device.canRecord) {
            return false
        }

        if (_inputDevices.value.contains(device) && device.connectionState == DeviceConnectionState.AVAILABLE) {
            _selectedInputDevice.value = device
            updateDeviceCurrentState(device, true)
            return true
        }
        return false
    }

    /**
     * Select an output device with validation
     */
    fun selectOutputDevice(device: AudioDevice): Boolean {
        if (!device.canPlay) {
            return false
        }

        if (_outputDevices.value.contains(device) && device.connectionState == DeviceConnectionState.AVAILABLE) {
            _selectedOutputDevice.value = device
            updateDeviceCurrentState(device, true)
            return true
        }
        return false
    }

    /**
     * Set preferred device types for automatic selection
     */
    fun setPreferredDeviceTypes(types: Set<AudioUnitTypes>) {
        _preferredDeviceTypes.value = types
        autoSelectOptimalDevices()
    }

    /**
     * Update device connection state
     */
    fun updateDeviceConnectionState(deviceDescriptor: String, state: DeviceConnectionState) {
        val currentStates = _deviceConnectionStates.value.toMutableMap()
        currentStates[deviceDescriptor] = state
        _deviceConnectionStates.value = currentStates

        // If selected device becomes unavailable, auto-select alternative
        if (state != DeviceConnectionState.AVAILABLE && state != DeviceConnectionState.CONNECTED) {
            val selectedInput = _selectedInputDevice.value
            val selectedOutput = _selectedOutputDevice.value

            if (selectedInput?.descriptor == deviceDescriptor) {
                autoSelectAlternativeInputDevice()
            }
            if (selectedOutput?.descriptor == deviceDescriptor) {
                autoSelectAlternativeOutputDevice()
            }
        }
    }

    /**
     * Get devices by type
     */
    fun getDevicesByType(type: AudioUnitTypes, isOutput: Boolean): List<AudioDevice> {
        val devices = if (isOutput) _outputDevices.value else _inputDevices.value
        return devices.filter { it.audioUnit.type == type }
    }

    /**
     * Get devices by capability
     */
    fun getDevicesByCapability(capability: AudioUnitCompatibilities): List<AudioDevice> {
        val allDevices = _inputDevices.value + _outputDevices.value
        return allDevices.filter { it.audioUnit.capability == capability }
    }

    /**
     * Get bluetooth devices with signal strength
     */
    fun getBluetoothDevicesWithSignal(): List<AudioDevice> {
        val allDevices = _inputDevices.value + _outputDevices.value
        return allDevices.filter { it.isBluetooth && it.signalStrength != null }
    }

    /**
     * Check if high-quality audio is available
     */
    fun isHighQualityAudioAvailable(): Boolean {
        val allDevices = _inputDevices.value + _outputDevices.value
        return allDevices.any { it.supportsHDVoice && it.connectionState == DeviceConnectionState.AVAILABLE }
    }

    private fun autoSelectOptimalDevices() {
        coroutineScope.launch {
            // Auto-select input device if none selected or current is unavailable
            if (_selectedInputDevice.value == null ||
                !_inputDevices.value.contains(_selectedInputDevice.value) ||
                _selectedInputDevice.value?.connectionState != DeviceConnectionState.AVAILABLE) {
                autoSelectAlternativeInputDevice()
            }

            // Auto-select output device if none selected or current is unavailable
            if (_selectedOutputDevice.value == null ||
                !_outputDevices.value.contains(_selectedOutputDevice.value) ||
                _selectedOutputDevice.value?.connectionState != DeviceConnectionState.AVAILABLE) {
                autoSelectAlternativeOutputDevice()
            }
        }
    }

    private fun autoSelectAlternativeInputDevice() {
        val availableInputs = _inputDevices.value.filter {
            it.connectionState == DeviceConnectionState.AVAILABLE && it.canRecord
        }

        val bestInput = availableInputs
            .sortedWith(getDeviceComparator())
            .firstOrNull()

        bestInput?.let { device ->
            _selectedInputDevice.value = device
            updateDeviceCurrentState(device, true)
        }
    }

    private fun autoSelectAlternativeOutputDevice() {
        val availableOutputs = _outputDevices.value.filter {
            it.connectionState == DeviceConnectionState.AVAILABLE && it.canPlay
        }

        val bestOutput = availableOutputs
            .sortedWith(getDeviceComparator())
            .firstOrNull()

        bestOutput?.let { device ->
            _selectedOutputDevice.value = device
            updateDeviceCurrentState(device, true)
        }
    }

    private fun updateDeviceCurrentState(selectedDevice: AudioDevice, isCurrent: Boolean) {
        val deviceList = if (selectedDevice.isOutput) _outputDevices.value else _inputDevices.value
        val updatedDevices = deviceList.map { device ->
            if (device.descriptor == selectedDevice.descriptor) {
                device.copy(audioUnit = device.audioUnit.copy(isCurrent = isCurrent))
            } else if (device.audioUnit.isCurrent) {
                device.copy(audioUnit = device.audioUnit.copy(isCurrent = false))
            } else {
                device
            }
        }

        if (selectedDevice.isOutput) {
            _outputDevices.value = updatedDevices
        } else {
            _inputDevices.value = updatedDevices
        }
    }

    private fun getDeviceComparator(): Comparator<AudioDevice> {
        val preferredTypes = _preferredDeviceTypes.value

        return compareByDescending<AudioDevice> { device ->
            // Prioritize preferred types
            if (preferredTypes.contains(device.audioUnit.type)) 3 else 0
        }.thenByDescending { device ->
            // Prioritize currently connected devices
            if (device.audioUnit.isCurrent) 2 else 0
        }.thenByDescending { device ->
            // Prioritize by quality score
            device.qualityScore
        }.thenByDescending { device ->
            // Prioritize HD voice support
            if (device.supportsHDVoice) 1 else 0
        }.thenBy { device ->
            // Sort by name for consistency
            device.name
        }
    }

    /**
     * Reset to default state
     */
    fun reset() {
        _selectedInputDevice.value = null
        _selectedOutputDevice.value = null
        _deviceConnectionStates.value = emptyMap()
        _preferredDeviceTypes.value = emptySet()
    }

    /**
     * Get diagnostic information
     */
    fun getDiagnosticInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== AUDIO DEVICE MANAGER DIAGNOSTICS ===")
        sb.appendLine("Input devices: ${_inputDevices.value.size}")
        sb.appendLine("Output devices: ${_outputDevices.value.size}")
        sb.appendLine("Selected input: ${_selectedInputDevice.value?.name ?: "None"}")
        sb.appendLine("Selected output: ${_selectedOutputDevice.value?.name ?: "None"}")
        sb.appendLine("Preferred types: ${_preferredDeviceTypes.value}")
        sb.appendLine("Device states: ${_deviceConnectionStates.value}")

        sb.appendLine("\n--- Input Devices ---")
        _inputDevices.value.forEach { device ->
            sb.appendLine("${device.name} (${device.audioUnit.type}) - Quality: ${device.qualityScore}")
        }

        sb.appendLine("\n--- Output Devices ---")
        _outputDevices.value.forEach { device ->
            sb.appendLine("${device.name} (${device.audioUnit.type}) - Quality: ${device.qualityScore}")
        }

        return sb.toString()
    }
}