package com.eddyslarez.kmpsiprtc.services.audio

import android.app.Activity
import android.app.UiModeManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.data.models.*
import com.eddyslarez.kmpsiprtc.platform.log
import java.util.concurrent.CopyOnWriteArrayList

class AudioController(
    private val context: Context,
    private val bluetoothController: BluetoothController,
    private val onDeviceChanged: (AudioDevice?) -> Unit
) {
    private val TAG = "AudioController"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val androidAutoDetector = AndroidAutoDetector(context)

    private val audioDevices = CopyOnWriteArrayList<AudioDevice>()
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var isStarted = false

    // ✅ NUEVO: Control de estado de Bluetooth SCO
    @Volatile
    private var isBluetoothScoOn = false

    @Volatile
    private var isBluetoothScoRequested = false

    @Volatile
    private var isAndroidAutoMode = false

    // 🛂 Modo "gestionado por Android TelecomManager".
    // Cuando está activo, el sistema (Telecom) se encarga de:
    //   - AudioManager.mode (MODE_IN_COMMUNICATION)
    //   - Audio focus (USAGE_VOICE_COMMUNICATION)
    //   - Ruta del audio (earpiece/speaker/bluetooth) via Connection.setAudioRoute
    //   - Bluetooth SCO (el framework lo gestiona automáticamente)
    // Si además tocamos estas propiedades desde AudioController, nos peleamos
    // con Telecom y el resultado es: WebRTC no captura micrófono, el remoto no
    // escucha al usuario, o se pierde audio en ambos sentidos (típico en llamadas
    // que entran desde push/segundo plano). Con el flag activo, saltamos esas
    // operaciones y dejamos que Telecom las haga.
    @Volatile
    var telecomManaged: Boolean = false

    // Callback que el app puede usar para redirigir cambios de ruta a
    // Connection.setAudioRoute cuando estamos en modo telecom-managed.
    // Si es null y telecomManaged=true, setActiveRoute() se vuelve no-op
    // y solo actualiza el estado local.
    @Volatile
    var telecomRouteHandler: ((AudioUnitTypes) -> Boolean)? = null

    private val carModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UiModeManager.ACTION_ENTER_CAR_MODE -> {
                    log.d(TAG) { "🚗 Entered Car Mode (Android Auto)" }
                    isAndroidAutoMode = true
                    if (isStarted) {
                        mainHandler.postDelayed({
                            configureForAndroidAuto()
                        }, 500)
                    }
                }
                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    log.d(TAG) { "🚗 Exited Car Mode" }
                    isAndroidAutoMode = false
                    if (isStarted) {
                        selectDefaultDeviceWithPriority()
                    }
                }
            }
        }
    }
    // ✅ NUEVO: BroadcastReceiver para eventos de Bluetooth SCO
    private val bluetoothScoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    )

                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            log.d(TAG) { "✅ Bluetooth SCO Connected" }
                            isBluetoothScoOn = true

                            mainHandler.post {
                                scanDevices()
                                updateCurrentDeviceState()
                                onDeviceChanged(getCurrentOutputDevice())
                            }
                        }


                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            log.d(TAG) { "❌ Bluetooth SCO Disconnected" }
                            val wasPreviouslyOn = isBluetoothScoOn
                            isBluetoothScoOn = false

                            if (wasPreviouslyOn) {
                                mainHandler.post {
                                    updateCurrentDeviceState()
                                    onDeviceChanged(getCurrentOutputDevice())
                                }
                            }

                            if (isBluetoothScoRequested && isStarted && !isAndroidAutoMode) {
                                log.d(TAG) { "Retrying Bluetooth SCO connection..." }
                                mainHandler.postDelayed({
                                    startBluetoothScoIfNeeded()
                                }, 500)
                            }
                        }
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                            log.d(TAG) { "🔄 Bluetooth SCO Connecting..." }
                        }
                    }
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    log.d(TAG) { "Bluetooth device connected" }
                    mainHandler.postDelayed({
                        handleBluetoothConnection()
                    }, 500)
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    log.d(TAG) { "Bluetooth device disconnected" }
                    stopBluetoothSco()
                    mainHandler.postDelayed({
                        refreshDevices()
                        selectDefaultDeviceWithPriority()
                    }, 300)
                }

                // ✅ NUEVO: Detectar cambios en dispositivos de audio
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    log.d(TAG) { "Audio becoming noisy - device disconnected" }
                    refreshDevices()
                }
            }
        }
    }


    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                log.d(TAG) { "Audio focus gained" }
                audioManager?.isMicrophoneMute = false

                // ✅ Restaurar audio apropiado para Android Auto
                if (isAndroidAutoMode) {
                    configureForAndroidAuto()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                log.d(TAG) { "Audio focus lost permanently" }
                // NO mutear mic durante llamada VoIP activa — el interlocutor
                // dejaria de escuchar al usuario. La llamada VoIP tiene prioridad.
                if (!isStarted) {
                    audioManager?.isMicrophoneMute = true
                    stopBluetoothSco()
                } else {
                    log.w(TAG) { "Llamada activa, ignorando AUDIOFOCUS_LOSS para no silenciar mic" }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log.d(TAG) { "Audio focus lost temporarily" }
                // NO mutear mic durante llamada VoIP activa — causa que el
                // interlocutor no escuche al usuario al contestar via TelecomManager.
                if (!isStarted) {
                    audioManager?.isMicrophoneMute = true
                } else {
                    log.w(TAG) { "Llamada activa, ignorando AUDIOFOCUS_LOSS_TRANSIENT" }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                log.d(TAG) { "Audio focus lost, can duck" }
            }
        }
    }


    fun initialize() {
        log.d(TAG) { "Initializing AudioController" }
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Detectar si ya estamos en Android Auto
        isAndroidAutoMode = androidAutoDetector.isAndroidAutoConnected()

        // Registrar receivers
        try {
            val intentFilter = IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }
            context.registerReceiver(bluetoothScoReceiver, intentFilter)

            val carModeFilter = IntentFilter().apply {
                addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            }
            context.registerReceiver(carModeReceiver, carModeFilter)

            log.d(TAG) { "✅ All receivers registered" }
        } catch (e: Exception) {
            log.e(TAG) { "Error registering receivers: ${e.message}" }
        }
    }


    fun startForCall() {
        if (isStarted) {
            log.d(TAG) { "Audio already started" }
            return
        }

        // 🛂 Telecom-managed: NO tocar mode/focus/speaker/SCO. Telecom ya lo hizo
        // (setActive() en la Connection con audioModeIsVoip=true dispara MODE_IN_COMMUNICATION
        // y focus de VOICE_COMMUNICATION). Si intentamos hacerlo también, peleamos con Telecom
        // y WebRTC pierde el micrófono cuando la llamada entra desde push/segundo plano.
        if (telecomManaged) {
            log.d(TAG) { "🛂 Telecom-managed mode: skipping audio mode/focus/SCO setup" }
            audioManager?.let { am ->
                // Asegurar que el micro no esté muteado (la app controla esto por Connection.setMuted)
                am.isMicrophoneMute = false
            }
            scanDevices()
            isStarted = true
            return
        }

        log.d(TAG) { "🔊 Starting audio for call" }

        audioManager?.let { am ->
            savedAudioMode = am.mode
            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn

            requestAudioFocus()

            // ✅ Detectar Android Auto
            isAndroidAutoMode = androidAutoDetector.isAndroidAutoConnected()

            if (isAndroidAutoMode) {
                log.d(TAG) { "🚗 Android Auto detected - using car mode configuration" }
            }

            // ✅ CRÍTICO: Modo de comunicación apropiado
            am.mode = if (isAndroidAutoMode) {
                // Para Android Auto, usar MODE_IN_CALL puede funcionar mejor
                AudioManager.MODE_IN_CALL
            } else {
                AudioManager.MODE_IN_COMMUNICATION
            }

            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false

            scanDevices()

            mainHandler.postDelayed({
                if (isAndroidAutoMode) {
                    configureForAndroidAuto()
                } else if (hasBluetoothDevices()) {
                    log.d(TAG) { "Bluetooth devices detected, starting SCO..." }
                    startBluetoothSco()
                }
                selectDefaultDeviceWithPriority()
            }, 300)

            isStarted = true
            log.d(TAG) { "✅ Audio started" }
        }
    }


    fun stop() {
        if (!isStarted) return

        log.d(TAG) { "🔇 Stopping audio" }

        // 🛂 Telecom-managed: Telecom limpia mode/focus/SCO cuando setDisconnected() se ejecuta.
        // No tocamos nada para no pelear.
        if (telecomManaged) {
            isStarted = false
            return
        }

        isBluetoothScoRequested = false
        stopBluetoothSco()

        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
            abandonAudioFocus()
        }

        isStarted = false
        isAndroidAutoMode = false
    }

    fun dispose() {
        stop()

        try {
            context.unregisterReceiver(bluetoothScoReceiver)
            context.unregisterReceiver(carModeReceiver)
            log.d(TAG) { "All receivers unregistered" }
        } catch (e: Exception) {
            log.w(TAG) { "Error unregistering receivers: ${e.message}" }
        }

        audioManager = null
    }


    // ==================== DEVICE MANAGEMENT ====================


    private fun configureForAndroidAuto() {
        log.d(TAG) { "🚗 Configuring audio for Android Auto..." }

        audioManager?.let { am ->
            try {
                // 1. Escanear dispositivos del coche
                val carAudioDevice = androidAutoDetector.getCarAudioDeviceType()

                if (carAudioDevice != null) {
                    log.d(TAG) { "✅ Car audio device found: ${carAudioDevice.name}" }

                    // 2. Configurar según el tipo de conexión del coche
                    when (carAudioDevice.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                            log.d(TAG) { "Car using Bluetooth A2DP" }
                            // Para A2DP, NO usar SCO
                            stopBluetoothSco()
                            am.isSpeakerphoneOn = false
                        }

                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> {
                            log.d(TAG) { "Car using USB Audio" }
                            stopBluetoothSco()
                            am.isSpeakerphoneOn = false
                        }

                        AudioDeviceInfo.TYPE_AUX_LINE -> {
                            log.d(TAG) { "Car using AUX (3.5mm)" }
                            stopBluetoothSco()
                            am.isSpeakerphoneOn = false
                        }

                        else -> {
                            log.d(TAG) { "Car using unknown audio type: ${carAudioDevice.type}" }
                        }
                    }

                    // 3. Forzar enrutamiento al dispositivo del coche
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setCommunicationDevice(carAudioDevice.type)
                    }

                } else {
                    log.w(TAG) { "⚠️ No car audio device found, using default routing" }
                }

                refreshDevices()
                onDeviceChanged(getCurrentOutputDevice())

            } catch (e: Exception) {
                log.e(TAG) { "Error configuring Android Auto: ${e.message}" }
                e.printStackTrace()
            }
        }
    }
    /**
     * ✅ NUEVO: Establecer dispositivo de comunicación (Android 6.0+)
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setCommunicationDevice(deviceType: Int) {
        audioManager?.let { am ->
            try {
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val targetDevice = devices.firstOrNull { it.type == deviceType }

                if (targetDevice != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+ (API 31+)
                        val success = am.setCommunicationDevice(targetDevice)
                        log.d(TAG) { "setCommunicationDevice result: $success" }
                    } else {
                        // Android 6-11
                        log.d(TAG) { "Using legacy audio routing for ${targetDevice.productName}" }
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error setting communication device: ${e.message}" }
            }
        }
    }
    fun setActiveRoute(audioUnitType: AudioUnitTypes): Boolean {
        // 🛂 Telecom-managed: enrutamiento vive en Connection.setAudioRoute().
        // Si lo hacemos vía AudioManager (setSpeakerphoneOn / startBluetoothSco),
        // Telecom revierte o ignora los cambios y el audio se queda donde estaba.
        if (telecomManaged) {
            val handler = telecomRouteHandler
            if (handler != null) {
                val ok = try {
                    handler(audioUnitType)
                } catch (e: Exception) {
                    log.e(TAG) { "telecomRouteHandler error: ${e.message}" }
                    false
                }
                if (ok) {
                    mainHandler.postDelayed({
                        updateCurrentDeviceState()
                        onDeviceChanged(getCurrentOutputDevice())
                    }, 200)
                }
                return ok
            } else {
                log.w(TAG) { "🛂 setActiveRoute($audioUnitType) ignorado: Telecom activo sin handler" }
                return false
            }
        }

        return audioManager?.let { am ->
            try {
                log.d(TAG) { "Setting active route to: $audioUnitType" }

                when (audioUnitType) {
                    AudioUnitTypes.SPEAKER -> {
                        stopBluetoothSco()
                        am.isSpeakerphoneOn = true
                        log.d(TAG) { "✅ Switched to SPEAKER" }
                    }
                    AudioUnitTypes.EARPIECE -> {
                        stopBluetoothSco()
                        am.isSpeakerphoneOn = false
                        log.d(TAG) { "✅ Switched to EARPIECE" }
                    }
                    AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> {
                        am.isSpeakerphoneOn = false
                        startBluetoothSco()
                        log.d(TAG) { "✅ Switched to BLUETOOTH" }
                    }
                    AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> {
                        stopBluetoothSco()
                        am.isSpeakerphoneOn = false
                        log.d(TAG) { "✅ Switched to HEADSET" }
                    }
                    else -> {
                        log.w(TAG) { "Unsupported audio route: $audioUnitType" }
                        return false
                    }
                }

                // ✅ CRÍTICO: Actualizar estado después del cambio
                mainHandler.postDelayed({
                    updateCurrentDeviceState()
                    onDeviceChanged(getCurrentOutputDevice())
                }, 200)

                true
            } catch (e: Exception) {
                log.e(TAG) { "Error setting route: ${e.message}" }
                false
            }
        } ?: false
    }

    fun getActiveRoute(): AudioUnitTypes? {
        return audioManager?.let { am ->
            val hasBluetooth = bluetoothController.hasConnectedDevices()

            when {
                isAndroidAutoMode -> {
                    log.d(TAG) { "📍 Active route: BLUETOOTH (Android Auto mode)" }
                    AudioUnitTypes.BLUETOOTH
                }

                isBluetoothScoOn -> {
                    log.d(TAG) { "📍 Active route: BLUETOOTH (SCO connected)" }
                    AudioUnitTypes.BLUETOOTH
                }

                hasBluetooth -> {
                    log.d(TAG) { "📍 Active route: BLUETOOTH (device connected, SCO not yet active)" }
                    // Inicia SCO si aún no está activo
                    try {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                        log.d(TAG) { "🎧 Bluetooth SCO started manually" }
                    } catch (e: Exception) {
                        log.e(TAG) { "❌ Error starting Bluetooth SCO: ${e.message}" }
                    }
                    AudioUnitTypes.BLUETOOTH
                }

                am.isSpeakerphoneOn -> {
                    log.d(TAG) { "📍 Active route: SPEAKER" }
                    AudioUnitTypes.SPEAKER
                }

                am.isWiredHeadsetOn -> {
                    log.d(TAG) { "📍 Active route: HEADSET" }
                    AudioUnitTypes.HEADSET
                }

                else -> {
                    log.d(TAG) { "📍 Active route: EARPIECE (default)" }
                    AudioUnitTypes.EARPIECE
                }
            }
        }
    }



    fun getAvailableRoutes(): Set<AudioUnitTypes> {
        val routes = mutableSetOf<AudioUnitTypes>()
        audioManager?.let { am ->
            // ✅ En Android Auto, solo mostrar Bluetooth
            if (isAndroidAutoMode) {
                routes.add(AudioUnitTypes.BLUETOOTH)
                return routes
            }

            routes.add(AudioUnitTypes.EARPIECE)
            routes.add(AudioUnitTypes.SPEAKER)

            if (am.isWiredHeadsetOn) {
                routes.add(AudioUnitTypes.HEADSET)
            }

            if (hasBluetoothDevices() || am.isBluetoothScoAvailableOffCall) {
                routes.add(AudioUnitTypes.BLUETOOTH)
            }
        }
        return routes
    }

    private fun hasBluetoothDevices(): Boolean {
        return bluetoothController.hasConnectedDevices()
    }

    fun getAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        scanDevices()
        val inputs = audioDevices.filter { it.isInput && it.canRecord }
        val outputs = audioDevices.filter { it.isOutput && it.canPlay }

        log.d(TAG) { "All devices - Inputs: ${inputs.size}, Outputs: ${outputs.size}" }
        outputs.forEach { device ->
            log.d(TAG) { "  Output: ${device.name} (${device.audioUnit.type})" }
        }

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
        val activeRoute = getActiveRoute()
        val device = audioDevices.firstOrNull { device ->
            device.isOutput && device.audioUnit.type == activeRoute
        }

        log.d(TAG) { "Current output device: ${device?.name ?: "none"} (route: $activeRoute)" }
        return device
    }

    fun getAvailableAudioUnits(): Set<AudioUnit> {
        scanDevices()
        return audioDevices.map { it.audioUnit }.toSet()
    }

    fun getCurrentActiveAudioUnit(): AudioUnit? {
        val activeRoute = getActiveRoute()
        log.d(TAG) { "🎯 Getting current active audio unit for route: $activeRoute" }

        // ✅ Buscar en la lista de dispositivos escaneados
        val device = audioDevices.firstOrNull { device ->
            device.isOutput && when (activeRoute) {
                AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> {
                    device.audioUnit.type == AudioUnitTypes.BLUETOOTH ||
                            device.audioUnit.type == AudioUnitTypes.BLUETOOTHA2DP
                }
                else -> device.audioUnit.type == activeRoute
            }
        }

        val audioUnit = device?.audioUnit?.copy(isCurrent = true)

        log.d(TAG) { "📍 Current active unit: ${audioUnit?.type} (from device: ${device?.name})" }

        return audioUnit
    }
    fun updateCurrentDeviceState() {
        val activeRoute = getActiveRoute()

        log.d(TAG) { "🔄 Updating device states - Active route: $activeRoute" }

        // Actualizar todos los dispositivos para reflejar el estado actual
        audioDevices.forEach { device ->
            if (device.isOutput) {
                val shouldBeCurrent = when (activeRoute) {
                    AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> {
                        device.audioUnit.type == AudioUnitTypes.BLUETOOTH ||
                                device.audioUnit.type == AudioUnitTypes.BLUETOOTHA2DP
                    }
                    else -> device.audioUnit.type == activeRoute
                }

                // Actualizar el estado isCurrent en el AudioUnit
                if (device.audioUnit.isCurrent != shouldBeCurrent) {
                    log.d(TAG) { "  📝 Updating ${device.name}: isCurrent = $shouldBeCurrent" }
                }
            }
        }
    }
    fun refreshDevices() {
        scanDevices()
        onDeviceChanged(getCurrentOutputDevice())
    }

    fun refreshWithBluetoothPriority() {
        scanDevices()
        if (!isAndroidAutoMode) {
            selectDefaultDeviceWithPriority()
        }
    }

    // ==================== BLUETOOTH SCO MANAGEMENT ====================

    /**
     * ✅ NUEVO: Iniciar Bluetooth SCO correctamente
     */
    private fun startBluetoothSco() {
        // 🛂 Telecom-managed: el sistema gestiona SCO automáticamente al enrutar a BT.
        if (telecomManaged) {
            log.d(TAG) { "🛂 Skipping SCO: Telecom-managed mode" }
            return
        }
        // ✅ No iniciar SCO en modo Android Auto
        if (isAndroidAutoMode) {
            log.d(TAG) { "Skipping SCO in Android Auto mode" }
            return
        }

        audioManager?.let { am ->
            try {
                if (isBluetoothScoOn) {
                    log.d(TAG) { "Bluetooth SCO already on" }
                    return
                }

                if (!hasBluetoothDevices()) {
                    log.w(TAG) { "No Bluetooth devices available" }
                    return
                }

                log.d(TAG) { "Starting Bluetooth SCO..." }

                isBluetoothScoRequested = true
                am.isBluetoothScoOn = true
                am.startBluetoothSco()

                mainHandler.postDelayed({
                    if (!isBluetoothScoOn && isBluetoothScoRequested) {
                        log.w(TAG) { "SCO connection timeout, retrying..." }
                        am.stopBluetoothSco()
                        // Reintentar SCO sin bloquear main thread
                        mainHandler.postDelayed({
                            if (!isBluetoothScoOn && isBluetoothScoRequested) {
                                am.startBluetoothSco()
                            }
                        }, 100)
                    }
                }, 1000)

            } catch (e: Exception) {
                log.e(TAG) { "Error starting Bluetooth SCO: ${e.message}" }
                isBluetoothScoRequested = false
            }
        }
    }
    /**
     * ✅ NUEVO: Detener Bluetooth SCO
     */
    private fun stopBluetoothSco() {
        audioManager?.let { am ->
            try {
                if (!isBluetoothScoOn && !isBluetoothScoRequested) {
                    return
                }

                log.d(TAG) { "Stopping Bluetooth SCO..." }

                isBluetoothScoRequested = false
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
                isBluetoothScoOn = false

            } catch (e: Exception) {
                log.e(TAG) { "Error stopping Bluetooth SCO: ${e.message}" }
            }
        }
    }

    /**
     * ✅ MEJORADO: Manejo de conexión Bluetooth
     */
    private fun handleBluetoothConnection() {
        refreshDevices()

        // Verificar si es Android Auto
        if (androidAutoDetector.isAndroidAutoConnected()) {
            isAndroidAutoMode = true
            configureForAndroidAuto()
        } else if (isStarted) {
            selectDefaultDeviceWithPriority()
        }
    }
    /**
     * ✅ NUEVO: Iniciar SCO solo si es necesario
     */
    private fun startBluetoothScoIfNeeded() {
        if (!isStarted || !hasBluetoothDevices() || isAndroidAutoMode) {
            return
        }

        if (!isBluetoothScoOn && !isBluetoothScoRequested) {
            startBluetoothSco()
        }
    }

    // ==================== MUTE CONTROL ====================

    fun setMicrophoneMute(muted: Boolean) {
        audioManager?.isMicrophoneMute = muted
        log.d(TAG) { "Microphone muted: $muted" }
    }

    fun isMicrophoneMuted(): Boolean {
        return audioManager?.isMicrophoneMute ?: false
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * ✅ MEJORADO: Selección con prioridad REAL de Bluetooth
     */
    private fun selectDefaultDeviceWithPriority() {
        // ✅ Android Auto tiene máxima prioridad
        if (isAndroidAutoMode) {
            log.d(TAG) { "🚗 Android Auto mode - using car audio" }
            configureForAndroidAuto()
            return
        }

        scanDevices()

        val availableTypes = audioDevices
            .filter { it.isOutput }
            .map { it.audioUnit.type }
            .toSet()

        log.d(TAG) { "Available output devices: $availableTypes" }

        val priorityType = when {
            AudioUnitTypes.BLUETOOTH in availableTypes && hasBluetoothDevices() -> {
                log.d(TAG) { "✅ Selecting BLUETOOTH (highest priority)" }
                AudioUnitTypes.BLUETOOTH
            }
            AudioUnitTypes.HEADSET in availableTypes -> {
                log.d(TAG) { "Selecting HEADSET" }
                AudioUnitTypes.HEADSET
            }
            else -> {
                log.d(TAG) { "Selecting EARPIECE (default)" }
                AudioUnitTypes.EARPIECE
            }
        }

        setActiveRoute(priorityType)
    }

    private fun scanDevices() {
        audioDevices.clear()

        val currentRoute = getActiveRoute()
        log.d(TAG) { "📡 Scanning devices with current route: $currentRoute" }

        audioManager?.let { am ->
            // Dispositivos básicos
            audioDevices.add(createMicrophoneDevice())
            audioDevices.add(createEarpieceDevice())
            audioDevices.add(createSpeakerDevice())

            // Headset con cable
            if (am.isWiredHeadsetOn) {
                audioDevices.add(createWiredHeadsetDevice())
                log.d(TAG) { "Wired headset detected" }
            }

            // Android Auto
            if (isAndroidAutoMode) {
                val carDevice = createAndroidAutoDevice()
                if (carDevice != null) {
                    audioDevices.add(carDevice)
                    log.d(TAG) { "✅ Android Auto device added" }
                }
            }

            // Dispositivos Bluetooth
            val bluetoothDevices = bluetoothController.getBluetoothDevices()
            if (bluetoothDevices.isNotEmpty()) {
                // ✅ Marcar como current si SCO está activo
                val updatedBluetoothDevices = bluetoothDevices.map { device ->
                    device.copy(
                        audioUnit = device.audioUnit.copy(
                            isCurrent = isBluetoothScoOn &&
                                    (device.audioUnit.type == AudioUnitTypes.BLUETOOTH ||
                                            device.audioUnit.type == AudioUnitTypes.BLUETOOTHA2DP)
                        )
                    )
                }

                audioDevices.addAll(updatedBluetoothDevices)
                log.d(TAG) { "✅ Added ${bluetoothDevices.size} Bluetooth devices (SCO: $isBluetoothScoOn)" }
                updatedBluetoothDevices.forEach { device ->
                    log.d(TAG) { "  - ${device.name} (${device.descriptor}) isCurrent=${device.audioUnit.isCurrent}" }
                }
            }
        }

        log.d(TAG) { "Total devices scanned: ${audioDevices.size}" }
    }

    private fun requestAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(audioAttributes)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(false)
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
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    val result = am.requestAudioFocus(
                        audioFocusChangeListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN
                    )

                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        log.d(TAG) { "✅ Audio focus granted (legacy)" }
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

    private fun createAndroidAutoDevice(): AudioDevice? {
        val carDeviceInfo = androidAutoDetector.getCarAudioDeviceType() ?: return null

        val deviceType = when (carDeviceInfo.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioUnitTypes.BLUETOOTHA2DP
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioUnitTypes.USB
            AudioDeviceInfo.TYPE_AUX_LINE -> AudioUnitTypes.AUX
            else -> AudioUnitTypes.BLUETOOTH
        }

        return AudioDevice(
            name = carDeviceInfo.name.ifBlank { "Android Auto" },
            descriptor = "android_auto_device_${carDeviceInfo.type}",
            nativeDevice = carDeviceInfo,
            isOutput = true,
            audioUnit = AudioUnit(
                type = deviceType,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = getActiveRoute() == deviceType,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = deviceType == AudioUnitTypes.BLUETOOTH || deviceType == AudioUnitTypes.BLUETOOTHA2DP,
            supportsHDVoice = true,
            latency = 25
        )
    }

    fun diagnose(): String {
        return buildString {
            appendLine("Started: $isStarted")
            appendLine("Mic Muted: ${isMicrophoneMuted()}")
            appendLine("Active Route: ${getActiveRoute()}")
            appendLine("Bluetooth SCO On: $isBluetoothScoOn")
            appendLine("Bluetooth SCO Requested: $isBluetoothScoRequested")
            appendLine("Audio Focus Request: ${audioFocusRequest != null}")
            appendLine("Has Bluetooth Devices: ${hasBluetoothDevices()}")
            audioManager?.let { am ->
                appendLine("Mode: ${am.mode}")
                appendLine("Speakerphone: ${am.isSpeakerphoneOn}")
                appendLine("Bluetooth SCO Available: ${am.isBluetoothScoAvailableOffCall}")
                appendLine("Bluetooth SCO Manager: ${am.isBluetoothScoOn}")
                appendLine("Wired Headset: ${am.isWiredHeadsetOn}")
            }
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