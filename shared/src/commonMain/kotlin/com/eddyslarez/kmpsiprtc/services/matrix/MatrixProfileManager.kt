package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import net.folivo.trixnity.client.*
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.Presence

/**
 * Perfil propio (display name + avatar), presencia y perfiles de otros usuarios.
 *
 * El cliente se obtiene perezosamente vía [clientProvider]. Para subir avatares
 * reutiliza [MatrixFileManager].
 */
class MatrixProfileManager(
    private val clientProvider: () -> MatrixClient?,
    private val fileManager: MatrixFileManager,
) {
    private val TAG = "MatrixProfileManager"

    /** Display name propio observable (null si no hay sesión). */
    val myDisplayName: StateFlow<String?>?
        get() = clientProvider()?.displayName

    /** Avatar (mxc) propio observable. */
    val myAvatarUrl: StateFlow<String?>?
        get() = clientProvider()?.avatarUrl

    /** Cambia el display name propio. */
    suspend fun setDisplayName(name: String?): Result<Unit> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            client.setDisplayName(name)
        } catch (e: Exception) {
            log.e(TAG) { "setDisplayName failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Sube los bytes como avatar y actualiza el perfil propio.
     */
    suspend fun setAvatar(bytes: ByteArray, mimeType: String): Result<Unit> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val mxc = fileManager.uploadMedia(bytes, mimeType).getOrThrow()
            client.setAvatarUrl(mxc)
        } catch (e: Exception) {
            log.e(TAG) { "setAvatar failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /** Quita el avatar propio. */
    suspend fun clearAvatar(): Result<Unit> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            client.setAvatarUrl(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Perfil global de un usuario (display name + avatar mxc). */
    suspend fun getProfile(userId: String): Result<MatrixUserProfile> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val profile = client.api.user.getProfile(UserId(userId)).getOrThrow()
            Result.success(
                MatrixUserProfile(
                    userId = userId,
                    displayName = profile.displayName,
                    avatarUrl = profile.avatarUrl,
                )
            )
        } catch (e: Exception) {
            log.w(TAG) { "getProfile($userId) failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /** Publica la presencia propia (online/offline/unavailable). */
    suspend fun setPresence(presence: MatrixPresence, statusMessage: String? = null): Result<Unit> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            client.api.user.setPresence(client.userId, presence.toTrixnity(), statusMessage)
        } catch (e: Exception) {
            log.w(TAG) { "setPresence failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /** Observa la presencia de un usuario. */
    fun observePresence(userId: String): Flow<MatrixUserPresence?>? {
        val client = clientProvider() ?: return null
        return client.user.getPresence(UserId(userId)).map { up ->
            up?.let {
                MatrixUserPresence(
                    userId = userId,
                    presence = it.presence.toMatrix(),
                    lastActiveAgo = null,
                    statusMessage = it.statusMessage,
                    currentlyActive = it.isCurrentlyActive,
                )
            }
        }
    }
}

internal fun MatrixPresence.toTrixnity(): Presence = when (this) {
    MatrixPresence.ONLINE -> Presence.ONLINE
    MatrixPresence.OFFLINE -> Presence.OFFLINE
    MatrixPresence.UNAVAILABLE -> Presence.UNAVAILABLE
}

internal fun Presence.toMatrix(): MatrixPresence = when (this) {
    Presence.ONLINE -> MatrixPresence.ONLINE
    Presence.OFFLINE -> MatrixPresence.OFFLINE
    Presence.UNAVAILABLE -> MatrixPresence.UNAVAILABLE
}
