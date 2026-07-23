package com.eddyslarez.kmpsiprtc.services.screencapture

import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Version
import org.freedesktop.gstreamer.elements.AppSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lee el stream de PipeWire que abrió el portal y empuja cada frame a WebRTC.
 *
 * Detalles que costaron encontrar y conviene no "simplificar":
 *
 *  - **No se pasa el fd de `OpenPipeWireRemote`.** Aunque el portal lo entregue,
 *    setear `fd=` en `pipewiresrc` hace fallar `pw_stream_connect` ("connect
 *    error"). La app no corre en sandbox, así que el nodo es accesible por el
 *    socket por defecto usando solo `path=<node_id>`.
 *  - **`video/x-raw` pelado justo después del source**: Mutter también ofrece
 *    DMABuf, que `videoconvert` no consume; sin ese capsfilter el pipeline llega
 *    a PLAYING y no entrega un solo frame.
 *  - **Sin tope de alto**: los monitores verticales (p.ej. 1080x1920) se caían
 *    fuera de un `height=[16,1200]` y no negociaban.
 *  - **BGRA en vez de I420**: 4 bytes por píxel no tiene padding de stride en
 *    ningún ancho, lo que evita el descalce entre el stride de GStreamer y el de
 *    `NativeI420Buffer`. La conversión la hace libyuv.
 *  - **`keepalive-time`**: el compositor solo emite en cambios; sin esto una
 *    pantalla quieta deja de mandar frames.
 */
internal class PipeWireVideoCapture private constructor(
    private val pipeline: Pipeline,
    private val sink: AppSink,
    private val source: CustomVideoSource,
) {

    private val running = AtomicBoolean(true)
    private var pumpThread: Thread? = null

    private fun startPump() {
        val thread = Thread({ pump() }, "screen-capture-pump").apply { isDaemon = true }
        pumpThread = thread
        thread.start()
    }

    private fun pump() {
        var width = 0
        var height = 0
        while (running.get()) {
            val sample = try {
                sink.pullSample()
            } catch (_: Throwable) {
                null
            } ?: break

            try {
                val caps = sample.caps.getStructure(0)
                width = caps.getInteger("width")
                height = caps.getInteger("height")

                val buffer = sample.buffer
                val bytes = buffer.map(false) ?: continue
                try {
                    // Buffer nuevo por frame: reutilizarlo compite con el encoder,
                    // que retiene el frame de forma asincrónica.
                    val i420 = NativeI420Buffer.allocate(width, height)
                    VideoBufferConverter.convertToI420(bytes, i420, FourCC.ARGB)
                    val frame = VideoFrame(i420, 0, System.nanoTime())
                    try {
                        source.pushFrame(frame)
                    } finally {
                        frame.release()
                    }
                } finally {
                    buffer.unmap()
                }
            } catch (_: Throwable) {
                // Un frame malo no debe cortar la compartida.
            } finally {
                // Sin dispose() el pool de buffers se agota en segundos.
                runCatching { sample.dispose() }
            }
        }
    }

    /** Orden importante: parar el pump antes de soltar el pipeline evita segfaults. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { pipeline.setState(State.NULL) }
        runCatching { pumpThread?.join(2_000) }
        pumpThread = null
        runCatching { pipeline.dispose() }
    }

    companion object {
        private val gstInitialized = AtomicBoolean(false)

        private fun pipelineDescription(nodeId: Int): String = buildString {
            append("pipewiresrc name=src path=$nodeId ")
            append("always-copy=true do-timestamp=true keepalive-time=1000 ! ")
            append("video/x-raw ! ")
            append("videorate drop-only=true ! video/x-raw,max-framerate=30/1 ! ")
            append("videoscale ! videoconvert ! ")
            append("video/x-raw,format=BGRA ! ")
            append("appsink name=sink emit-signals=false sync=false max-buffers=2 drop=true")
        }

        /**
         * Arranca la captura del nodo indicado empujando a [source].
         *
         * @throws ScreenShareUnavailableException si falta GStreamer o el plugin de
         *   PipeWire, para que la UI muestre un mensaje en vez de fallar en silencio.
         */
        fun start(nodeId: Int, source: CustomVideoSource): PipeWireVideoCapture {
            try {
                if (gstInitialized.compareAndSet(false, true)) {
                    Gst.init(Version.BASELINE, "MCNSoftphone")
                }
            } catch (t: Throwable) {
                gstInitialized.set(false)
                throw ScreenShareUnavailableException(
                    "GStreamer no está disponible en este sistema", t
                )
            }

            val pipeline = try {
                Gst.parseLaunch(pipelineDescription(nodeId)) as Pipeline
            } catch (t: Throwable) {
                throw ScreenShareUnavailableException(
                    "Falta el plugin pipewiresrc de GStreamer (gstreamer1.0-pipewire)", t
                )
            }

            val sink = pipeline.getElementByName("sink") as AppSink
            pipeline.setState(State.PLAYING)
            return PipeWireVideoCapture(pipeline, sink, source).also { it.startPump() }
        }
    }
}
