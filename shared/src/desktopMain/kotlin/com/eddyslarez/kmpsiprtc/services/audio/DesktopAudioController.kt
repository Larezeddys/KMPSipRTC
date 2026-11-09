package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import dev.onvoid.webrtc.media.audio.AudioDevice as RtcAudioDevice

/**
 * Dispositivos de audio de una llamada sobre el ADM de WebRTC: la lista, los ids
 * ([AudioDevice.descriptor]) y el dispositivo que se aplica salen del mismo módulo que reproduce y
 * captura. Sin ADM se enumera con `MediaDevices`.
 */
class DesktopAudioController(
    private var audioDeviceModule: AudioDeviceModule?,
    private val onDeviceChanged: (AudioDevice?) -> Unit
) {
    private val TAG = "DesktopAudioController"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var inputs: List<AudioDevice> = emptyList()

    @Volatile
    private var outputs: List<AudioDevice> = emptyList()

    @Volatile
    private var isStarted = false

    /** Mientras se inyecta audio de traducción el ADM no captura: cambiar de micro no debe reabrirlo. */
    @Volatile
    internal var recordingSuspended = false

    @Volatile
    private var activeInputId: String? = null

    @Volatile
    private var activeOutputId: String? = null

    @Volatile
    var preferredInputId: String? = null
        private set

    @Volatile
    var preferredOutputId: String? = null
        private set

    private var monitoring = false

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    fun updateAudioDeviceModule(adm: AudioDeviceModule?) {
        synchronized(lock) {
            audioDeviceModule = adm
            activeInputId = null
            activeOutputId = null
        }
        scanDevices()
        reapplyPreferred()
    }

    // ==================== INITIALIZATION ====================

    fun initialize() {
        scanDevices()
        synchronized(lock) {
            if (!monitoring) {
                monitoring = true
                DesktopAudioDevices.startMonitoring()
                coroutineScope.launch {
                    DesktopAudioDevices.devices.drop(1).collect { onSystemDevicesChanged() }
                }
            }
        }
        log.d(TAG) { "AudioController initialized: ${inputs.size} inputs, ${outputs.size} outputs" }
    }

    /** Restaura la selección de una sesión anterior del mismo gestor. */
    fun restorePreferences(inputId: String?, outputId: String?) {
        preferredInputId = inputId
        preferredOutputId = outputId
        reapplyPreferred()
    }

    fun startForCall() {
        isStarted = true
        scanDevices()
        reapplyPreferred()
    }

    fun stop() {
        isStarted = false
    }

    fun dispose() {
        stop()
        synchronized(lock) {
            if (monitoring) {
                monitoring = false
                DesktopAudioDevices.stopMonitoring()
            }
            audioDeviceModule = null
        }
        coroutineScope.cancel()
    }

    // ==================== DEVICE MANAGEMENT ====================

    fun setActiveRoute(audioUnitType: AudioUnitTypes): Boolean {
        val device = outputs.firstOrNull { it.audioUnit.type == audioUnitType }
            ?: run {
                scanDevices()
                outputs.firstOrNull { it.audioUnit.type == audioUnitType }
            }
            ?: return false.also { log.w(TAG) { "Route $audioUnitType not available" } }
        return selectOutput(device, remember = true)
    }

    fun getActiveRoute(): AudioUnitTypes? =
        getCurrentOutputDevice()?.audioUnit?.type ?: AudioUnitTypes.SPEAKER

    fun getAvailableRoutes(): Set<AudioUnitTypes> =
        outputs.map { it.audioUnit.type }.toSet().ifEmpty { setOf(AudioUnitTypes.SPEAKER) }

    fun getAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        scanDevices()
        return inputs to outputs
    }

    /** Devuelve `false` si el dispositivo no existe o el ADM lo rechaza. */
    fun changeOutputDevice(device: AudioDevice): Boolean {
        val target = resolveOutput(device.descriptor) ?: resolveOutput(device.name)
        if (target == null) {
            log.w(TAG) { "Output device not found: ${device.name}" }
            return false
        }
        return selectOutput(target, remember = true)
    }

    /** Devuelve `false` si el dispositivo no existe o el ADM lo rechaza. */
    fun changeInputDevice(device: AudioDevice): Boolean {
        val target = resolveInput(device.descriptor) ?: resolveInput(device.name)
        if (target == null) {
            log.w(TAG) { "Input device not found: ${device.name}" }
            return false
        }
        return selectInput(target, remember = true)
    }

    /** Acepta id estable, nombre exacto o nombre equivalente (selecciones antiguas guardadas por nombre). */
    fun selectOutputByQuery(query: String): Boolean {
        val target = resolveOutput(query) ?: return false.also { log.w(TAG) { "Output device not found for query" } }
        return selectOutput(target, remember = true)
    }

    fun selectInputByQuery(query: String): Boolean {
        val target = resolveInput(query) ?: return false.also { log.w(TAG) { "Input device not found for query" } }
        return selectInput(target, remember = true)
    }

    fun getCurrentInputDevice(): AudioDevice? =
        inputs.firstOrNull { it.descriptor == activeInputId }
            ?: inputs.firstOrNull { it.audioUnit.isDefault }
            ?: inputs.firstOrNull()

    fun getCurrentOutputDevice(): AudioDevice? =
        outputs.firstOrNull { it.descriptor == activeOutputId }
            ?: outputs.firstOrNull { it.audioUnit.isDefault }
            ?: outputs.firstOrNull()

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        scanDevices()
        return (inputs + outputs).map { it.audioUnit }.toSet()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? =
        getCurrentOutputDevice()?.audioUnit?.copy(isCurrent = true)

    fun updateCurrentDeviceState() {
        refreshCurrentFlags()
    }

    fun refreshDevices() {
        scanDevices()
        onDeviceChanged(getCurrentOutputDevice())
    }

    /** Sin selección del usuario prioriza Bluetooth, auriculares con micro y auriculares. */
    fun refreshWithBluetoothPriority() {
        scanDevices()
        if (preferredOutputId != null) {
            reapplyPreferred()
            return
        }
        val types = outputs.map { it.audioUnit.type }.toSet()
        val priority = listOf(AudioUnitTypes.BLUETOOTH, AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES)
            .firstOrNull { it in types } ?: return
        outputs.firstOrNull { it.audioUnit.type == priority }?.let { selectOutput(it, remember = false) }
    }

    // ==================== PRIVATE HELPERS ====================

    private fun resolveOutput(query: String): AudioDevice? {
        if (outputs.isEmpty()) scanDevices()
        return AudioDeviceNameMatcher.resolve(query, outputs, { it.descriptor }, { it.name })
            ?: run {
                scanDevices()
                AudioDeviceNameMatcher.resolve(query, outputs, { it.descriptor }, { it.name })
            }
    }

    private fun resolveInput(query: String): AudioDevice? {
        if (inputs.isEmpty()) scanDevices()
        return AudioDeviceNameMatcher.resolve(query, inputs, { it.descriptor }, { it.name })
            ?: run {
                scanDevices()
                AudioDeviceNameMatcher.resolve(query, inputs, { it.descriptor }, { it.name })
            }
    }

    private fun selectOutput(device: AudioDevice, remember: Boolean): Boolean {
        val native = device.nativeDevice as? RtcAudioDevice
        val adm = audioDeviceModule
        if (adm != null && native != null && !applyPlayout(adm, native)) return false
        synchronized(lock) {
            if (remember) preferredOutputId = device.descriptor
            activeOutputId = device.descriptor
        }
        DesktopAudioRouting.selectedOutputName = native?.name
        refreshCurrentFlags()
        log.d(TAG) { "Output device selected: ${device.name}" }
        onDeviceChanged(device)
        return true
    }

    private fun selectInput(device: AudioDevice, remember: Boolean): Boolean {
        val native = device.nativeDevice as? RtcAudioDevice
        val adm = audioDeviceModule
        if (adm != null && native != null && !applyRecording(adm, native)) return false
        synchronized(lock) {
            if (remember) preferredInputId = device.descriptor
            activeInputId = device.descriptor
        }
        DesktopAudioRouting.selectedInputName = native?.name
        refreshCurrentFlags()
        log.d(TAG) { "Input device selected: ${device.name}" }
        onDeviceChanged(device)
        return true
    }

    /**
     * El ADM rechaza cambiar de dispositivo con la reproducción ya inicializada; en llamada se
     * detiene, se cambia y se vuelve a arrancar.
     */
    private fun applyPlayout(adm: AudioDeviceModule, device: RtcAudioDevice): Boolean {
        try {
            adm.setPlayoutDevice(device)
            return true
        } catch (first: Throwable) {
            if (!isStarted) {
                log.w(TAG) { "WebRTC rejected playout device: ${first.message}" }
                return false
            }
        }
        return try {
            adm.stopPlayout()
            adm.setPlayoutDevice(device)
            adm.initPlayout()
            adm.startPlayout()
            true
        } catch (t: Throwable) {
            log.e(TAG) { "Error switching playout device during call: ${t.message}" }
            false
        }
    }

    private fun applyRecording(adm: AudioDeviceModule, device: RtcAudioDevice): Boolean {
        try {
            adm.setRecordingDevice(device)
            return true
        } catch (first: Throwable) {
            if (!isStarted) {
                log.w(TAG) { "WebRTC rejected recording device: ${first.message}" }
                return false
            }
        }
        return try {
            adm.stopRecording()
            adm.setRecordingDevice(device)
            if (!recordingSuspended) {
                adm.initRecording()
                adm.startRecording()
            }
            true
        } catch (t: Throwable) {
            log.e(TAG) { "Error switching recording device during call: ${t.message}" }
            false
        }
    }

    private fun reapplyPreferred() {
        preferredOutputId?.let { id ->
            if (id != activeOutputId) {
                AudioDeviceNameMatcher.resolve(id, outputs, { it.descriptor }, { it.name })
                    ?.let { selectOutput(it, remember = false) }
            }
        }
        preferredInputId?.let { id ->
            if (id != activeInputId) {
                AudioDeviceNameMatcher.resolve(id, inputs, { it.descriptor }, { it.name })
                    ?.let { selectInput(it, remember = false) }
            }
        }
    }

    private fun onSystemDevicesChanged() {
        val before = inputs.map { it.descriptor } to outputs.map { it.descriptor }
        scanDevices()
        val after = inputs.map { it.descriptor } to outputs.map { it.descriptor }
        if (before == after) return

        activeOutputId?.let { id ->
            if (outputs.none { it.descriptor == id }) {
                log.i(TAG) { "Active output device disconnected; falling back to system default" }
                activeOutputId = null
                DesktopAudioRouting.selectedOutputName = null
                (outputs.firstOrNull { it.audioUnit.isDefault } ?: outputs.firstOrNull())
                    ?.let { selectOutput(it, remember = false) }
            }
        }
        activeInputId?.let { id ->
            if (inputs.none { it.descriptor == id }) {
                log.i(TAG) { "Active input device disconnected; falling back to system default" }
                activeInputId = null
                DesktopAudioRouting.selectedInputName = null
                (inputs.firstOrNull { it.audioUnit.isDefault } ?: inputs.firstOrNull())
                    ?.let { selectInput(it, remember = false) }
            }
        }
        reapplyPreferred()
        onDeviceChanged(getCurrentOutputDevice())
    }

    private fun scanDevices() {
        val adm = audioDeviceModule
        val defaultIn = DesktopAudioDevices.defaultCaptureDevice()
        val defaultOut = DesktopAudioDevices.defaultRenderDevice()
        val rtcInputs = adm?.let { module -> runCatching { module.recordingDevices.orEmpty().filterNotNull() }.getOrNull() }
            ?: DesktopAudioDevices.captureDevices()
        val rtcOutputs = adm?.let { module -> runCatching { module.playoutDevices.orEmpty().filterNotNull() }.getOrNull() }
            ?: DesktopAudioDevices.renderDevices()

        val newInputs = rtcInputs.map { toAudioDevice(it, isOutput = false, isDefault = DesktopAudioDevices.isDefault(it, defaultIn)) }
            .ifEmpty { listOf(createDefaultMicrophone()) }
        val newOutputs = rtcOutputs.map { toAudioDevice(it, isOutput = true, isDefault = DesktopAudioDevices.isDefault(it, defaultOut)) }
            .ifEmpty { listOf(createDefaultSpeaker()) }

        synchronized(lock) {
            inputs = newInputs
            outputs = newOutputs
        }
        refreshCurrentFlags()
    }

    private fun refreshCurrentFlags() {
        synchronized(lock) {
            val currentIn = activeInputId
            val currentOut = activeOutputId
            inputs = inputs.map { it.withCurrent(if (currentIn == null) it.audioUnit.isDefault else it.descriptor == currentIn) }
            outputs = outputs.map { it.withCurrent(if (currentOut == null) it.audioUnit.isDefault else it.descriptor == currentOut) }
            _availableDevices.value = inputs + outputs
        }
    }

    private fun AudioDevice.withCurrent(current: Boolean): AudioDevice =
        if (audioUnit.isCurrent == current) this else copy(audioUnit = audioUnit.copy(isCurrent = current))

    private fun toAudioDevice(device: RtcAudioDevice, isOutput: Boolean, isDefault: Boolean): AudioDevice {
        val name = device.name.orEmpty()
        val type = if (isOutput) detectOutputDeviceType(name) else detectInputDeviceType(name)
        return AudioDevice(
            name = name,
            descriptor = DesktopAudioDevices.idOf(device),
            nativeDevice = device,
            isOutput = isOutput,
            audioUnit = AudioUnit(
                type = type,
                capability = if (isOutput) AudioUnitCompatibilities.PLAY else AudioUnitCompatibilities.RECORD,
                isCurrent = false,
                isDefault = isDefault
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = type == AudioUnitTypes.BLUETOOTH,
            supportsHDVoice = !isOutput || type != AudioUnitTypes.SPEAKER,
            latency = when (type) {
                AudioUnitTypes.BLUETOOTH -> 50
                AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> 10
                else -> 20
            }
        )
    }

    private fun createDefaultMicrophone(): AudioDevice = AudioDevice(
        name = "Default Microphone",
        descriptor = "default_mic",
        nativeDevice = null,
        isOutput = false,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.MICROPHONE,
            capability = AudioUnitCompatibilities.RECORD,
            isCurrent = true,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        supportsHDVoice = true,
        latency = 10
    )

    private fun createDefaultSpeaker(): AudioDevice = AudioDevice(
        name = "Default Speaker",
        descriptor = "default_speaker",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.SPEAKER,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = true,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        latency = 20
    )

    private fun detectInputDeviceType(name: String): AudioUnitTypes {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("bluetooth") -> AudioUnitTypes.BLUETOOTH
            lowerName.contains("headset") || lowerName.contains("headphone") -> AudioUnitTypes.HEADSET
            lowerName.contains("usb") -> AudioUnitTypes.USB
            else -> AudioUnitTypes.MICROPHONE
        }
    }

    private fun detectOutputDeviceType(name: String): AudioUnitTypes {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("bluetooth") -> AudioUnitTypes.BLUETOOTH
            lowerName.contains("headset") -> AudioUnitTypes.HEADSET
            lowerName.contains("headphone") || lowerName.contains("auricular") -> AudioUnitTypes.HEADPHONES
            lowerName.contains("hdmi") || lowerName.contains("displayport") -> AudioUnitTypes.HDMI
            lowerName.contains("usb") -> AudioUnitTypes.USB
            else -> AudioUnitTypes.SPEAKER
        }
    }

    // ==================== DIAGNOSTICS ====================

    fun diagnose(): String {
        val (ins, outs) = getAllDevices()
        return buildString {
            appendLine("=== Desktop Audio Diagnostics ===")
            appendLine("Started: $isStarted")
            appendLine("ADM: ${audioDeviceModule != null}")
            appendLine("Active Route: ${getActiveRoute()}")
            appendLine("Current Input: ${getCurrentInputDevice()?.name ?: "none"}")
            appendLine("Current Output: ${getCurrentOutputDevice()?.name ?: "none"}")
            appendLine("\nInput Devices: ${ins.size}")
            ins.forEach { appendLine("  - ${it.name} (${it.audioUnit.type}) current=${it.audioUnit.isCurrent}") }
            appendLine("\nOutput Devices: ${outs.size}")
            outs.forEach { appendLine("  - ${it.name} (${it.audioUnit.type}) current=${it.audioUnit.isCurrent}") }
        }
    }
}
