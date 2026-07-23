package com.eddyslarez.kmpsiprtc.services.screencapture

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.random.Random

private const val PORTAL_NAME = "org.freedesktop.portal.Desktop"
private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"

/** El usuario elige en el diálogo del sistema, así que hay que darle tiempo humano. */
private const val START_TIMEOUT_SECONDS = 180L
private const val CALL_TIMEOUT_SECONDS = 15L

@DBusInterfaceName("org.freedesktop.portal.ScreenCast")
internal interface ScreenCastPortal : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun SelectSources(session: DBusPath, options: Map<String, Variant<*>>): DBusPath
    fun Start(session: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Session")
internal interface PortalSession : DBusInterface {
    fun Close()
}

@DBusInterfaceName("org.freedesktop.portal.Request")
internal interface PortalRequest : DBusInterface {
    fun Close()

    class Response(
        path: String,
        val response: UInt32,
        val results: Map<String, Variant<*>>,
    ) : DBusSignal(path, response, results)
}

/** Stream devuelto por `Start`: `(node_id, propiedades)`. */
internal class PortalStream(
    @Position(0) val nodeId: UInt32,
    @Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

/** El usuario cerró el diálogo del sistema sin elegir nada. */
class ScreenShareCancelledException : Exception("El usuario canceló la selección de pantalla")

/** No se puede capturar en este equipo (falta el portal, PipeWire o GStreamer). */
class ScreenShareUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Sesión de captura de pantalla vía xdg-desktop-portal.
 *
 * Es el único camino que funciona en Wayland: el capturador nativo de webrtc-java
 * está compilado sin PipeWire y solo sabe capturar por X11, que bajo Wayland
 * devuelve pantalla en negro.
 */
internal class PortalScreenCastSession private constructor(
    private val connection: DBusConnection,
    private val sessionHandle: String,
    val nodeId: Int,
) {

    /**
     * Cierra la sesión. Es obligatorio: si queda abierta, el backend del portal
     * no vuelve a mostrar el diálogo de selección en el próximo intento.
     */
    fun close() {
        runCatching {
            connection.getRemoteObject(PORTAL_NAME, sessionHandle, PortalSession::class.java).Close()
        }
        runCatching { connection.disconnect() }
    }

    companion object {
        private val prefs: Preferences
            get() = Preferences.userRoot().node("com/eddyslarez/kmpsiprtc/screencast")

        private const val RESTORE_TOKEN_KEY = "restore_token"

        fun clearRestoreToken() {
            runCatching { prefs.remove(RESTORE_TOKEN_KEY) }
        }

        /**
         * Negocia una sesión con el portal. Abre el selector del sistema salvo que
         * haya un token guardado de una compartida anterior.
         *
         * @param forcePicker fuerza el diálogo aunque exista token (para "cambiar
         *   qué comparto").
         */
        fun open(forcePicker: Boolean = false): PortalScreenCastSession {
            var connection: DBusConnection? = null
            try {
                connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                val portal = connection.getRemoteObject(
                    PORTAL_NAME, PORTAL_PATH, ScreenCastPortal::class.java
                )

                val createToken = newToken()
                val createResponse = expectResponse(connection, createToken)
                portal.CreateSession(
                    mapOf(
                        "handle_token" to Variant(createToken),
                        "session_handle_token" to Variant(newToken()),
                    )
                )
                val created = createResponse.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                check(created.response.toInt() == 0) { "CreateSession devolvió ${created.response}" }
                val sessionHandle = created.results["session_handle"]?.value?.toString()
                    ?: error("El portal no devolvió session_handle")
                val session = DBusPath(sessionHandle)

                val selectToken = newToken()
                val selectResponse = expectResponse(connection, selectToken)
                val selectOptions = mutableMapOf<String, Variant<*>>(
                    "handle_token" to Variant(selectToken),
                    "types" to Variant(UInt32(3)),        // MONITOR | WINDOW
                    "multiple" to Variant(false),
                    "cursor_mode" to Variant(UInt32(2)),  // EMBEDDED
                    "persist_mode" to Variant(UInt32(2)), // hasta que el usuario revoque
                )
                val savedToken = if (forcePicker) null else prefs.get(RESTORE_TOKEN_KEY, null)
                if (!savedToken.isNullOrBlank()) {
                    selectOptions["restore_token"] = Variant(savedToken)
                }
                portal.SelectSources(session, selectOptions)
                selectResponse.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val startToken = newToken()
                val startResponse = expectResponse(connection, startToken)
                portal.Start(session, "", mapOf("handle_token" to Variant(startToken)))
                val started = startResponse.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (started.response.toInt() != 0) {
                    runCatching {
                        connection.getRemoteObject(PORTAL_NAME, sessionHandle, PortalSession::class.java).Close()
                    }
                    connection.disconnect()
                    // 1 = cancelado por el usuario; cualquier otro valor es error real.
                    if (started.response.toInt() == 1) throw ScreenShareCancelledException()
                    throw ScreenShareUnavailableException("El portal rechazó la captura (${started.response})")
                }

                started.results["restore_token"]?.value?.toString()?.let {
                    runCatching { prefs.put(RESTORE_TOKEN_KEY, it) }
                }

                val nodeId = extractNodeId(started.results)
                return PortalScreenCastSession(connection, sessionHandle, nodeId)
            } catch (e: ScreenShareCancelledException) {
                throw e
            } catch (e: ScreenShareUnavailableException) {
                runCatching { connection?.disconnect() }
                throw e
            } catch (e: Throwable) {
                runCatching { connection?.disconnect() }
                throw ScreenShareUnavailableException(
                    "No se pudo negociar la captura con el portal: ${e.message}", e
                )
            }
        }

        private fun extractNodeId(results: Map<String, Variant<*>>): Int {
            val streams = results["streams"]?.value as? List<*>
                ?: error("El portal no devolvió streams")
            return when (val first = streams.firstOrNull()) {
                is PortalStream -> first.nodeId.toInt()
                // Si la firma no matchea el Struct, dbus-java lo entrega como array.
                is Array<*> -> (first[0] as UInt32).toInt()
                else -> error("Formato de stream inesperado: ${first?.let { it::class.simpleName }}")
            }
        }

        /**
         * El portal responde por una señal en una ruta predecible. Hay que suscribirse
         * ANTES de invocar el método: si se hace después, la respuesta se pierde.
         */
        private fun expectResponse(
            connection: DBusConnection,
            token: String,
        ): CompletableFuture<PortalRequest.Response> {
            val sender = connection.uniqueName.removePrefix(":").replace('.', '_')
            val path = "/org/freedesktop/portal/desktop/request/$sender/$token"
            val future = CompletableFuture<PortalRequest.Response>()
            connection.addSigHandler(PortalRequest.Response::class.java) { signal ->
                if (signal.path == path) future.complete(signal)
            }
            return future
        }

        private fun newToken(): String = "mcn${Random.nextInt(0, Int.MAX_VALUE)}"
    }
}
