package com.eddyslarez.kmpsiprtc.services.audio

import com.eddyslarez.kmpsiprtc.platform.log
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Abre líneas de JavaSound en el mezclador que corresponde al dispositivo elegido en WebRTC, para
 * que el tono de llamada y la grabación usen el mismo dispositivo que la llamada.
 *
 * Emparejamiento: se buscan los mezcladores con líneas del tipo pedido y se elige el de mayor
 * [AudioDeviceNameMatcher.score] con el nombre del dispositivo de WebRTC, exigiendo al menos 50
 * (mitad de las palabras en común). En Windows (DirectSound) y macOS los nombres suelen coincidir;
 * en Linux JavaSound ve ALSA (`PCH [plughw:0,0]`) y WebRTC ve PulseAudio, así que lo normal es no
 * encontrar pareja y usar el dispositivo por defecto del sistema.
 */
internal object JavaSoundDevices {
    private const val TAG = "JavaSoundDevices"
    private const val MIN_SCORE = 50

    fun findMixer(deviceName: String?, output: Boolean): Mixer.Info? {
        if (deviceName.isNullOrBlank()) return null
        val lineClass = if (output) SourceDataLine::class.java else TargetDataLine::class.java
        val candidates = try {
            AudioSystem.getMixerInfo().filter { info ->
                runCatching {
                    val mixer = AudioSystem.getMixer(info)
                    val lines = if (output) mixer.sourceLineInfo else mixer.targetLineInfo
                    lines.any { lineClass.isAssignableFrom(it.lineClass) }
                }.getOrDefault(false)
            }
        } catch (t: Throwable) {
            emptyList()
        }
        return AudioDeviceNameMatcher.bestMatch(deviceName, candidates, MIN_SCORE) { it.name }
    }

    fun openSourceLine(format: AudioFormat, deviceName: String? = DesktopAudioRouting.selectedOutputName): SourceDataLine {
        findMixer(deviceName, output = true)?.let { info ->
            try {
                return AudioSystem.getSourceDataLine(format, info)
            } catch (t: Throwable) {
                log.w(tag = TAG) { "Salida '${info.name}' no admite el formato; se usa la del sistema" }
            }
        }
        return AudioSystem.getSourceDataLine(format)
    }

    fun openTargetLine(format: AudioFormat, deviceName: String? = DesktopAudioRouting.selectedInputName): TargetDataLine {
        findMixer(deviceName, output = false)?.let { info ->
            try {
                return AudioSystem.getTargetDataLine(format, info)
            } catch (t: Throwable) {
                log.w(tag = TAG) { "Entrada '${info.name}' no admite el formato; se usa la del sistema" }
            }
        }
        return AudioSystem.getTargetDataLine(format)
    }
}
