package com.eddyslarez.kmpsiprtc.services.webrtc
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.data.models.RecordingResult
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.SipEngineErrors
import com.eddyslarez.kmpsiprtc.data.models.SipEngineInitError
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.screencapture.DesktopScreenCaptureSupport
import com.eddyslarez.kmpsiprtc.services.audio.AudioStreamListener
import com.eddyslarez.kmpsiprtc.services.audio.DesktopAudioController
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual fun createWebRtcManager(): WebRtcManager = DesktopWebRtcManager()


class DesktopWebRtcManager : WebRtcManager {
    private val TAG = "DesktopWebRtcManager"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Controllers
    private lateinit var peerConnectionController: DesktopPeerConnectionController
    private lateinit var audioController: DesktopAudioController

    // State
    private val _isInitialized = MutableStateFlow(false)
    private var webRtcEventListener: WebRtcEventListener? = null

    @Volatile
    private var initError: SipEngineInitError? = null

    // Selección del usuario; sobrevive a dispose()/initialize() del mismo gestor.
    @Volatile
    private var preferredInputId: String? = null

    @Volatile
    private var preferredOutputId: String? = null

    // ==================== INITIALIZATION ====================

    @Synchronized
    override fun initialize() {
        log.d(TAG) { "🔧 Initializing WebRTC Manager..." }

        if (_isInitialized.value) {
            log.d(TAG) { "Already initialized" }
            return
        }

        val controller = DesktopPeerConnectionController(
            onIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
                webRtcEventListener?.onIceCandidate(candidate, sdpMid, sdpMLineIndex)
            },
            onConnectionStateChange = { state ->
                handleConnectionStateChange(state)
            },
            onRemoteAudioTrack = {
                webRtcEventListener?.onRemoteAudioTrack()
            }
        )
        try {
            controller.initialize()
        } catch (t: Throwable) {
            val error = classifyInitError(t)
            initError = error
            SipEngineErrors.report(error)
            log.e(TAG) { "WebRTC engine unavailable: ${error.message}" }
            _isInitialized.value = false
            return
        }
        peerConnectionController = controller

        try {
            audioController = DesktopAudioController(
                audioDeviceModule = controller.getAudioDeviceModule(),
                onDeviceChanged = { device ->
                    webRtcEventListener?.onAudioDeviceChanged(device)
                }
            )
            audioController.initialize()
            audioController.restorePreferences(preferredInputId, preferredOutputId)
        } catch (t: Throwable) {
            log.e(TAG) { "Audio device controller failed: ${t::class.simpleName}: ${t.message}" }
        }

        initError = null
        SipEngineErrors.clear()
        _isInitialized.value = true
        log.d(TAG) { "✅ WebRTC initialized" }
    }

    override fun getEngineInitError(): SipEngineInitError? = initError

    private fun classifyInitError(t: Throwable): SipEngineInitError {
        val platform = "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
        val root = generateSequence(t) { it.cause }.last()
        val nativeMissing = t is UnsatisfiedLinkError || root is UnsatisfiedLinkError ||
            t is ExceptionInInitializerError || t is NoClassDefFoundError ||
            (t.message ?: root.message).orEmpty().contains("Load library", ignoreCase = true)
        return when {
            nativeMissing -> SipEngineInitError.NativeLibraryUnavailable(
                platform = platform,
                message = "webrtc-java native library not available for $platform",
                cause = t
            )
            t.stackTrace.any { it.className.contains("AudioDeviceModule") } ->
                SipEngineInitError.AudioDeviceModuleFailed(
                    message = "Audio device module failed: ${t.message}",
                    cause = t
                )
            else -> SipEngineInitError.FactoryCreationFailed(
                message = "PeerConnectionFactory creation failed: ${t.message}",
                cause = t
            )
        }
    }

    override fun isInitialized(): Boolean = _isInitialized.value

    // ==================== DATA CHANNEL (para conferencias) ====================

    fun createPublisherDataChannel(label: String = "_reliable") {
        ensureInitialized()
        // El data channel requiere PeerConnection viva. Si todavía no existe
        // (porque aún no se llamó a createOffer/addLocalXxx), la creamos aquí
        // para que el data channel quede incluido en el siguiente SDP.
        if (!peerConnectionController.hasPeerConnection()) {
            peerConnectionController.createNewPeerConnection()
        }
        peerConnectionController.createPublisherDataChannel(label)
    }

    /**
     * Aplica los ICE servers (STUN + TURN con credenciales) que vienen del
     * JoinResponse de LiveKit. DEBE llamarse ANTES de createOffer/createAnswer
     * para que el PeerConnection se cree con la configuración correcta.
     *
     * @param servers Lista de (urls, username, credential) — el cliente
     *   extrae estos del LiveKitJoinResponse.iceServers.
     */
    /**
     * Override de la interfaz común: aplica ICE servers (STUN + TURN con
     * credenciales) al PeerConnection. Usado por llamadas Matrix 1:1 (TURN del
     * homeserver) — mismo camino interno que usa LiveKit en sus controllers.
     * DEBE llamarse ANTES de createOffer/createAnswer.
     */
    override fun setIceServers(servers: List<com.eddyslarez.kmpsiprtc.data.models.IceServerInfo>) {
        ensureInitialized()
        peerConnectionController.setIceServers(servers.map { Triple(it.urls, it.username, it.credential) })
    }

    fun sendDataChannelMessage(data: ByteArray): Boolean {
        return peerConnectionController.sendDataChannelMessage(data)
    }

    fun setDataChannelMessageListener(listener: ((ByteArray) -> Unit)?) {
        peerConnectionController.dataChannelMessageListener = listener
    }

    // ==================== VIDEO (para conferencias) ====================

    fun enumerateVideoDevices(): List<VideoDevice> {
        return peerConnectionController.enumerateVideoDevices()
    }

    fun addLocalVideoTrack(device: VideoDevice? = null): VideoTrack? {
        ensureInitialized()
        if (!peerConnectionController.hasPeerConnection()) {
            peerConnectionController.createNewPeerConnection()
        }
        return peerConnectionController.addLocalVideoTrack(device)
    }

    fun removeLocalVideoTrack() {
        peerConnectionController.removeLocalVideoTrack()
    }

    fun getLocalVideoTrack(): VideoTrack? {
        return peerConnectionController.getLocalVideoTrack()
    }

    fun enumerateScreenShareSources(): List<Pair<String, String>> {
        when (DesktopScreenCaptureSupport.backend()) {
            // Con el portal es el propio escritorio el que muestra el selector, así
            // que se expone una única fuente sintética; la UI, al ver una sola,
            // abre el diálogo del sistema directamente.
            DesktopScreenCaptureSupport.Backend.PORTAL ->
                return listOf(PORTAL_SOURCE_ID to PORTAL_SOURCE_ID)
            DesktopScreenCaptureSupport.Backend.NONE -> {
                log.w(TAG) { "Sin captura de pantalla: ${DesktopScreenCaptureSupport.unavailableReason()}" }
                return emptyList()
            }
            DesktopScreenCaptureSupport.Backend.WEBRTC_DESKTOP -> Unit
        }

        var capturer: ScreenCapturer? = null
        return try {
            capturer = ScreenCapturer()
            val sources = capturer.getDesktopSources().mapIndexed { index, source ->
                source.id.toString() to (source.title?.takeIf { it.isNotBlank() } ?: "Screen ${index + 1}")
            }
            if (sources.isEmpty() && isMacHost()) {
                // En macOS getDesktopSources() suele devolver vacío cuando la app NO tiene
                // permiso de "Grabación de pantalla" (System Settings > Privacy & Security >
                // Screen Recording). También puede fallar si el native de webrtc-java (0.10.0)
                // no implementa el desktop capturer de macOS. Log explícito para diagnóstico.
                log.w(TAG) {
                    "macOS: getDesktopSources() devolvió 0 pantallas. Revisa el permiso de " +
                        "'Grabación de pantalla' para la app (o el JDK) en Ajustes del Sistema. " +
                        "Si el permiso está concedido y sigue vacío, el capturer nativo de " +
                        "webrtc-java 0.10.0/macOS no soporta captura de escritorio."
                }
            }
            sources
        } catch (e: Throwable) {
            log.e(TAG) {
                "Error enumerando pantallas (os=${System.getProperty("os.name")}): " +
                    "${e::class.simpleName}: ${e.message}"
            }
            emptyList()
        } finally {
            try {
                capturer?.dispose()
            } catch (_: Throwable) {}
        }
    }

    private fun isMacHost(): Boolean =
        System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

    fun addLocalScreenShareTrack(sourceId: String? = null): VideoTrack? {
        ensureInitialized()
        if (!peerConnectionController.hasPeerConnection()) {
            peerConnectionController.createNewPeerConnection()
        }
        return peerConnectionController.addLocalScreenShareTrack(sourceId)
    }

    fun removeLocalScreenShareTrack() {
        peerConnectionController.removeLocalScreenShareTrack()
    }

    fun getLocalScreenShareTrack(): VideoTrack? {
        return peerConnectionController.getLocalScreenShareTrack()
    }


    fun setOnRemoteVideoTrack(listener: ((VideoTrack) -> Unit)?) {
        peerConnectionController.onRemoteVideoTrack = listener
    }

    fun setRemoteVideoTrackListeners(
        added: ((VideoTrack, List<String>, String?) -> Unit)?,
        removed: ((String) -> Unit)?,
    ) {
        peerConnectionController.onRemoteVideoTrackAdded = added
        peerConnectionController.onRemoteVideoTrackRemoved = removed
    }

    fun remoteVideoReceivers(): List<Pair<VideoTrack, String?>> = peerConnectionController.remoteVideoReceivers()

    // ==================== CONNECTION MANAGEMENT ====================

    override suspend fun createOffer(): String {
        log.d(TAG) { "📝 Creating offer..." }
        ensureInitialized()

        if (!peerConnectionController.hasPeerConnection()) {
            peerConnectionController.createNewPeerConnection()
        }

        if (::audioController.isInitialized) audioController.startForCall()
        return peerConnectionController.createOffer()
    }

    override suspend fun createAnswer(offerSdp: String): String {
        log.d(TAG) { "📝 Creating answer..." }
        ensureInitialized()

        if (!peerConnectionController.hasPeerConnection()) {
            peerConnectionController.createNewPeerConnection()
        }

        if (::audioController.isInitialized) audioController.startForCall()
        return peerConnectionController.createAnswer(offerSdp)
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        ensureInitialized()
        peerConnectionController.setRemoteDescription(sdp, type)
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        // Race: el ICE del SFU puede llegar antes de que `initialize()` termine
        // de poblar peerConnectionController (lateinit). Si todavía no está listo
        // descartamos el candidato — webrtc-java lo recolecta de nuevo cuando el
        // remote description se aplica.
        if (!::peerConnectionController.isInitialized) {
            log.w(TAG) { "addIceCandidate ignorado: peerConnectionController no inicializado todavía" }
            return
        }
        if (!peerConnectionController.hasPeerConnection()) {
            log.w(TAG) { "addIceCandidate ignorado: PeerConnection no creada" }
            return
        }
        try {
            peerConnectionController.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
        } catch (e: Exception) {
            log.w(TAG) { "Error agregando ICE candidate: ${e.message}" }
        }
    }

    override fun closePeerConnection() {
        log.d(TAG) { "Closing peer connection" }
        if (::peerConnectionController.isInitialized) {
            peerConnectionController.closePeerConnection()
        }
        if (::audioController.isInitialized) {
            audioController.recordingSuspended = false
            audioController.stop()
        }
    }

    override fun releaseCallResources() {
        closePeerConnection()
    }

    @Synchronized
    override fun dispose() {
        log.d(TAG) { "Disposing WebRTC Manager" }

        try {
            if (::audioController.isInitialized) {
                preferredInputId = audioController.preferredInputId ?: preferredInputId
                preferredOutputId = audioController.preferredOutputId ?: preferredOutputId
                audioController.dispose()
            }

            if (::peerConnectionController.isInitialized) {
                peerConnectionController.dispose()
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        } finally {
            coroutineScope.coroutineContext.cancelChildren()
            _isInitialized.value = false
        }
    }

    override fun getConnectionState(): WebRtcConnectionState {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.getConnectionState()
        } else {
            WebRtcConnectionState.DISCONNECTED
        }
    }

    override fun getLocalDescription(): String? {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.getLocalDescription()
        } else {
            null
        }
    }

    // ==================== AUDIO MANAGEMENT ====================

    override fun setAudioEnabled(enabled: Boolean) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setAudioEnabled(enabled)
    }

    override fun setMuted(muted: Boolean) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setMuted(muted)
    }

    override fun isMuted(): Boolean {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.isMuted()
        } else {
            false
        }
    }

    override fun setRemoteAudioEnabled(enabled: Boolean) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setRemoteAudioEnabled(enabled)
    }

    override fun isRemoteAudioEnabled(): Boolean {
        if (!::peerConnectionController.isInitialized) return true
        return peerConnectionController.isRemoteAudioEnabled()
    }

    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
        return if (::audioController.isInitialized) {
            audioController.setActiveRoute(audioUnitType)
        } else {
            false
        }
    }

    override fun getActiveAudioRoute(): AudioUnitTypes? {
        return if (::audioController.isInitialized) {
            audioController.getActiveRoute()
        } else {
            null
        }
    }

    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
        return if (::audioController.isInitialized) {
            audioController.getAvailableRoutes()
        } else {
            emptySet()
        }
    }

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return if (::audioController.isInitialized) {
            audioController.getAllDevices()
        } else {
            Pair(emptyList(), emptyList())
        }
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        return if (::audioController.isInitialized) {
            audioController.changeOutputDevice(device)
        } else {
            false
        }
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        return if (::audioController.isInitialized) {
            audioController.changeInputDevice(device)
        } else {
            false
        }
    }

    override fun getCurrentInputDevice(): AudioDevice? {
        return if (::audioController.isInitialized) {
            audioController.getCurrentInputDevice()
        } else {
            null
        }
    }

    override fun getCurrentOutputDevice(): AudioDevice? {
        return if (::audioController.isInitialized) {
            audioController.getCurrentOutputDevice()
        } else {
            null
        }
    }

    override fun getAvailableAudioUnits(): Set<AudioUnit> {
        return if (::audioController.isInitialized) {
            audioController.getAvailableAudioUnits()
        } else {
            emptySet()
        }
    }

    override fun getCurrentActiveAudioUnit(): AudioUnit? {
        return if (::audioController.isInitialized) {
            audioController.getCurrentActiveAudioUnit()
        } else {
            null
        }
    }

    override fun selectAudioInputDeviceByName(deviceName: String): Boolean = selectInput(deviceName)

    override fun selectAudioOutputDeviceByName(deviceName: String): Boolean = selectOutput(deviceName)

    override fun selectAudioInputDeviceById(deviceId: String): Boolean = selectInput(deviceId)

    override fun selectAudioOutputDeviceById(deviceId: String): Boolean = selectOutput(deviceId)

    /** Dispositivos de la sesión con id estable; se actualiza al conectar o desconectar hardware. */
    fun audioDevicesFlow(): StateFlow<List<AudioDevice>>? =
        if (::audioController.isInitialized) audioController.availableDevices else null

    private fun selectInput(query: String): Boolean {
        if (!::audioController.isInitialized) {
            preferredInputId = query
            return false
        }
        val ok = audioController.selectInputByQuery(query)
        if (ok) preferredInputId = audioController.preferredInputId
        return ok
    }

    private fun selectOutput(query: String): Boolean {
        if (!::audioController.isInitialized) {
            preferredOutputId = query
            return false
        }
        val ok = audioController.selectOutputByQuery(query)
        if (ok) preferredOutputId = audioController.preferredOutputId
        return ok
    }

    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
        if (::audioController.isInitialized) {
            audioController.refreshDevices()
        }
    }

    override fun refreshAudioDevicesWithBluetoothPriority() {
        if (::audioController.isInitialized) {
            audioController.refreshWithBluetoothPriority()
        }
    }

    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return setActiveAudioRoute(audioUnitType)
    }

    override fun prepareAudioForCall() {
        if (::audioController.isInitialized) {
            audioController.startForCall()
        }
    }

    override fun prepareAudioForIncomingCall() {
        prepareAudioForCall()
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== DESKTOP AUDIO DIAGNOSIS ===")
            appendLine("Initialized: ${_isInitialized.value}")
            appendLine("Connection State: ${getConnectionState()}")

            if (::audioController.isInitialized) {
                appendLine("\n--- Audio Controller ---")
                appendLine(audioController.diagnose())
            }

            if (::peerConnectionController.isInitialized) {
                appendLine("\n--- PeerConnection Controller ---")
                appendLine(peerConnectionController.diagnose())
            }
        }
    }

    // ==================== RECORDING ====================

    override fun startCallRecording(callId: String) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.startRecording(callId)
    }

    override suspend fun stopCallRecording(): RecordingResult? {
        if (!::peerConnectionController.isInitialized) return null
        return peerConnectionController.stopRecording()
    }

    override fun isRecordingCall(): Boolean {
        if (!::peerConnectionController.isInitialized) return false
        return peerConnectionController.isRecording()
    }

    // ==================== STREAMING EN TIEMPO REAL ====================

    override fun setAudioStreamListener(listener: AudioStreamListener?) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.setAudioStreamListener(listener)
    }

    override fun startAudioStreaming(callId: String) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.startStreaming(callId)
    }

    override fun stopAudioStreaming() {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.stopStreaming()
    }

    override fun isAudioStreaming(): Boolean {
        if (!::peerConnectionController.isInitialized) return false
        return peerConnectionController.isStreaming()
    }

    // ==================== DTMF & MEDIA DIRECTION ====================

    override fun captureDtmfSender(): ((String, Int, Int) -> Boolean)? =
        if (::peerConnectionController.isInitialized) peerConnectionController.captureDtmfSender() else null

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        return if (::peerConnectionController.isInitialized) {
            peerConnectionController.sendDtmf(tones, duration, gap)
        } else {
            false
        }
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        when (direction) {
            WebRtcManager.MediaDirection.SENDRECV -> setAudioEnabled(true)
            WebRtcManager.MediaDirection.SENDONLY -> setAudioEnabled(true)
            WebRtcManager.MediaDirection.RECVONLY -> setAudioEnabled(false)
            WebRtcManager.MediaDirection.INACTIVE -> setAudioEnabled(false)
        }
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            ensureInitialized()
            peerConnectionController.setLocalDescriptionDirect(modifiedSdp)
            true
        } catch (e: Exception) {
            log.e(TAG) { "Failed to apply modified SDP: ${e.message}" }
            false
        }
    }

    override fun setListener(listener: WebRtcEventListener?) {
        this.webRtcEventListener = listener
    }

    // ==================== INYECCIÓN DE AUDIO PARA TRADUCCIÓN ====================

    override fun setLocalAudioEnabled(enabled: Boolean) {
        if (!::peerConnectionController.isInitialized) return
        if (!enabled && ::audioController.isInitialized) audioController.recordingSuspended = true
        peerConnectionController.setLocalAudioEnabled(enabled)
        if (::audioController.isInitialized) {
            audioController.recordingSuspended = peerConnectionController.isDeviceCaptureSuspended()
        }
    }

    override fun isLocalAudioEnabled(): Boolean {
        if (!::peerConnectionController.isInitialized) return true
        return peerConnectionController.isLocalAudioEnabled()
    }

    override fun injectLocalAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.injectLocalAudio(pcmData, sampleRate, channels, bitsPerSample)
    }

    override fun injectRemoteAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (!::peerConnectionController.isInitialized) return
        peerConnectionController.injectRemoteAudio(pcmData, sampleRate, channels, bitsPerSample)
    }

    // ==================== PRIVATE HELPERS ====================

    private fun handleConnectionStateChange(state: WebRtcConnectionState) {
        when (state) {
            WebRtcConnectionState.CONNECTED -> {
                coroutineScope.launch {
                    setAudioEnabled(!isMuted())
                }
            }
            WebRtcConnectionState.DISCONNECTED,
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CLOSED -> {
                if (::audioController.isInitialized) {
                    audioController.stop()
                }
            }
            else -> {}
        }
        webRtcEventListener?.onConnectionStateChange(state)
    }

    private fun ensureInitialized() {
        if (!_isInitialized.value) {
            throw IllegalStateException("WebRTC not initialized")
        }
    }
}
