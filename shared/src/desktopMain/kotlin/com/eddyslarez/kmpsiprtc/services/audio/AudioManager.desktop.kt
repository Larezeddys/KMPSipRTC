package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.*

actual fun createAudioManager(): AudioManager = DesktopAudioManagerImpl()

class DesktopAudioManagerImpl : AudioManager {
    private var incomingClip: Clip? = null
    private var outgoingClip: Clip? = null
    private var incomingRingtoneJob: Job? = null
    private var outgoingRingtoneJob: Job? = null
    private var vibrationJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var incomingRingtonePath: String? = null
    private var outgoingRingtonePath: String? = null

    @Volatile private var isIncomingRingtonePlaying = false
    @Volatile private var isOutgoingRingtonePlaying = false
    @Volatile private var shouldStopIncoming = false
    @Volatile private var shouldStopOutgoing = false
    @Volatile private var isVibrating = false

    private var currentVibrationPattern = "default"

    init {
        val resourceUtils = createResourceUtils()
        incomingRingtonePath = resourceUtils.getDefaultIncomingRingtonePath()
        outgoingRingtonePath = resourceUtils.getDefaultOutgoingRingtonePath()

        println("AudioManager initialized - Desktop")
        println("Incoming path: $incomingRingtonePath")
        println("Outgoing path: $outgoingRingtonePath")
    }

    override fun setVibrationPattern(patternName: String) {
        if (VibrationPatterns.patterns.containsKey(patternName)) {
            currentVibrationPattern = patternName
            println("Vibration pattern set to: $patternName (Desktop - visual only)")
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

        println("Starting incoming ringtone: $path")

        incomingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopIncoming && isIncomingRingtonePlaying) {
                    loopCount++
                    println("Incoming ringtone loop iteration: $loopCount")

                    val clip = createAudioClip(path)
                    if (clip == null) {
                        println("Failed to create audio clip")
                        break
                    }

                    incomingClip = clip

                    try {
                        if (syncVibration) {
                            startSynchronizedVibration()
                        } else {
                            startVibration()
                        }

                        clip.start()

                        // Esperar mientras reproduce
                        while (clip.isRunning && !shouldStopIncoming && isIncomingRingtonePlaying) {
                            delay(100)
                        }

                        println("Clip finished playing")

                    } catch (e: Exception) {
                        println("Error during playback: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        try {
                            if (clip.isRunning) clip.stop()
                            clip.close()
                        } catch (e: Exception) {
                            println("Error closing clip: ${e.message}")
                        }
                        incomingClip = null
                    }

                    if (!shouldStopIncoming && isIncomingRingtonePlaying) {
                        delay(800)
                    }
                }

                println("Ringtone loop ended after $loopCount iterations")

            } catch (e: Exception) {
                println("Error in incoming ringtone coroutine: ${e.message}")
                e.printStackTrace()
            } finally {
                isIncomingRingtonePlaying = false
                shouldStopIncoming = false
                stopVibration()
                incomingClip?.close()
                incomingClip = null
            }
        }
    }

    private fun startSynchronizedVibration() {
        if (isVibrating) return

        isVibrating = true
        println("Starting synchronized vibration (Desktop - simulated)")

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
            // En desktop, podríamos simular vibración visualmente
            // o simplemente logear el patrón
            println("Vibration pulse (pattern: $currentVibrationPattern)")

            val totalDuration = pattern.pattern.sum()
            delay(totalDuration)
        } catch (e: Exception) {
            println("Error in rhythm vibration: ${e.message}")
        }
    }

    private fun startVibration() {
        if (isVibrating) return

        isVibrating = true
        println("Starting vibration (Desktop - simulated)")

        vibrationJob = audioScope.launch {
            try {
                val pattern = VibrationPatterns.patterns[currentVibrationPattern]
                    ?: VibrationPatterns.patterns["default"]!!

                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    println("Vibration pulse")
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
            println("Vibration stopped")
        } catch (e: Exception) {
            println("Error stopping vibration: ${e.message}")
        }
    }

    private fun createAudioClip(path: String): Clip? {
        return try {
            // Intentar cargar desde recursos
            val audioInputStream = if (path.startsWith("http://") || path.startsWith("https://")) {
                // URL externa
                val url = java.net.URL(path)
                AudioSystem.getAudioInputStream(BufferedInputStream(url.openStream()))
            } else if (path.startsWith("/")) {
                // Recurso en classpath
                val resourceStream = this::class.java.getResourceAsStream(path)
                if (resourceStream != null) {
                    AudioSystem.getAudioInputStream(BufferedInputStream(resourceStream))
                } else {
                    // Intentar como archivo
                    AudioSystem.getAudioInputStream(File(path))
                }
            } else {
                // Archivo local
                AudioSystem.getAudioInputStream(File(path))
            }

            val clip = AudioSystem.getClip()
            clip.open(audioInputStream)
            println("Audio clip created successfully")
            clip
        } catch (e: Exception) {
            println("Error creating audio clip: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override fun playOutgoingRingtone() {
        if (isOutgoingRingtonePlaying) {
            println("Outgoing ringtone already playing")
            return
        }

        stopRingtone()

        val path = outgoingRingtonePath
        if (path == null) {
            println("No outgoing ringtone path set")
            return
        }

        isOutgoingRingtonePlaying = true
        shouldStopOutgoing = false

        println("Starting outgoing ringtone: $path")

        outgoingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                    loopCount++
                    println("Outgoing ringtone loop iteration: $loopCount")

                    val clip = createAudioClip(path)
                    if (clip == null) {
                        println("Failed to create outgoing audio clip")
                        break
                    }

                    outgoingClip = clip

                    try {
                        clip.start()

                        while (clip.isRunning && !shouldStopOutgoing && isOutgoingRingtonePlaying) {
                            delay(100)
                        }

                    } catch (e: Exception) {
                        println("Error during outgoing playback: ${e.message}")
                    } finally {
                        try {
                            if (clip.isRunning) clip.stop()
                            clip.close()
                        } catch (e: Exception) {
                            println("Error closing outgoing clip: ${e.message}")
                        }
                        outgoingClip = null
                    }

                    if (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                        delay(1000)
                    }
                }

                println("Outgoing ringtone loop ended after $loopCount iterations")

            } catch (e: Exception) {
                println("Error in outgoing coroutine: ${e.message}")
                e.printStackTrace()
            } finally {
                isOutgoingRingtonePlaying = false
                shouldStopOutgoing = false
                outgoingClip?.close()
                outgoingClip = null
            }
        }
    }

    override fun stopRingtone() {
        println("Stopping incoming ringtone")
        shouldStopIncoming = true
        isIncomingRingtonePlaying = false
        stopVibration()

        try {
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

            incomingClip?.let { clip ->
                try {
                    if (clip.isRunning) clip.stop()
                    clip.close()
                } catch (e: Exception) {
                    println("Error stopping clip: ${e.message}")
                }
            }
            incomingClip = null
        } catch (e: Exception) {
            println("Error stopping ringtone: ${e.message}")
        }
    }

    override fun stopOutgoingRingtone() {
        println("Stopping outgoing ringtone")
        shouldStopOutgoing = true
        isOutgoingRingtonePlaying = false

        try {
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null

            outgoingClip?.let { clip ->
                try {
                    if (clip.isRunning) clip.stop()
                    clip.close()
                } catch (e: Exception) {
                    println("Error stopping outgoing clip: ${e.message}")
                }
            }
            outgoingClip = null
        } catch (e: Exception) {
            println("Error stopping outgoing ringtone: ${e.message}")
        }
    }

    override fun stopAllRingtones() {
        println("Stopping ALL ringtones")
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

            incomingClip?.let { clip ->
                try {
                    if (clip.isRunning) clip.stop()
                    clip.close()
                } catch (e: Exception) {}
            }
            incomingClip = null

            outgoingClip?.let { clip ->
                try {
                    if (clip.isRunning) clip.stop()
                    clip.close()
                } catch (e: Exception) {}
            }
            outgoingClip = null

            println("All ringtones stopped successfully")
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

   override  fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO MANAGER DIAGNOSTIC (Desktop) ===")
            appendLine("Incoming playing: $isIncomingRingtonePlaying")
            appendLine("Outgoing playing: $isOutgoingRingtonePlaying")
            appendLine("Vibrating: $isVibrating")
            appendLine("Should stop incoming: $shouldStopIncoming")
            appendLine("Should stop outgoing: $shouldStopOutgoing")
            appendLine("Incoming job active: ${incomingRingtoneJob?.isActive}")
            appendLine("Outgoing job active: ${outgoingRingtoneJob?.isActive}")
            appendLine("Vibration job active: ${vibrationJob?.isActive}")
            appendLine("Current vibration pattern: $currentVibrationPattern")
            appendLine("Incoming clip: ${incomingClip != null}")
            appendLine("Outgoing clip: ${outgoingClip != null}")
            appendLine("Audio scope active: ${audioScope.isActive}")
        }
    }

   override  fun cleanup() {
        stopAllRingtones()
        audioScope.cancel()
    }
}

