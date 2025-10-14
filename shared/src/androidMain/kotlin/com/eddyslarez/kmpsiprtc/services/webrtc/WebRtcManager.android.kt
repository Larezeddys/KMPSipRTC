package com.eddyslarez.kmpsiprtc.services.webrtc


import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.shepeliev.webrtckmp.AudioStreamTrack
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.MediaDeviceInfo
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.webrtc.*
import kotlin.invoke

actual fun createWebRtcManager(): WebRtcManager = AndroidWebRtcManager()


class AndroidWebRtcManager() : WebRtcManager {
    private var application: Application? = null
    private val TAG = "AndroidWebRtcManager"
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private var peerConnection: com.shepeliev.webrtckmp.PeerConnection? = null
    private var localAudioTrack: AudioStreamTrack? = null
    private var remoteAudioTrack: AudioStreamTrack? = null
    private var webRtcEventListener: WebRtcEventListener? = null
    private var isInitialized = false
    private var isLocalAudioReady = false
    private val context: Context = application!!.applicationContext

    // Simplified audio management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false

    // Current device tracking
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null

    // Audio device states
    private val _currentAudioDevice = MutableStateFlow(AudioDeviceType.EARPIECE)
    val currentAudioDevice: StateFlow<AudioDeviceType> = _currentAudioDevice

    private val _availableAudioDevices = MutableStateFlow<List<AudioDeviceType>>(emptyList())
    val availableAudioDevices: StateFlow<List<AudioDeviceType>> = _availableAudioDevices


    enum class AudioDeviceType {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
    }

    override fun initialize() {
        log.d(TAG) { "Initializing WebRTC Manager..." }
        if (!isInitialized) {
            initializePeerConnection()
            coroutineScope.launch {
                getAudioInputDevices()
            }
            isInitialized = true
        }
    }

    override fun closePeerConnection() {
        log.d(TAG) { "Closing peer connection" }
        cleanupCall()
    }

    override fun dispose() {
        log.d(TAG) { "Disposing WebRTC Manager resources..." }

        try {
            stopAudioManager()
            cleanupCall()
            isInitialized = false
            isLocalAudioReady = false
            currentInputDevice = null
            currentOutputDevice = null
        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        }
    }

    override suspend fun createOffer(): String {
        log.d(TAG) { "Creating SDP offer..." }

        if (!isInitialized) {
            initialize()
        } else {
            startAudioManager()
        }

        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!isLocalAudioReady) {
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val options = OfferAnswerOptions(voiceActivityDetection = true)
        val sessionDescription = peerConn.createOffer(options)
        peerConn.setLocalDescription(sessionDescription)

        audioManager.isMicrophoneMute = false

        log.d(TAG) { "Created offer SDP" }
        return sessionDescription.sdp
    }

    override suspend fun createAnswer( offerSdp: String): String {
        log.d(TAG) { "Creating SDP answer..." }

        if (!isInitialized) {
            initialize()
        } else {
            startAudioManager()
        }

        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!isLocalAudioReady) {
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val remoteOffer = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = offerSdp
        )
        peerConn.setRemoteDescription(remoteOffer)

        val options = OfferAnswerOptions(voiceActivityDetection = true)
        val sessionDescription = peerConn.createAnswer(options)
        peerConn.setLocalDescription(sessionDescription)

        setAudioEnabled(true)
        audioManager.isMicrophoneMute = false

        log.d(TAG) { "Created answer SDP" }
        return sessionDescription.sdp
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
            log.d(TAG) { "Setting remote description type: $type" }

        if (!isInitialized) {
            initialize()
        }

        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (type == SdpType.OFFER && !isLocalAudioReady) {
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val sdpType = when (type) {
            SdpType.OFFER -> SessionDescriptionType.Offer
            SdpType.ANSWER -> SessionDescriptionType.Answer
        }

        val sessionDescription = SessionDescription(type = sdpType, sdp = sdp)
        peerConn.setRemoteDescription(sessionDescription)

        if (type == SdpType.ANSWER) {
            setAudioEnabled(true)
            audioManager.isMicrophoneMute = false
        }
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        log.d(TAG) { "Adding ICE candidate" }

        if (!isInitialized) {
            initialize()
            if (peerConnection == null) {
                return
            }
        }

        val peerConn = peerConnection ?: return

        val iceCandidate = IceCandidate(
            sdpMid = sdpMid ?: "",
            sdpMLineIndex = sdpMLineIndex ?: 0,
            candidate = candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        updateAvailableAudioDevices()

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        // Built-in microphone (always available)
        inputDevices.add(createBuiltinMicDevice())

        // Add other input devices based on availability
        if (_availableAudioDevices.value.contains(AudioDeviceType.WIRED_HEADSET)) {
            inputDevices.add(createWiredHeadsetMicDevice())
        }

        if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH)) {
            inputDevices.add(createBluetoothMicDevice())
        }

        // Output devices
        _availableAudioDevices.value.forEach { deviceType ->
            when (deviceType) {
                AudioDeviceType.EARPIECE -> outputDevices.add(createEarpieceDevice())
                AudioDeviceType.SPEAKER_PHONE -> outputDevices.add(createSpeakerDevice())
                AudioDeviceType.WIRED_HEADSET -> outputDevices.add(createWiredHeadsetDevice())
                AudioDeviceType.BLUETOOTH -> outputDevices.add(createBluetoothDevice())
                AudioDeviceType.NONE -> {}
            }
        }

        return Pair(inputDevices, outputDevices)
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio output to: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            return false
        }

        return try {
            val success = when (device.descriptor) {
                "bluetooth" -> selectAudioDevice(AudioDeviceType.BLUETOOTH)
                "speaker" -> selectAudioDevice(AudioDeviceType.SPEAKER_PHONE)
                "wired_headset" -> selectAudioDevice(AudioDeviceType.WIRED_HEADSET)
                "earpiece" -> selectAudioDevice(AudioDeviceType.EARPIECE)
                else -> false
            }

            if (success) {
                currentOutputDevice = device
                webRtcEventListener?.onAudioDeviceChanged(device)
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio output: ${e.message}" }
            false
        }
    }


    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio input to: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            return false
        }

        return try {
            val success = when {
                device.descriptor.startsWith("bluetooth") -> selectAudioDevice(AudioDeviceType.BLUETOOTH)
                device.descriptor == "wired_headset_mic" -> selectAudioDevice(AudioDeviceType.WIRED_HEADSET)
                else -> selectAudioDevice(AudioDeviceType.EARPIECE)
            }

            if (success) {
                currentInputDevice = device
                webRtcEventListener?.onAudioDeviceChanged(device)
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio input: ${e.message}" }
            false
        }
    }


    override fun getCurrentInputDevice(): AudioDevice? {
        return currentInputDevice ?: when (_currentAudioDevice.value) {
            AudioDeviceType.BLUETOOTH -> createBluetoothMicDevice()
            AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetMicDevice()
            else -> createBuiltinMicDevice()
        }
    }

    override fun getCurrentOutputDevice(): AudioDevice? {
        return currentOutputDevice ?: when (_currentAudioDevice.value) {
            AudioDeviceType.BLUETOOTH -> createBluetoothDevice()
            AudioDeviceType.SPEAKER_PHONE -> createSpeakerDevice()
            AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetDevice()
            AudioDeviceType.EARPIECE -> createEarpieceDevice()
            AudioDeviceType.NONE -> null
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        audioManager.isMicrophoneMute = !enabled
        localAudioTrack?.enabled = enabled
    }

    override fun setMuted(muted: Boolean) {
        audioManager.isMicrophoneMute = muted
        localAudioTrack?.enabled = !muted
    }

    override fun isMuted(): Boolean {
        return audioManager.isMicrophoneMute
    }

    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.sdp
    }

    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
        log.d(TAG) { "Setting active audio route to: $audioUnitType" }

        val deviceType = when (audioUnitType) {
            AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> AudioDeviceType.BLUETOOTH
            AudioUnitTypes.SPEAKER -> AudioDeviceType.SPEAKER_PHONE
            AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> AudioDeviceType.WIRED_HEADSET
            AudioUnitTypes.EARPIECE -> AudioDeviceType.EARPIECE
            else -> {
                log.w(TAG) { "Unsupported audio unit type: $audioUnitType" }
                return false
            }
        }

        return selectAudioDevice(deviceType)
    }

    override fun getActiveAudioRoute(): AudioUnitTypes? {
        val currentDevice = _currentAudioDevice.value

        return when (currentDevice) {
            AudioDeviceType.BLUETOOTH -> AudioUnitTypes.BLUETOOTH
            AudioDeviceType.SPEAKER_PHONE -> AudioUnitTypes.SPEAKER
            AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
            AudioDeviceType.EARPIECE -> AudioUnitTypes.EARPIECE
            AudioDeviceType.NONE -> null
        }
    }

    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
        updateAvailableAudioDevices()

        return _availableAudioDevices.value.mapNotNull { deviceType ->
            when (deviceType) {
                AudioDeviceType.BLUETOOTH -> AudioUnitTypes.BLUETOOTH
                AudioDeviceType.SPEAKER_PHONE -> AudioUnitTypes.SPEAKER
                AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
                AudioDeviceType.EARPIECE -> AudioUnitTypes.EARPIECE
                AudioDeviceType.NONE -> null
            }
        }.toSet()
    }


    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== AUDIO DIAGNOSIS ===")
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("Local Audio Ready: $isLocalAudioReady")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Audio Mode: ${audioManager.mode}")
            appendLine("Speaker On: ${audioManager.isSpeakerphoneOn}")
            appendLine("Mic Muted: ${audioManager.isMicrophoneMute}")
            appendLine("Current Device: ${_currentAudioDevice.value}")
            appendLine("Available Devices: ${_availableAudioDevices.value}")
            appendLine("Bluetooth SCO On: ${audioManager.isBluetoothScoOn}")
            appendLine("Wired Headset On: ${audioManager.isWiredHeadsetOn}")
        }
    }

    override fun getConnectionState(): WebRtcConnectionState {
        if (!isInitialized || peerConnection == null) {
            return WebRtcConnectionState.NEW
        }

        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
        return mapConnectionState(state)
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        // Implementation for media direction
    }

    override fun setListener(listener: WebRtcEventListener?) {
            webRtcEventListener = listener
    }

    override fun prepareAudioForIncomingCall() {
        startAudioManager()
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
            peerConnection?.setLocalDescription(description)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        if (!isInitialized || peerConnection == null) return false

        return try {
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == MediaStreamTrackKind.Audio
            }

            val dtmfSender = audioSender?.dtmf ?: return false
            dtmfSender.insertDtmf(tones, duration, gap)
        } catch (e: Exception) {
            false
        }
    }

    // Audio Management Methods (Simplified approach)

    fun startAudioManager() {
        log.d(TAG) { "Starting audio manager for call" }

        // Save current state
        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute

        // Request audio focus
        requestAudioFocus()

        // Set communication mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Update available devices and select with priority
        updateAvailableAudioDevices()
        selectDefaultAudioDeviceWithPriority()
    }

    fun stopAudioManager() {
        log.d(TAG) { "Stopping audio manager" }

        // Restore original settings
        audioManager.mode = savedAudioMode
        audioManager.isSpeakerphoneOn = savedIsSpeakerPhoneOn
        audioManager.isMicrophoneMute = savedIsMicrophoneMute

        // Release audio focus
        abandonAudioFocus()

        _currentAudioDevice.value = AudioDeviceType.NONE
    }

    private fun selectDefaultAudioDeviceWithPriority() {
        val availableDevices = _availableAudioDevices.value

        log.d(TAG) { "Selecting audio device with Bluetooth priority. Available: $availableDevices" }

        val defaultDevice = when {
            // Prioridad 1: Bluetooth (SIEMPRE tiene máxima prioridad)
            availableDevices.contains(AudioDeviceType.BLUETOOTH) -> {
                log.d(TAG) { "✅ Selecting Bluetooth as priority device" }
                AudioDeviceType.BLUETOOTH
            }
            // Prioridad 2: Wired headset (audífonos por cable)
            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> {
                log.d(TAG) { "✅ Selecting Wired Headset as priority device" }
                AudioDeviceType.WIRED_HEADSET
            }
            // Prioridad 3: Speaker (corneta del dispositivo)
            availableDevices.contains(AudioDeviceType.EARPIECE) -> {
                log.d(TAG) { "✅ Selecting Earpiece as priority device" }
                AudioDeviceType.EARPIECE
            }
            // Prioridad 4: Speaker como fallback
            else -> {
                log.d(TAG) { "✅ Selecting Speaker as default device" }
                AudioDeviceType.SPEAKER_PHONE
            }
        }

        val success = selectAudioDevice(defaultDevice)
        if (success) {
            log.d(TAG) { "✅ Audio device selected successfully: $defaultDevice" }
        } else {
            log.e(TAG) { "❌ Failed to select audio device: $defaultDevice" }
        }
    }

//    /**
//     * Selección de dispositivo por defecto con prioridad mejorada
//     */
//    private fun selectDefaultAudioDeviceWithPriority() {
//        val availableDevices = _availableAudioDevices.value
//
//        val defaultDevice = when {
//            // Prioridad 1: Bluetooth (si la auto-prioridad está habilitada)
//            _isBluetoothPriorityEnabled.value && availableDevices.contains(AudioDeviceType.BLUETOOTH) -> {
//                log.d(TAG) { "Selecting Bluetooth as default (auto-priority enabled)" }
//                AudioDeviceType.BLUETOOTH
//            }
//            // Prioridad 2: Wired headset
//            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> {
//                log.d(TAG) { "Selecting Wired Headset as default" }
//                AudioDeviceType.WIRED_HEADSET
//            }
//            // Prioridad 3: Earpiece
//            else -> {
//                log.d(TAG) { "Selecting Earpiece as default" }
//                AudioDeviceType.EARPIECE
//            }
//        }
//
//        selectAudioDevice(defaultDevice)
//    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            log.d(TAG) { "Audio focus gained" }
                            setAudioEnabled(true)
                        }

                        AudioManager.AUDIOFOCUS_LOSS -> {
                            log.d(TAG) { "Audio focus lost" }
                        }
                    }
                }
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> setAudioEnabled(true)
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun updateAvailableAudioDevices() {
        val devices = mutableListOf<AudioDeviceType>()

        // Always available
        devices.add(AudioDeviceType.EARPIECE)
        devices.add(AudioDeviceType.SPEAKER_PHONE)

        // Check for connected devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            for (deviceInfo in audioDevices) {
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        if (!devices.contains(AudioDeviceType.WIRED_HEADSET)) {
                            devices.add(AudioDeviceType.WIRED_HEADSET)
                        }
                    }

                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        if (!devices.contains(AudioDeviceType.BLUETOOTH)) {
                            devices.add(AudioDeviceType.BLUETOOTH)
                            log.d(TAG) { "Bluetooth device detected: ${deviceInfo.productName}" }
                        }
                    }
                }
            }
        } else {
            // For older versions
            if (audioManager.isWiredHeadsetOn) {
                devices.add(AudioDeviceType.WIRED_HEADSET)
            }
            if (audioManager.isBluetoothScoAvailableOffCall) {
                devices.add(AudioDeviceType.BLUETOOTH)
                log.d(TAG) { "Bluetooth SCO available" }
            }
        }

        val oldDevices = _availableAudioDevices.value
        _availableAudioDevices.value = devices

        // Log cambios
        if (oldDevices != devices) {
            log.d(TAG) { "Audio devices changed from $oldDevices to $devices" }

            // Si Bluetooth se conectó y no estaba antes, activar auto-prioridad
            if (devices.contains(AudioDeviceType.BLUETOOTH) &&
                !oldDevices.contains(AudioDeviceType.BLUETOOTH)
            ) {
                log.d(TAG) { "New Bluetooth device detected" }
                onBluetoothConnectionChanged(true)
            }
        }
    }

    /**
     * Función simplificada que SipManagerImpl puede usar
     * Maneja automáticamente la prioridad de Bluetooth
     */
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        log.d(TAG) { "Applying audio route change to: $audioUnitType" }
//
//        val deviceType = when (audioUnitType) {
//            AudioUnitTypes.BLUETOOTH -> AudioDeviceType.BLUETOOTH
//            AudioUnitTypes.SPEAKER -> AudioDeviceType.SPEAKER_PHONE
//            AudioUnitTypes.HEADSET -> AudioDeviceType.WIRED_HEADSET
//            AudioUnitTypes.EARPIECE -> AudioDeviceType.EARPIECE
//            else -> AudioDeviceType.EARPIECE
//        }
//
//        return selectAudioDevice(deviceType)
//    }
    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return setActiveAudioRoute(audioUnitType)
    }
    /**
     * Obtiene el dispositivo actualmente activo en formato AudioUnit
     */
    override fun getCurrentActiveAudioUnit(): AudioUnit? {
        val activeRoute = getActiveAudioRoute() ?: return null

        return AudioUnit(
            type = activeRoute,
            capability = AudioUnitCompatibilities.ALL,
            isCurrent = true,
            isDefault = activeRoute == AudioUnitTypes.EARPIECE
        )
    }

    /**
     * Obtiene todos los dispositivos disponibles en formato AudioUnit
     */
    override fun getAvailableAudioUnits(): Set<AudioUnit> {
        val routes = getAvailableAudioRoutes()
        val currentRoute = getActiveAudioRoute()

        return routes.map { type ->
            AudioUnit(
                type = type,
                capability = AudioUnitCompatibilities.ALL,
                isCurrent = type == currentRoute,
                isDefault = type == AudioUnitTypes.EARPIECE
            )
        }.toSet()
    }

    private fun selectDefaultAudioDevice() {
        val availableDevices = _availableAudioDevices.value

        val defaultDevice = when {
            availableDevices.contains(AudioDeviceType.BLUETOOTH) -> AudioDeviceType.BLUETOOTH
            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> AudioDeviceType.WIRED_HEADSET
            else -> AudioDeviceType.EARPIECE
        }

        selectAudioDevice(defaultDevice)
    }

    private fun selectAudioDevice(device: AudioDeviceType): Boolean {
        if (!_availableAudioDevices.value.contains(device)) {
            log.d(TAG) { "Audio device not available: $device" }
            return false
        }

        log.d(TAG) { "Selecting audio device: $device" }

        return try {
            when (device) {
                AudioDeviceType.SPEAKER_PHONE -> {
                    audioManager.isSpeakerphoneOn = true
                    stopBluetoothSco()
                }

                AudioDeviceType.EARPIECE -> {
                    audioManager.isSpeakerphoneOn = false
                    stopBluetoothSco()
                }

                AudioDeviceType.WIRED_HEADSET -> {
                    audioManager.isSpeakerphoneOn = false
                    stopBluetoothSco()
                }

                AudioDeviceType.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    startBluetoothSco()
                }

                AudioDeviceType.NONE -> return false
            }

            _currentAudioDevice.value = device
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error selecting audio device: ${e.message}" }
            false
        }
    }

    private fun startBluetoothSco() {
        if (audioManager.isBluetoothScoAvailableOffCall) {
            audioManager.startBluetoothSco()
            log.d(TAG) { "Bluetooth SCO started" }
        }
    }

    private fun stopBluetoothSco() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            log.d(TAG) { "Bluetooth SCO stopped" }
        }
    }

    /**
     * Verifica y activa automáticamente Bluetooth si está disponible y la prioridad está habilitada
     */
    private fun ensureBluetoothPriorityIfAvailable() {
        try {
            updateAvailableAudioDevices()

            if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH) &&
                _currentAudioDevice.value != AudioDeviceType.BLUETOOTH
            ) {

                log.d(TAG) { "Bluetooth available, switching automatically (priority always enabled)" }

                val success = selectAudioDevice(AudioDeviceType.BLUETOOTH)
                if (success) {
                    log.d(TAG) { "Successfully switched to Bluetooth with automatic priority" }

                    // Actualizar dispositivos actuales
                    currentInputDevice = createBluetoothMicDevice()
                    currentOutputDevice = createBluetoothDevice()

                    // Notificar cambio
                    webRtcEventListener?.onAudioDeviceChanged(createBluetoothDevice())
                } else {
                    log.w(TAG) { "Failed to auto-switch to Bluetooth" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error in ensureBluetoothPriorityIfAvailable: ${e.message}" }
        }
    }

    /**
     * Función pública para forzar verificación de prioridad Bluetooth
     * Esta es la que puede ser llamada desde SipManagerImpl
     */
    override fun refreshAudioDevicesWithBluetoothPriority() {
        log.d(TAG) { "Refreshing audio devices with Bluetooth priority check" }
        updateAvailableAudioDevices()
        ensureBluetoothPriorityIfAvailable()
    }

    /**
     * Función llamada cuando se inicia una llamada para asegurar el mejor dispositivo
     */
    override fun prepareAudioForCall() {
        log.d(TAG) { "Preparing audio for call with Bluetooth prioritization" }
        startAudioManager()
        ensureBluetoothPriorityIfAvailable()
    }

    /**
     * Función para ser llamada cuando el estado de Bluetooth cambia
     */
    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
        log.d(TAG) { "Bluetooth connection changed: $isConnected" }

        if (isConnected) {
            // Pequeño delay para asegurar que el dispositivo esté listo
            Handler(Looper.getMainLooper()).postDelayed({
                refreshAudioDevicesWithBluetoothPriority()
            }, 300)
        } else if (!isConnected) {
            // Si Bluetooth se desconecta, cambiar a siguiente mejor opción
            selectNextBestAudioDevice()
        }
    }

    private fun selectNextBestAudioDevice() {
        updateAvailableAudioDevices()

        val nextBestDevice = when {
            _availableAudioDevices.value.contains(AudioDeviceType.WIRED_HEADSET) -> AudioDeviceType.WIRED_HEADSET
            else -> AudioDeviceType.EARPIECE
        }

        selectAudioDevice(nextBestDevice)
    }

    fun toggleSpeaker() {
        val currentDevice = _currentAudioDevice.value
        val newDevice = if (currentDevice == AudioDeviceType.SPEAKER_PHONE) {
            when {
                _availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH) -> AudioDeviceType.BLUETOOTH
                _availableAudioDevices.value.contains(AudioDeviceType.WIRED_HEADSET) -> AudioDeviceType.WIRED_HEADSET
                else -> AudioDeviceType.EARPIECE
            }
        } else {
            AudioDeviceType.SPEAKER_PHONE
        }

        selectAudioDevice(newDevice)
    }

    // Helper methods to create AudioDevice objects

    private fun createBuiltinMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Built-in Microphone",
            descriptor = "builtin_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.MICROPHONE,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.EARPIECE,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 10,
            vendorInfo = "Built-in"
        )
    }

    private fun createWiredHeadsetMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset Microphone",
            descriptor = "wired_headset_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.WIRED_HEADSET,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = null
        )
    }

    private fun createBluetoothMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Bluetooth Microphone",
            descriptor = "bluetooth_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.BLUETOOTH,
                isDefault = false
            ),
            connectionState = if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH))
                DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = null
        )
    }

    private fun createEarpieceDevice(): AudioDevice {
        return AudioDevice(
            name = "Earpiece",
            descriptor = "earpiece",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.EARPIECE,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 5,
            vendorInfo = "Built-in"
        )
    }

    private fun createSpeakerDevice(): AudioDevice {
        return AudioDevice(
            name = "Speaker",
            descriptor = "speaker",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.SPEAKER,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.SPEAKER_PHONE,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 15,
            vendorInfo = "Built-in"
        )
    }

    private fun createWiredHeadsetDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset",
            descriptor = "wired_headset",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.WIRED_HEADSET,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = null
        )
    }

    private fun createBluetoothDevice(): AudioDevice {
        return AudioDevice(
            name = "Bluetooth",
            descriptor = "bluetooth",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = _currentAudioDevice.value == AudioDeviceType.BLUETOOTH,
                isDefault = false
            ),
            connectionState = if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH))
                DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = null
        )
    }

    // Helper methods

    private suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
        return MediaDevices.enumerateDevices()
    }

    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
        return when (state) {
            PeerConnectionState.New -> WebRtcConnectionState.NEW
            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
        }
    }

    private fun initializePeerConnection() {
        log.d(TAG) { "Initializing PeerConnection..." }
        cleanupCall()

        try {
            val rtcConfig = RtcConfiguration(
                iceServers = listOf(
                    IceServer(
                        urls = listOf(
                            "stun:stun.l.google.com:19302",
                            "stun:stun1.l.google.com:19302"
                        )
                    )
                )
            )

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            isLocalAudioReady = false
        } catch (e: Exception) {
            log.d(TAG) { "Error initializing PeerConnection: ${e.message}" }
            peerConnection = null
            isInitialized = false
            isLocalAudioReady = false
        }
    }

    private fun PeerConnection.setupPeerConnectionObservers() {
        onIceCandidate.onEach { candidate ->
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            when (state) {
                PeerConnectionState.Connected -> {
                    setAudioEnabled(true)
                    audioManager.isMicrophoneMute = false
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    stopAudioManager()
                }

                else -> {}
            }
            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            val track = event.receiver.track
            if (track is AudioStreamTrack) {
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true
                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }

    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: return false

            if (localAudioTrack != null) {
                return true
            }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false

            val mediaStream = MediaDevices.getUserMedia(audio = true, video = false)
            val audioTrack = mediaStream.audioTracks.firstOrNull()

            if (audioTrack != null) {
                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true
                peerConn.addTrack(audioTrack, mediaStream)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.d(TAG) { "Error getting audio: ${e.message}" }
            false
        }
    }

    private fun cleanupCall() {
        try {
            localAudioTrack?.enabled = false

            peerConnection?.let { pc ->
                pc.getSenders().forEach { sender ->
                    try {
                        pc.removeTrack(sender)
                    } catch (e: Exception) {
                        log.d(TAG) { "Error removing track: ${e.message}" }
                    }
                }
            }

            peerConnection?.close()
            peerConnection = null

            Thread.sleep(100)

            localAudioTrack = null
            remoteAudioTrack = null
            isLocalAudioReady = false

            System.gc()

        } catch (e: Exception) {
            log.d(TAG) { "Error in cleanupCall: ${e.message}" }
        }
    }
}