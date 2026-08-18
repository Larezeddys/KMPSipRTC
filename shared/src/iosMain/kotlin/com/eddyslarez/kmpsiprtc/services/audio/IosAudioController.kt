package com.eddyslarez.kmpsiprtc.services.audio


import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import platform.AVFAudio.*
import platform.Foundation.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
class IosAudioController(
    private val onDeviceChanged: (AudioDevice?) -> Unit
) {
    private companion object {
        /** Intentos de enganche del puerto HFP tras pedir ruta Bluetooth. */
        const val BLUETOOTH_HFP_RETRIES = 6
        const val BLUETOOTH_HFP_RETRY_DELAY_MS = 300L

        /** Ventana en la que se ignoran cambios de ruta provocados por una re-aplicacion. */
        const val REAPPLY_COOLDOWN_MS = 600L
    }

    private val TAG = "IosAudioController"
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Thread-safety usando Mutex (compatible con Kotlin/Native)
    private val audioDevicesMutex = Mutex()
    private val audioDevices = mutableListOf<AudioDevice>()

    private var savedAudioCategory: String? = null
    private var isStarted = false
    private var audioSessionConfigured = false

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    // Flag para evitar escaneos concurrentes
    @Volatile
    private var isScanning = false

    // Job de debounce para el observer de cambio de ruta
    private var routeChangeJob: Job? = null

    /**
     * Ruta que el usuario (o la prioridad por defecto) pidio para esta llamada.
     *
     * iOS renegocia la ruta por su cuenta varias veces al empezar una llamada: al activar
     * la sesion, cuando CallKit la reactiva en didActivateAudioSession, y cuando unos
     * AirPods pasan de A2DP a HFP. Cada renegociacion descartaba la seleccion y el audio
     * acababa en el camino interno aunque la UI siguiera marcando el Bluetooth. Guardarla
     * permite re-asentarla en el observer de cambios de ruta.
     */
    @Volatile
    private var desiredRoute: AudioUnitTypes? = null

    /** Evita que re-aplicar la ruta dispare otra re-aplicacion en bucle. */
    @Volatile
    private var isReapplyingRoute = false

    /** Reintentos de enganche del puerto HFP (los AirPods tardan en exponerlo). */
    private var bluetoothRetryJob: Job? = null

    // ==================== INITIALIZATION ====================

    fun initialize() {
        log.d(TAG) { "Initializing AudioController" }

        // Registrar observer para cambios de ruta de audio
        setupAudioRouteChangeObserver()

        scanDevices()
        log.d(TAG) { "✅ AudioController initialized" }
    }

    private fun setupAudioRouteChangeObserver() {
        val notificationCenter = NSNotificationCenter.defaultCenter

        notificationCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = null
        ) { notification ->
            log.d(TAG) { "Audio route changed" }
            // Debounce: cancelar job anterior si llega otro cambio rapido
            routeChangeJob?.cancel()
            routeChangeJob = coroutineScope.launch {
                delay(300)
                scanDevices()
                // Re-asentar la ruta pedida: iOS acaba de renegociar (activacion de la
                // sesion, CallKit, o AirPods saltando de A2DP a HFP) y sin esto la
                // seleccion del usuario se perdia en silencio.
                reapplyDesiredRouteIfNeeded()
                onDeviceChanged(getCurrentOutputDevice())
            }
        }
    }

    fun startForCall() {
        if (isStarted) {
            log.d(TAG) { "Audio already started" }
            return
        }

        log.d(TAG) { "🔊 Starting audio for call" }

        val audioSession = AVAudioSession.sharedInstance()
        savedAudioCategory = audioSession.category

        if (!configureAudioSession()) {
            log.w(TAG) { "Failed to configure audio session (CallKit may activate it later)" }
        }

        scanDevices()
        selectDefaultDeviceWithPriority()

        isStarted = true
        log.d(TAG) { "✅ Audio started" }
    }

    fun stop() {
        if (!isStarted) return

        log.d(TAG) { "🔇 Stopping audio" }

        val audioSession = AVAudioSession.sharedInstance()

        // Restaurar categoría anterior
        savedAudioCategory?.let { category ->
            try {
                audioSession.setCategory(category, null)
            } catch (e: Exception) {
                log.w(TAG) { "Error restoring audio category: ${e.message}" }
            }
        }

        try {
            audioSession.setActive(false, null)
        } catch (e: Exception) {
            log.w(TAG) { "Error deactivating audio session: ${e.message}" }
        }

        isStarted = false
        audioSessionConfigured = false
        // La ruta pedida es por llamada: no debe sobrevivir a la siguiente.
        desiredRoute = null
        isReapplyingRoute = false
        bluetoothRetryJob?.cancel()
        bluetoothRetryJob = null
        log.d(TAG) { "✅ Audio stopped" }
    }

    fun dispose() {
        stop()

        // Remover observers
        NSNotificationCenter.defaultCenter.removeObserver(this)

        coroutineScope.cancel()
        log.d(TAG) { "AudioController disposed" }
    }

    // ==================== AUDIO SESSION MANAGEMENT ====================

    private fun configureAudioSession(): Boolean {
        return try {
            val audioSession = AVAudioSession.sharedInstance()

            // Configurar categoría para llamadas de voz.
            // Las opciones DEBEN coincidir con las que aplica la app en el callback
            // didActivateAudioSession de CallKit (AllowBluetooth + AllowBluetoothA2DP).
            // Cuando diferian, la ultima configuracion en ejecutarse ganaba y el
            // comportamiento con AirPods dependia del timing: unas veces enrutaba y
            // otras no.
            val success1 = audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionCategoryOptionAllowBluetooth or
                        AVAudioSessionCategoryOptionAllowBluetoothA2DP,
                null
            )
            if (!success1) {
                log.e(TAG) { "Failed to set audio category" }
                return false
            }

            // Configurar modo de voz
            val success2 = audioSession.setMode(AVAudioSessionModeVoiceChat, null)
            if (!success2) {
                log.e(TAG) { "Failed to set audio mode" }
                return false
            }

            // Activar sesión
            val success3 = audioSession.setActive(true, null)
            if (!success3) {
                log.e(TAG) { "Failed to activate audio session" }
                return false
            }

            audioSessionConfigured = true
            log.d(TAG) { "✅ Audio session configured" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Exception configuring audio session: ${e.message}" }
            false
        }
    }

    // ==================== DEVICE MANAGEMENT ====================

    fun setActiveRoute(audioUnitType: AudioUnitTypes): Boolean =
        setActiveRoute(audioUnitType, remember = true)

    /**
     * @param remember guarda la ruta como "la que el usuario quiere", para re-asentarla
     *   cuando iOS renegocie. Las re-aplicaciones internas pasan false para no
     *   reescribir la intencion original.
     */
    private fun setActiveRoute(audioUnitType: AudioUnitTypes, remember: Boolean): Boolean {
        val audioSession = AVAudioSession.sharedInstance()

        return try {
            log.d(TAG) { "Setting active route to: $audioUnitType (remember=$remember)" }
            if (remember) {
                desiredRoute = audioUnitType
                bluetoothRetryJob?.cancel()
            }

            when (audioUnitType) {
                AudioUnitTypes.SPEAKER -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideSpeaker,
                        null
                    )
                    log.d(TAG) { "✅ Switched to SPEAKER" }
                }
                AudioUnitTypes.EARPIECE -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideNone,
                        null
                    )
                    log.d(TAG) { "✅ Switched to EARPIECE" }
                }
                AudioUnitTypes.BLUETOOTH -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideNone,
                        null
                    )
                    // Forzar la entrada al puerto HFP del Bluetooth. Sin setPreferredInput,
                    // iOS puede dejar la ruta en el micro/altavoz interno aunque el BT este
                    // disponible, y seleccionar "Bluetooth" en el picker no movia el audio.
                    val hfpInput = audioSession.availableInputs
                        ?.mapNotNull { it as? AVAudioSessionPortDescription }
                        ?.firstOrNull { it.portType == AVAudioSessionPortBluetoothHFP }
                    if (hfpInput != null) {
                        audioSession.setPreferredInput(hfpInput, null)
                        log.d(TAG) { "✅ Switched to BLUETOOTH (preferredInput=${hfpInput.portName})" }
                    } else {
                        // Sin puerto HFP todavia. Es lo normal con AirPods: al empezar la
                        // llamada siguen en A2DP (perfil de media, sin microfono) y solo
                        // exponen el HFP cuando la sesion PlayAndRecord ya esta activa.
                        // Antes se salia en silencio y la ruta se quedaba en el camino
                        // interno: ni se oia ni entraba el micro, con la UI marcando
                        // Bluetooth. Ahora se reintenta hasta que el puerto aparece.
                        log.w(TAG) { "BLUETOOTH sin puerto HFP aun; reintentando enganche" }
                        scheduleBluetoothInputRetry()
                    }
                }
                AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
                    audioSession.overrideOutputAudioPort(
                        AVAudioSessionPortOverrideNone,
                        null
                    )
                    log.d(TAG) { "✅ Switched to HEADSET" }
                }
                else -> {
                    log.w(TAG) { "Unsupported audio route: $audioUnitType" }
                    return false
                }
            }

            // Actualizar dispositivos después del cambio
            coroutineScope.launch {
                delay(200)
                scanDevices()
                onDeviceChanged(getCurrentOutputDevice())
            }

            true
        } catch (e: Exception) {
            log.e(TAG) { "Error setting route: ${e.message}" }
            false
        }
    }

    fun getActiveRoute(): AudioUnitTypes? {
        val audioSession = AVAudioSession.sharedInstance()
        val currentRoute = audioSession.currentRoute
        val outputs = currentRoute.outputs

        if (outputs.isEmpty()) {
            return AudioUnitTypes.EARPIECE
        }

        val firstOutput = outputs.firstOrNull() as? AVAudioSessionPortDescription
        val portType = firstOutput?.portType

        return when (portType) {
            AVAudioSessionPortBuiltInSpeaker -> AudioUnitTypes.SPEAKER
            AVAudioSessionPortBuiltInReceiver -> AudioUnitTypes.EARPIECE
            AVAudioSessionPortBluetoothHFP -> AudioUnitTypes.BLUETOOTH
            AVAudioSessionPortBluetoothA2DP -> AudioUnitTypes.BLUETOOTH
            AVAudioSessionPortHeadphones -> AudioUnitTypes.HEADPHONES
            AVAudioSessionPortHeadsetMic -> AudioUnitTypes.HEADSET
            else -> AudioUnitTypes.EARPIECE
        }
    }

    fun getAvailableRoutes(): Set<AudioUnitTypes> {
        val routes = mutableSetOf<AudioUnitTypes>()

        // Siempre disponibles
        routes.add(AudioUnitTypes.EARPIECE)
        routes.add(AudioUnitTypes.SPEAKER)

        // Verificar dispositivos disponibles
        val audioSession = AVAudioSession.sharedInstance()

        // Bluetooth
        val hasBluetoothInput = audioSession.availableInputs?.any { input ->
            val port = input as? AVAudioSessionPortDescription
            port?.portType == AVAudioSessionPortBluetoothHFP
        } ?: false

        if (hasBluetoothInput) {
            routes.add(AudioUnitTypes.BLUETOOTH)
        }

        // Headphones/Headset (detectar por ruta actual)
        val currentRoute = audioSession.currentRoute
        currentRoute.outputs.forEach { output ->
            val port = output as? AVAudioSessionPortDescription
            when (port?.portType) {
                AVAudioSessionPortHeadphones -> routes.add(AudioUnitTypes.HEADPHONES)
                AVAudioSessionPortHeadsetMic -> routes.add(AudioUnitTypes.HEADSET)
            }
        }

        return routes
    }

    fun getAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        // CRÍTICO: Crear copia inmutable ANTES de filtrar
        // Esto evita ConcurrentModificationException
        val devicesCopy = audioDevices.toList()

        val inputs = devicesCopy.filter { !it.isOutput }
        val outputs = devicesCopy.filter { it.isOutput }

        log.d(TAG) { "All devices - Inputs: ${inputs.size}, Outputs: ${outputs.size}" }
        return Pair(inputs, outputs)
    }

    fun changeOutputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing output device to: ${device.name}" }
        val success = setActiveRoute(device.audioUnit.type)
        if (success) {
            onDeviceChanged(device)
        }
        return success
    }

    fun changeInputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Input device change requested (iOS auto-manages)" }
        return true
    }

    fun getCurrentInputDevice(): AudioDevice? {
        // Crear copia para evitar ConcurrentModificationException
        val devicesCopy = audioDevices.toList()
        return devicesCopy.firstOrNull { !it.isOutput && it.audioUnit.isCurrent }
    }

    fun getCurrentOutputDevice(): AudioDevice? {
        val activeRoute = getActiveRoute()
        // Crear copia para evitar ConcurrentModificationException
        val devicesCopy = audioDevices.toList()
        return devicesCopy.firstOrNull {
            it.isOutput && it.audioUnit.type == activeRoute
        }
    }

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        scanDevices()
        // Crear copia para evitar ConcurrentModificationException
        val devicesCopy = audioDevices.toList()
        return devicesCopy.map { it.audioUnit }.toSet()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        return getCurrentOutputDevice()?.audioUnit?.copy(isCurrent = true)
    }

    fun refreshDevices() {
        scanDevices()
        onDeviceChanged(getCurrentOutputDevice())
    }

    fun refreshWithBluetoothPriority() {
        scanDevices()
        selectDefaultDeviceWithPriority()
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Reintenta enganchar el microfono del Bluetooth hasta que iOS expone el puerto HFP.
     *
     * Los AirPods entran en la llamada en A2DP y tardan unos cientos de ms en ofrecer el
     * perfil HFP. Un unico intento en [setActiveRoute] llegaba siempre demasiado pronto.
     */
    private fun scheduleBluetoothInputRetry() {
        bluetoothRetryJob?.cancel()
        bluetoothRetryJob = coroutineScope.launch {
            repeat(BLUETOOTH_HFP_RETRIES) { attempt ->
                delay(BLUETOOTH_HFP_RETRY_DELAY_MS)

                // El usuario pudo cambiar de ruta mientras esperabamos.
                if (desiredRoute != AudioUnitTypes.BLUETOOTH) return@launch

                val session = AVAudioSession.sharedInstance()
                val hfp = session.availableInputs
                    ?.mapNotNull { it as? AVAudioSessionPortDescription }
                    ?.firstOrNull { it.portType == AVAudioSessionPortBluetoothHFP }

                if (hfp != null) {
                    session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, null)
                    session.setPreferredInput(hfp, null)
                    log.d(TAG) { "✅ HFP enganchado en intento ${attempt + 1} (${hfp.portName})" }
                    scanDevices()
                    onDeviceChanged(getCurrentOutputDevice())
                    return@launch
                }
            }
            log.w(TAG) { "No se pudo enganchar el HFP tras $BLUETOOTH_HFP_RETRIES intentos" }
        }
    }

    /**
     * Vuelve a aplicar [desiredRoute] si iOS movio la ruta por su cuenta.
     *
     * Se llama desde el observer de cambios de ruta. El guard [isReapplyingRoute] evita el
     * bucle: re-aplicar genera otra notificacion de cambio de ruta.
     */
    private fun reapplyDesiredRouteIfNeeded() {
        val wanted = desiredRoute ?: return
        if (isReapplyingRoute) return

        val current = getActiveRoute()
        if (current == wanted) return

        log.d(TAG) { "Ruta actual ($current) != pedida ($wanted); re-aplicando" }
        isReapplyingRoute = true
        setActiveRoute(wanted, remember = false)
        coroutineScope.launch {
            delay(REAPPLY_COOLDOWN_MS)
            isReapplyingRoute = false
        }
    }

    private fun selectDefaultDeviceWithPriority() {
        // Crear copia para evitar ConcurrentModificationException
        val devicesCopy = audioDevices.toList()
        val availableTypes = devicesCopy
            .filter { it.isOutput }
            .map { it.audioUnit.type }
            .toSet()

        log.d(TAG) { "Available output devices: $availableTypes" }

        val priorityType = when {
            AudioUnitTypes.BLUETOOTH in availableTypes -> {
                log.d(TAG) { "✅ Selecting BLUETOOTH (highest priority)" }
                AudioUnitTypes.BLUETOOTH
            }
            AudioUnitTypes.HEADSET in availableTypes ||
                    AudioUnitTypes.HEADPHONES in availableTypes -> {
                log.d(TAG) { "Selecting HEADSET/HEADPHONES" }
                availableTypes.firstOrNull {
                    it == AudioUnitTypes.HEADSET || it == AudioUnitTypes.HEADPHONES
                } ?: AudioUnitTypes.EARPIECE
            }
            else -> {
                log.d(TAG) { "Selecting EARPIECE (default)" }
                AudioUnitTypes.EARPIECE
            }
        }

        setActiveRoute(priorityType)
    }

    private fun scanDevices() {
        // Evitar escaneos concurrentes
        if (isScanning) {
            log.d(TAG) { "Already scanning, skipping..." }
            return
        }

        isScanning = true

        try {
            // Construir lista local para evitar OOM por acceso concurrente a audioDevices
            val newDevices = mutableListOf<AudioDevice>()

            val audioSession = AVAudioSession.sharedInstance()
            val currentRoute = audioSession.currentRoute
            val currentOutputPort = currentRoute.outputs.firstOrNull()?.let {
                (it as AVAudioSessionPortDescription).portType
            }

            // Dispositivos siempre disponibles
            newDevices.add(createMicrophoneDevice())
            newDevices.add(createEarpieceDevice(currentOutputPort))
            newDevices.add(createSpeakerDevice(currentOutputPort))

            // Detectar Bluetooth REALMENTE conectados.
            // Para outputs Bluetooth (A2DP/LE), la fuente autoritativa es currentRoute.outputs:
            // un BT pareado pero no conectado NO aparece allí. Para HFP usamos availableInputs
            // porque HFP es bidireccional y AVAudioSession lo expone como input enumerable
            // cuando hay un dispositivo realmente conectado con ese perfil.
            val seenDescriptors = mutableSetOf<String>()

            audioSession.availableInputs?.forEach { input ->
                (input as? AVAudioSessionPortDescription)?.let { port ->
                    if (port.portType == AVAudioSessionPortBluetoothHFP) {
                        val desc = port.portType ?: "bluetooth_hfp"
                        if (seenDescriptors.add(desc)) {
                            newDevices.add(createBluetoothDevice(port, currentOutputPort))
                        }
                    }
                }
            }

            // BT A2DP / BT LE / Headphones / Headset: enumerar SOLO outputs en uso.
            currentRoute.outputs.forEach { output ->
                (output as? AVAudioSessionPortDescription)?.let { port ->
                    val pt = port.portType ?: return@let
                    when (pt) {
                        AVAudioSessionPortBluetoothA2DP,
                        AVAudioSessionPortBluetoothLE -> {
                            if (seenDescriptors.add(pt)) {
                                newDevices.add(createBluetoothDevice(port, currentOutputPort))
                            }
                        }
                        AVAudioSessionPortHeadphones -> {
                            if (seenDescriptors.add(pt)) {
                                newDevices.add(createHeadphonesDevice(port, currentOutputPort))
                            }
                        }
                        AVAudioSessionPortHeadsetMic -> {
                            if (seenDescriptors.add(pt)) {
                                newDevices.add(createHeadsetDevice(port, currentOutputPort))
                            }
                        }
                    }
                }
            }

            // Reemplazar atomicamente la lista compartida
            audioDevices.clear()
            audioDevices.addAll(newDevices)

            // Actualizar StateFlow con copia inmutable
            _availableDevices.value = newDevices.toList()
            log.d(TAG) { "Total devices scanned: ${newDevices.size}" }
        } catch (e: Exception) {
            log.e(TAG) { "Error scanning devices: ${e.message}" }
        } finally {
            isScanning = false
        }
    }

    // ==================== DEVICE CREATION ====================

    private fun createMicrophoneDevice() = AudioDevice(
        name = "Built-in Microphone",
        descriptor = "builtin_mic",
        nativeDevice = null,
        isOutput = false,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.MICROPHONE,
            capability = AudioUnitCompatibilities.RECORD,
            isCurrent = true,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    private fun createEarpieceDevice(currentPort: String?) = AudioDevice(
        name = "iPhone",
        descriptor = AVAudioSessionPortBuiltInReceiver ?: "builtin_receiver",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.EARPIECE,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentPort == AVAudioSessionPortBuiltInReceiver,
            isDefault = true
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 15
    )

    private fun createSpeakerDevice(currentPort: String?) = AudioDevice(
        name = "Speaker",
        descriptor = AVAudioSessionPortBuiltInSpeaker ?: "builtin_speaker",
        nativeDevice = null,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.SPEAKER,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentPort == AVAudioSessionPortBuiltInSpeaker,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = false,
        latency = 20
    )

    private fun createBluetoothDevice(
        port: AVAudioSessionPortDescription,
        currentPort: String?
    ): AudioDevice {
        val isA2dp = port.portType == AVAudioSessionPortBluetoothA2DP
        return AudioDevice(
            name = port.portName ?: "Bluetooth Device",
            descriptor = port.portType ?: "bluetooth",
            nativeDevice = port,
            isOutput = true,
            audioUnit = AudioUnit(
                type = if (isA2dp) AudioUnitTypes.BLUETOOTHA2DP else AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.ALL,
                // isCurrent compara con el portType real, no asume HFP.
                isCurrent = currentPort != null && currentPort == port.portType,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = true,
            supportsHDVoice = !isA2dp,
            latency = if (isA2dp) 100 else 50
        )
    }

    private fun createHeadphonesDevice(
        port: AVAudioSessionPortDescription,
        currentPort: String?
    ) = AudioDevice(
        name = port.portName ?: "Headphones",
        descriptor = port.portType ?: "headphones",
        nativeDevice = port,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.HEADPHONES,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentPort == AVAudioSessionPortHeadphones,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    private fun createHeadsetDevice(
        port: AVAudioSessionPortDescription,
        currentPort: String?
    ) = AudioDevice(
        name = port.portName ?: "Headset",
        descriptor = port.portType ?: "headset",
        nativeDevice = port,
        isOutput = true,
        audioUnit = AudioUnit(
            type = AudioUnitTypes.HEADSET,
            capability = AudioUnitCompatibilities.ALL,
            isCurrent = currentPort == AVAudioSessionPortHeadsetMic,
            isDefault = false
        ),
        connectionState = DeviceConnectionState.CONNECTED,
        isWireless = false,
        supportsHDVoice = true,
        latency = 10
    )

    fun diagnose(): String {
        return buildString {
            appendLine("=== iOS Audio Diagnostics ===")
            appendLine("Started: $isStarted")
            appendLine("Audio Session Configured: $audioSessionConfigured")
            appendLine("Active Route: ${getActiveRoute()}")

            val audioSession = AVAudioSession.sharedInstance()
            appendLine("Category: ${audioSession.category}")
            appendLine("Mode: ${audioSession.mode}")
            appendLine("Is Active: ${audioSession.secondaryAudioShouldBeSilencedHint}")

            val (inputs, outputs) = getAllDevices()
            appendLine("Input Devices: ${inputs.size}")
            appendLine("Output Devices: ${outputs.size}")
            appendLine("\nOutput Devices Detail:")
            outputs.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type}) - Current: ${device.audioUnit.isCurrent}")
            }
        }
    }
}