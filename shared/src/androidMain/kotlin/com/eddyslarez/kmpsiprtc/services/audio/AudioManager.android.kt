package com.eddyslarez.kmpsiprtc.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import androidx.annotation.RequiresPermission
import androidx.core.net.toUri
import com.eddyslarez.kmpsiprtc.platform.AndroidContext
import kotlinx.coroutines.*

actual fun createAudioManager(): AudioManager = AndroidAudioManagerImpl()

class AndroidAudioManagerImpl : AudioManager {

    private var outgoingRingtoneJob: Job? = null
    private var incomingRingtoneJob: Job? = null
    private var vibrationJob: Job? = null
    private var vibrationSyncJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var application= AndroidContext.getApplication()
    private val TAG = "AndroidAudioManager"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private var incomingRingtonePath: String? = null
    private var outgoingRingtonePath: String? = null

    // Estados para control de reproducción con atomic para thread safety
    @Volatile private var isIncomingRingtonePlaying = false
    @Volatile private var isOutgoingRingtonePlaying = false
    @Volatile private var shouldStopIncoming = false
    @Volatile private var shouldStopOutgoing = false
    @Volatile private var isVibrating = false

    private var currentVibrationPattern = "default"
    fun initialize(application: Application) {
        this.application = application
        println("$TAG: AudioManager initialized")
    }
    init {

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            println("💥 [AndroidAudioManagerImpl] App crashed on thread ${thread.name}: ${e.message}")
            try {
                cleanupOnCrash()
            } catch (_: Exception) {}

            // Llamar al handler original si existía
            originalHandler?.uncaughtException(thread, e)
        }

        val resourceUtils = createResourceUtils()
        incomingRingtonePath = resourceUtils.getDefaultIncomingRingtonePath()
        outgoingRingtonePath = resourceUtils.getDefaultOutgoingRingtonePath()
        initialize(application)
        // Inicializar vibrador
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        println("$TAG: AudioManager initialized")
        println("$TAG: Incoming path: $incomingRingtonePath")
        println("$TAG: Outgoing path: $outgoingRingtonePath")
    }

     override fun setVibrationPattern(patternName: String) {
        if (VibrationPatterns.patterns.containsKey(patternName)) {
            currentVibrationPattern = patternName
            println("$TAG: Vibration pattern set to: $patternName")
        }
    }

     override fun setIncomingRingtone(path: String) {
        incomingRingtonePath = path
        println("$TAG: Incoming ringtone path set: $path")
    }

     override fun setOutgoingRingtone(path: String) {
        outgoingRingtonePath = path
        println("$TAG: Outgoing ringtone path set: $path")
    }

    /**
     * Reproduce ringtone de entrada con vibración sincronizada
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun playRingtone(syncVibration: Boolean) {
        println("$TAG: playRingtone() called - sync vibration: $syncVibration")

        if (isTelecomActiveAndEnabled()) {
            println("$TAG: System (TelecomManager) managing ringtone, skipping custom ringtone playback")
            return
        }
        if (isIncomingRingtonePlaying) {
            println("$TAG: Incoming ringtone already playing, ignoring request")
            return
        }

        stopOutgoingRingtone()

        val uriString = incomingRingtonePath
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString()
        val uri = uriString.toUri()

        isIncomingRingtonePlaying = true
        shouldStopIncoming = false

        println("$TAG: Starting incoming ringtone with URI: $uri")

        incomingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopIncoming && isIncomingRingtonePlaying) {
                    loopCount++
                    println("$TAG: Incoming ringtone loop iteration: $loopCount")

                    val mediaPlayer = createIncomingMediaPlayer(uri)
                    if (mediaPlayer == null) {
                        println("$TAG: Failed to create MediaPlayer, stopping ringtone")
                        break
                    }

                    incomingRingtone = mediaPlayer

                    try {
                        // Iniciar vibración sincronizada con el ringtone
                        if (syncVibration) {
                            startSynchronizedVibration(mediaPlayer)
                        } else {
                            startVibration()
                        }

                        mediaPlayer.start()
                        println("$TAG: MediaPlayer started successfully")

                        while (mediaPlayer.isPlaying && !shouldStopIncoming && isIncomingRingtonePlaying) {
                            delay(100)
                        }

                        println("$TAG: MediaPlayer finished or stopped")

                    } catch (e: Exception) {
                        println("$TAG: Error during MediaPlayer playback: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        try {
                            if (mediaPlayer.isPlaying) {
                                mediaPlayer.stop()
                            }
                            mediaPlayer.release()
                        } catch (e: Exception) {
                            println("$TAG: Error releasing MediaPlayer: ${e.message}")
                        }
                        incomingRingtone = null
                    }

                    if (!shouldStopIncoming && isIncomingRingtonePlaying) {
                        println("$TAG: Pausing before next iteration...")
                        delay(800)
                    }
                }

                println("$TAG: Ringtone loop ended after $loopCount iterations")

            } catch (e: Exception) {
                println("$TAG: Error in incoming ringtone coroutine: ${e.message}")
                e.printStackTrace()
            } finally {
                isIncomingRingtonePlaying = false
                shouldStopIncoming = false
                stopVibration()

                incomingRingtone?.let { player ->
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (e: Exception) {
                        println("$TAG: Error in final cleanup: ${e.message}")
                    }
                }
                incomingRingtone = null
                println("$TAG: Incoming ringtone completely stopped and cleaned up")
            }
        }
    }
    private fun cleanupOnCrash() {
        println("🧹 [AndroidAudioManagerImpl] Cleaning up audio due to crash")

        // Detener ringtones y vibración
        stopAllRingtones()

        // Cancelar coroutines
        audioScope.cancel()

        // Liberar MediaPlayer si todavía existe
        incomingRingtone?.let { if (it.isPlaying) it.stop(); it.release() }
        outgoingRingtone?.let { if (it.isPlaying) it.stop(); it.release() }
    }

    /**
     * Inicia vibración sincronizada con el MediaPlayer
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun startSynchronizedVibration(mediaPlayer: MediaPlayer) {
        if (isVibrating) {
            println("$TAG: Vibration already active")
            return
        }

        if (application.checkSelfPermission(android.Manifest.permission.VIBRATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            println("$TAG: VIBRATE permission not granted")
            return
        }

        if (vibrator == null || !vibrator!!.hasVibrator()) {
            println("$TAG: Device doesn't support vibration")
            return
        }

        isVibrating = true
        println("$TAG: Starting synchronized vibration")

        vibrationSyncJob = audioScope.launch {
            try {
                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    // Vibración basada en tempo estimado
                    vibrateToRhythm()

                    // Esperar un poco antes del siguiente ciclo
                    delay(100)
                }
            } catch (e: Exception) {
                println("$TAG: Error in synchronized vibration: ${e.message}")
                e.printStackTrace()
            } finally {
                stopVibration()
            }
        }
    }

    /**
     * Vibra siguiendo un patrón rítmico estimado
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    private suspend fun vibrateToRhythm() {
        val pattern = VibrationPatterns.patterns[currentVibrationPattern]
            ?: VibrationPatterns.patterns["default"]!!

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Crear efecto de vibración con el patrón seleccionado
                val vibrationEffect = VibrationEffect.createWaveform(
                    pattern.pattern,
                    pattern.amplitudes,
                    -1 // No repetir automáticamente
                )
                vibrator?.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern.pattern, -1)
            }

            // Esperar la duración del patrón
            val totalDuration = pattern.pattern.sum()
            delay(totalDuration)

        } catch (e: Exception) {
            println("$TAG: Error in rhythm vibration: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Inicia el patrón de vibración estándar
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun startVibration() {
        if (isVibrating) {
            println("$TAG: Vibration already active")
            return
        }

        if (application.checkSelfPermission(android.Manifest.permission.VIBRATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            println("$TAG: VIBRATE permission not granted")
            return
        }

        if (vibrator == null || !vibrator!!.hasVibrator()) {
            println("$TAG: Device doesn't support vibration")
            return
        }

        isVibrating = true
        println("$TAG: Starting standard vibration pattern")

        vibrationJob = audioScope.launch {
            try {
                val pattern = VibrationPatterns.patterns[currentVibrationPattern]
                    ?: VibrationPatterns.patterns["default"]!!

                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(
                            VibrationEffect.createWaveform(
                                pattern.pattern,
                                pattern.amplitudes,
                                0 // Repetir desde índice 0
                            )
                        )
                        delay(pattern.pattern.sum())
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(pattern.pattern, 0)
                        delay(pattern.pattern.sum())
                    }
                }
            } catch (e: Exception) {
                println("$TAG: Error in vibration coroutine: ${e.message}")
                e.printStackTrace()
            } finally {
                stopVibration()
            }
        }
    }

    /**
     * Detiene la vibración
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun stopVibration() {
        if (!isVibrating) return

        isVibrating = false

        try {
            vibrationJob?.cancel()
            vibrationSyncJob?.cancel()
            vibrationJob = null
            vibrationSyncJob = null

            vibrator?.cancel()
            println("$TAG: Vibration stopped")
        } catch (e: Exception) {
            println("$TAG: Error stopping vibration: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Crea MediaPlayer para ringtone entrante
     */
    private fun createIncomingMediaPlayer(uri: Uri): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setDataSource(application, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(android.media.AudioManager.STREAM_RING)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_RING)
                }

                isLooping = false
                prepare()
                println("$TAG: MediaPlayer prepared successfully")
            }
        } catch (e: Exception) {
            println("$TAG: Error creating MediaPlayer: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Reproduce ringtone de salida
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun playOutgoingRingtone() {
        println("$TAG: playOutgoingRingtone() called - Current state: isPlaying=$isOutgoingRingtonePlaying")

        if (isOutgoingRingtonePlaying) {
            println("$TAG: Outgoing ringtone already playing, ignoring request")
            return
        }

        stopRingtone()

        val uriString = outgoingRingtonePath
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        val uri = uriString.toUri()

        isOutgoingRingtonePlaying = true
        shouldStopOutgoing = false

        println("$TAG: Starting outgoing ringtone with URI: $uri")

        outgoingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                    loopCount++
                    println("$TAG: Outgoing ringtone loop iteration: $loopCount")

                    val mediaPlayer = createOutgoingMediaPlayer(uri)
                    if (mediaPlayer == null) {
                        println("$TAG: Failed to create outgoing MediaPlayer, stopping ringtone")
                        break
                    }

                    outgoingRingtone = mediaPlayer

                    try {
                        mediaPlayer.start()
                        println("$TAG: Outgoing MediaPlayer started successfully")

                        while (mediaPlayer.isPlaying && !shouldStopOutgoing && isOutgoingRingtonePlaying) {
                            delay(100)
                        }

                        println("$TAG: Outgoing MediaPlayer finished or stopped")

                    } catch (e: Exception) {
                        println("$TAG: Error during outgoing MediaPlayer playback: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        try {
                            if (mediaPlayer.isPlaying) {
                                mediaPlayer.stop()
                            }
                            mediaPlayer.release()
                        } catch (e: Exception) {
                            println("$TAG: Error releasing outgoing MediaPlayer: ${e.message}")
                        }
                        outgoingRingtone = null
                    }

                    if (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                        delay(1000)
                    }
                }

                println("$TAG: Outgoing ringtone loop ended after $loopCount iterations")

            } catch (e: Exception) {
                println("$TAG: Error in outgoing ringtone coroutine: ${e.message}")
                e.printStackTrace()
            } finally {
                isOutgoingRingtonePlaying = false
                shouldStopOutgoing = false
                outgoingRingtone?.let { player ->
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (e: Exception) {
                        println("$TAG: Error in outgoing final cleanup: ${e.message}")
                    }
                }
                outgoingRingtone = null
                println("$TAG: Outgoing ringtone completely stopped and cleaned up")
            }
        }
    }

    /**
     * Crea MediaPlayer para ringtone saliente
     */
    private fun createOutgoingMediaPlayer(uri: Uri): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setDataSource(application, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                }

                isLooping = false
                prepare()
                println("$TAG: Outgoing MediaPlayer prepared successfully")
            }
        } catch (e: Exception) {
            println("$TAG: Error creating outgoing MediaPlayer: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Detiene el ringtone de entrada
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun stopRingtone() {
        println("$TAG: stopRingtone() called - Stopping incoming ringtone and vibration")

        shouldStopIncoming = true
        isIncomingRingtonePlaying = false

        stopVibration()

        try {
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

            incomingRingtone?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                        println("$TAG: Incoming MediaPlayer stopped")
                    }
                    player.release()
                    println("$TAG: Incoming MediaPlayer released")
                } catch (e: Exception) {
                    println("$TAG: Error stopping/releasing incoming ringtone: ${e.message}")
                    e.printStackTrace()
                }
            }
            incomingRingtone = null

            println("$TAG: Incoming ringtone stopped successfully")
        } catch (e: Exception) {
            println("$TAG: Error stopping incoming ringtone: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Detiene el ringtone de salida
     */
    override fun stopOutgoingRingtone() {
        println("$TAG: stopOutgoingRingtone() called - Stopping outgoing ringtone")

        shouldStopOutgoing = true
        isOutgoingRingtonePlaying = false

        try {
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null

            outgoingRingtone?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                        println("$TAG: Outgoing MediaPlayer stopped")
                    }
                    player.release()
                    println("$TAG: Outgoing MediaPlayer released")
                } catch (e: Exception) {
                    println("$TAG: Error stopping/releasing outgoing ringtone: ${e.message}")
                    e.printStackTrace()
                }
            }
            outgoingRingtone = null

            println("$TAG: Outgoing ringtone stopped successfully")
        } catch (e: Exception) {
            println("$TAG: Error stopping outgoing ringtone: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Detiene todos los ringtones y vibraciones
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun stopAllRingtones() {
        println("$TAG: stopAllRingtones() called - FORCE STOPPING ALL RINGTONES AND VIBRATION")

        shouldStopIncoming = true
        shouldStopOutgoing = true
        isIncomingRingtonePlaying = false
        isOutgoingRingtonePlaying = false

        stopVibration()

        try {
            incomingRingtoneJob?.cancel()
            outgoingRingtoneJob?.cancel()
            incomingRingtoneJob = null
            outgoingRingtoneJob = null

            incomingRingtone?.let { player ->
                try {
                    if (player.isPlaying) player.stop()
                    player.release()
                    println("$TAG: Incoming MediaPlayer force stopped")
                } catch (e: Exception) {
                    println("$TAG: Error force stopping incoming: ${e.message}")
                }
            }
            incomingRingtone = null

            outgoingRingtone?.let { player ->
                try {
                    if (player.isPlaying) player.stop()
                    player.release()
                    println("$TAG: Outgoing MediaPlayer force stopped")
                } catch (e: Exception) {
                    println("$TAG: Error force stopping outgoing: ${e.message}")
                }
            }
            outgoingRingtone = null

            println("$TAG: ALL ringtones and vibration force stopped successfully")
        } catch (e: Exception) {
            println("$TAG: Error in stopAllRingtones: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Verifica si algún ringtone está sonando
     */
    override fun isRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying || isOutgoingRingtonePlaying
    }

    /**
     * Verifica si el ringtone de entrada está sonando
     */
    override fun isIncomingRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying && !shouldStopIncoming
    }

    /**
     * Verifica si el ringtone de salida está sonando
     */
    override fun isOutgoingRingtonePlaying(): Boolean {
        return isOutgoingRingtonePlaying && !shouldStopOutgoing
    }

    /**
     * Verifica si está vibrando
     */
    override fun isVibrating(): Boolean {
        return isVibrating
    }

    /**
     * Obtiene información diagnóstica
     */
    override fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO MANAGER DIAGNOSTIC (ANDROID) ===")
            appendLine("Incoming playing: $isIncomingRingtonePlaying")
            appendLine("Outgoing playing: $isOutgoingRingtonePlaying")
            appendLine("Vibrating: $isVibrating")
            appendLine("Should stop incoming: $shouldStopIncoming")
            appendLine("Should stop outgoing: $shouldStopOutgoing")
            appendLine("Incoming job active: ${incomingRingtoneJob?.isActive}")
            appendLine("Outgoing job active: ${outgoingRingtoneJob?.isActive}")
            appendLine("Vibration job active: ${vibrationJob?.isActive}")
            appendLine("Vibration sync job active: ${vibrationSyncJob?.isActive}")
            appendLine("Current vibration pattern: $currentVibrationPattern")
            appendLine("Incoming MediaPlayer: ${incomingRingtone != null}")
            appendLine("Outgoing MediaPlayer: ${outgoingRingtone != null}")
            appendLine("Vibrator available: ${vibrator?.hasVibrator()}")
            appendLine("Audio scope active: ${audioScope.isActive}")
            appendLine("Incoming path: $incomingRingtonePath")
            appendLine("Outgoing path: $outgoingRingtonePath")
        }
    }

    /**
     * Limpia recursos
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    override fun cleanup() {
        println("$TAG: cleanup() called")
        stopAllRingtones()
        audioScope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun isTelecomActiveAndEnabled(): Boolean {
        return try {
            println("$TAG: ========== TELECOM DETECTION START ==========")

            // 1. Verificar versión de Android
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                println("$TAG: ❌ TelecomManager not fully supported on Android < M (Current: ${Build.VERSION.SDK_INT})")
                return false
            }
            println("$TAG: ✓ Android version ${Build.VERSION.SDK_INT} supports TelecomManager")

            // 2. Verificar disponibilidad del servicio
            val telecomManager = application.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager == null) {
                println("$TAG: ❌ TelecomManager service not available")
                return false
            }
            println("$TAG: ✓ TelecomManager service available")

            // 3. Verificar permisos detalladamente
            val hasPermissions = hasTelecomPermissions()
            println("$TAG: Permissions check: $hasPermissions")

            // 4. Verificar si hay cuentas registradas
            val hasPhoneAccount = hasTelecomPhoneAccountRegistered()
            println("$TAG: Phone account registered: $hasPhoneAccount")

            // 5. Verificar si está manejando una llamada actualmente
            val isManagingCall = isTelecomManagingCall()
            println("$TAG: Currently managing call: $isManagingCall")

            // 6. Verificar el estado del audio
            val isTelecomHandlingAudio = isTelecomHandlingAudio()
            println("$TAG: Handling audio: $isTelecomHandlingAudio")

            // 7. Verificar si hay una llamada activa en el sistema
            val hasActiveSystemCall = hasActiveSystemCall()
            println("$TAG: Active system call: $hasActiveSystemCall")

            // 8. Verificar el modo de audio del sistema
            val audioMode = getAudioMode()
            val isInCallAudioMode = audioMode == android.media.AudioManager.MODE_IN_CALL ||
                    audioMode == android.media.AudioManager.MODE_IN_COMMUNICATION
            println("$TAG: Audio mode: $audioMode (In call mode: $isInCallAudioMode)")

            // 9. Verificar si la app tiene capacidad de llamada
            val hasCallCapability = hasCallCapability()
            println("$TAG: App has call capability: $hasCallCapability")

            // 10. Verificar ConnectionService activo
            val hasActiveConnectionService = hasActiveConnectionService()
            println("$TAG: Active ConnectionService: $hasActiveConnectionService")

            // 11. Verificar si el sistema está tocando el ringtone
            val isSystemPlayingRingtone = isSystemPlayingRingtone()
            println("$TAG: System playing ringtone: $isSystemPlayingRingtone")

            // DECISIÓN FINAL CON LÓGICA MÚLTIPLE
            // Escenario 1: Llamada activa completa manejada por Telecom
            val scenario1 = hasPermissions && hasPhoneAccount && isManagingCall && isTelecomHandlingAudio

            // Escenario 2: Sistema está manejando la llamada (modo audio + llamada activa)
            val scenario2 = hasActiveSystemCall && isInCallAudioMode && hasCallCapability

            // Escenario 3: ConnectionService activo con llamada
            val scenario3 = hasActiveConnectionService && isManagingCall

            // Escenario 4: Sistema está tocando su propio ringtone
            val scenario4 = isSystemPlayingRingtone && hasPhoneAccount

            val isActive = scenario1 || scenario2 || scenario3 || scenario4

            println("$TAG: ========== DECISION MATRIX ==========")
            println("$TAG: Scenario 1 (Full Telecom): $scenario1")
            println("$TAG: Scenario 2 (System Call): $scenario2")
            println("$TAG: Scenario 3 (ConnectionService): $scenario3")
            println("$TAG: Scenario 4 (System Ringtone): $scenario4")
            println("$TAG: ========================================")
            println("$TAG: 🎯 FINAL DECISION: Telecom is ${if (isActive) "ACTIVE" else "INACTIVE"}")
            println("$TAG: ========== TELECOM DETECTION END ==========")

            isActive
        } catch (e: Exception) {
            println("$TAG: ❌ Error checking TelecomManager status: ${e.message}")
            e.printStackTrace()
            false
        }
    }

// ==================== FUNCIONES DE SOPORTE ====================

    private fun hasTelecomPermissions(): Boolean {
        return try {
            val permissions = mutableMapOf<String, Boolean>()

            // MANAGE_OWN_CALLS (Android O+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permissions["MANAGE_OWN_CALLS"] = application.checkSelfPermission(
                    Manifest.permission.MANAGE_OWN_CALLS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            // ANSWER_PHONE_CALLS (Android O+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permissions["ANSWER_PHONE_CALLS"] = application.checkSelfPermission(
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            // READ_PHONE_STATE
            permissions["READ_PHONE_STATE"] = application.checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            // CALL_PHONE
            permissions["CALL_PHONE"] = application.checkSelfPermission(
                Manifest.permission.CALL_PHONE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            // READ_CALL_LOG
            permissions["READ_CALL_LOG"] = application.checkSelfPermission(
                Manifest.permission.READ_CALL_LOG
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            permissions.forEach { (perm, granted) ->
                println("$TAG:   - $perm: ${if (granted) "✓ GRANTED" else "✗ DENIED"}")
            }

            // Necesitamos al menos uno de los permisos críticos
            val hasCriticalPermission = permissions.values.any { it }

            hasCriticalPermission
        } catch (e: Exception) {
            println("$TAG: Error checking permissions: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasTelecomPhoneAccountRegistered(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }

            val telecomManager = application.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false

            // Obtener todas las cuentas disponibles
            val callCapableAccounts = try {
                telecomManager.callCapablePhoneAccounts
            } catch (e: SecurityException) {
                println("$TAG:   - No permission to access call capable accounts")
                emptyList()
            }

            val selfManagedAccounts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    telecomManager.selfManagedPhoneAccounts
                } catch (e: SecurityException) {
                    println("$TAG:   - No permission to access self-managed accounts")
                    emptyList()
                }
            } else {
                emptyList()
            }

            println("$TAG:   - Call capable accounts: ${callCapableAccounts.size}")
            println("$TAG:   - Self-managed accounts: ${selfManagedAccounts.size}")

            callCapableAccounts.forEach { account ->
                println("$TAG:     → ${account.componentName.flattenToShortString()}")
            }

            val hasAccounts = callCapableAccounts.isNotEmpty() || selfManagedAccounts.isNotEmpty()
            hasAccounts
        } catch (e: Exception) {
            println("$TAG: Error checking phone accounts: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun isTelecomManagingCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }

            val telecomManager = application.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false

            val isInCall = try {
                telecomManager.isInCall
            } catch (e: SecurityException) {
                println("$TAG:   - SecurityException checking isInCall")
                false
            }

            val isInManagedCall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    telecomManager.isInManagedCall
                } catch (e: SecurityException) {
                    println("$TAG:   - SecurityException checking isInManagedCall")
                    false
                }
            } else {
                false
            }

            println("$TAG:   - isInCall: $isInCall")
            println("$TAG:   - isInManagedCall: $isInManagedCall")

            isInCall || isInManagedCall
        } catch (e: Exception) {
            println("$TAG: Error checking if managing call: ${e.message}")
            false
        }
    }

    private fun isTelecomHandlingAudio(): Boolean {
        return try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return false

            val mode = audioManager.mode
            val isSpeakerphoneOn = audioManager.isSpeakerphoneOn
            val isMicrophoneMute = audioManager.isMicrophoneMute
            val isBluetoothScoOn = audioManager.isBluetoothScoOn

            println("$TAG:   - Audio mode: $mode")
            println("$TAG:   - Speakerphone: $isSpeakerphoneOn")
            println("$TAG:   - Mic muted: $isMicrophoneMute")
            println("$TAG:   - Bluetooth SCO: $isBluetoothScoOn")

            // Si está en modo de llamada, el audio está siendo manejado
            mode == android.media.AudioManager.MODE_IN_CALL ||
                    mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            println("$TAG: Error checking audio handling: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasActiveSystemCall(): Boolean {
        return try {
            val telephonyManager = application.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                ?: return false

            val callState = try {
                telephonyManager.callState
            } catch (e: SecurityException) {
                println("$TAG:   - SecurityException checking call state")
                android.telephony.TelephonyManager.CALL_STATE_IDLE
            }

            val stateString = when (callState) {
                android.telephony.TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                android.telephony.TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                else -> "UNKNOWN"
            }

            println("$TAG:   - Telephony call state: $stateString")

            callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) {
            println("$TAG: Error checking system call: ${e.message}")
            false
        }
    }

    private fun getAudioMode(): Int {
        return try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val mode = audioManager?.mode ?: android.media.AudioManager.MODE_NORMAL

            val modeString = when (mode) {
                android.media.AudioManager.MODE_NORMAL -> "NORMAL"
                android.media.AudioManager.MODE_RINGTONE -> "RINGTONE"
                android.media.AudioManager.MODE_IN_CALL -> "IN_CALL"
                android.media.AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "UNKNOWN($mode)"
            }

            println("$TAG:   - Current mode: $modeString")
            mode
        } catch (e: Exception) {
            println("$TAG: Error getting audio mode: ${e.message}")
            android.media.AudioManager.MODE_NORMAL
        }
    }

    private fun hasCallCapability(): Boolean {
        return try {
            val packageManager = application.packageManager
            val hasSystemFeature = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)

            println("$TAG:   - Device has telephony feature: $hasSystemFeature")

            // Verificar si la app declara usar telefonía
            val appInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        application.packageName,
                        android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(application.packageName, 0)
                }
            } catch (e: Exception) {
                null
            }

            val hasCallPermissions = application.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

            println("$TAG:   - App has CALL_PHONE permission: $hasCallPermissions")

            hasSystemFeature || hasCallPermissions
        } catch (e: Exception) {
            println("$TAG: Error checking call capability: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasActiveConnectionService(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }

            // Verificar si hay algún ConnectionService registrado en el manifiesto
            val packageManager = application.packageManager
            val serviceIntent = android.content.Intent(android.telecom.ConnectionService.SERVICE_INTERFACE)

            val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentServices(
                    serviceIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentServices(serviceIntent, 0)
            }

            println("$TAG:   - ConnectionServices found: ${services.size}")

            services.forEach { resolveInfo ->
                println("$TAG:     → ${resolveInfo.serviceInfo.name}")
            }

            val hasConnectionService = services.isNotEmpty()

            // Además, verificar si TelecomManager está gestionando actualmente
            val telecomManager = application.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val isManaging = telecomManager?.let {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.isInManagedCall
                    } else {
                        it.isInCall
                    }
                } catch (e: SecurityException) {
                    false
                }
            } ?: false

            hasConnectionService && isManaging
        } catch (e: Exception) {
            println("$TAG: Error checking ConnectionService: ${e.message}")
            false
        }
    }

    private fun isSystemPlayingRingtone(): Boolean {
        return try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return false

            val mode = audioManager.mode
            val isRingtoneMode = mode == android.media.AudioManager.MODE_RINGTONE

            // Verificar si hay un stream de ringtone activo
            val ringtoneVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_RING)
            val isRingtoneStreamActive = ringtoneVolume > 0

            println("$TAG:   - Ringtone mode: $isRingtoneMode")
            println("$TAG:   - Ringtone volume: $ringtoneVolume")

            // También verificar si el sistema está usando el stream de ring
            val isMusicActive = audioManager.isMusicActive

            println("$TAG:   - Music active (could be ringtone): $isMusicActive")

            isRingtoneMode && isRingtoneStreamActive
        } catch (e: Exception) {
            println("$TAG: Error checking system ringtone: ${e.message}")
            false
        }
    }
}