package com.eddyslarez.kmpsiprtc.services.audio

import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.*

actual fun createAudioManager(): AudioManager = DesktopAudioManagerImpl()

class DesktopAudioManagerImpl : AudioManager {
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

    // Referencia al SourceDataLine activo para poder cerrarlo al detener
    @Volatile private var activeIncomingLine: SourceDataLine? = null
    @Volatile private var activeOutgoingLine: SourceDataLine? = null

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
        }
    }

    override fun setIncomingRingtone(path: String) {
        incomingRingtonePath = path
    }

    override fun setOutgoingRingtone(path: String) {
        outgoingRingtonePath = path
    }

    /**
     * Reproduce un archivo de audio usando SourceDataLine (streaming).
     * Más confiable que Clip para archivos WAV y otros formatos PCM.
     * Retorna true si la reproducción terminó normalmente, false si fue interrumpida o falló.
     */
    private fun playAudioStreaming(path: String, shouldStop: () -> Boolean, lineRef: (SourceDataLine?) -> Unit): Boolean {
        var audioStream: AudioInputStream? = null
        var convertedStream: AudioInputStream? = null
        var line: SourceDataLine? = null

        try {
            // Abrir el audio input stream según el tipo de path
            audioStream = openAudioInputStream(path)
            if (audioStream == null) {
                println("AudioManager: No se pudo abrir audio stream para: $path")
                return false
            }

            val baseFormat = audioStream.format
            println("AudioManager: Formato original: $baseFormat")

            // Convertir a formato reproducible (PCM_SIGNED, 16-bit)
            val playableFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.sampleRate,
                16,
                baseFormat.channels,
                baseFormat.channels * 2,
                baseFormat.sampleRate,
                false // little-endian
            )

            convertedStream = if (baseFormat.encoding != AudioFormat.Encoding.PCM_SIGNED ||
                baseFormat.sampleSizeInBits != 16) {
                if (AudioSystem.isConversionSupported(playableFormat, baseFormat)) {
                    println("AudioManager: Convirtiendo formato a: $playableFormat")
                    AudioSystem.getAudioInputStream(playableFormat, audioStream)
                } else {
                    println("AudioManager: Conversión no soportada, usando formato original")
                    audioStream
                }
            } else {
                audioStream
            }

            val formatToUse = convertedStream.format
            println("AudioManager: Formato final: $formatToUse")

            // Abrir SourceDataLine
            val dataLineInfo = DataLine.Info(SourceDataLine::class.java, formatToUse)
            if (!AudioSystem.isLineSupported(dataLineInfo)) {
                println("AudioManager: Línea de audio no soportada para formato: $formatToUse")
                return false
            }

            line = AudioSystem.getLine(dataLineInfo) as SourceDataLine
            line.open(formatToUse)
            lineRef(line)
            line.start()

            println("AudioManager: Reproduciendo audio...")

            // Streaming: leer y escribir en bloques
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (!shouldStop()) {
                bytesRead = convertedStream.read(buffer, 0, buffer.size)
                if (bytesRead == -1) break
                line.write(buffer, 0, bytesRead)
            }

            if (!shouldStop()) {
                // Solo hacer drain si no fue interrumpido
                line.drain()
            }

            println("AudioManager: Reproducción completada")
            return !shouldStop()

        } catch (e: UnsupportedAudioFileException) {
            println("AudioManager: Formato de audio no soportado: ${e.message}")
            e.printStackTrace()
            return false
        } catch (e: Exception) {
            if (!shouldStop()) {
                println("AudioManager: Error reproduciendo audio: ${e.message}")
                e.printStackTrace()
            }
            return false
        } finally {
            lineRef(null)
            try { line?.stop() } catch (_: Exception) {}
            try { line?.close() } catch (_: Exception) {}
            try { convertedStream?.close() } catch (_: Exception) {}
            // Solo cerrar audioStream si es diferente de convertedStream
            if (convertedStream !== audioStream) {
                try { audioStream?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Abre un AudioInputStream desde diferentes tipos de rutas:
     * - jar: URLs (recursos dentro del JAR)
     * - http/https URLs
     * - Rutas de classpath (empezando con /)
     * - Archivos locales
     */
    private fun openAudioInputStream(path: String): AudioInputStream? {
        return try {
            when {
                path.startsWith("jar:") || path.startsWith("http://") || path.startsWith("https://") -> {
                    // Usar stream bufferizado para mejorar compatibilidad con SPI (p.ej. MP3)
                    val url = java.net.URL(path)
                    val buffered = BufferedInputStream(url.openStream())
                    AudioSystem.getAudioInputStream(buffered)
                }
                path.startsWith("/") -> {
                    // Intentar como recurso de classpath primero
                    val resourceUrl = this::class.java.getResource(path)
                    if (resourceUrl != null) {
                        val buffered = BufferedInputStream(resourceUrl.openStream())
                        AudioSystem.getAudioInputStream(buffered)
                    } else {
                        val buffered = BufferedInputStream(File(path).inputStream())
                        AudioSystem.getAudioInputStream(buffered)
                    }
                }
                else -> {
                    val buffered = BufferedInputStream(File(path).inputStream())
                    AudioSystem.getAudioInputStream(buffered)
                }
            }
        } catch (e: Exception) {
            println("AudioManager: Error abriendo audio stream: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override fun playRingtone(syncVibration: Boolean) {
        if (isIncomingRingtonePlaying) return

        stopOutgoingRingtone()

        val path = incomingRingtonePath ?: run {
            println("AudioManager: No incoming ringtone path set")
            return
        }

        isIncomingRingtonePlaying = true
        shouldStopIncoming = false

        println("AudioManager: Iniciando ringtone entrante: $path")

        incomingRingtoneJob = audioScope.launch {
            try {
                if (syncVibration) {
                    startSynchronizedVibration()
                } else {
                    startVibration()
                }

                var loopCount = 0
                while (!shouldStopIncoming && isIncomingRingtonePlaying) {
                    loopCount++
                    println("AudioManager: Ringtone entrante iteración: $loopCount")

                    val played = playAudioStreaming(
                        path,
                        shouldStop = { shouldStopIncoming || !isIncomingRingtonePlaying },
                        lineRef = { activeIncomingLine = it }
                    )

                    if (!played && !shouldStopIncoming) {
                        println("AudioManager: Fallo reproducción, deteniendo loop")
                        break
                    }

                    // Pausa entre repeticiones
                    if (!shouldStopIncoming && isIncomingRingtonePlaying) {
                        delay(800)
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación normal
            } catch (e: Exception) {
                println("AudioManager: Error en coroutine de ringtone: ${e.message}")
            } finally {
                isIncomingRingtonePlaying = false
                shouldStopIncoming = false
                stopVibration()
            }
        }
    }

    override fun playOutgoingRingtone() {
        if (isOutgoingRingtonePlaying) return

        stopRingtone()

        val path = outgoingRingtonePath ?: run {
            println("AudioManager: No outgoing ringtone path set")
            return
        }

        isOutgoingRingtonePlaying = true
        shouldStopOutgoing = false

        println("AudioManager: Iniciando ringback: $path")

        outgoingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                    loopCount++
                    println("AudioManager: Ringback iteración: $loopCount")

                    val played = playAudioStreaming(
                        path,
                        shouldStop = { shouldStopOutgoing || !isOutgoingRingtonePlaying },
                        lineRef = { activeOutgoingLine = it }
                    )

                    if (!played && !shouldStopOutgoing) {
                        println("AudioManager: Fallo reproducción ringback, deteniendo loop")
                        break
                    }

                    if (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                        delay(1000)
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación normal
            } catch (e: Exception) {
                println("AudioManager: Error en coroutine de ringback: ${e.message}")
            } finally {
                isOutgoingRingtonePlaying = false
                shouldStopOutgoing = false
            }
        }
    }

    override fun stopRingtone() {
        shouldStopIncoming = true
        isIncomingRingtonePlaying = false
        stopVibration()

        try {
            // Cerrar línea activa para interrumpir reproducción inmediatamente
            activeIncomingLine?.let { line ->
                try { line.stop() } catch (_: Exception) {}
                try { line.close() } catch (_: Exception) {}
            }
            activeIncomingLine = null

            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null
        } catch (e: Exception) {
            println("AudioManager: Error deteniendo ringtone: ${e.message}")
        }
    }

    override fun stopOutgoingRingtone() {
        shouldStopOutgoing = true
        isOutgoingRingtonePlaying = false

        try {
            activeOutgoingLine?.let { line ->
                try { line.stop() } catch (_: Exception) {}
                try { line.close() } catch (_: Exception) {}
            }
            activeOutgoingLine = null

            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null
        } catch (e: Exception) {
            println("AudioManager: Error deteniendo ringback: ${e.message}")
        }
    }

    override fun stopAllRingtones() {
        shouldStopIncoming = true
        shouldStopOutgoing = true
        isIncomingRingtonePlaying = false
        isOutgoingRingtonePlaying = false
        stopVibration()

        try {
            activeIncomingLine?.let { line ->
                try { line.stop() } catch (_: Exception) {}
                try { line.close() } catch (_: Exception) {}
            }
            activeIncomingLine = null

            activeOutgoingLine?.let { line ->
                try { line.stop() } catch (_: Exception) {}
                try { line.close() } catch (_: Exception) {}
            }
            activeOutgoingLine = null

            incomingRingtoneJob?.cancel()
            outgoingRingtoneJob?.cancel()
            incomingRingtoneJob = null
            outgoingRingtoneJob = null
        } catch (e: Exception) {
            println("AudioManager: Error en stopAllRingtones: ${e.message}")
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
            appendLine("=== AUDIO MANAGER DIAGNOSTIC (Desktop) ===")
            appendLine("Incoming playing: $isIncomingRingtonePlaying")
            appendLine("Outgoing playing: $isOutgoingRingtonePlaying")
            appendLine("Vibrating: $isVibrating")
            appendLine("Should stop incoming: $shouldStopIncoming")
            appendLine("Should stop outgoing: $shouldStopOutgoing")
            appendLine("Incoming job active: ${incomingRingtoneJob?.isActive}")
            appendLine("Outgoing job active: ${outgoingRingtoneJob?.isActive}")
            appendLine("Active incoming line: ${activeIncomingLine != null}")
            appendLine("Active outgoing line: ${activeOutgoingLine != null}")
            appendLine("Audio scope active: ${audioScope.isActive}")
            appendLine("Incoming path: $incomingRingtonePath")
            appendLine("Outgoing path: $outgoingRingtonePath")
        }
    }

    override fun cleanup() {
        stopAllRingtones()
        audioScope.cancel()
    }

    // --- Vibration (simulada en Desktop) ---

    private fun startSynchronizedVibration() {
        if (isVibrating) return
        isVibrating = true

        vibrationJob = audioScope.launch {
            try {
                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    val pattern = VibrationPatterns.patterns[currentVibrationPattern]
                        ?: VibrationPatterns.patterns["default"]!!
                    delay(pattern.pattern.sum())
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                println("AudioManager: Error en vibración: ${e.message}")
            } finally {
                isVibrating = false
            }
        }
    }

    private fun startVibration() {
        if (isVibrating) return
        isVibrating = true

        vibrationJob = audioScope.launch {
            try {
                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    val pattern = VibrationPatterns.patterns[currentVibrationPattern]
                        ?: VibrationPatterns.patterns["default"]!!
                    delay(pattern.pattern.sum())
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                println("AudioManager: Error en vibración: ${e.message}")
            } finally {
                isVibrating = false
            }
        }
    }

    private fun stopVibration() {
        if (!isVibrating) return
        isVibrating = false
        vibrationJob?.cancel()
        vibrationJob = null
    }
}
