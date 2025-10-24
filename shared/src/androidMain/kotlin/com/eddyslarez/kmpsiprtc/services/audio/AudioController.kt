package com.eddyslarez.kmpsiprtc.services.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log

class AudioController(
    private val context: Context,
    private val bluetoothController: BluetoothController,
    private val onDeviceChanged: (AudioDevice?) -> Unit
) {
    private val TAG = "AudioController"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioDevices = mutableListOf<AudioDevice>()
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var isStarted = false

    // ✅ NUEVO: Listener para AudioFocus
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                log.d(TAG) { "Audio focus gained" }
                // Restaurar audio si fue pausado
                audioManager?.isMicrophoneMute = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                log.d(TAG) { "Audio focus lost permanently" }
                // Pausar audio
                audioManager?.isMicrophoneMute = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log.d(TAG) { "Audio focus lost temporarily" }
                // Pausar temporalmente
                audioManager?.isMicrophoneMute = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                log.d(TAG) { "Audio focus lost, can duck" }
                // Reducir volumen (duck) - opcional
            }
        }
    }

    fun initialize() {
        log.d(TAG) { "Initializing AudioController" }
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun startForCall() {
        if (isStarted) {
            log.d(TAG) { "Audio already started" }
            return
        }

        log.d(TAG) { "🔊 Starting audio for call" }

        audioManager?.let { am ->
            savedAudioMode = am.mode
            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn

            requestAudioFocus()

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false

            scanDevices()

            mainHandler.postDelayed({
                selectDefaultDeviceWithPriority()
            }, 100)

            isStarted = true
            log.d(TAG) { "✅ Audio started" }
        }
    }

    fun stop() {
        if (!isStarted) return

        log.d(TAG) { "🔇 Stopping audio" }

        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
            am.stopBluetoothSco()
            abandonAudioFocus()
        }

        isStarted = false
    }

    fun dispose() {
        stop()
        audioManager = null
    }

    // ==================== DEVICE MANAGEMENT ====================

    fun setActiveRoute(audioUnitType: AudioUnitTypes): Boolean {
        return audioManager?.let { am ->
            try {
                when (audioUnitType) {
                    AudioUnitTypes.SPEAKER -> {
                        am.isSpeakerphoneOn = true
                        am.isBluetoothScoOn = false
                        am.stopBluetoothSco()
                    }
                    AudioUnitTypes.EARPIECE -> {
                        am.isSpeakerphoneOn = false
                        am.isBluetoothScoOn = false
                        am.stopBluetoothSco()
                    }
                    AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> {
                        am.isSpeakerphoneOn = false
                        am.isBluetoothScoOn = true
                        am.startBluetoothSco()
                    }
                    AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
                        am.isSpeakerphoneOn = false
                        am.isBluetoothScoOn = false
                        am.stopBluetoothSco()
                    }
                    else -> return false
                }

                mainHandler.postDelayed({
                    onDeviceChanged(getCurrentOutputDevice())
                }, 100)

                true
            } catch (e: Exception) {
                log.e(TAG) { "Error setting route: ${e.message}" }
                false
            }
        } ?: false
    }

    fun getActiveRoute(): AudioUnitTypes? {
        return audioManager?.let { am ->
            when {
                am.isBluetoothScoOn -> AudioUnitTypes.BLUETOOTH
                am.isSpeakerphoneOn -> AudioUnitTypes.SPEAKER
                am.isWiredHeadsetOn -> AudioUnitTypes.HEADSET
                else -> AudioUnitTypes.EARPIECE
            }
        }
    }

    fun getAvailableRoutes(): Set<AudioUnitTypes> {
        val routes = mutableSetOf<AudioUnitTypes>()
        audioManager?.let { am ->
            routes.add(AudioUnitTypes.EARPIECE)
            routes.add(AudioUnitTypes.SPEAKER)
            if (am.isWiredHeadsetOn) routes.add(AudioUnitTypes.HEADSET)
            if (am.isBluetoothScoAvailableOffCall || bluetoothController.hasConnectedDevices()) {
                routes.add(AudioUnitTypes.BLUETOOTH)
            }
        }
        return routes
    }

    fun getAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        scanDevices()
        val inputs = audioDevices.filter { it.isInput && it.canRecord }
        val outputs = audioDevices.filter { it.isOutput && it.canPlay }
        return Pair(inputs, outputs)
    }

    fun changeOutputDevice(device: AudioDevice): Boolean {
        val success = setActiveRoute(device.audioUnit.type)
        if (success) {
            onDeviceChanged(device)
        }
        return success
    }

    fun changeInputDevice(device: AudioDevice): Boolean = true

    fun getCurrentInputDevice(): AudioDevice? {
        return audioDevices.firstOrNull { device ->
            when (getActiveRoute()) {
                AudioUnitTypes.BLUETOOTH -> device.isBluetooth && device.isInput
                else -> device.audioUnit.type == AudioUnitTypes.MICROPHONE
            }
        }
    }

    fun getCurrentOutputDevice(): AudioDevice? {
        return audioDevices.firstOrNull { device ->
            device.isOutput && device.audioUnit.type == getActiveRoute()
        }
    }

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        scanDevices()
        return audioDevices.map { it.audioUnit }.toSet()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        return getCurrentOutputDevice()?.audioUnit
    }

    fun refreshDevices() {
        scanDevices()
        onDeviceChanged(getCurrentOutputDevice())
    }

    fun refreshWithBluetoothPriority() {
        selectDefaultDeviceWithPriority()
    }

    // ==================== MUTE CONTROL ====================

    fun setMicrophoneMute(muted: Boolean) {
        audioManager?.isMicrophoneMute = muted
    }

    fun isMicrophoneMuted(): Boolean {
        return audioManager?.isMicrophoneMute ?: false
    }

    // ==================== PRIVATE HELPERS ====================

    private fun selectDefaultDeviceWithPriority() {
        scanDevices()

        val availableTypes = audioDevices.map { it.audioUnit.type }.toSet()
        log.d(TAG) { "Available devices: $availableTypes" }

        val priorityType = when {
            AudioUnitTypes.BLUETOOTH in availableTypes -> AudioUnitTypes.BLUETOOTH
            AudioUnitTypes.HEADSET in availableTypes -> AudioUnitTypes.HEADSET
            else -> AudioUnitTypes.EARPIECE
        }

        log.d(TAG) { "Selected priority: $priorityType" }
        setActiveRoute(priorityType)
    }

    private fun scanDevices() {
        audioDevices.clear()

        audioManager?.let { am ->
            audioDevices.add(createMicrophoneDevice())
            audioDevices.add(createEarpieceDevice())
            audioDevices.add(createSpeakerDevice())

            if (am.isWiredHeadsetOn) {
                audioDevices.add(createWiredHeadsetDevice())
            }

            audioDevices.addAll(bluetoothController.getBluetoothDevices(getActiveRoute()))
        }
    }

    private fun requestAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    // ✅ CORRECCIÓN: Agregar el listener ANTES de setAcceptsDelayedFocusGain
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(audioAttributes)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(false)  // ✅ Opcional: no pausar al hacer duck
                        .build()

                    val result = am.requestAudioFocus(audioFocusRequest!!)

                    when (result) {
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                            log.d(TAG) { "✅ Audio focus granted" }
                        }
                        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                            log.w(TAG) { "⚠️ Audio focus request failed" }
                        }
                        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                            log.d(TAG) { "⏳ Audio focus request delayed" }
                        }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Error requesting audio focus: ${e.message}" }
                    // Continuar sin audio focus en caso de error
                }
            } else {
                // ✅ Para versiones anteriores a Android O
                @Suppress("DEPRECATION")
                try {
                    val result = am.requestAudioFocus(
                        audioFocusChangeListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN
                    )

                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        log.d(TAG) { "✅ Audio focus granted (legacy)" }
                    } else {
                        log.w(TAG) { "⚠️ Audio focus request failed (legacy)" }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Error requesting audio focus (legacy): ${e.message}" }
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    try {
                        am.abandonAudioFocusRequest(request)
                        log.d(TAG) { "✅ Audio focus abandoned" }
                    } catch (e: Exception) {
                        log.e(TAG) { "Error abandoning audio focus: ${e.message}" }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    am.abandonAudioFocus(audioFocusChangeListener)
                    log.d(TAG) { "✅ Audio focus abandoned (legacy)" }
                } catch (e: Exception) {
                    log.e(TAG) { "Error abandoning audio focus (legacy): ${e.message}" }
                }
            }
        }
    }

    // ==================== DEVICE CREATION ====================

    private fun createMicrophoneDevice() = AudioDevice(
        name = "Micrófono integrado",
        descriptor = "builtin_mic",
        nativeDevice = null,
        isOutput = false,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.MICROPHONE,
            capability = AudioUnitCompatibilities.RECORD,
            isCurrent = getActiveRoute() == AudioUnitTypes.EARPIECE,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    private fun createEarpieceDevice() = AudioDevice(
        name = "Auricular integrado",
        descriptor = "builtin_earpiece",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.EARPIECE,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = getActiveRoute() == AudioUnitTypes.EARPIECE,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 15
    )

    private fun createSpeakerDevice() = AudioDevice(
        name = "Altavoz",
        descriptor = "builtin_speaker",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.SPEAKER,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = getActiveRoute() == AudioUnitTypes.SPEAKER,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = false,
        latency = 20
    )

    private fun createWiredHeadsetDevice() = AudioDevice(
        name = "Auricular cableado",
        descriptor = "wired_headset",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.HEADSET,
            capability = AudioUnitCompatibilities.ALL,
            isCurrent = getActiveRoute() == AudioUnitTypes.HEADSET,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    fun diagnose(): String {
        return buildString {
            appendLine("Started: $isStarted")
            appendLine("Mic Muted: ${isMicrophoneMuted()}")
            appendLine("Active Route: ${getActiveRoute()}")
            appendLine("Audio Focus Request: ${audioFocusRequest != null}")
            audioManager?.let { am ->
                appendLine("Mode: ${am.mode}")
                appendLine("Speakerphone: ${am.isSpeakerphoneOn}")
                appendLine("Bluetooth SCO: ${am.isBluetoothScoOn}")
                appendLine("Wired Headset: ${am.isWiredHeadsetOn}")
            }
            val (inputs, outputs) = getAllDevices()
            appendLine("Input Devices: ${inputs.size}")
            appendLine("Output Devices: ${outputs.size}")
        }
    }
}