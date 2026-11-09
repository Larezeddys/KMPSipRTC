package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.*

actual fun createAudioManager(): AudioManager = DesktopAudioManagerImpl()

class DesktopAudioManagerImpl : AudioManager {
    private val TAG = "DesktopAudioManager"
    private var incomingRingtoneJob: Job? = null
    private var outgoingRingtoneJob: Job? = null
    private var vibrationJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var incomingRingtonePath: String? = null
    @Volatile private var outgoingRingtonePath: String? = null

    /**
     * Numero de reproduccion del tono de entrada.
     *
     * Cada vez que se arranca o se para el tono, sube. Cada corrutina se queda con el suyo y solo
     * actua mientras siga siendo el vigente.
     *
     * Sin esto, cambiar de tono con la llamada ya sonando —que es justo lo que pasa con el tono
     * por cuenta: la libreria arranca con el global y la app lo sustituye enseguida— era una
     * carrera: la corrutina vieja, ya cancelada, ejecutaba su `finally` DESPUES de que la nueva
     * hubiera arrancado y le ponia `isIncomingRingtonePlaying = false` por debajo, matandola. Se
     * oia el tono que hubiera antes, o ninguno.
     */
    private val incomingGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /** Ultima peticion de vibracion, para poder reiniciar el tono conservandola. */
    @Volatile private var lastSyncVibration = true

    /** Fichero que esta sonando ahora mismo, para no reiniciarlo si piden el mismo. */
    @Volatile private var pathEnCurso: String? = null

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

        log.d(tag = TAG) { "AudioManager initialized - Desktop" }
        log.d(tag = TAG) { "Incoming path: $incomingRingtonePath" }
        log.d(tag = TAG) { "Outgoing path: $outgoingRingtonePath" }
    }

    override fun setVibrationPattern(patternName: String) {
        if (VibrationPatterns.patterns.containsKey(patternName)) {
            currentVibrationPattern = patternName
        }
    }

    override fun setIncomingRingtone(path: String) {
        val cambio = incomingRingtonePath != path
        incomingRingtonePath = path
        // Si el tono ya esta sonando y ahora es otro, hay que reiniciarlo. Es el caso del tono por
        // cuenta: cuando la app resuelve que esta llamada va con otro tono, el reproductor ya
        // arranco con el global; sin reinicio se seguiria oyendo aquel hasta el final.
        if (cambio && isIncomingRingtonePlaying) {
            log.d(tag = TAG) { "tono entrante cambiado en caliente -> $path" }
            playRingtone(lastSyncVibration)
        }
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
                log.w(tag = TAG) { "No se pudo abrir audio stream para: $path" }
                return false
            }

            val baseFormat = audioStream.format
            log.d(tag = TAG) { "Formato original: $baseFormat" }
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
                    log.d(tag = TAG) { "Convirtiendo formato a: $playableFormat" }
                    AudioSystem.getAudioInputStream(playableFormat, audioStream)
                } else {
                    log.w(tag = TAG) { "Conversión no soportada, usando formato original" }
                    audioStream
                }
            } else {
                audioStream
            }

            val formatToUse = convertedStream.format
            log.d(tag = TAG) { "Formato final: $formatToUse" }
            line = JavaSoundDevices.openSourceLine(formatToUse)
            line.open(formatToUse)
            lineRef(line)
            line.start()

            log.d(tag = TAG) { "Reproduciendo audio..." }
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

            log.d(tag = TAG) { "Reproducción completada" }
            return !shouldStop()

        } catch (e: UnsupportedAudioFileException) {
            log.w(tag = TAG) { "Formato de audio no soportado: ${e.message}" }
            return false
        } catch (e: Exception) {
            if (!shouldStop()) {
                log.w(tag = TAG) { "Error reproduciendo audio: ${e.message}" }
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
            log.w(tag = TAG) { "Error abriendo audio stream: ${e.message}" }
            null
        }
    }

    /**
     * Emite un solo aviso corto a volumen digital bajo para que la segunda llamada no tape
     * la conversación activa.
     */
    override fun playCallWaitingTone() {
        audioScope.launch {
            val sampleRate = 48_000
            val durationMs = 150
            val sampleCount = sampleRate * durationMs / 1_000
            val fadeSamples = sampleRate / 200
            val amplitude = (Short.MAX_VALUE * 0.12).toInt()
            val samples = ByteArray(sampleCount * 2)
            for (index in 0 until sampleCount) {
                val envelope = when {
                    index < fadeSamples -> index.toDouble() / fadeSamples
                    index >= sampleCount - fadeSamples ->
                        (sampleCount - index).toDouble() / fadeSamples
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val value = (
                    kotlin.math.sin(2.0 * kotlin.math.PI * 425.0 * index / sampleRate) *
                        amplitude * envelope
                    ).toInt()
                samples[index * 2] = value.toByte()
                samples[index * 2 + 1] = (value shr 8).toByte()
            }

            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            var line: SourceDataLine? = null
            try {
                line = JavaSoundDevices.openSourceLine(format)
                line.open(format)
                line.start()
                line.write(samples, 0, samples.size)
                line.drain()
            } catch (error: Exception) {
                log.w(tag = TAG) { "No se pudo reproducir el aviso de llamada en espera: ${error.message}" }
            } finally {
                runCatching { line?.stop() }
                runCatching { line?.close() }
            }
        }
    }

    override fun playRingtone(syncVibration: Boolean) {
        val path = incomingRingtonePath ?: run {
            log.d(tag = TAG) { "No incoming ringtone path set" }
            return
        }

        // Idempotente si YA esta sonando este mismo fichero. La condicion original era
        // `if (isIncomingRingtonePlaying) return`, sin mirar el path, y hacia imposible cambiar de
        // tono con la llamada sonando —que es justo el caso del tono por cuenta—. Pero quitarla
        // del todo tampoco vale: la app llama a stop+play despues de fijar el tono, asi que cada
        // llamada entrante cortaba el audio a media reproduccion y volvia a empezar con un clic.
        if (isIncomingRingtonePlaying && pathEnCurso == path) {
            log.d(tag = TAG) { "ringtone entrante ya sonando con $path, no se reinicia" }
            return
        }

        stopOutgoingRingtone()
        stopRingtone()

        val generacion = incomingGeneration.incrementAndGet()
        lastSyncVibration = syncVibration
        pathEnCurso = path
        isIncomingRingtonePlaying = true
        shouldStopIncoming = false

        log.d(tag = TAG) { "Iniciando ringtone entrante: $path (gen $generacion)" }
        // Solo sigue viva la reproduccion mas reciente: cualquier otra se apaga sola aunque su
        // cancelacion tarde en llegar.
        fun vigente() = incomingGeneration.get() == generacion && !shouldStopIncoming

        incomingRingtoneJob = audioScope.launch {
            try {
                if (syncVibration) {
                    startSynchronizedVibration()
                } else {
                    startVibration()
                }

                var loopCount = 0
                while (vigente()) {
                    loopCount++
                    log.d(tag = TAG) { "Ringtone entrante iteración: $loopCount" }
                    // Se relee en cada vuelta: si el tono cambia entre repeticiones, la
                    // siguiente ya suena con el nuevo.
                    val pathVuelta = incomingRingtonePath ?: path
                    pathEnCurso = pathVuelta
                    val played = playAudioStreaming(
                        pathVuelta,
                        shouldStop = { !vigente() },
                        lineRef = { if (vigente()) activeIncomingLine = it }
                    )

                    if (!played && vigente()) {
                        log.w(tag = TAG) { "Fallo reproducción, deteniendo loop" }
                        break
                    }

                    // Pausa entre repeticiones
                    if (vigente()) {
                        delay(800)
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación normal
            } catch (e: Exception) {
                log.w(tag = TAG) { "Error en coroutine de ringtone: ${e.message}" }
            } finally {
                // Solo si nadie ha arrancado otra reproducción mientras tanto: si no, esta
                // corrutina moribunda apagaria la que acaba de empezar.
                if (incomingGeneration.get() == generacion) {
                    isIncomingRingtonePlaying = false
                    shouldStopIncoming = false
                    pathEnCurso = null
                    stopVibration()
                }
            }
        }
    }

    override fun playOutgoingRingtone() {
        if (isOutgoingRingtonePlaying) return

        stopRingtone()

        val path = outgoingRingtonePath ?: run {
            log.d(tag = TAG) { "No outgoing ringtone path set" }
            return
        }

        isOutgoingRingtonePlaying = true
        shouldStopOutgoing = false

        log.d(tag = TAG) { "Iniciando ringback: $path" }
        outgoingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                    loopCount++
                    log.d(tag = TAG) { "Ringback iteración: $loopCount" }
                    val played = playAudioStreaming(
                        path,
                        shouldStop = { shouldStopOutgoing || !isOutgoingRingtonePlaying },
                        lineRef = { activeOutgoingLine = it }
                    )

                    if (!played && !shouldStopOutgoing) {
                        log.w(tag = TAG) { "Fallo reproducción ringback, deteniendo loop" }
                        break
                    }

                    if (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                        delay(1000)
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación normal
            } catch (e: Exception) {
                log.w(tag = TAG) { "Error en coroutine de ringback: ${e.message}" }
            } finally {
                // Mismo cuidado que en el tono de entrada: no apagar una reproducción posterior.
                if (outgoingRingtoneJob == coroutineContext[Job]) {
                    isOutgoingRingtonePlaying = false
                    shouldStopOutgoing = false
                }
            }
        }
    }

    override fun stopRingtone() {
        // Invalida cualquier reproduccion viva aunque su cancelacion tarde en hacerse efectiva.
        incomingGeneration.incrementAndGet()
        shouldStopIncoming = true
        isIncomingRingtonePlaying = false
        pathEnCurso = null
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
            log.w(tag = TAG) { "Error deteniendo ringtone: ${e.message}" }
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
            log.w(tag = TAG) { "Error deteniendo ringback: ${e.message}" }
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
            log.w(tag = TAG) { "Error en stopAllRingtones: ${e.message}" }
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
                log.w(tag = TAG) { "Error en vibración: ${e.message}" }
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
                log.w(tag = TAG) { "Error en vibración: ${e.message}" }
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
