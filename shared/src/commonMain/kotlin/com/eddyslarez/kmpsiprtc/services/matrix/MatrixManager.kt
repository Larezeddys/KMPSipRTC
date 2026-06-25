package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcEventListener
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.CallData
import com.eddyslarez.kmpsiprtc.data.models.CallDirections
import com.eddyslarez.kmpsiprtc.data.models.CallState
import com.eddyslarez.kmpsiprtc.services.calls.CallStateManager
import com.eddyslarez.kmpsiprtc.services.unified.CallType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.folivo.trixnity.client.*
import net.folivo.trixnity.client.room.message.audio
import net.folivo.trixnity.client.room.message.file
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.react
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.replace
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.room.message.thread
import net.folivo.trixnity.client.room.message.video
import net.folivo.trixnity.client.room.getTimelineEventReactionAggregation
import net.folivo.trixnity.client.room.getState
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.originTimestamp
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.utils.toByteArrayFlow
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.RedactionEventContent
import net.folivo.trixnity.core.model.events.m.room.Membership
import net.folivo.trixnity.core.model.events.m.room.CreateEventContent
import net.folivo.trixnity.core.model.events.m.space.ChildEventContent
import net.folivo.trixnity.core.model.events.m.call.CallEventContent
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.generateId
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.media.Media
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.Duration.Companion.seconds


class MatrixManager(
    private val config: MatrixConfig,
    private val webRtcManager: WebRtcManager
) {
    private var matrixClient: MatrixClient? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Sub-managers especializados (comparten el MatrixClient vivo del coordinador).
    /** Subida/descarga de media (avatares, adjuntos) vía el MediaService de Trixnity. */
    val fileManager: MatrixFileManager = MatrixFileManager { matrixClient }
    /** Perfil propio (nombre/avatar), presencia y perfiles de otros usuarios. */
    val profileManager: MatrixProfileManager = MatrixProfileManager({ matrixClient }, fileManager)
    /** Typing, read receipts y miembros de sala. */
    val roomManager: MatrixRoomManager = MatrixRoomManager({ matrixClient }, config)

    private val TAG = "MatrixManager"
    private val HTML_FORMAT = "org.matrix.custom.html"
    private val CALL_VERSION = "1"
    private val CALL_LIFETIME = 60000L // 60 segundos para que expire el invite
    private var storedAccessToken: String? = null
    private var storedUserId: String? = null

    // Control de "sync siempre vivo": queremos estar sincronizando mientras haya
    // sesión. El watchdog reinicia el sync si Trixnity lo deja en STOPPED.
    private var wantSync: Boolean = false
    private var syncWatchdogJob: Job? = null

    // Referencia a SipCoreManager para notificar cambios de estado
    private var sipCoreManager: com.eddyslarez.kmpsiprtc.core.SipCoreManager? = null

    fun setSipCoreManager(coreManager: com.eddyslarez.kmpsiprtc.core.SipCoreManager) {
        sipCoreManager = coreManager
    }

    // Listener para eventos de llamada (propaga a la app)
    private var callEventListener: MatrixCallEventListener? = null

    // Estados observables
    private val _connectionState = MutableStateFlow<MatrixConnectionState>(
        MatrixConnectionState.Disconnected
    )
    val connectionState: StateFlow<MatrixConnectionState> = _connectionState.asStateFlow()

    private val _rooms = MutableStateFlow<List<MatrixRoom>>(emptyList())
    val rooms: StateFlow<List<MatrixRoom>> = _rooms.asStateFlow()

    // Hijos de cada Space (m.space.child): spaceId -> lista de roomIds hijos.
    // Se combina en la emisión de _rooms para rellenar MatrixRoom.childRoomIds.
    private val _spaceChildren = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    // Jobs por space para colectar getAllState(ChildEventContent); se cancelan
    // cuando el space deja de existir.
    private val spaceChildJobs = mutableMapOf<String, Job>()

    private val _activeCall = MutableStateFlow<MatrixCall?>(null)
    val activeCall: StateFlow<MatrixCall?> = _activeCall.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<MatrixMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<MatrixMessage>>> = _messages.asStateFlow()

    // Mensajes entrantes EN VIVO (de otros usuarios), para notificaciones locales.
    // Solo emite desde el observer de sync (no en la carga histórica).
    private val _incomingMessages = MutableSharedFlow<MatrixMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val incomingMessages: SharedFlow<MatrixMessage> = _incomingMessages.asSharedFlow()

    /**
     * Listener para notificar eventos de llamada Matrix a la app
     */
    interface MatrixCallEventListener {
        fun onIncomingCall(call: MatrixCall)
        fun onCallAnswered(callId: String)
        fun onCallHangup(callId: String, reason: String?)
        fun onCallStateChanged(callId: String, state: MatrixCallState)
    }

    fun setCallEventListener(listener: MatrixCallEventListener?) {
        callEventListener = listener
    }

    /**
     * Inicializa el cliente Matrix
     */
    suspend fun initialize() {
        try {
            log.d { "Initializing Matrix client..." }
            _connectionState.value = MatrixConnectionState.Initialized
            log.d { "Matrix manager initialized" }
        } catch (e: Exception) {
            log.e(TAG, { "Error initializing Matrix: $e" })
            throw e
        }
    }

    /**
     * Resuelve el baseUrl real del homeserver. Si [MatrixConfig.enableWellKnownDiscovery]
     * está activo, usa `.well-known/matrix/client` (con fallback al valor dado).
     * Prioridad: homeserverOverride → dominio del userId (@user:dominio) → config.homeserverUrl.
     * Soporta cualquier homeserver Matrix, no solo el configurado por defecto.
     */
    private suspend fun resolveBaseUrl(userId: String, homeserverOverride: String?): Url {
        val explicit = homeserverOverride?.takeIf { it.isNotBlank() }
        if (!config.enableWellKnownDiscovery) {
            return Url(explicit ?: config.homeserverUrl)
        }
        val domain = userId.substringAfter(":", "").takeIf { it.isNotBlank() }
        val seed = explicit ?: domain?.let { "https://$it" } ?: config.homeserverUrl
        return seed.serverDiscovery().getOrElse {
            log.w(TAG) { "serverDiscovery failed for '$seed', using fallback: ${it.message}" }
            Url(explicit ?: config.homeserverUrl)
        }
    }

    // ── Gestión del store local persistente (trixnity.db + media) ─────────────

    private fun lastUserMarkerPath() = "${matrixStoragePath()}/last_user".toPath()

    private fun readLastUser(): String? = runCatching {
        FileSystem.SYSTEM.read(lastUserMarkerPath()) { readUtf8() }.trim().ifBlank { null }
    }.getOrNull()

    private fun writeLastUser(userIdFull: String) {
        runCatching {
            FileSystem.SYSTEM.write(lastUserMarkerPath()) { writeUtf8(userIdFull.lowercase()) }
        }.onFailure { log.w(TAG) { "No se pudo guardar last_user: ${it.message}" } }
    }

    /**
     * Borra TODO el store local de Matrix (BD de Trixnity, media cache y marcador
     * de usuario). Se usa al hacer logout y antes de un login fresco para que la
     * sesión nueva no vea datos de una cuenta anterior.
     */
    private fun clearLocalStore() {
        runCatching {
            val fs = FileSystem.SYSTEM
            val base = matrixStoragePath().toPath()
            fs.listOrNull(base)?.forEach { child ->
                runCatching { fs.deleteRecursively(child) }
                    .onFailure { log.w(TAG) { "No se pudo borrar $child: ${it.message}" } }
            }
            log.d(TAG) { "Store local de Matrix limpiado" }
        }.onFailure { log.w(TAG) { "clearLocalStore fallo: ${it.message}" } }
    }

    /**
     * Login con password. homeserverOverride permite cambiar el servidor sin
     * recrear el MatrixManager (util cuando el usuario escribe su propio homeserver).
     */
    suspend fun login(userId: String, password: String, homeserverOverride: String? = null): Result<Unit> {
        return try {
            log.d { "Intentando login para el usuario: $userId" }

            _connectionState.value = MatrixConnectionState.Connecting
            log.d { "Estado de conexion: Connecting..." }

            // Un login con password SIEMPRE es una sesión nueva (deviceId nuevo).
            // Si quedó un store de una sesión anterior (misma u otra cuenta), hay
            // que limpiarlo: Trixnity no puede abrir un store de otra sesión y,
            // peor, mostraría las salas/mensajes de la cuenta vieja.
            if (readLastUser() != null) {
                log.d { "Store previo detectado (${readLastUser()}); limpiando antes del login fresco" }
                clearLocalStore()
            }

            // Persistencia real: Room KMP para state + Okio para media.
            val (reposModule, mediaModule) = MatrixModuleFactory.createPersistentModules(
                matrixStoragePath()
            )
            log.d { "Modulos de repositorios y media store creados" }

            val baseUrl = resolveBaseUrl(userId, homeserverOverride)
            log.d { "Resolved Matrix baseUrl: $baseUrl" }

            // Crear cliente Matrix usando la API correcta
            val loginResult = MatrixClient.loginWithPassword(
                baseUrl = baseUrl,
                identifier = IdentifierType.User(userId),
                password = password,
                deviceId = null,
                initialDeviceDisplayName = config.deviceDisplayName,
                repositoriesModule = reposModule,
                mediaStoreModule = mediaModule,
                configuration = {
                    syncLoopTimeout = config.syncTimeout.seconds
                }
            )

            loginResult.onSuccess { client ->
                matrixClient = client
                storedUserId = client.userId.full
                writeLastUser(client.userId.full)
                log.d { "Login exitoso para $userId (resolved: ${storedUserId})" }

                // Iniciar sincronizacion
                wantSync = true
                client.startSync()
                _connectionState.value = MatrixConnectionState.Connected
                log.d { "Sincronizacion iniciada, estado: Connected" }

                observeMatrixChanges()
                setupWebRtcListener()
                startSyncWatchdog()
                log.d { "Observando cambios de Matrix..." }
            }.onFailure { error ->
                log.e(TAG) { "Login fallido: $error" }
                _connectionState.value = MatrixConnectionState.Error(error.message ?: "Unknown error")
                return Result.failure(error)
            }

            Result.success(Unit)

        } catch (e: Exception) {
            log.e(TAG) { "Login fallido con excepcion: $e" }
            _connectionState.value = MatrixConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }


    /**
     * Login desde almacenamiento persistente (para reconexion automatica)
     */
    suspend fun loginFromStore(): Result<Unit> {
        return try {
            log.d { "Attempting login from store" }

            // Sin marcador de sesión previa no hay nada que restaurar: evita
            // abrir/crear la BD para nada en el primer arranque.
            if (readLastUser() == null) {
                log.d { "No stored session marker found" }
                _connectionState.value = MatrixConnectionState.Disconnected
                return Result.failure(Exception("No stored session"))
            }

            _connectionState.value = MatrixConnectionState.Connecting
            val (reposModule, mediaModule) = MatrixModuleFactory.createPersistentModules(
                matrixStoragePath()
            )

            // Intentar recuperar sesion desde el almacenamiento
            val clientResult = MatrixClient.fromStore(
                repositoriesModule = reposModule,
                mediaStoreModule = mediaModule,
                configuration = {
                    syncLoopTimeout = config.syncTimeout.seconds
                }
            )

            clientResult.onSuccess { client ->
                if (client != null) {
                    matrixClient = client
                    storedUserId = client.userId.full
                    wantSync = true
                    client.startSync()
                    _connectionState.value = MatrixConnectionState.Connected
                    observeMatrixChanges()
                    setupWebRtcListener()
                    startSyncWatchdog()
                    log.d { "Login from store successful (${client.userId.full})" }
                } else {
                    log.d { "No stored session found" }
                    _connectionState.value = MatrixConnectionState.Disconnected
                    return Result.failure(Exception("No stored session"))
                }
            }.onFailure { error ->
                log.e(TAG, { "Error loading from store: $error" })
                _connectionState.value = MatrixConnectionState.Error(error.message ?: "Unknown error")
                return Result.failure(error)
            }

            Result.success(Unit)

        } catch (e: Exception) {
            log.e(TAG, { "Login from store failed: $e" })
            _connectionState.value = MatrixConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Login con un access token ya emitido (sin password). Replica el flujo de la
     * versión web: el backoffice intercambia la sesión de cookies por credenciales
     * de Matrix (POST /api/protected/v1/auth → {accessToken, refreshToken, userId})
     * y aquí solo levantamos el cliente con ese token, sin pedir contraseña.
     *
     * [userId] admite localpart ("eddys") o mxid completo ("@eddys:m.mcn.hu").
     * [homeserverUrl] es el baseUrl del homeserver — normalmente el ORIGEN del
     * backoffice, que hace de proxy de `/_matrix/...` (igual que la web). El
     * deviceId real del token se resuelve con `/_matrix/client/v3/account/whoami`.
     */
    suspend fun loginWithToken(
        userId: String,
        accessToken: String,
        refreshToken: String? = null,
        homeserverUrl: String? = null,
    ): Result<Unit> {
        return try {
            if (accessToken.isBlank()) {
                return Result.failure(IllegalArgumentException("accessToken vacío"))
            }
            log.d(TAG) { "Login Matrix con token para: $userId" }
            _connectionState.value = MatrixConnectionState.Connecting

            // La web usa el baseUrl explícito (sin .well-known): el backoffice
            // proxya `/_matrix/`. Respetamos lo mismo para evitar sorpresas.
            val base = (homeserverUrl?.takeIf { it.isNotBlank() } ?: config.homeserverUrl).trimEnd('/')
            val baseUrl = Url(base)

            // whoami: confirma el mxid real y obtiene el deviceId asociado al token.
            val who = whoAmIWithToken(base, accessToken)
                ?: return Result.failure(
                    IllegalStateException("Matrix whoami falló (token inválido o sin acceso al homeserver)")
                )
            val resolvedUserId = who.first
            val resolvedDeviceId = who.second?.takeIf { it.isNotBlank() } ?: "MCN_${generateId()}"

            // Si el store local es de OTRA cuenta, limpiarlo antes de levantar.
            val prev = readLastUser()
            if (prev != null && prev != resolvedUserId.lowercase()) {
                log.d(TAG) { "Store previo de otra cuenta ($prev); limpiando antes del login con token" }
                clearLocalStore()
            }

            val (reposModule, mediaModule) = MatrixModuleFactory.createPersistentModules(
                matrixStoragePath()
            )

            val loginResult = MatrixClient.loginWith(
                baseUrl = baseUrl,
                repositoriesModuleFactory = { reposModule },
                mediaStoreModuleFactory = { mediaModule },
                getLoginInfo = { _ ->
                    Result.success(
                        MatrixClient.LoginInfo(
                            userId = UserId(resolvedUserId),
                            deviceId = resolvedDeviceId,
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                        )
                    )
                },
                configuration = {
                    syncLoopTimeout = config.syncTimeout.seconds
                },
            )

            loginResult.onSuccess { client ->
                matrixClient = client
                storedAccessToken = accessToken
                storedUserId = client.userId.full
                writeLastUser(client.userId.full)
                log.d(TAG) { "Login con token OK (${storedUserId}, device=$resolvedDeviceId)" }
                wantSync = true
                client.startSync()
                _connectionState.value = MatrixConnectionState.Connected
                observeMatrixChanges()
                setupWebRtcListener()
                startSyncWatchdog()
            }.onFailure { error ->
                log.e(TAG) { "Login con token fallido: $error" }
                _connectionState.value = MatrixConnectionState.Error(error.message ?: "Unknown error")
                return Result.failure(error)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "Login con token excepción: $e" }
            _connectionState.value = MatrixConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * `GET /_matrix/client/v3/account/whoami` con Bearer token. Devuelve
     * `(userId, deviceId?)` o null si falla. Valida el token y obtiene el
     * deviceId real antes de crear el MatrixClient (no hay sesión todavía, así
     * que no se puede usar [authedHttpClient]).
     */
    private suspend fun whoAmIWithToken(baseUrl: String, accessToken: String): Pair<String, String?>? {
        val client = io.ktor.client.HttpClient()
        return try {
            val response = client.get("${baseUrl.trimEnd('/')}/_matrix/client/v3/account/whoami") {
                header("Authorization", "Bearer $accessToken")
            }
            if (response.status.value != 200) {
                log.w(TAG) { "whoami HTTP ${response.status}" }
                return null
            }
            val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val uid = obj["user_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val dev = obj["device_id"]?.jsonPrimitive?.contentOrNull
            uid to dev
        } catch (e: Exception) {
            log.w(TAG) { "whoami fallo: ${e.message}" }
            null
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Verifica si esta logueado en Matrix
     */
    fun isLoggedIn(): Boolean = matrixClient != null &&
            _connectionState.value == MatrixConnectionState.Connected

    /**
     * Obtiene el user ID del usuario logueado (ej: "@user:localhost")
     */
    fun getUserId(): String? = storedUserId ?: matrixClient?.userId?.full

    /**
     * Obtiene el access token de la sesion activa (para autenticacion con servicios externos)
     */
    fun getAccessToken(): String? = storedAccessToken

    /**
     * Establece el access token manualmente (ej: obtenido durante login)
     */
    fun setAccessToken(token: String) {
        storedAccessToken = token
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TURN servers del homeserver (GET /_matrix/client/v3/voip/turnServer).
    // Trixnity 4.22.7 no tipa este endpoint, asi que se llama por HTTP directo
    // (mismo patron que el media download URL). Cache segun el ttl del server.
    // ─────────────────────────────────────────────────────────────────────────

    @kotlinx.serialization.Serializable
    private data class TurnServerResponse(
        val username: String? = null,
        val password: String? = null,
        val uris: List<String> = emptyList(),
        val ttl: Long? = null,
    )

    private var cachedTurnServers: List<com.eddyslarez.kmpsiprtc.data.models.IceServerInfo> = emptyList()
    @kotlin.concurrent.Volatile
    private var turnCacheExpiresAtMs: Long = 0L

    /**
     * HttpClient ktor de Trixnity, YA autenticado (el auth provider añade el
     * Bearer del access token a cada request). Es la vía correcta para los
     * endpoints que Trixnity 4.22 no tipa (voip/turnServer, etc.) — antes se
     * usaba un HttpClient propio con `storedAccessToken`, que nunca se asignaba
     * tras el login, dejando estos endpoints muertos.
     */
    private fun authedHttpClient(): io.ktor.client.HttpClient? =
        matrixClient?.api?.baseClient?.baseClient

    private fun apiBaseUrl(): String =
        matrixClient?.baseUrl?.toString()?.trimEnd('/') ?: config.homeserverUrl.trimEnd('/')

    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun getTurnServers(): List<com.eddyslarez.kmpsiprtc.data.models.IceServerInfo> {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now < turnCacheExpiresAtMs && cachedTurnServers.isNotEmpty()) {
            return cachedTurnServers
        }
        val client = authedHttpClient()
        if (client == null) {
            log.w(TAG) { "getTurnServers: sin sesion Matrix, usando solo STUN por defecto" }
            return emptyList()
        }
        return try {
            val response = client.get("${apiBaseUrl()}/_matrix/client/v3/voip/turnServer")
            if (response.status != io.ktor.http.HttpStatusCode.OK) {
                log.w(TAG) { "getTurnServers: HTTP ${response.status}" }
                return emptyList()
            }
            val body = response.bodyAsText()
            val parsed = json.decodeFromString<TurnServerResponse>(body)
            val servers = if (parsed.uris.isEmpty()) emptyList() else listOf(
                com.eddyslarez.kmpsiprtc.data.models.IceServerInfo(
                    urls = parsed.uris,
                    username = parsed.username,
                    credential = parsed.password,
                )
            )
            cachedTurnServers = servers
            // Renovar algo antes del vencimiento real (10% de margen, min 60s)
            val ttlMs = ((parsed.ttl ?: 3600L) * 1000L * 0.9).toLong().coerceAtLeast(60_000L)
            turnCacheExpiresAtMs = now + ttlMs
            log.d(TAG) { "TURN servers obtenidos: ${parsed.uris.size} uris, ttl=${parsed.ttl}s" }
            servers
        } catch (e: Exception) {
            log.w(TAG) { "getTurnServers fallo (${e.message}), usando solo STUN por defecto" }
            emptyList()
        }
    }

    /**
     * Obtiene los TURN del homeserver y los aplica al WebRtcManager antes de
     * crear el peer connection. Si no hay TURN disponibles, deja los STUN
     * por defecto (lista vacia = no tocar).
     */
    private suspend fun applyTurnServersToWebRtc() {
        try {
            val servers = getTurnServers()
            if (servers.isNotEmpty()) {
                webRtcManager.setIceServers(servers)
            }
        } catch (e: Exception) {
            log.w(TAG) { "applyTurnServersToWebRtc fallo: ${e.message}" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Silenciar salas (push rules de tipo room, como Element).
    // PUT/DELETE /_matrix/client/v3/pushrules/global/room/{roomId}.
    // Trixnity 4.22.7 no tipa pushrules, asi que va por HTTP directo.
    // ─────────────────────────────────────────────────────────────────────────

    private val _mutedRooms = MutableStateFlow<Set<String>>(emptySet())
    /** Salas silenciadas (sincronizadas con las push rules del servidor). */
    val mutedRooms: StateFlow<Set<String>> = _mutedRooms.asStateFlow()

    /** Carga desde el servidor qué salas están silenciadas (rules room sin acciones). */
    suspend fun refreshMutedRooms() {
        val client = matrixClient ?: return
        try {
            val rules = client.api.push.getPushRules().getOrThrow()
            val muted = rules.global.room.orEmpty().mapNotNull { rule ->
                if (rule.enabled && rule.actions.isEmpty()) rule.roomId.full else null
            }.toSet()
            _mutedRooms.value = muted
            log.d(TAG) { "Muted rooms sincronizadas: ${muted.size}" }
        } catch (e: Exception) {
            log.w(TAG) { "refreshMutedRooms fallo: ${e.message}" }
        }
    }

    /** Silencia/des-silencia una sala (push rule room con actions=[], como Element). */
    suspend fun setRoomMuted(roomId: String, muted: Boolean): Result<Unit> {
        val client = matrixClient
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            if (muted) {
                client.api.push.setPushRule(
                    "global",
                    net.folivo.trixnity.core.model.push.PushRuleKind.ROOM,
                    roomId,
                    net.folivo.trixnity.clientserverapi.model.push.SetPushRule.Request(
                        actions = setOf(),
                    ),
                ).getOrThrow()
            } else {
                // 404 al borrar una regla inexistente = ya estaba sin mute
                client.api.push.deletePushRule(
                    "global",
                    net.folivo.trixnity.core.model.push.PushRuleKind.ROOM,
                    roomId,
                ).onFailure { log.d(TAG) { "deletePushRule (ya sin mute): ${it.message}" } }
            }
            _mutedRooms.value = if (muted) _mutedRooms.value + roomId else _mutedRooms.value - roomId
            Result.success(Unit)
        } catch (e: Exception) {
            log.w(TAG) { "setRoomMuted fallo: ${e.message}" }
            Result.failure(e)
        }
    }

    fun isRoomMuted(roomId: String): Boolean = roomId in _mutedRooms.value

    // ─────────────────────────────────────────────────────────────────────────
    // Favoritos (m.tag "m.favourite" por sala, como Element). API tipada.
    // ─────────────────────────────────────────────────────────────────────────

    private val _favoriteRooms = MutableStateFlow<Set<String>>(emptySet())
    /** Salas marcadas como favoritas (tag m.favourite, sincronizado con el servidor). */
    val favoriteRooms: StateFlow<Set<String>> = _favoriteRooms.asStateFlow()

    /** Sincroniza desde el servidor qué salas tienen el tag m.favourite. */
    suspend fun refreshFavoriteRooms() {
        val client = matrixClient ?: return
        try {
            val ids = _rooms.value.map { it.id }
            if (ids.isEmpty()) return
            val favs = mutableSetOf<String>()
            for (id in ids) {
                val tags = client.api.room.getTags(client.userId, RoomId(id)).getOrNull() ?: continue
                val isFav = tags.tags.keys.any {
                    it is net.folivo.trixnity.core.model.events.m.TagEventContent.TagName.Favourite
                }
                if (isFav) favs += id
            }
            _favoriteRooms.value = favs
            log.d(TAG) { "Favoritos sincronizados: ${favs.size}" }
        } catch (e: Exception) {
            log.w(TAG) { "refreshFavoriteRooms fallo: ${e.message}" }
        }
    }

    /** Marca/desmarca una sala como favorita (tag m.favourite del servidor). */
    suspend fun setRoomFavorite(roomId: String, favorite: Boolean): Result<Unit> {
        val client = matrixClient
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            if (favorite) {
                client.api.room.setTag(
                    client.userId,
                    RoomId(roomId),
                    "m.favourite",
                    net.folivo.trixnity.core.model.events.m.TagEventContent.Tag(0.5),
                ).getOrThrow()
            } else {
                client.api.room.deleteTag(
                    client.userId,
                    RoomId(roomId),
                    "m.favourite",
                ).onFailure { log.d(TAG) { "deleteTag (ya sin favorito): ${it.message}" } }
            }
            _favoriteRooms.value =
                if (favorite) _favoriteRooms.value + roomId else _favoriteRooms.value - roomId
            Result.success(Unit)
        } catch (e: Exception) {
            log.w(TAG) { "setRoomFavorite fallo: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Pausa el long-polling de sync. Útil para llamar desde un lifecycle hook
     * cuando la app pasa a background: ahorra batería + bandwidth y evita
     * mantener conexiones abiertas innecesariamente.
     *
     * Idempotente: si no hay cliente o ya está pausado, no hace nada.
     */
    suspend fun pauseSync() {
        // Política "siempre vivo": NO detenemos el sync al ir a background para
        // que sigan llegando los mensajes. (El proceso lo mantiene vivo el
        // foreground service de SIP en Android; en Desktop el proceso vive.)
        log.d(TAG) { "pauseSync ignorado (keep-alive): el sync de Matrix se mantiene activo" }
    }

    /**
     * Asegura que el sync esté corriendo (foreground, red recuperada, etc.).
     * Idempotente.
     */
    suspend fun resumeSync() {
        val client = matrixClient ?: return
        try {
            if (client.syncState.value != SyncState.RUNNING && client.syncState.value != SyncState.STARTED) {
                log.d(TAG) { "resumeSync: (re)starting sync, state=${client.syncState.value}" }
                client.startSync()
            }
        } catch (e: Exception) {
            log.w(TAG) { "resumeSync failed: ${e.message}" }
        }
    }

    /**
     * Watchdog que mantiene el sync SIEMPRE vivo: observa [MatrixClient.syncState]
     * y, si Trixnity lo deja en STOPPED mientras seguimos logueados ([wantSync]),
     * lo reinicia con un pequeño backoff. Garantiza reconexión automática en
     * Desktop (red caída, errores) y en Android mientras el proceso siga vivo.
     */
    private fun startSyncWatchdog() {
        syncWatchdogJob?.cancel()
        val client = matrixClient ?: return
        syncWatchdogJob = scope.launch {
            client.syncState.collect { state ->
                if (state == SyncState.STOPPED && wantSync && matrixClient != null) {
                    log.w(TAG) { "Sync STOPPED de forma inesperada — reiniciando…" }
                    delay(2000)
                    if (wantSync) {
                        try {
                            matrixClient?.startSync()
                            log.d(TAG) { "Sync reiniciado por watchdog" }
                        } catch (e: Exception) {
                            log.w(TAG) { "Watchdog restart failed: ${e.message}" }
                        }
                    }
                }
            }
        }
    }

    /**
     * Logout
     */
    suspend fun logout() {
        try {
            log.d { "Logging out from Matrix" }

            wantSync = false
            syncWatchdogJob?.cancel()
            syncWatchdogJob = null
            // Si el server rechaza el logout (token caducado, sin red), la
            // limpieza local debe ocurrir igualmente.
            runCatching { matrixClient?.logout() }
                .onFailure { log.w(TAG) { "Server logout fallo (continuando limpieza local): ${it.message}" } }
            matrixClient = null
            storedUserId = null

            _connectionState.value = MatrixConnectionState.Disconnected
            _rooms.value = emptyList()
            _activeCall.value = null
            _messages.value = emptyMap()
            spaceChildJobs.values.forEach { it.cancel() }
            spaceChildJobs.clear()
            _spaceChildren.value = emptyMap()
            historicalLoaded.clear()
            oldestLoaded.clear()
            followJob?.cancel()
            followJob = null
            followedRoom = null

            // Borrar el store local: claves olm, BD de salas/mensajes y media.
            // Si algún archivo queda lockeado (Windows), el próximo login()
            // detecta el marcador last_user y vuelve a intentar la limpieza.
            clearLocalStore()

        } catch (e: Exception) {
            log.e(TAG, { "Logout error: $e" })
        }
    }

    /**
     * Registra el listener de WebRTC de Matrix en el CompositeWebRtcEventListener.
     * Esto permite que tanto SIP como Matrix reciban eventos WebRTC simultaneamente.
     */
    internal fun registerWebRtcListener(composite: com.eddyslarez.kmpsiprtc.services.webrtc.CompositeWebRtcEventListener) {
        composite.addListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Enviar ICE candidate via Matrix cuando se descubre
                val call = _activeCall.value ?: return
                scope.launch {
                    sendIceCandidates(call.roomId, call.callId, listOf(
                        IceCandidate(candidate, sdpMid, sdpMLineIndex)
                    ))
                }
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                log.d(TAG) { "WebRTC connection state changed (Matrix): $state" }
                val call = _activeCall.value ?: return

                when (state) {
                    WebRtcConnectionState.CONNECTED -> {
                        _activeCall.value = call.copy(state = MatrixCallState.CONNECTED)
                        callEventListener?.onCallStateChanged(call.callId, MatrixCallState.CONNECTED)
                    }
                    WebRtcConnectionState.DISCONNECTED, WebRtcConnectionState.FAILED -> {
                        _activeCall.value = call.copy(state = MatrixCallState.ENDED)
                        callEventListener?.onCallStateChanged(call.callId, MatrixCallState.ENDED)
                    }
                    else -> {}
                }
            }

            override fun onRemoteAudioTrack() {
                log.d(TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(TAG) { "Audio device changed: $device" }
            }
        })
    }

    /**
     * Legacy: configura listener de WebRTC directamente (para cuando no hay composite)
     */
    private fun setupWebRtcListener() {
        // No-op: ahora se usa registerWebRtcListener() via wireMatrixManager()
        log.d(TAG) { "setupWebRtcListener() is now a no-op, using composite listener instead" }
    }

    /**
     * Texto de preview para la lista de salas, derivado del último evento del
     * timeline (como Element). Devuelve null para eventos no-mensaje (llamadas, etc.).
     */
    private fun previewTextOf(ev: TimelineEvent): String? {
        val content = ev.content?.getOrNull() as? RoomMessageEventContent ?: return null
        val name = (content as? RoomMessageEventContent.FileBased)?.fileName ?: content.body
        return when (content.type) {
            "m.image" -> "📷 $name"
            "m.video" -> "🎬 $name"
            "m.audio" -> "🎤 $name"
            "m.file" -> "📎 $name"
            else -> content.body
        }
    }

    /**
     * Observa cambios en Matrix (rooms, mensajes, eventos de llamada)
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMatrixChanges() {
        val client = matrixClient ?: return

        // Sincronizar salas silenciadas (push rules) al iniciar sesión
        scope.launch { refreshMutedRooms() }

        // Sincronizar favoritos (m.tag) cuando la lista de salas esté poblada
        scope.launch {
            runCatching {
                _rooms.first { it.isNotEmpty() }
                refreshFavoriteRooms()
            }
        }

        // Observar rooms - collectLatest + combine para reaccionar reactivamente a cada room flow
        scope.launch {
            try {
                client.room.getAll().collectLatest { roomsMap ->
                    if (roomsMap.isEmpty()) {
                        _rooms.value = emptyList()
                        return@collectLatest
                    }
                    val myUserId = client.userId.full
                    val roomFlows: List<Flow<MatrixRoom?>> = roomsMap.entries.map { (roomId, roomFlow) ->
                        // Preview de la lista: combinamos cada sala con el cache compartido
                        // `_messages` (un único StateFlow, ligero) y con `_spaceChildren`
                        // (jerarquía de grupos). Así el preview y los hijos de un grupo se
                        // actualizan reactivamente SIN crear un flow pesado por sala.
                        combine(roomFlow, _messages, _spaceChildren) { room, messagesMap, spaceChildren ->
                            room?.let {
                                // ¿Es un Space (grupo) en lugar de una sala de chat?
                                val isSpace = it.createEventContent?.type is CreateEventContent.RoomType.Space

                                // Auto-join de invitaciones (UX tipo Element para DMs).
                                if (it.membership == Membership.INVITE) {
                                    autoJoinInvitedRoom(roomId.full)
                                }
                                // Al estar joineado: los grupos cargan sus hijos; las salas
                                // de chat cargan su historial (idempotente).
                                if (it.membership == Membership.JOIN) {
                                    if (isSpace) {
                                        observeSpaceChildren(roomId.full)
                                    } else {
                                        loadHistoricalTimeline(roomId.full)
                                    }
                                }

                                val heroes: List<String> = try {
                                    it.name?.heroes?.map { h -> h.full } ?: emptyList()
                                } catch (_: Throwable) { emptyList() }

                                val resolvedName = resolveRoomName(
                                    roomId = roomId.full,
                                    explicitName = it.name?.explicitName,
                                    isDirect = it.isDirect,
                                    myUserId = myUserId,
                                    heroes = heroes,
                                )

                                val cachedLast = messagesMap[roomId.full]?.lastOrNull()
                                val avatarMxc = it.avatarUrl
                                    ?.takeIf { mxc -> mxc.startsWith("mxc://") }

                                MatrixRoom(
                                    id = roomId.full,
                                    name = resolvedName,
                                    avatarUrl = avatarMxc,
                                    isDirect = it.isDirect,
                                    // Estado real de cifrado de la sala (m.room.encryption)
                                    isEncrypted = it.encrypted,
                                    // Contador real de no leídos que mantiene Trixnity
                                    unreadCount = it.unreadMessageCount.toInt(),
                                    lastMessage = cachedLast?.content,
                                    lastMessageTime = cachedLast?.timestamp,
                                    isSpace = isSpace,
                                    childRoomIds = if (isSpace) spaceChildren[roomId.full].orEmpty() else emptyList(),
                                )
                            }
                        }
                    }
                    combine(roomFlows) { rooms ->
                        rooms.filterNotNull()
                    }.collect { roomsList ->
                        // Orden tipo Element: actividad mas reciente primero.
                        // Al llegar un mensaje, el combine con _messages re-emite la
                        // sala con lastMessageTime nuevo y sube al principio.
                        _rooms.value = roomsList.sortedByDescending { it.lastMessageTime ?: 0L }
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error observing rooms: ${e.message}" }
            }
        }

        // Observar mensajes y eventos de llamada via timeline
        scope.launch {
            try {
                client.room.getTimelineEventsFromNowOn(
                    decryptionTimeout = 30.seconds
                ).collect { timelineEvent ->
                    try {
                        val eventRoomId = timelineEvent.roomId.full
                        val event = timelineEvent.event
                        val content = timelineEvent.content?.getOrNull()
                        val senderId = event.sender.full
                        val myUserId = matrixClient?.userId?.full ?: return@collect
                        log.d(TAG) { "TIMELINE-EVT room=$eventRoomId sender=$senderId type=${content?.let { it::class.simpleName } ?: "null"}" }

                        when (content) {
                            // Eventos de llamada Matrix (dedup por eventId en processCallEvent).
                            is CallEventContent -> processCallEvent(timelineEvent)
                            // Procesar mensajes de chat (texto + media). Antes se filtraba
                            // por sender != yo, lo que perdía mensajes propios al re-loguear.
                            // Ahora dedupe por event.id o por timestamp+sender+content si
                            // todavía está el optimista local.
                            is RoomMessageEventContent -> {
                                processRoomMessageContent(
                                    roomId = eventRoomId,
                                    eventId = event.id.full,
                                    senderId = senderId,
                                    timestamp = event.originTimestamp,
                                    content = content,
                                )
                                // Emitir entrante EN VIVO (de otros) para notificaciones.
                                if (senderId != myUserId) {
                                    val preview = (content as? RoomMessageEventContent.TextBased)?.body
                                        ?: when (content.type) {
                                            "m.image" -> "📷 Imagen"
                                            "m.video" -> "🎬 Vídeo"
                                            "m.audio" -> "🎤 Audio"
                                            "m.file" -> "📎 Archivo"
                                            else -> "Mensaje"
                                        }
                                    _incomingMessages.tryEmit(
                                        MatrixMessage(
                                            id = event.id.full,
                                            roomId = eventRoomId,
                                            senderId = senderId,
                                            senderDisplayName = extractDisplayName(senderId),
                                            content = preview,
                                            timestamp = event.originTimestamp,
                                            type = MessageType.TEXT,
                                        )
                                    )
                                }
                            }
                            // Borrado (m.room.redaction): elimina el mensaje del cache.
                            // También aplica a reacciones redactadas (no-op si no estaba).
                            is RedactionEventContent -> {
                                removeMessage(eventRoomId, content.redacts.full)
                            }
                            else -> { /* Ignorar otros tipos de eventos */ }
                        }
                    } catch (e: Exception) {
                        log.w(TAG) { "Error processing timeline event: ${e.message}" }
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error observing messages: ${e.message}" }
            }
        }
    }

    // EventIds de eventos de llamada ya procesados, para no manejarlos dos veces
    // (los entrega tanto el observador global como el seguidor de sala).
    private val processedCallEvents = mutableSetOf<String>()

    /**
     * Procesa un evento m.call.* (invite/answer/candidates/hangup) recibido por
     * CUALQUIER observador (global o seguidor de sala), con dedup por eventId.
     * Esto arregla el caso en que el observador global no entrega los eventos de
     * llamada de ciertas salas, dejando la llamada colgada en "conectando".
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun processCallEvent(timelineEvent: TimelineEvent) {
        val content = timelineEvent.content?.getOrNull()
        if (content !is CallEventContent) return
        val eventId = timelineEvent.event.id.full
        if (!processedCallEvents.add(eventId)) return
        val roomId = timelineEvent.roomId.full
        val senderId = timelineEvent.event.sender.full
        val myUserId = matrixClient?.userId?.full ?: return
        if (senderId == myUserId) {
            // Eventos de NUESTRA propia cuenta (esta u otra sesion/dispositivo):
            // - Invite propio: somos el llamante en algun dispositivo -> no sonar aqui.
            // - Answer/Hangup propio: la llamada se atendio/colgo en OTRO dispositivo
            //   -> dejar de sonar localmente (comportamiento Element multi-device).
            when (content) {
                is CallEventContent.Answer -> stopLocalRingingIfHandledElsewhere(content.callId, "answered")
                is CallEventContent.Hangup -> stopLocalRingingIfHandledElsewhere(content.callId, "hungup")
                else -> log.d(TAG) {
                    "CALL-EVT propio ignorado (${content::class.simpleName}) room=$roomId"
                }
            }
            return
        }
        val ts = timelineEvent.event.originTimestamp
        log.d(TAG) { "CALL-EVT ${content::class.simpleName} room=$roomId from=$senderId" }
        when (content) {
            is CallEventContent.Invite -> {
                handleCallInvite(roomId, senderId, content)
                addCallEventMessage(roomId, eventId, senderId, ts, MessageType.CALL_INVITE)
            }
            is CallEventContent.Answer -> {
                handleCallAnswer(roomId, senderId, content)
                addCallEventMessage(roomId, eventId, senderId, ts, MessageType.CALL_ANSWER)
            }
            is CallEventContent.Hangup -> {
                handleCallHangup(roomId, senderId, content)
                addCallEventMessage(roomId, eventId, senderId, ts, MessageType.CALL_HANGUP)
            }
            is CallEventContent.Candidates -> handleCallCandidates(roomId, senderId, content)
            else -> { /* otros m.call.* (select_answer, negotiate…) no soportados */ }
        }
    }

    /**
     * La llamada que esta sonando AQUI fue atendida/colgada en otro dispositivo
     * de la misma cuenta: parar el ring local y limpiar estado.
     */
    private fun stopLocalRingingIfHandledElsewhere(callId: String, how: String) {
        try {
            val call = _activeCall.value ?: return
            if (call.callId != callId) return
            if (call.state != MatrixCallState.RINGING) return
            log.d(TAG) { "Call $callId $how on another device - stopping local ring" }
            CallStateManager.callEnded(callId, sipReason = "handled_elsewhere")
            sipCoreManager?.notifyCallStateChanged(CallState.ENDED)
            _activeCall.value = null
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping local ring: ${e.message}" }
        }
    }

    /**
     * Maneja m.call.invite recibido
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun handleCallInvite(
        roomId: String,
        senderId: String,
        content: CallEventContent.Invite
    ) {
        try {
            log.d(TAG) { "Received m.call.invite from $senderId in $roomId" }
            // Seguir la sala para recibir candidates/hangup del otro extremo aunque
            // el observador global no entregue eventos de esta sala.
            followRoom(roomId)

            val callId = content.callId
            val sdp = content.offer.sdp

            // Glare (ambos lados llaman a la vez, regla Element/MSC2746): gana el
            // callId lexicograficamente menor. Si perdemos, colgamos nuestra
            // saliente y atendemos el invite entrante; si ganamos, lo ignoramos
            // (el otro lado cedera al recibir nuestro invite).
            val existing = _activeCall.value
            if (existing != null && existing.roomId == roomId && existing.callId != callId &&
                existing.state == MatrixCallState.INVITING
            ) {
                if (callId < existing.callId) {
                    log.w(TAG) { "Glare: cediendo nuestra saliente ${existing.callId} ante $callId" }
                    try { hangupCall(existing.callId) } catch (_: Exception) {}
                } else {
                    log.w(TAG) { "Glare: ignorando invite $callId, nuestra ${existing.callId} gana" }
                    return
                }
            }

            val call = MatrixCall(
                callId = callId,
                roomId = roomId,
                isVideo = false,
                state = MatrixCallState.RINGING,
                remoteSdp = sdp,
                participants = listOf(senderId)
            )

            _activeCall.value = call

            // NO inicializar WebRTC aqui (se hara en acceptCall via CallManager)

            // Alimentar CallStateManager con CallData de tipo Matrix
            val callerName = extractDisplayName(senderId).ifBlank { resolveCallDisplayName(roomId) }
            val callData = CallData(
                callId = callId,
                from = senderId,
                to = matrixClient?.userId?.full ?: "",
                direction = CallDirections.INCOMING,
                startTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                callType = CallType.MATRIX_INTERNAL,
                roomId = roomId,
                remoteSdp = sdp,
                remoteDisplayName = callerName,
                sipName = callerName,
            )
            CallStateManager.incomingCallReceived(callId, senderId, callData)

            // Notificar a SipCoreManager para que dispare la UI de llamada entrante
            sipCoreManager?.notifyCallStateChanged(CallState.INCOMING_RECEIVED)

            // Notificar listeners legacy de Matrix
            callEventListener?.onIncomingCall(call)
            callEventListener?.onCallStateChanged(callId, MatrixCallState.RINGING)

        } catch (e: Exception) {
            log.e(TAG) { "Error handling call invite: ${e.message}" }
        }
    }

    /**
     * Maneja m.call.answer recibido (la otra parte acepto nuestra llamada saliente)
     */
    private suspend fun handleCallAnswer(
        roomId: String,
        senderId: String,
        content: CallEventContent.Answer
    ) {
        try {
            log.d(TAG) { "Received m.call.answer from $senderId in $roomId" }

            val sdp = content.answer.sdp
            val call = _activeCall.value ?: return

            // Setear remote SDP
            webRtcManager.setRemoteDescription(sdp, SdpType.ANSWER)

            // Habilitar audio
            webRtcManager.setAudioEnabled(true)

            // Actualizar estado local de Matrix
            _activeCall.value = call.copy(
                state = MatrixCallState.CONNECTED,
                remoteSdp = sdp
            )

            // Alimentar CallStateManager
            CallStateManager.callConnected(call.callId, 200)
            CallStateManager.streamsRunning(call.callId)
            sipCoreManager?.notifyCallStateChanged(CallState.STREAMS_RUNNING)

            // Notificar listeners legacy de Matrix
            callEventListener?.onCallAnswered(call.callId)
            callEventListener?.onCallStateChanged(call.callId, MatrixCallState.CONNECTED)

        } catch (e: Exception) {
            log.e(TAG) { "Error handling call answer: ${e.message}" }
        }
    }

    /**
     * Maneja m.call.hangup recibido (la otra parte colgo)
     */
    private fun handleCallHangup(
        roomId: String,
        senderId: String,
        content: CallEventContent.Hangup
    ) {
        try {
            log.d(TAG) { "Received m.call.hangup from $senderId in $roomId" }

            val reason = content.reason?.name
            val call = _activeCall.value ?: return

            // Limpiar WebRTC
            webRtcManager.closePeerConnection()

            // Alimentar CallStateManager
            CallStateManager.callEnded(call.callId, sipReason = reason)
            sipCoreManager?.notifyCallStateChanged(CallState.ENDED)

            // Actualizar estado local de Matrix
            _activeCall.value = call.copy(state = MatrixCallState.ENDED)
            _activeCall.value = null

            // Notificar listeners legacy de Matrix
            callEventListener?.onCallHangup(call.callId, reason)
            callEventListener?.onCallStateChanged(call.callId, MatrixCallState.ENDED)

        } catch (e: Exception) {
            log.e(TAG) { "Error handling call hangup: ${e.message}" }
        }
    }

    /**
     * Maneja m.call.candidates recibido
     */
    private suspend fun handleCallCandidates(
        roomId: String,
        senderId: String,
        content: CallEventContent.Candidates
    ) {
        try {
            log.d(TAG) { "Received m.call.candidates from $senderId in $roomId" }

            // Agregar ICE candidates al WebRTC
            content.candidates.forEach { candidate ->
                webRtcManager.addIceCandidate(
                    candidate.candidate,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex?.toInt() ?: 0
                )
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error handling call candidates: ${e.message}" }
        }
    }

    /**
     * Extrae el nombre de usuario legible de un ID Matrix completo.
     * "@usuario:servidor.com" → "usuario"
     */
    private fun extractDisplayName(userId: String): String {
        return userId.substringAfter("@").substringBefore(":").takeIf { it.isNotBlank() } ?: userId
    }

    /**
     * Procesa un mensaje (texto o media). Decide el tipo a partir del
     * `msgtype` Matrix (`content.type` String estándar) en lugar de pattern
     * matching sobre subtipos sealed — `content.type` es campo público de
     * la interfaz `RoomMessageEventContent` y siempre compila.
     *
     * Por ahora la `mxcUrl` se deja en null para mensajes media. La UI
     * los mostrará como texto con su nombre. Cuando se haga el wire-up de
     * Coil/AsyncImage en las burbujas, se reactivará el extracción de
     * url usando un cast seguro a RoomMessageEventContent.FileBased.
     */
    private fun processRoomMessageContent(
        roomId: String,
        eventId: String,
        senderId: String,
        timestamp: Long,
        content: RoomMessageEventContent,
    ) {
        // 1) Edición (m.replace): no es un mensaje nuevo, actualiza el original.
        val relatesTo = content.relatesTo
        if (relatesTo is RelatesTo.Replace) {
            val newContent = relatesTo.newContent as? RoomMessageEventContent
            val newBody = newContent?.body ?: content.body.removePrefix("* ")
            val newHtml = (newContent as? RoomMessageEventContent.TextBased)?.formattedBody
                ?: (content as? RoomMessageEventContent.TextBased)?.formattedBody
            applyEdit(roomId, relatesTo.eventId.full, newBody, newHtml)
            return
        }

        // 2) Relaciones reply / thread para el modelo.
        val replyToEventId = when (relatesTo) {
            is RelatesTo.Reply -> relatesTo.replyTo.eventId.full
            is RelatesTo.Thread -> relatesTo.replyTo?.eventId?.full
            else -> null
        }
        val threadRootId = (relatesTo as? RelatesTo.Thread)?.eventId?.full

        val msgType = when (content.type) {
            "m.image" -> MessageType.IMAGE
            "m.video" -> MessageType.VIDEO
            "m.audio" -> MessageType.AUDIO
            "m.file" -> MessageType.FILE
            "m.notice" -> MessageType.NOTICE
            else -> MessageType.TEXT // m.text, m.emote, etc.
        }

        if (msgType == MessageType.TEXT || msgType == MessageType.NOTICE) {
            val textBased = content as? RoomMessageEventContent.TextBased
            upsertTextMessage(
                roomId = roomId,
                eventId = eventId,
                senderId = senderId,
                body = content.body,
                timestamp = timestamp,
                type = msgType,
                replyToEventId = replyToEventId,
                threadRootId = threadRootId,
                formattedBody = textBased?.formattedBody,
                format = textBased?.format,
            )
        } else {
            // Resolver el mxc:// real de la media (FileBased) para descarga/preview.
            // En salas CIFRADAS la media viaja como `file` (EncryptedFile) y `url`
            // es null — exponemos el mxc del file para que la UI sepa que hay
            // media; la descarga descifrada va por getMediaBytesForEvent().
            val fileBased = content as? RoomMessageEventContent.FileBased
            val mxc = fileBased?.url ?: fileBased?.file?.url
            val realName = fileBased?.fileName ?: content.body
            upsertMediaMessage(
                roomId = roomId,
                eventId = eventId,
                senderId = senderId,
                timestamp = timestamp,
                type = msgType,
                mxcUrl = mxc,
                fileName = realName,
                replyToEventId = replyToEventId,
                threadRootId = threadRootId,
            )
        }
    }

    /**
     * Aplica una edición (m.replace) al mensaje original: actualiza su contenido
     * y lo marca como editado. Si el original aún no está en el cache, no hace nada.
     */
    private fun applyEdit(roomId: String, targetEventId: String, newBody: String, newFormattedBody: String? = null) {
        val current = _messages.value[roomId] ?: return
        val idx = current.indexOfFirst { it.id == targetEventId }
        if (idx < 0) return
        val updated = current.toMutableList().apply {
            set(idx, this[idx].copy(
                content = newBody,
                isEdited = true,
                formattedBody = newFormattedBody ?: this[idx].formattedBody,
            ))
        }
        _messages.value = _messages.value + (roomId to updated)
    }

    /**
     * Inserta una media (imagen/vídeo/audio/archivo) en el cache con dedupe.
     * Resuelve `mxc://server/id` a una URL HTTPS lista para Coil/AsyncImage.
     */
    private fun upsertMediaMessage(
        roomId: String,
        eventId: String,
        senderId: String,
        timestamp: Long,
        type: MessageType,
        mxcUrl: String?,
        fileName: String,
        replyToEventId: String? = null,
        threadRootId: String? = null,
    ) {
        val current = _messages.value[roomId] ?: emptyList()
        if (current.any { it.id == eventId }) return

        val httpUrl = mxcUrl?.let { resolveMxcToHttpUrl(it) }

        val msg = MatrixMessage(
            id = eventId,
            roomId = roomId,
            senderId = senderId,
            senderDisplayName = extractDisplayName(senderId),
            content = fileName,
            timestamp = timestamp,
            type = type,
            mediaUrl = httpUrl,
            mxcUrl = mxcUrl,
            fileName = fileName,
            replyToEventId = replyToEventId,
            threadRootId = threadRootId,
        )

        // Reemplazar optimista local. NO exigimos igualdad de fileName: el nombre
        // del eco del servidor suele diferir del optimista (p. ej. "voice-1947.wav"),
        // lo que dejaba la nota de voz propia con spinner infinito (sin mxc). Basta
        // con sender + tipo + cercanía temporal.
        val replaceIdx = current.indexOfFirst { existing ->
            existing.id.startsWith("local_") &&
                existing.senderId == senderId &&
                existing.type == type &&
                kotlin.math.abs(existing.timestamp - timestamp) < 60_000L
        }
        val updated = if (replaceIdx >= 0) {
            current.toMutableList().apply { set(replaceIdx, msg) }
        } else {
            (current + msg).sortedBy { it.timestamp }
        }
        _messages.value = _messages.value + (roomId to updated)
    }

    /**
     * Convierte `mxc://server.com/mediaId` → URL HTTPS de descarga via el
     * homeserver actual del cliente Matrix.
     *
     * Endpoint: `${baseUrl}/_matrix/media/v3/download/{server}/{mediaId}`.
     * Adjuntamos `?access_token=…` para que los clientes puedan abrir la URL
     * sin headers (Coil network-ktor incluirá cualquier query param tal cual).
     *
     * Devuelve null si la URI no es válida o no tenemos token de acceso.
     */
    /**
     * Descarga los bytes de la media de un EVENTO concreto, manejando también
     * salas cifradas: si el content trae `file` (EncryptedFile), descarga y
     * DESCIFRA vía el MediaService de Trixnity; si trae `url`, descarga normal.
     * Es el camino correcto para notas de voz/adjuntos que la descarga directa
     * por mxc no cubre cuando hay E2EE.
     */
    suspend fun getMediaBytesForEvent(roomId: String, eventId: String, maxSize: Long? = null): ByteArray? {
        val client = matrixClient ?: return null
        return try {
            val ev = withTimeoutOrNull(10_000L) {
                client.room.getTimelineEvent(RoomId(roomId), EventId(eventId))
                    .filterNotNull()
                    .first { it.content != null }
            } ?: run {
                log.w(TAG) { "getMediaBytesForEvent: evento $eventId no disponible" }
                return null
            }
            val content = ev.content?.getOrNull() as? RoomMessageEventContent.FileBased ?: return null
            val encrypted = content.file
            if (encrypted != null) {
                client.media.getEncryptedMedia(encrypted).getOrThrow().toByteArray(maxSize = maxSize)
            } else {
                content.url?.let { fileManager.getMediaBytes(it, maxSize) }
            }
        } catch (e: Exception) {
            log.w(TAG) { "getMediaBytesForEvent fallo ($eventId): ${e.message}" }
            null
        }
    }

    private fun resolveMxcToHttpUrl(mxc: String): String? {
        if (!mxc.startsWith("mxc://")) return null
        val rest = mxc.removePrefix("mxc://")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val serverName = rest.substring(0, slash)
        val mediaId = rest.substring(slash + 1)
        if (mediaId.isEmpty()) return null
        val baseUrl = config.homeserverUrl.trimEnd('/')
        val token = storedAccessToken
        val tokenQuery = if (!token.isNullOrBlank()) "?access_token=$token" else ""
        return "$baseUrl/_matrix/media/v3/download/$serverName/$mediaId$tokenQuery"
    }

    /**
     * Inserta o actualiza un mensaje de texto en el cache `_messages` con
     * deduplicación inteligente:
     *
     *   1. Si ya existe un mensaje con `event.id` igual → ignora (eco doble).
     *   2. Si existe un mensaje optimista local (id="local_…") del MISMO sender
     *      con el MISMO contenido y timestamp ±10 segundos → lo reemplaza por
     *      el real (preservando posición + id de servidor para futuras dedupes).
     *   3. Si no, lo añade respetando orden cronológico por timestamp.
     */
    private fun upsertTextMessage(
        roomId: String,
        eventId: String,
        senderId: String,
        body: String,
        timestamp: Long,
        type: MessageType = MessageType.TEXT,
        replyToEventId: String? = null,
        threadRootId: String? = null,
        formattedBody: String? = null,
        format: String? = null,
    ) {
        val current = _messages.value[roomId] ?: emptyList()

        // 1) Dedupe exacta por eventId
        if (current.any { it.id == eventId }) return

        val newMessage = MatrixMessage(
            id = eventId,
            roomId = roomId,
            senderId = senderId,
            senderDisplayName = extractDisplayName(senderId),
            content = body,
            timestamp = timestamp,
            type = type,
            replyToEventId = replyToEventId,
            threadRootId = threadRootId,
            formattedBody = formattedBody,
            format = format,
        )

        // 2) Buscar optimista local del mismo sender+content+timestamp cercano
        val replaceIdx = current.indexOfFirst { existing ->
            existing.id.startsWith("local_") &&
                existing.senderId == senderId &&
                existing.content == body &&
                kotlin.math.abs(existing.timestamp - timestamp) < 10_000L
        }

        val updated = if (replaceIdx >= 0) {
            log.d(TAG) { "UPSERT replace-optimistic room=$roomId event=$eventId" }
            current.toMutableList().apply { set(replaceIdx, newMessage) }
        } else {
            log.d(TAG) { "UPSERT insert-new room=$roomId event=$eventId sender=$senderId" }
            // 3) Insertar manteniendo orden cronológico (mayor timestamp al final)
            val combined = (current + newMessage).sortedBy { it.timestamp }
            combined
        }
        _messages.value = _messages.value + (roomId to updated)
    }

    // Cache de rooms cuyo timeline histórico ya cargamos en esta sesión, para
    // no relanzar la carga cada vez que el observer re-emite.
    /**
     * Colecta los hijos (m.space.child) de un Space y los publica en
     * [_spaceChildren]. Idempotente por space. Un hijo se considera presente si
     * su evento `m.space.child` tiene `via` no vacío (vacío = removido del grupo).
     * Los hijos se ordenan por el campo `order` del evento (como Element).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeSpaceChildren(spaceId: String) {
        val client = matrixClient ?: return
        if (spaceChildJobs[spaceId]?.isActive == true) return
        spaceChildJobs[spaceId] = scope.launch {
            try {
                client.room.getAllState(RoomId(spaceId), ChildEventContent::class)
                    .flatMapLatest { byChild ->
                        if (byChild.isEmpty()) {
                            flowOf(emptyList<Pair<String, String?>>())
                        } else {
                            val flows = byChild.entries.map { (childRoomId, evFlow) ->
                                evFlow.map { ev ->
                                    val content = ev?.content
                                    if (content is ChildEventContent && content.via.isNotEmpty()) {
                                        childRoomId to content.order
                                    } else null
                                }
                            }
                            combine(flows) { arr -> arr.filterNotNull() }
                        }
                    }
                    .collect { children ->
                        val ordered = children
                            .sortedWith(compareBy({ it.second ?: "￿" }, { it.first }))
                            .map { it.first }
                        _spaceChildren.value = _spaceChildren.value + (spaceId to ordered)
                    }
            } catch (e: Exception) {
                log.w(TAG) { "observeSpaceChildren($spaceId) failed: ${e.message}" }
                spaceChildJobs.remove(spaceId)
            }
        }
    }

    private val historicalLoaded = mutableSetOf<String>()

    // Evento más antiguo cargado por sala, para paginar hacia atrás (scroll back).
    private val oldestLoaded = mutableMapOf<String, net.folivo.trixnity.client.store.TimelineEvent>()

    /**
     * Carga los últimos N eventos persistidos del store de Trixnity para una
     * room y los inyecta en `_messages` vía `upsertTextMessage`. Esto permite
     * que al re-loguear o al entrar a una room por primera vez en la sesión
     * el usuario vea sus propios mensajes anteriores y los recibidos antes
     * de subscribirse al sync.
     */
    /**
     * Devuelve el evento ANTERIOR a [current], rellenando el hueco (gap) desde el
     * servidor si hace falta. `getPreviousTimelineEvent` por sí solo NO rellena
     * gaps de forma fiable en Trixnity 4.22 (devuelve null aunque haya historial
     * en el servidor), por eso forzamos `fillTimelineGaps` cuando hay
     * `previousEventId`/`hasGapBefore` pero el store local no tiene el anterior.
     * Devuelve null solo cuando se llegó al inicio real de la sala.
     */
    private suspend fun previousEventFilling(
        rid: RoomId,
        current: net.folivo.trixnity.client.store.TimelineEvent,
        batchSize: Long = 30L,
    ): net.folivo.trixnity.client.store.TimelineEvent? {
        val client = matrixClient ?: return null
        // 1º intento (rápido, store local + fetch corto).
        var prev = client.room.getPreviousTimelineEvent(current) { fetchTimeout = 5.seconds }?.firstOrNull()
        if (prev != null) return prev
        // Hay hueco: si el server conoce un anterior (previousEventId) o el evento
        // marca gap-before, rellenar desde el servidor y reintentar.
        val needsFill = current.previousEventId != null || current.gap?.hasGapBefore == true
        if (!needsFill) return null // inicio real de la sala
        runCatching { client.room.fillTimelineGaps(rid, current.eventId, batchSize) }
            .onFailure { log.w(TAG) { "fillTimelineGaps(${rid.full}) fallo: ${it.message}" } }
        prev = client.room.getPreviousTimelineEvent(current) { fetchTimeout = 10.seconds }?.firstOrNull()
        return prev
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun loadHistoricalTimeline(roomId: String, limit: Int = 80) {
        if (!historicalLoaded.add(roomId)) return
        val client = matrixClient ?: return
        scope.launch {
            try {
                val rid = RoomId(roomId)
                // CRÍTICO: pasar fetchTimeout/fetchSize → si el store local no tiene
                // timeline (recién joineado / sync parcial), Trixnity lo trae del
                // servidor. Sin esto la sala se quedaba vacía ("se sincroniza al abrir").
                val last = client.room.getLastTimelineEvent(rid) {
                    fetchTimeout = 10.seconds
                    fetchSize = limit.toLong()
                }
                    .firstOrNull()
                    ?.firstOrNull()
                if (last == null) {
                    // El timeline aún no está disponible (sync en curso). Liberar el
                    // gate para reintentar la próxima vez que se abra la sala.
                    historicalLoaded.remove(roomId)
                    return@launch
                }

                val history = mutableListOf(last)
                var current: net.folivo.trixnity.client.store.TimelineEvent? = last
                while (history.size < limit) {
                    val prev = current?.let { previousEventFilling(rid, it) } ?: break
                    history.add(prev)
                    current = prev
                }
                // Guardar el más antiguo para poder paginar hacia atrás.
                current?.let { oldestLoaded[roomId] = it }
                // Procesar del más antiguo al más reciente
                history.reversed().forEach { tev ->
                    val content = tev.content?.getOrNull() as? RoomMessageEventContent
                        ?: return@forEach
                    processRoomMessageContent(
                        roomId = tev.event.roomId.full,
                        eventId = tev.event.id.full,
                        senderId = tev.event.sender.full,
                        timestamp = tev.event.originTimestamp,
                        content = content,
                    )
                }
                log.d(TAG) { "Historical timeline loaded for $roomId: ${history.size} events" }
            } catch (e: Exception) {
                log.w(TAG) { "loadHistoricalTimeline($roomId) failed: ${e.message}" }
                // Permitir reintentar más tarde
                historicalLoaded.remove(roomId)
            }
        }
    }

    /**
     * Solicita explícitamente la carga del historial de una sala (al abrirla).
     * Idempotente: si ya se cargó en esta sesión no hace nada. Útil cuando se
     * navega directo a una sala antes de que el observer de rooms dispare la
     * carga, o como reintento si la primera carga falló.
     */
    fun requestRoomHistory(roomId: String) {
        loadHistoricalTimeline(roomId)
        followRoom(roomId)
    }

    // Seguimiento EN VIVO de la sala abierta. El observador global
    // (getTimelineEventsFromNowOn) a veces no entrega eventos de ciertas salas;
    // este flujo por-sala (uno solo, barato) garantiza que el eco de tus propios
    // mensajes y las respuestas entrantes lleguen siempre a la sala que estás viendo.
    private var followJob: Job? = null
    private var followedRoom: String? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun followRoom(roomId: String) {
        val client = matrixClient ?: return
        if (followedRoom == roomId && followJob?.isActive == true) return
        followedRoom = roomId
        followJob?.cancel()
        followJob = scope.launch {
            try {
                client.room.getLastTimelineEvent(RoomId(roomId))
                    .flatMapLatest { inner -> inner ?: flowOf(null) }
                    .collect { tev ->
                        val ev = tev ?: return@collect
                        val content = ev.content?.getOrNull() ?: return@collect
                        when (content) {
                            is RoomMessageEventContent -> processRoomMessageContent(
                                roomId = ev.event.roomId.full,
                                eventId = ev.event.id.full,
                                senderId = ev.event.sender.full,
                                timestamp = ev.event.originTimestamp,
                                content = content,
                            )
                            is RedactionEventContent -> removeMessage(ev.event.roomId.full, content.redacts.full)
                            // Eventos de llamada de la sala seguida (dedup por eventId).
                            is CallEventContent -> processCallEvent(ev)
                            else -> { }
                        }
                    }
            } catch (e: Exception) {
                log.w(TAG) { "followRoom($roomId) failed: ${e.message}" }
            }
        }
    }

    /**
     * Pagina hacia atrás: carga [count] mensajes MÁS ANTIGUOS que el más viejo ya
     * cargado en la sala (rellenando gaps desde el servidor si hace falta).
     * Devuelve cuántos mensajes nuevos se añadieron (0 = se llegó al inicio de la sala).
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun loadOlderMessages(roomId: String, count: Int = 30): Int {
        val client = matrixClient ?: return 0
        // Si aún no hay punto de partida, asegurar la carga inicial primero.
        var start = oldestLoaded[roomId]
        if (start == null) {
            loadHistoricalTimeline(roomId)
            // Esperar brevemente a que se fije el punto más antiguo.
            repeat(20) {
                start = oldestLoaded[roomId]
                if (start != null) return@repeat
                kotlinx.coroutines.delay(150)
            }
            start = oldestLoaded[roomId] ?: return 0
        }
        return try {
            val rid = RoomId(roomId)
            var current = start
            var added = 0
            // Tope de saltos: cada 'prev' puede ser un evento de estado (no-mensaje);
            // recorremos hasta 'count*4' eventos para juntar 'count' mensajes reales.
            var hops = 0
            while (added < count && hops < count * 4) {
                hops++
                val prev = current?.let { previousEventFilling(rid, it) } ?: break
                current = prev
                val content = prev.content?.getOrNull() as? RoomMessageEventContent
                if (content != null) {
                    processRoomMessageContent(
                        roomId = prev.event.roomId.full,
                        eventId = prev.event.id.full,
                        senderId = prev.event.sender.full,
                        timestamp = prev.event.originTimestamp,
                        content = content,
                    )
                    added++
                }
            }
            current?.let { oldestLoaded[roomId] = it }
            log.d(TAG) { "loadOlderMessages($roomId): +$added (count target=$count)" }
            added
        } catch (e: Exception) {
            log.w(TAG) { "loadOlderMessages($roomId) failed: ${e.message}" }
            0
        }
    }

    /**
     * Resuelve el nombre de una sala de forma correcta.
     * - Salas con nombre explícito → usa ese nombre
     * - Salas DM (isDirect=true) sin nombre → usa el displayName del otro miembro
     * - Salas de grupo sin nombre → usa localpart del room ID
     */
    private fun resolveRoomName(
        roomId: String,
        explicitName: String?,
        isDirect: Boolean,
        myUserId: String,
        heroes: List<String> = emptyList(),
    ): String {
        // 1. Intentar nombre explícito (para salas con nombre configurado)
        if (!explicitName.isNullOrBlank()) return explicitName

        // 2. Heroes: en Matrix los "heroes" son los otros miembros del room que
        // el server seleccionó para componer el display name. Esto cubre tanto
        // DMs (un solo hero) como grupos pequeños sin nombre (varios heroes).
        if (heroes.isNotEmpty()) {
            val others = heroes.filter { it != myUserId }
            if (others.isNotEmpty()) {
                val names = others.map { extractDisplayName(it) }
                return when {
                    names.size == 1 -> names[0]
                    names.size == 2 -> "${names[0]} & ${names[1]}"
                    else -> "${names[0]}, ${names[1]} +${names.size - 2}"
                }
            }
        }

        // 3. Para salas DM: buscar el otro miembro en mensajes recientes
        if (isDirect) {
            val roomMessages = _messages.value[roomId]
            val otherSender = roomMessages
                ?.map { it.senderId }
                ?.firstOrNull { it != myUserId }
            if (otherSender != null) {
                return extractDisplayName(otherSender)
            }
        }

        // 4. Fallback: usar el localpart del room ID (!localpart:server)
        val localPart = roomId.substringAfter("!").substringBefore(":")
        return localPart.takeIf { it.isNotBlank() } ?: "Sala"
    }

    // Cache para evitar auto-join repetido del mismo room (la observación de
    // rooms re-emite múltiples veces y no queremos lanzar joinRoom cada vez).
    private val autoJoinAttempted = mutableSetOf<String>()

    /**
     * Auto-acepta una invitación a sala. Se llama desde el observer cuando
     * detectamos un room con membership=INVITE. Es idempotente — si ya
     * intentamos joinear esta sala antes en esta sesión, no se reintenta.
     *
     * Hace que el envío de mensajes funcione sin que el usuario tenga que
     * aceptar manualmente la invitación (UX tipo Element para DMs).
     */
    private fun autoJoinInvitedRoom(roomId: String) {
        if (!autoJoinAttempted.add(roomId)) return
        val client = matrixClient ?: return
        scope.launch {
            try {
                log.d(TAG) { "Auto-joining invited room: $roomId" }
                client.api.room.joinRoom(RoomId(roomId)).getOrThrow()
                log.d(TAG) { "Auto-join success: $roomId" }
            } catch (e: Exception) {
                log.w(TAG) { "Auto-join failed for $roomId: ${e.message}" }
                // Permitir reintentar en una próxima emisión
                autoJoinAttempted.remove(roomId)
            }
        }
    }

    /**
     * Une explícitamente al usuario a una sala (catálogo BackOffice CATALOG_ONLY,
     * invitaciones, o salas públicas). Idempotente: si ya está joined, el server
     * responde OK. Tras unirse, carga el historial para que la sala deje de verse
     * vacía. Devuelve el resultado del join.
     */
    suspend fun joinRoom(roomId: String): Result<Unit> {
        val client = matrixClient ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            client.api.room.joinRoom(RoomId(roomId)).getOrThrow()
            log.d(TAG) { "joinRoom OK: $roomId" }
            // Permitir que el historial se (re)cargue ahora que somos miembros.
            historicalLoaded.remove(roomId)
            loadHistoricalTimeline(roomId)
            Result.success(Unit)
        } catch (e: Exception) {
            log.w(TAG) { "joinRoom failed for $roomId: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Enviar mensaje de texto.
     * Añade actualización optimista inmediata para que el mensaje aparezca en la UI sin esperar el eco del servidor.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun sendTextMessage(roomId: String, message: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            val myUserId = client.userId.full

            // Actualización optimista: mostrar el mensaje enviado de inmediato
            val tempId = "local_${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
            val optimisticMessage = MatrixMessage(
                id = tempId,
                roomId = roomId,
                senderId = myUserId,
                senderDisplayName = extractDisplayName(myUserId),
                content = message,
                timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                type = MessageType.TEXT
            )
            val currentMessages = _messages.value[roomId] ?: emptyList()
            _messages.value = _messages.value + (roomId to (currentMessages + optimisticMessage))

            // Defensa: si el room aún está en estado INVITE (auto-join no se ha
            // completado, o falló), intentamos joinear de forma síncrona antes
            // de enviar para evitar el error "missing permissions in this room".
            try {
                val roomFlow = client.room.getById(RoomId(roomId))
                val currentRoom = roomFlow.firstOrNull()
                if (currentRoom?.membership == Membership.INVITE) {
                    log.d(TAG) { "Room still in INVITE state, joining before send: $roomId" }
                    client.api.room.joinRoom(RoomId(roomId)).getOrThrow()
                }
            } catch (joinErr: Throwable) {
                // Si el chequeo/join falla seguimos intentando enviar — el server
                // dará error real y lo capturamos abajo.
                log.w(TAG) { "Pre-send join check failed for $roomId: ${joinErr.message}" }
            }

            client.room.sendMessage(RoomId(roomId)) {
                text(message)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG, { "Error sending message: $e" })
            Result.failure(e)
        }
    }

    /**
     * Enviar un mensaje con cuerpo formateado HTML (`org.matrix.custom.html`).
     * [body] es el fallback en texto plano; [html] el cuerpo formateado (negrita,
     * itálica, links, `<pre><code>` para bloques de código, etc.). Element y otros
     * clientes lo renderizan. Inserta optimista con el formato para feedback inmediato.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun sendFormattedMessage(roomId: String, body: String, html: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            val myUserId = client.userId.full
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

            val optimistic = MatrixMessage(
                id = "local_$now",
                roomId = roomId,
                senderId = myUserId,
                senderDisplayName = extractDisplayName(myUserId),
                content = body,
                timestamp = now,
                type = MessageType.TEXT,
                formattedBody = html,
                format = HTML_FORMAT,
            )
            val current = _messages.value[roomId] ?: emptyList()
            _messages.value = _messages.value + (roomId to (current + optimistic))

            ensureJoinedBeforeSend(roomId)

            client.room.sendMessage(RoomId(roomId)) {
                text(body = body, format = HTML_FORMAT, formattedBody = html)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "sendFormattedMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Responder con cuerpo formateado HTML.
     */
    suspend fun sendFormattedReply(roomId: String, replyToEventId: String, body: String, html: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.room.sendMessage(RoomId(roomId)) {
                reply(EventId(replyToEventId), null)
                text(body = body, format = HTML_FORMAT, formattedBody = html)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "sendFormattedReply failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Editar un mensaje con cuerpo formateado HTML (m.replace).
     */
    suspend fun editFormattedMessage(roomId: String, targetEventId: String, body: String, html: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.room.sendMessage(RoomId(roomId)) {
                replace(EventId(targetEventId))
                text(body = body, format = HTML_FORMAT, formattedBody = html)
            }
            applyEdit(roomId, targetEventId, body, html)
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "editFormattedMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Defensa: si el room está en estado INVITE, joinear antes de enviar para
     * evitar "missing permissions". No-op si ya estamos joineados.
     */
    private suspend fun ensureJoinedBeforeSend(roomId: String) {
        val client = matrixClient ?: return
        try {
            val currentRoom = client.room.getById(RoomId(roomId)).firstOrNull()
            if (currentRoom?.membership == Membership.INVITE) {
                client.api.room.joinRoom(RoomId(roomId)).getOrThrow()
            }
        } catch (e: Throwable) {
            log.w(TAG) { "ensureJoinedBeforeSend failed for $roomId: ${e.message}" }
        }
    }

    /**
     * Responder a un mensaje (m.in_reply_to). Mantiene soporte de thread si el
     * mensaje respondido pertenece a uno (la DSL lo resuelve internamente).
     */
    suspend fun sendReply(roomId: String, replyToEventId: String, message: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.room.sendMessage(RoomId(roomId)) {
                reply(EventId(replyToEventId), null)
                text(message)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "sendReply failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Enviar un mensaje dentro de un thread (rel_type m.thread). [threadRootId]
     * es el evento raíz del hilo.
     */
    suspend fun sendThreadMessage(roomId: String, threadRootId: String, message: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.room.sendMessage(RoomId(roomId)) {
                thread(EventId(threadRootId), null, false)
                text(message)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "sendThreadMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Editar un mensaje propio (m.replace). [targetEventId] es el mensaje original.
     */
    suspend fun editMessage(roomId: String, targetEventId: String, newMessage: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.room.sendMessage(RoomId(roomId)) {
                replace(EventId(targetEventId))
                text(newMessage)
            }
            // Optimista: reflejar la edición de inmediato.
            applyEdit(roomId, targetEventId, newMessage)
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "editMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Borrar (redactar) un mensaje. Funciona sobre mensajes propios o ajenos si
     * el usuario tiene permisos en la sala.
     */
    suspend fun deleteMessage(roomId: String, eventId: String, reason: String? = null): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.api.room.redactEvent(RoomId(roomId), EventId(eventId), reason).getOrThrow()
            removeMessage(roomId, eventId)
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "deleteMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Reenviar un mensaje a otra sala. No hay evento nativo de forward en Matrix:
     * se re-envía el contenido. Para media se re-referencia el `mxc://` original.
     */
    suspend fun forwardMessage(targetRoomId: String, message: MatrixMessage): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            when (message.type) {
                MessageType.TEXT, MessageType.NOTICE,
                MessageType.CALL_INVITE, MessageType.CALL_ANSWER, MessageType.CALL_HANGUP -> {
                    client.room.sendMessage(RoomId(targetRoomId)) { text(message.content) }
                }
                else -> {
                    // Media: descargar bytes del mxc original y re-subir al reenviar.
                    val mxc = message.mxcUrl
                    if (mxc == null) {
                        client.room.sendMessage(RoomId(targetRoomId)) { text(message.content) }
                    } else {
                        val bytes = fileManager.getMediaBytes(mxc)
                            ?: throw Exception("No se pudo descargar la media a reenviar")
                        val mime = when (message.type) {
                            MessageType.IMAGE -> "image/*"
                            MessageType.VIDEO -> "video/*"
                            MessageType.AUDIO -> "audio/*"
                            else -> "application/octet-stream"
                        }
                        sendFile(targetRoomId, bytes, mime, message.fileName ?: message.content)
                            .getOrThrow()
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "forwardMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Reaccionar a un mensaje con un emoji (m.reaction / annotation).
     */
    suspend fun react(roomId: String, eventId: String, emoji: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.room.sendMessage(RoomId(roomId)) {
                react(EventId(eventId), emoji)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "react failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Quitar una reacción propia: redacta el evento m.reaction. [reactionEventId]
     * se obtiene de [ReactionInfo.reactionEventIds] (vía [observeReactions]).
     */
    suspend fun removeReaction(roomId: String, reactionEventId: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.api.room.redactEvent(RoomId(roomId), EventId(reactionEventId)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "removeReaction failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Observa las reacciones agregadas de un mensaje: emoji -> [ReactionInfo].
     * Pensado para llamarse por mensaje visible (Trixnity agrega bajo demanda).
     */
    fun observeReactions(roomId: String, eventId: String): Flow<Map<String, ReactionInfo>>? {
        val client = matrixClient ?: return null
        val myUserId = client.userId.full
        return client.room
            .getTimelineEventReactionAggregation(RoomId(roomId), EventId(eventId))
            .map { aggregation ->
                aggregation.reactions.mapValues { (_, events) ->
                    ReactionInfo(
                        count = events.size,
                        reactedByMe = events.any { it.sender.full == myUserId },
                        reactionEventIds = events.map { it.eventId.full },
                    )
                }
            }
    }

    /**
     * Elimina un mensaje del cache local (por borrado/redacción).
     */
    private fun removeMessage(roomId: String, eventId: String) {
        val current = _messages.value[roomId] ?: return
        if (current.none { it.id == eventId }) return
        _messages.value = _messages.value + (roomId to current.filterNot { it.id == eventId })
    }

    /**
     * Crear sala nueva
     */
    suspend fun createRoom(
        name: String,
        isDirect: Boolean = false,
        inviteUserIds: List<String> = emptyList()
    ): Result<String> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")

            val createRoomResult = client.api.room.createRoom(
                name = name,
                isDirect = isDirect,
                invite = inviteUserIds.map { UserId(it) }.toSet()
            )

            createRoomResult.fold(
                onSuccess = { roomId ->
                    Result.success(roomId.full)
                },
                onFailure = { error ->
                    log.e(TAG, { "Error creating room: $error" })
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            log.e(TAG, { "Error creating room: $e" })
            Result.failure(e)
        }
    }

    /**
     * Invita a un usuario a una sala existente (m.room.invite).
     */
    suspend fun inviteUser(roomId: String, userId: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.api.room.inviteUser(RoomId(roomId), UserId(userId)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "inviteUser failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Abandonar una sala (m.room.leave). Tras esto el room desaparece del sync.
     */
    suspend fun leaveRoom(roomId: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            client.api.room.leaveRoom(RoomId(roomId)).getOrThrow()
            // Limpiar cache local de mensajes de esa sala.
            _messages.value = _messages.value - roomId
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "leaveRoom failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Observa los eventos fijados de una sala (m.room.pinned_events). Devuelve
     * la lista de eventIds fijados (el más reciente al final).
     */
    fun observePinnedEvents(roomId: String): Flow<List<String>>? {
        val client = matrixClient ?: return null
        return client.room
            .getState<net.folivo.trixnity.core.model.events.m.room.PinnedEventsEventContent>(RoomId(roomId))
            .map { stateEvent ->
                stateEvent?.content?.pinned?.map { it.full } ?: emptyList()
            }
    }

    /**
     * Fija un mensaje: añade su eventId a m.room.pinned_events (state event).
     */
    suspend fun pinMessage(roomId: String, eventId: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            val current = client.room
                .getState<net.folivo.trixnity.core.model.events.m.room.PinnedEventsEventContent>(RoomId(roomId))
                .firstOrNull()?.content?.pinned ?: emptyList()
            val target = EventId(eventId)
            if (current.contains(target)) return Result.success(Unit)
            client.api.room.sendStateEvent(
                roomId = RoomId(roomId),
                eventContent = net.folivo.trixnity.core.model.events.m.room.PinnedEventsEventContent(
                    pinned = current + target
                ),
            ).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "pinMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /** Quita un mensaje de los fijados. */
    suspend fun unpinMessage(roomId: String, eventId: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            val current = client.room
                .getState<net.folivo.trixnity.core.model.events.m.room.PinnedEventsEventContent>(RoomId(roomId))
                .firstOrNull()?.content?.pinned ?: emptyList()
            val target = EventId(eventId)
            if (!current.contains(target)) return Result.success(Unit)
            client.api.room.sendStateEvent(
                roomId = RoomId(roomId),
                eventContent = net.folivo.trixnity.core.model.events.m.room.PinnedEventsEventContent(
                    pinned = current - target
                ),
            ).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "unpinMessage failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Iniciar llamada de voz nativa Matrix - envia m.call.invite.
     * Gateada por [MatrixConfig.enableVoip] (true por defecto). Convive con
     * LiveKit: el [com.eddyslarez.kmpsiprtc.services.unified.UnifiedCallRouter]
     * decide la ruta por destino.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun startVoiceCall(roomId: String): Result<MatrixCall> {
        if (!config.enableVoip) {
            log.w(TAG) { "startVoiceCall: bloqueado, MatrixConfig.enableVoip=false (chat-only)" }
            return Result.failure(IllegalStateException("Matrix VoIP disabled by config"))
        }
        return try {
            log.d { "Starting Matrix voice call in room: $roomId" }
            // Seguir la sala para recibir el m.call.answer y candidates del callee
            // aunque el observador global no entregue eventos de esta sala.
            followRoom(roomId)

            val client = matrixClient ?: throw Exception("Not logged in to Matrix")
            val myUserId = client.userId.full

            // Inicializar WebRTC y crear oferta. ICE primero: sin los TURN del
            // homeserver, la llamada falla entre NATs simetricos (4G<->WiFi).
            webRtcManager.initialize()
            applyTurnServersToWebRtc()
            webRtcManager.prepareAudioForCall()
            val offerSdp = webRtcManager.createOffer()
            val callId = generateCallId()

            val call = MatrixCall(
                callId = callId,
                roomId = roomId,
                isVideo = false,
                state = MatrixCallState.INVITING,
                localSdp = offerSdp
            )
            _activeCall.value = call

            // Alimentar CallStateManager con CallData de tipo Matrix
            val displayName = resolveCallDisplayName(roomId)
            val callData = CallData(
                callId = callId,
                from = myUserId,
                to = roomId,
                direction = CallDirections.OUTGOING,
                startTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                callType = CallType.MATRIX_INTERNAL,
                roomId = roomId,
                localSdp = offerSdp,
                remoteDisplayName = displayName,
                sipName = displayName,
            )
            CallStateManager.startOutgoingCall(callId, roomId, callData)
            sipCoreManager?.notifyCallStateChanged(CallState.OUTGOING_INIT)

            // Enviar m.call.invite usando tipos nativos de Trixnity
            client.api.room.sendMessageEvent(
                roomId = RoomId(roomId),
                eventContent = CallEventContent.Invite(
                    callId = callId,
                    version = CALL_VERSION,
                    lifetime = CALL_LIFETIME,
                    offer = CallEventContent.Invite.Offer(
                        sdp = offerSdp,
                        type = CallEventContent.Invite.OfferType.OFFER
                    ),
                    sdpStreamMetadata = null
                )
            )

            // Notificar que esta sonando
            CallStateManager.outgoingCallRinging(callId)
            sipCoreManager?.notifyCallStateChanged(CallState.OUTGOING_RINGING)

            log.d(TAG) { "m.call.invite sent for call $callId" }
            Result.success(call)

        } catch (e: Exception) {
            log.e(TAG, { "Error starting voice call: $e" })
            _activeCall.value = _activeCall.value?.copy(state = MatrixCallState.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Iniciar videollamada nativa Matrix - envia m.call.invite con video.
     * Gateada por [MatrixConfig.enableVideo] (true por defecto).
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun startVideoCall(roomId: String): Result<MatrixCall> {
        if (!config.enableVideo) {
            log.w(TAG) { "startVideoCall: bloqueado, MatrixConfig.enableVideo=false (chat-only)" }
            return Result.failure(IllegalStateException("Matrix video disabled by config"))
        }
        return try {
            log.d { "Starting Matrix video call in room: $roomId" }
            followRoom(roomId)

            val client = matrixClient ?: throw Exception("Not logged in to Matrix")
            val myUserId = client.userId.full

            webRtcManager.initialize()
            webRtcManager.prepareAudioForCall()
            val offerSdp = webRtcManager.createOffer()
            val callId = generateCallId()

            val call = MatrixCall(
                callId = callId,
                roomId = roomId,
                isVideo = true,
                state = MatrixCallState.INVITING,
                localSdp = offerSdp
            )
            _activeCall.value = call

            // Alimentar CallStateManager (igual que en voz) para no corromper la
            // máquina de estados (sin esto: "Invalid state transition: ENDED -> CONNECTED").
            val displayName = resolveCallDisplayName(roomId)
            val callData = CallData(
                callId = callId,
                from = myUserId,
                to = roomId,
                direction = CallDirections.OUTGOING,
                startTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                callType = CallType.MATRIX_INTERNAL,
                roomId = roomId,
                localSdp = offerSdp,
                remoteDisplayName = displayName,
                sipName = displayName,
            )
            CallStateManager.startOutgoingCall(callId, roomId, callData)
            sipCoreManager?.notifyCallStateChanged(CallState.OUTGOING_INIT)

            client.api.room.sendMessageEvent(
                roomId = RoomId(roomId),
                eventContent = CallEventContent.Invite(
                    callId = callId,
                    version = CALL_VERSION,
                    lifetime = CALL_LIFETIME,
                    offer = CallEventContent.Invite.Offer(
                        sdp = offerSdp,
                        type = CallEventContent.Invite.OfferType.OFFER
                    ),
                    sdpStreamMetadata = null
                )
            )

            CallStateManager.outgoingCallRinging(callId)
            sipCoreManager?.notifyCallStateChanged(CallState.OUTGOING_RINGING)

            log.d(TAG) { "m.call.invite (video) sent for call $callId" }
            Result.success(call)

        } catch (e: Exception) {
            log.e(TAG, { "Error starting video call: $e" })
            _activeCall.value = _activeCall.value?.copy(state = MatrixCallState.ERROR)
            Result.failure(e)
        }
    }

    /** Nombre legible del contacto/sala para la pantalla de llamada (evita mostrar el roomId). */
    private fun resolveCallDisplayName(roomId: String): String =
        _rooms.value.firstOrNull { it.id == roomId }?.name?.takeIf { it.isNotBlank() } ?: roomId

    /**
     * Responder llamada nativa Matrix - envia m.call.answer.
     */
    suspend fun answerCall(callId: String): Result<Unit> {
        if (!config.enableVoip && !config.enableVideo) {
            log.w(TAG) { "answerCall: bloqueado, Matrix VoIP/Video deshabilitados por config" }
            return Result.failure(IllegalStateException("Matrix calls disabled by config"))
        }
        return try {
            val call = _activeCall.value ?: throw Exception("No active call")
            val client = matrixClient ?: throw Exception("Not logged in")

            log.d(TAG) { "Answering call $callId in room ${call.roomId}" }

            // Crear answer SDP basado en la oferta remota
            val remoteSdp = call.remoteSdp ?: throw Exception("No remote SDP for answer")
            // Inicializar WebRTC antes de crear la respuesta (necesario para peer connection).
            // ICE primero: sin los TURN del homeserver falla entre NATs simetricos.
            webRtcManager.initialize()
            applyTurnServersToWebRtc()
            webRtcManager.prepareAudioForIncomingCall()
            val answerSdp = webRtcManager.createAnswer(remoteSdp)

            // Actualizar estado local
            _activeCall.value = call.copy(
                state = MatrixCallState.CONNECTING,
                localSdp = answerSdp
            )

            // Enviar m.call.answer
            client.api.room.sendMessageEvent(
                roomId = RoomId(call.roomId),
                eventContent = CallEventContent.Answer(
                    callId = callId,
                    version = CALL_VERSION,
                    answer = CallEventContent.Answer.Answer(
                        sdp = answerSdp,
                        type = CallEventContent.Answer.AnswerType.ANSWER
                    )
                )
            )

            log.d(TAG) { "m.call.answer sent for call $callId" }
            callEventListener?.onCallStateChanged(callId, MatrixCallState.CONNECTING)

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG, { "Error answering call: $e" })
            Result.failure(e)
        }
    }

    /**
     * Colgar llamada nativa Matrix - envia m.call.hangup.
     */
    suspend fun hangupCall(callId: String): Result<Unit> {
        return try {
            val call = _activeCall.value ?: throw Exception("No active call")
            val client = matrixClient ?: throw Exception("Not logged in")

            log.d(TAG) { "Hanging up call $callId in room ${call.roomId}" }

            // Enviar m.call.hangup
            client.api.room.sendMessageEvent(
                roomId = RoomId(call.roomId),
                eventContent = CallEventContent.Hangup(
                    callId = callId,
                    version = CALL_VERSION,
                    reason = CallEventContent.Hangup.Reason.USER_HANGUP
                )
            )

            // Limpiar WebRTC y estado local
            webRtcManager.closePeerConnection()
            _activeCall.value = null

            log.d(TAG) { "m.call.hangup sent for call $callId" }
            callEventListener?.onCallHangup(callId, "user_hangup")
            callEventListener?.onCallStateChanged(callId, MatrixCallState.ENDED)

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG, { "Error hanging up: $e" })
            Result.failure(e)
        }
    }

    /**
     * Agrega un evento de llamada como mensaje visible en el chat
     */
    private fun addCallEventMessage(
        roomId: String,
        eventId: String,
        senderId: String,
        timestamp: Long,
        type: MessageType
    ) {
        val currentMessages = _messages.value[roomId] ?: emptyList()
        if (currentMessages.any { it.id == eventId }) return
        val label = when (type) {
            MessageType.CALL_INVITE -> "Llamada entrante"
            MessageType.CALL_ANSWER -> "Llamada respondida"
            MessageType.CALL_HANGUP -> "Llamada finalizada"
            else -> "Evento de llamada"
        }
        val callMsg = MatrixMessage(
            id = eventId,
            roomId = roomId,
            senderId = senderId,
            senderDisplayName = extractDisplayName(senderId),
            content = label,
            timestamp = timestamp,
            type = type
        )
        _messages.value = _messages.value + (roomId to (currentMessages + callMsg))
    }

    /**
     * Enviar ICE candidates via m.call.candidates
     */
    private suspend fun sendIceCandidates(roomId: String, callId: String, candidates: List<IceCandidate>) {
        try {
            val client = matrixClient ?: throw Exception("Not logged in")

            client.api.room.sendMessageEvent(
                roomId = RoomId(roomId),
                eventContent = CallEventContent.Candidates(
                    callId = callId,
                    version = CALL_VERSION,
                    candidates = candidates.map { candidate ->
                        CallEventContent.Candidates.Candidate(
                            candidate = candidate.candidate,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex.toLong()
                        )
                    }
                )
            )

            log.d(TAG) { "Sent ${candidates.size} ICE candidates for call $callId" }

        } catch (e: Exception) {
            log.e(TAG) { "Error sending ICE candidates: ${e.message}" }
        }
    }

    /**
     * Sube un archivo binario y envía el `m.room.message` correspondiente para
     * que el receptor lo vea. Antes esta función subía el archivo pero NO
     * publicaba el evento, así que el upload era invisible para el otro lado.
     *
     * El msgtype se elige por el `mimeType`:
     *   - image/...  → `m.image`
     *   - video/...  → `m.video`
     *   - audio/...  → `m.audio`
     *   - resto      → `m.file`
     *
     * (Nota: la barra-asterisco juntos abren un sub-comentario en Kotlin
     * porque permite block-comments anidados; por eso usamos `/...`)
     *
     * También inserta un mensaje optimista en el cache para feedback inmediato
     * en la UI (con un placeholder textual descriptivo). Cuando llegue el eco
     * del server vía sync, se deduplica por timestamp+sender.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun sendFile(
        roomId: String,
        fileData: ByteArray,
        mimeType: String,
        fileName: String,
    ): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")
            val myUserId = client.userId.full
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

            // Placeholder local mientras se sube y publica
            val displayLabel = when {
                mimeType.startsWith("image/") -> "🖼️ $fileName"
                mimeType.startsWith("video/") -> "🎬 $fileName"
                mimeType.startsWith("audio/") -> "🎵 $fileName"
                else -> "📎 $fileName"
            }
            val tempId = "local_${now}"
            val msgType = when {
                mimeType.startsWith("image/") -> MessageType.IMAGE
                mimeType.startsWith("video/") -> MessageType.VIDEO
                mimeType.startsWith("audio/") -> MessageType.AUDIO
                else -> MessageType.FILE
            }
            val optimistic = MatrixMessage(
                id = tempId,
                roomId = roomId,
                senderId = myUserId,
                senderDisplayName = extractDisplayName(myUserId),
                content = displayLabel,
                timestamp = now,
                type = msgType,
                fileName = fileName,
            )
            val current = _messages.value[roomId] ?: emptyList()
            _messages.value = _messages.value + (roomId to (current + optimistic))

            // Usar la DSL canónica de Trixnity: sendMessage + builder.
            // Trixnity hace internamente el upload de media a `mxc://` + envío
            // del evento m.room.message con msgtype correcto. Esto evita tocar
            // RoomMessageEventContent.FileBased.* y media.upload a pelo (que
            // tienen firmas frágiles entre versiones de la librería).
            val bytesFlow = fileData.toByteArrayFlow()
            val ktorType = try {
                io.ktor.http.ContentType.parse(mimeType)
            } catch (_: Throwable) {
                io.ktor.http.ContentType.Application.OctetStream
            }
            val sizeLong = fileData.size.toLong()

            client.room.sendMessage(roomId = RoomId(roomId)) {
                when (msgType) {
                    MessageType.IMAGE -> image(
                        body = fileName,
                        image = bytesFlow,
                        type = ktorType,
                        size = sizeLong,
                    )
                    MessageType.VIDEO -> video(
                        body = fileName,
                        video = bytesFlow,
                        type = ktorType,
                        size = sizeLong,
                    )
                    MessageType.AUDIO -> audio(
                        body = fileName,
                        audio = bytesFlow,
                        type = ktorType,
                        size = sizeLong,
                    )
                    else -> file(
                        body = fileName,
                        file = bytesFlow,
                        type = ktorType,
                        size = sizeLong,
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG) { "Error sending file: ${e.message}" }
            Result.failure(e)
        }
    }

    private fun generateCallId(): String {
        return "mcall_${generateId()}"
    }

    fun dispose() {
        scope.cancel()
        webRtcManager.closePeerConnection()
        matrixClient = null
    }
}

/**
 * Modelo interno para ICE candidates
 */
data class IceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)
