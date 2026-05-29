package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.folivo.trixnity.client.*
import net.folivo.trixnity.client.store.RoomUser
import net.folivo.trixnity.client.store.RoomUserReceipts
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.ReceiptType

/**
 * Operaciones de sala que no son "enviar mensaje": indicador de escritura,
 * read receipts y listado de miembros con su perfil resuelto.
 *
 * El cliente se obtiene perezosamente vía [clientProvider].
 */
class MatrixRoomManager(
    private val clientProvider: () -> MatrixClient?,
    private val config: MatrixConfig,
) {
    private val TAG = "MatrixRoomManager"

    /**
     * Notifica al servidor que el usuario está (o dejó de estar) escribiendo.
     * [typing]=true usa [MatrixConfig.typingTimeoutMs] como timeout.
     */
    suspend fun setTyping(roomId: String, typing: Boolean): Result<Unit> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            client.api.room.setTyping(
                roomId = RoomId(roomId),
                userId = client.userId,
                typing = typing,
                timeout = if (typing) config.typingTimeoutMs else null,
            )
        } catch (e: Exception) {
            log.w(TAG) { "setTyping failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Observa qué usuarios están escribiendo en cada sala.
     * Devuelve un Flow de `roomId -> set de userIds`.
     */
    fun observeTyping(): Flow<Map<String, Set<String>>>? {
        val client = clientProvider() ?: return null
        return client.room.usersTyping.map { byRoom ->
            byRoom.entries.associate { (rid, content) ->
                rid.full to content.users.map { it.full }.toSet()
            }
        }
    }

    /** Set de userIds escribiendo en una sala concreta. */
    fun observeTyping(roomId: String): Flow<Set<String>>? {
        val client = clientProvider() ?: return null
        return client.room.usersTyping.map { byRoom ->
            byRoom[RoomId(roomId)]?.users?.map { it.full }?.toSet() ?: emptySet()
        }
    }

    /** Marca como leído hasta [eventId] (read marker + receipt público). */
    suspend fun markRead(roomId: String, eventId: String): Result<Unit> {
        val client = clientProvider() ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            client.api.room.setReadMarkers(
                roomId = RoomId(roomId),
                read = EventId(eventId),
            )
        } catch (e: Exception) {
            log.w(TAG) { "markRead failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Observa los read receipts de una sala: devuelve `eventId -> set de userIds`
     * que han leído hasta ese evento (read receipt público `m.read`).
     */
    fun observeReadReceipts(roomId: String): Flow<Map<String, Set<String>>>? {
        val client = clientProvider() ?: return null
        return client.user.getAllReceipts(RoomId(roomId)).flatMapInner()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<Map<UserId, Flow<RoomUserReceipts?>>>.flatMapInner():
        Flow<Map<String, Set<String>>> = flatMapLatest { byUser ->
        if (byUser.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val flows = byUser.entries.map { (userId, receiptsFlow) ->
                receiptsFlow.map { userId to it }
            }
            combine(flows) { pairs ->
                val result = mutableMapOf<String, MutableSet<String>>()
                pairs.forEach { (userId, receipts) ->
                    val readEventId = receipts
                        ?.receipts
                        ?.get(ReceiptType.Read)
                        ?.eventId
                        ?.full
                    if (readEventId != null) {
                        result.getOrPut(readEventId) { mutableSetOf() }.add(userId.full)
                    }
                }
                result.mapValues { it.value.toSet() }
            }
        }
    }

    /**
     * Carga y observa los miembros de una sala con su display name y avatar (mxc).
     * Llama internamente a `loadMembers` (lazy member loading de Matrix).
     */
    suspend fun loadMembers(roomId: String): Flow<List<MatrixMember>>? {
        val client = clientProvider() ?: return null
        return try {
            client.user.loadMembers(RoomId(roomId))
            client.user.getAll(RoomId(roomId)).flattenMembers()
        } catch (e: Exception) {
            log.w(TAG) { "loadMembers failed: ${e.message}" }
            null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<Map<UserId, Flow<RoomUser?>>>.flattenMembers():
        Flow<List<MatrixMember>> = flatMapLatest { byUser ->
        if (byUser.isEmpty()) {
            flowOf(emptyList())
        } else {
            val flows = byUser.values.map { userFlow ->
                userFlow.map { ru ->
                    ru?.let {
                        MatrixMember(
                            userId = it.userId.full,
                            displayName = it.name,
                            avatarUrl = it.event.content.avatarUrl,
                            membership = it.event.content.membership.name.lowercase(),
                            powerLevel = 0,
                        )
                    }
                }
            }
            combine(flows) { arr -> arr.filterNotNull() }
        }
    }
}
