package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AudioToolbox.*
import platform.Foundation.*
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.sin

actual fun createAudioManager(): AudioManager = IosAudioManagerImpl()

@OptIn(ExperimentalForeignApi::class)
class IosAudioManagerImpl : AudioManager {
    private var incomingPlayer: AVAudioPlayer? = null
    private var outgoingPlayer: AVAudioPlayer? = null
    private var callWaitingPlayer: AVAudioPlayer? = null
    private var callWaitingJob: Job? = null
    private var incomingRingtoneJob: Job? = null
    private var outgoingRingtoneJob: Job? = null
    private var vibrationJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var incomingRingtonePath: String? = null
    private var outgoingRingtonePath: String? = null

    @Volatile private var isIncomingRingtonePlaying = false
    @Volatile
    private var isOutgoingRingtonePlaying = false
    @Volatile private var shouldStopIncoming = false
    @Volatile private var shouldStopOutgoing = false
    @Volatile private var isVibrating = false

    private var currentVibrationPattern = "default"

    /** Aviso breve para una entrante por socket; los pushes usan el aviso de CallKit. */
    override fun playCallWaitingTone() {
        stopCallWaitingTone()
        callWaitingJob = audioScope.launch {
            var player: AVAudioPlayer? = null
            try {
                val bytes = createCallWaitingWave()
                val data = bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
                player = AVAudioPlayer(data, null)
                callWaitingPlayer = player
                // Mantener la sesion y ruta de audio de la conversacion en curso.
                player.volume = 0.12f
                player.numberOfLoops = 0
                if (player.play()) delay(200)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                println("Error reproduciendo aviso de llamada en espera: ${error.message}")
            } finally {
                player?.stop()
                if (callWaitingPlayer === player) callWaitingPlayer = null
            }
        }
    }

    private fun stopCallWaitingTone() {
        callWaitingJob?.cancel()
        callWaitingJob = null
        callWaitingPlayer?.stop()
        callWaitingPlayer = null
    }

    /** PCM mono de 150 ms a 425 Hz, con rampas que evitan chasquidos. */
    private fun createCallWaitingWave(): ByteArray {
        val sampleRate = 8_000
        val sampleCount = sampleRate * 150 / 1_000
        val dataSize = sampleCount * 2
        val wave = ByteArray(44 + dataSize)
        fun write16(offset: Int, value: Int) {
            wave[offset] = value.toByte()
            wave[offset + 1] = (value ushr 8).toByte()
        }
        fun write32(offset: Int, value: Int) {
            repeat(4) { byte -> wave[offset + byte] = (value ushr (byte * 8)).toByte() }
        }
        fun writeText(offset: Int, text: String) {
            text.forEachIndexed { index, char -> wave[offset + index] = char.code.toByte() }
        }
        writeText(0, "RIFF")
        write32(4, 36 + dataSize)
        writeText(8, "WAVE")
        writeText(12, "fmt ")
        write32(16, 16)
        write16(20, 1)
        write16(22, 1)
        write32(24, sampleRate)
        write32(28, sampleRate * 2)
        write16(32, 2)
        write16(34, 16)
        writeText(36, "data")
        write32(40, dataSize)
        val fadeSamples = sampleRate * 5 / 1_000
        repeat(sampleCount) { index ->
            val envelope = minOf(index, sampleCount - 1 - index, fadeSamples).toDouble() / fadeSamples
            val sample = (sin(2.0 * PI * 425.0 * index / sampleRate) * Short.MAX_VALUE * envelope).toInt()
            write16(44 + index * 2, sample)
        }
        return wave
    }

    init {
        val resourceUtils = createResourceUtils()
        incomingRingtonePath = resourceUtils.getDefaultIncomingRingtonePath()
        outgoingRingtonePath = resourceUtils.getDefaultOutgoingRingtonePath()

        // Configurar sesión de audio
        configureAudioSession()
    }

    private fun configureAudioSession() {
        val audioSession = AVAudioSession.sharedInstance()
        try {
            audioSession.setCategory(
                AVAudioSessionCategoryPlayback,
                null
            )
            audioSession.setActive(true, null)
        } catch (e: Exception) {
            println("Error configuring audio session: ${e.message}")
        }
    }

    override fun setVibrationPattern(patternName: String) {
        if (VibrationPatterns.patterns.containsKey(patternName)) {
            currentVibrationPattern = patternName
            println("Vibration pattern set to: $patternName")
        }
    }

     override fun setIncomingRingtone(path: String) {
        incomingRingtonePath = path
        println("Incoming ringtone path set: $path")
    }

     override fun setOutgoingRingtone(path: String) {
        outgoingRingtonePath = path
        println("Outgoing ringtone path set: $path")
    }

     override fun playRingtone(syncVibration: Boolean) {
        if (isIncomingRingtonePlaying) {
            println("Incoming ringtone already playing")
            return
        }

        stopOutgoingRingtone()

        val path = incomingRingtonePath
        if (path == null) {
            println("No incoming ringtone path set")
            return
        }

        isIncomingRingtonePlaying = true
        shouldStopIncoming = false

        incomingRingtoneJob = audioScope.launch {
            try {
                while (!shouldStopIncoming && isIncomingRingtonePlaying) {
                    val player = createAudioPlayer(path)
                    if (player == null) {
                        println("Failed to create audio player")
                        break
                    }

                    incomingPlayer = player

                    try {
                        if (syncVibration) {
                            startSynchronizedVibration(player)
                        } else {
                            startVibration()
                        }

                        player.play()

                        // Esperar mientras reproduce
                        while (player.playing && !shouldStopIncoming && isIncomingRingtonePlaying) {
                            delay(100)
                        }

                    } catch (e: Exception) {
                        println("Error during playback: ${e.message}")
                    } finally {
                        player.stop()
                        incomingPlayer = null
                    }

                    if (!shouldStopIncoming && isIncomingRingtonePlaying) {
                        delay(800)
                    }
                }
            } catch (e: Exception) {
                println("Error in incoming ringtone coroutine: ${e.message}")
            } finally {
                isIncomingRingtonePlaying = false
                shouldStopIncoming = false
                stopVibration()
                incomingPlayer?.stop()
                incomingPlayer = null
            }
        }
    }

    private fun startSynchronizedVibration(player: AVAudioPlayer) {
        if (isVibrating) return

        isVibrating = true

        vibrationJob = audioScope.launch {
            try {
                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    vibrateToRhythm()
                    delay(100)
                }
            } catch (e: Exception) {
                println("Error in synchronized vibration: ${e.message}")
            } finally {
                stopVibration()
            }
        }
    }

    private suspend fun vibrateToRhythm() {
        val pattern = VibrationPatterns.patterns[currentVibrationPattern]
            ?: VibrationPatterns.patterns["default"]!!

        try {
            // iOS usa AudioServicesPlaySystemSound para vibración
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)

            val totalDuration = pattern.pattern.sum()
            delay(totalDuration)
        } catch (e: Exception) {
            println("Error in rhythm vibration: ${e.message}")
        }
    }

    private fun startVibration() {
        if (isVibrating) return

        isVibrating = true

        vibrationJob = audioScope.launch {
            try {
                val pattern = VibrationPatterns.patterns[currentVibrationPattern]
                    ?: VibrationPatterns.patterns["default"]!!

                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                    delay(pattern.pattern.sum())
                }
            } catch (e: Exception) {
                println("Error in vibration: ${e.message}")
            } finally {
                stopVibration()
            }
        }
    }

    private fun stopVibration() {
        if (!isVibrating) return
        isVibrating = false

        try {
            vibrationJob?.cancel()
            vibrationJob = null
        } catch (e: Exception) {
            println("Error stopping vibration: ${e.message}")
        }
    }

    private fun createAudioPlayer(path: String): AVAudioPlayer? {
        return try {
            val url = NSURL.fileURLWithPath(path)
            val player = AVAudioPlayer(url, null)
            player.numberOfLoops = 0
            player.prepareToPlay()
            player
        } catch (e: Exception) {
            println("Error creating audio player: ${e.message}")
            null
        }
    }

     override fun playOutgoingRingtone() {
        if (isOutgoingRingtonePlaying) return

        stopRingtone()

        val path = outgoingRingtonePath
        if (path == null) {
            println("No outgoing ringtone path set")
            return
        }

        isOutgoingRingtonePlaying = true
        shouldStopOutgoing = false

        outgoingRingtoneJob = audioScope.launch {
            try {
                while (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                    val player = createAudioPlayer(path)
                    if (player == null) break

                    outgoingPlayer = player

                    try {
                        player.play()

                        while (player.playing && !shouldStopOutgoing && isOutgoingRingtonePlaying) {
                            delay(100)
                        }
                    } catch (e: Exception) {
                        println("Error during outgoing playback: ${e.message}")
                    } finally {
                        player.stop()
                        outgoingPlayer = null
                    }

                    if (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                println("Error in outgoing coroutine: ${e.message}")
            } finally {
                isOutgoingRingtonePlaying = false
                shouldStopOutgoing = false
                outgoingPlayer?.stop()
                outgoingPlayer = null
            }
        }
    }

     override fun stopRingtone() {
        stopCallWaitingTone()
        shouldStopIncoming = true
        isIncomingRingtonePlaying = false
        stopVibration()

        try {
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null
            incomingPlayer?.stop()
            incomingPlayer = null
        } catch (e: Exception) {
            println("Error stopping ringtone: ${e.message}")
        }
    }

     override fun stopOutgoingRingtone() {
        shouldStopOutgoing = true
        isOutgoingRingtonePlaying = false

        try {
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null
            outgoingPlayer?.stop()
            outgoingPlayer = null
        } catch (e: Exception) {
            println("Error stopping outgoing: ${e.message}")
        }
    }

     override fun stopAllRingtones() {
        stopCallWaitingTone()
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

            incomingPlayer?.stop()
            outgoingPlayer?.stop()
            incomingPlayer = null
            outgoingPlayer = null
        } catch (e: Exception) {
            println("Error in stopAllRingtones: ${e.message}")
        }
    }

     override fun isRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying || isOutgoingRingtonePlaying
    }

     override fun isIncomingRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying && !shouldStopIncoming
    }

     override fun isOutgoingRingtonePlaying(): Boolean {
        return isOutgoingRingtonePlaying && !shouldStopOutgoing
    }

     override fun isVibrating(): Boolean {
        return isVibrating
    }

     override fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO MANAGER DIAGNOSTIC (iOS) ===")
            appendLine("Incoming playing: $isIncomingRingtonePlaying")
            appendLine("Outgoing playing: $isOutgoingRingtonePlaying")
            appendLine("Vibrating: $isVibrating")
            appendLine("Should stop incoming: $shouldStopIncoming")
            appendLine("Should stop outgoing: $shouldStopOutgoing")
            appendLine("Incoming job active: ${incomingRingtoneJob?.isActive}")
            appendLine("Outgoing job active: ${outgoingRingtoneJob?.isActive}")
            appendLine("Vibration job active: ${vibrationJob?.isActive}")
            appendLine("Current vibration pattern: $currentVibrationPattern")
            appendLine("Incoming player: ${incomingPlayer != null}")
            appendLine("Outgoing player: ${outgoingPlayer != null}")
            appendLine("Call waiting player: ${callWaitingPlayer != null}")
        }
    }

     override fun cleanup() {
        stopAllRingtones()
        audioScope.cancel()
    }
}
