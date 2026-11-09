package com.eddyslarez.kmpsiprtc.services.screencapture

import com.eddyslarez.kmpsiprtc.platform.log
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pantalla o ventana que se puede compartir con el capturador nativo de webrtc-java. */
data class DesktopShareSource(
    val id: String,
    val title: String,
    val isWindow: Boolean,
)

/** Miniatura en BGRA de 8 bits por canal, fila a fila sin relleno. */
class DesktopShareThumbnail(
    val width: Int,
    val height: Int,
    val bgra: ByteArray,
)

/**
 * Ids con prefijo: los ids nativos de pantallas y de ventanas son espacios distintos y el
 * mismo número puede existir en ambos. Un id sin prefijo es una pantalla, como antes.
 */
object DesktopShareSources {
    const val SCREEN_PREFIX = "screen:"
    const val WINDOW_PREFIX = "window:"

    private const val TAG = "DesktopShareSources"
    private const val THUMBNAIL_TIMEOUT_MS = 1_500L

    fun screenId(nativeId: Long): String = "$SCREEN_PREFIX$nativeId"

    fun windowId(nativeId: Long): String = "$WINDOW_PREFIX$nativeId"

    /** `(id nativo, es ventana)` o null si [id] no es de este capturador (p. ej. "portal"). */
    fun parse(id: String?): Pair<Long, Boolean>? {
        val value = id?.trim().orEmpty()
        return when {
            value.startsWith(WINDOW_PREFIX) -> value.removePrefix(WINDOW_PREFIX).toLongOrNull()?.let { it to true }
            value.startsWith(SCREEN_PREFIX) -> value.removePrefix(SCREEN_PREFIX).toLongOrNull()?.let { it to false }
            else -> value.toLongOrNull()?.let { it to false }
        }
    }

    fun screens(): List<DesktopShareSource> = enumerate(isWindow = false)

    fun windows(): List<DesktopShareSource> = enumerate(isWindow = true)
        .filter { it.title.isNotBlank() }

    /**
     * Un fotograma de la fuente reducido a [maxWidth]x[maxHeight]. Solo para el selector:
     * abre su propio capturador y lo cierra, sin tocar la compartida en curso.
     */
    fun thumbnail(id: String, maxWidth: Int, maxHeight: Int): DesktopShareThumbnail? {
        if (System.getProperty("mcn.screenshare.thumbnails") == "false") return null
        val (nativeId, isWindow) = parse(id) ?: return null
        var capturer: DesktopCapturer? = null
        return try {
            capturer = if (isWindow) WindowCapturer() else ScreenCapturer()
            val source = capturer.getDesktopSources().firstOrNull { it.id == nativeId }
                ?: DesktopSource("", nativeId)
            val latch = CountDownLatch(1)
            var result: DesktopShareThumbnail? = null
            capturer.selectSource(source)
            capturer.start { captureResult, frame ->
                try {
                    if (captureResult == DesktopCapturer.Result.SUCCESS && frame != null && result == null) {
                        val width = frame.buffer.width
                        val height = frame.buffer.height
                        if (width > 0 && height > 0) {
                            val full = ByteArray(width * height * 4)
                            VideoBufferConverter.convertFromI420(frame.buffer, full, FourCC.ARGB)
                            result = downscale(full, width, height, maxWidth, maxHeight)
                        }
                    }
                } catch (e: Throwable) {
                    log.w(tag = TAG) { "Miniatura descartada ($id): ${e.message}" }
                } finally {
                    // Como en los sinks de vídeo, el I420Buffer llega con AddRef y es de este callback.
                    runCatching { frame?.release() }
                    latch.countDown()
                }
            }
            capturer.captureFrame()
            latch.await(THUMBNAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result
        } catch (e: Throwable) {
            log.w(tag = TAG) { "Sin miniatura para $id: ${e::class.simpleName}: ${e.message}" }
            null
        } finally {
            runCatching { capturer?.dispose() }
        }
    }

    private fun enumerate(isWindow: Boolean): List<DesktopShareSource> {
        var capturer: DesktopCapturer? = null
        return try {
            capturer = if (isWindow) WindowCapturer() else ScreenCapturer()
            capturer.getDesktopSources().mapIndexed { index, source ->
                val title = source.title?.trim().orEmpty()
                DesktopShareSource(
                    id = if (isWindow) windowId(source.id) else screenId(source.id),
                    title = if (isWindow) title else title.ifBlank { "Screen ${index + 1}" },
                    isWindow = isWindow,
                )
            }
        } catch (e: Throwable) {
            log.w(tag = TAG) {
                "Error enumerando ${if (isWindow) "ventanas" else "pantallas"} " +
                    "(os=${System.getProperty("os.name")}): ${e::class.simpleName}: ${e.message}"
            }
            emptyList()
        } finally {
            runCatching { capturer?.dispose() }
        }
    }

    private fun downscale(
        source: ByteArray,
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): DesktopShareThumbnail {
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        if (targetWidth == width && targetHeight == height) return DesktopShareThumbnail(width, height, source)
        val target = ByteArray(targetWidth * targetHeight * 4)
        for (y in 0 until targetHeight) {
            val sourceRow = (y.toLong() * height / targetHeight).toInt() * width
            for (x in 0 until targetWidth) {
                val from = (sourceRow + (x.toLong() * width / targetWidth).toInt()) * 4
                val to = (y * targetWidth + x) * 4
                System.arraycopy(source, from, target, to, 4)
            }
        }
        return DesktopShareThumbnail(targetWidth, targetHeight, target)
    }
}
