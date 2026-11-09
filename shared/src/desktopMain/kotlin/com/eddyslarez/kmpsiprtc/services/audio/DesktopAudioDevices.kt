package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import dev.onvoid.webrtc.media.Device
import dev.onvoid.webrtc.media.DeviceChangeListener
import dev.onvoid.webrtc.media.MediaDevices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import dev.onvoid.webrtc.media.audio.AudioDevice as RtcAudioDevice

/**
 * Dispositivo de audio tal como lo ve WebRTC. [id] es el descriptor del dispositivo (GUID del
 * endpoint en Windows, UID de CoreAudio en macOS, nombre del sink/source de PulseAudio en Linux):
 * estable entre reinicios mientras el dispositivo exista, a diferencia del índice.
 */
data class DesktopAudioDeviceInfo(
    val id: String,
    val name: String,
    val isOutput: Boolean,
    val isDefault: Boolean
)

data class DesktopAudioDeviceList(
    val inputs: List<DesktopAudioDeviceInfo>,
    val outputs: List<DesktopAudioDeviceInfo>
)

/**
 * Modelo único de dispositivos de audio de escritorio, sobre `MediaDevices` de webrtc-java.
 * La librería no persiste la selección: la app guarda el [DesktopAudioDeviceInfo.id] y lo vuelve
 * a aplicar con `selectAudioInputDeviceById`/`selectAudioOutputDeviceById`.
 */
object DesktopAudioDevices {
    private const val TAG = "DesktopAudioDevices"
    private const val POLL_WITHOUT_LISTENER_MS = 2_000L
    private const val POLL_WITH_LISTENER_MS = 10_000L

    private val _devices = MutableStateFlow(DesktopAudioDeviceList(emptyList(), emptyList()))

    /** Lista vigente; se actualiza en caliente mientras haya alguien con [startMonitoring] activo. */
    val devices: StateFlow<DesktopAudioDeviceList> = _devices.asStateFlow()

    private val lock = Any()
    private var monitorUsers = 0
    private var monitorScope: CoroutineScope? = null
    private var changeListener: DeviceChangeListener? = null

    fun refresh(): DesktopAudioDeviceList {
        val list = enumerate()
        _devices.value = list
        return list
    }

    /** Arranca la escucha de conexión/desconexión. Cuenta referencias: cada llamada pide su [stopMonitoring]. */
    fun startMonitoring() {
        synchronized(lock) {
            monitorUsers++
            if (monitorUsers > 1) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            monitorScope = scope
            val listener = object : DeviceChangeListener {
                override fun deviceConnected(device: Device) {
                    scope.launch { refresh() }
                }

                override fun deviceDisconnected(device: Device) {
                    scope.launch { refresh() }
                }
            }
            val listening = try {
                MediaDevices.addDeviceChangeListener(listener)
                changeListener = listener
                true
            } catch (t: Throwable) {
                log.w(tag = TAG) { "Sin aviso de cambios de dispositivos (${t::class.simpleName}); se consulta cada 2 s" }
                false
            }
            val interval = if (listening) POLL_WITH_LISTENER_MS else POLL_WITHOUT_LISTENER_MS
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(interval)
                }
            }
        }
    }

    fun stopMonitoring() {
        synchronized(lock) {
            if (monitorUsers == 0) return
            monitorUsers--
            if (monitorUsers > 0) return
            changeListener?.let { listener -> runCatching { MediaDevices.removeDeviceChangeListener(listener) } }
            changeListener = null
            monitorScope?.cancel()
            monitorScope = null
        }
    }

    /**
     * Línea de JavaSound (sin abrir) sobre la entrada [deviceId], para medidores de nivel de la app.
     * Sin pareja en JavaSound se devuelve la del sistema; ver [JavaSoundDevices].
     */
    fun openCaptureLine(deviceId: String?, format: AudioFormat): TargetDataLine =
        JavaSoundDevices.openTargetLine(format, nameFor(deviceId, output = false))

    /** Como [openCaptureLine], para tonos de prueba en la salida [deviceId]. */
    fun openRenderLine(deviceId: String?, format: AudioFormat): SourceDataLine =
        JavaSoundDevices.openSourceLine(format, nameFor(deviceId, output = true))

    private fun nameFor(deviceId: String?, output: Boolean): String? {
        val query: String = deviceId?.takeIf { it.isNotBlank() } ?: return null
        fun find(list: DesktopAudioDeviceList): DesktopAudioDeviceInfo? {
            val candidates = if (output) list.outputs else list.inputs
            return AudioDeviceNameMatcher.resolve(query, candidates, { it.id }, { it.name })
        }
        return (find(devices.value) ?: find(refresh()))?.name ?: query
    }

    internal fun captureDevices(): List<RtcAudioDevice> =
        try {
            MediaDevices.getAudioCaptureDevices().orEmpty().filterNotNull()
        } catch (t: Throwable) {
            log.w(tag = TAG) { "No se pudieron enumerar entradas: ${t::class.simpleName}" }
            emptyList()
        }

    internal fun renderDevices(): List<RtcAudioDevice> =
        try {
            MediaDevices.getAudioRenderDevices().orEmpty().filterNotNull()
        } catch (t: Throwable) {
            log.w(tag = TAG) { "No se pudieron enumerar salidas: ${t::class.simpleName}" }
            emptyList()
        }

    internal fun defaultCaptureDevice(): RtcAudioDevice? =
        runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull()

    internal fun defaultRenderDevice(): RtcAudioDevice? =
        runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull()

    internal fun idOf(device: RtcAudioDevice): String =
        device.descriptor?.takeIf { it.isNotBlank() } ?: device.name.orEmpty()

    internal fun isDefault(device: RtcAudioDevice, systemDefault: RtcAudioDevice?): Boolean {
        val name = device.name.orEmpty()
        if (name.startsWith("Default", ignoreCase = true)) return true
        if (systemDefault == null) return false
        val defaultDescriptor = systemDefault.descriptor
        return (!defaultDescriptor.isNullOrBlank() && defaultDescriptor == device.descriptor) ||
            (systemDefault.name != null && systemDefault.name == name)
    }

    private fun enumerate(): DesktopAudioDeviceList {
        val defaultIn = defaultCaptureDevice()
        val defaultOut = defaultRenderDevice()
        return DesktopAudioDeviceList(
            inputs = captureDevices().map {
                DesktopAudioDeviceInfo(idOf(it), it.name.orEmpty(), isOutput = false, isDefault = isDefault(it, defaultIn))
            },
            outputs = renderDevices().map {
                DesktopAudioDeviceInfo(idOf(it), it.name.orEmpty(), isOutput = true, isDefault = isDefault(it, defaultOut))
            }
        )
    }
}

/**
 * Empareja nombres de dispositivo de fuentes distintas (ADM de WebRTC, `MediaDevices`, mezcladores
 * de JavaSound, nombres guardados por versiones anteriores).
 */
internal object AudioDeviceNameMatcher {
    private val rolePrefix = Regex("^(default|communication|predeterminado|comunicaciones)\\s*-\\s*")
    private val alsaSuffix = Regex("\\[(plug)?hw:[^\\]]*]")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(name: String): String {
        var n = name.lowercase().trim()
        n = rolePrefix.replace(n, "")
        n = n.removePrefix("port ")
        n = alsaSuffix.replace(n, " ")
        return nonAlnum.replace(n, " ").trim()
    }

    /**
     * 100 si coinciden normalizados; 80 si uno contiene al otro (JavaSound en Windows recorta los
     * nombres a 31 caracteres); si no, proporción de palabras compartidas escalada a 0..70.
     */
    fun score(a: String, b: String): Int {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0
        if (na == nb) return 100
        val (shorter, longer) = if (na.length <= nb.length) na to nb else nb to na
        if (shorter.length >= 4 && longer.contains(shorter)) return 80
        val ta = na.split(' ').toSet()
        val tb = nb.split(' ').toSet()
        val union = (ta + tb).size
        if (union == 0) return 0
        return (ta.intersect(tb).size * 70) / union
    }

    fun <T> bestMatch(target: String, candidates: List<T>, minScore: Int, nameOf: (T) -> String): T? =
        candidates
            .map { it to score(target, nameOf(it)) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first

    /** Por id exacto, luego nombre exacto, luego nombre sin mayúsculas, luego nombre equivalente. */
    fun <T> resolve(query: String, devices: List<T>, idOf: (T) -> String, nameOf: (T) -> String): T? {
        if (query.isBlank()) return null
        return devices.firstOrNull { idOf(it) == query }
            ?: devices.firstOrNull { nameOf(it) == query }
            ?: devices.firstOrNull { nameOf(it).equals(query, ignoreCase = true) }
            ?: bestMatch(query, devices, minScore = 80, nameOf = nameOf)
    }
}

/** Selección vigente compartida con el tono de llamada y la grabación, que suenan fuera de WebRTC. */
internal object DesktopAudioRouting {
    @Volatile
    var selectedOutputName: String? = null

    @Volatile
    var selectedInputName: String? = null
}
