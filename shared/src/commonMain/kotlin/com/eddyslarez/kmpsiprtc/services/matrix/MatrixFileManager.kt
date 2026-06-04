package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.log
import io.ktor.http.ContentType
import net.folivo.trixnity.client.*
import net.folivo.trixnity.utils.toByteArrayFlow

/**
 * Gestiona la subida y descarga de media de Matrix usando el MediaService de
 * Trixnity, que respeta el cache local (Okio) configurado en [MatrixModuleFactory].
 *
 * - **Descarga**: resuelve un `mxc://` a bytes reales (con cache). Reemplaza la
 *   construcción manual de URLs HTTP `/_matrix/media/...` que era frágil con
 *   homeservers que requieren autenticación por header.
 * - **Subida**: convierte bytes en un `mxc://` (para avatares de perfil/sala).
 *
 * El cliente se obtiene de forma perezosa vía [clientProvider] para reflejar
 * siempre la sesión actual (cambia en login/logout).
 */
class MatrixFileManager(
    private val clientProvider: () -> MatrixClient?,
) {
    private val TAG = "MatrixFileManager"

    /**
     * Descarga los bytes de una media `mxc://`. Devuelve null si no hay sesión
     * o la descarga falla. [maxSize] limita el tamaño (null = sin límite).
     */
    suspend fun getMediaBytes(mxcUri: String, maxSize: Long? = null): ByteArray? {
        val client = clientProvider() ?: run {
            log.w(TAG) { "getMediaBytes: no Matrix client (not logged in) for $mxcUri" }
            return null
        }
        return try {
            val bytes = client.media.getMedia(mxcUri).getOrThrow().toByteArray(maxSize = maxSize)
            log.d(TAG) { "getMediaBytes OK $mxcUri -> ${bytes?.size ?: 0} bytes" }
            bytes
        } catch (e: Exception) {
            log.w(TAG) { "getMediaBytes FAILED for $mxcUri: ${e.message}" }
            null
        }
    }

    /**
     * Descarga un thumbnail (recortado) de una imagen/vídeo `mxc://`.
     */
    suspend fun getThumbnailBytes(mxcUri: String, width: Long, height: Long): ByteArray? {
        val client = clientProvider() ?: return null
        return try {
            client.media.getThumbnail(mxcUri, width, height).getOrThrow().toByteArray()
        } catch (e: Exception) {
            log.w(TAG) { "getThumbnailBytes failed for $mxcUri: ${e.message}" }
            null
        }
    }

    /**
     * Sube bytes al homeserver y devuelve el `mxc://` resultante. Útil para
     * avatares de perfil/sala (los mensajes con adjuntos usan la DSL de
     * `sendMessage { image/file(...) }` que ya hace el upload internamente).
     */
    suspend fun uploadMedia(bytes: ByteArray, mimeType: String): Result<String> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val contentType = try {
                ContentType.parse(mimeType)
            } catch (_: Throwable) {
                ContentType.Application.OctetStream
            }
            val cacheUri = client.media.prepareUploadMedia(bytes.toByteArrayFlow(), contentType)
            val mxc = client.media.uploadMedia(cacheUri).getOrThrow()
            Result.success(mxc)
        } catch (e: Exception) {
            log.e(TAG) { "uploadMedia failed: ${e.message}" }
            Result.failure(e)
        }
    }
}
