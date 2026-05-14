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
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.room.message.video
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.utils.toByteArrayFlow
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.Membership
import net.folivo.trixnity.core.model.events.m.call.CallEventContent
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.generateId
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.media.Media
import kotlin.time.Duration.Companion.seconds


class MatrixManager(
    private val config: MatrixConfig,
    private val webRtcManager: WebRtcManager
) {
    private var matrixClient: MatrixClient? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val TAG = "MatrixManager"
    private val CALL_VERSION = "1"
    private val CALL_LIFETIME = 60000L // 60 segundos para que expire el invite
    private var storedAccessToken: String? = null
    private var storedUserId: String? = null

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

    private val _activeCall = MutableStateFlow<MatrixCall?>(null)
    val activeCall: StateFlow<MatrixCall?> = _activeCall.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<MatrixMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<MatrixMessage>>> = _messages.asStateFlow()

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
     * Login con password. homeserverOverride permite cambiar el servidor sin
     * recrear el MatrixManager (util cuando el usuario escribe su propio homeserver).
     */
    suspend fun login(userId: String, password: String, homeserverOverride: String? = null): Result<Unit> {
        return try {
            log.d { "Intentando login para el usuario: $userId" }

            _connectionState.value = MatrixConnectionState.Connecting
            log.d { "Estado de conexion: Connecting..." }

            // Usar persistencia para media (Okio) — los archivos descargados sobreviven al
            // restart de la app. Repositorios (sync state, rooms, events) siguen in-memory
            // hasta que se active Room KMP de Trixnity en una iteración posterior.
            val (reposModule, mediaModule) = MatrixModuleFactory.createPersistentModules(
                matrixStoragePath()
            )
            log.d { "Modulos de repositorios y media store creados" }

            val baseUrlStr = homeserverOverride?.takeIf { it.isNotBlank() } ?: config.homeserverUrl

            // Crear cliente Matrix usando la API correcta
            val loginResult = MatrixClient.loginWithPassword(
                baseUrl = Url(baseUrlStr),
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
                log.d { "Login exitoso para $userId (resolved: ${storedUserId})" }

                // Iniciar sincronizacion
                client.startSync()
                _connectionState.value = MatrixConnectionState.Connected
                log.d { "Sincronizacion iniciada, estado: Connected" }

                observeMatrixChanges()
                setupWebRtcListener()
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
                    client.startSync()
                    _connectionState.value = MatrixConnectionState.Connected
                    observeMatrixChanges()
                    setupWebRtcListener()
                    log.d { "Login from store successful" }
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

    /**
     * Pausa el long-polling de sync. Útil para llamar desde un lifecycle hook
     * cuando la app pasa a background: ahorra batería + bandwidth y evita
     * mantener conexiones abiertas innecesariamente.
     *
     * Idempotente: si no hay cliente o ya está pausado, no hace nada.
     */
    suspend fun pauseSync() {
        val client = matrixClient ?: return
        try {
            log.d(TAG) { "Pausing Matrix sync (background)" }
            client.stopSync()
        } catch (e: Exception) {
            log.w(TAG) { "pauseSync failed: ${e.message}" }
        }
    }

    /**
     * Reanuda el long-polling de sync. Llamar cuando la app vuelve a foreground.
     * Idempotente: si ya está corriendo Trixnity ignora la segunda llamada.
     */
    suspend fun resumeSync() {
        val client = matrixClient ?: return
        try {
            log.d(TAG) { "Resuming Matrix sync (foreground)" }
            client.startSync()
        } catch (e: Exception) {
            log.w(TAG) { "resumeSync failed: ${e.message}" }
        }
    }

    /**
     * Logout
     */
    suspend fun logout() {
        try {
            log.d { "Logging out from Matrix" }

            matrixClient?.logout()
            matrixClient = null

            _connectionState.value = MatrixConnectionState.Disconnected
            _rooms.value = emptyList()
            _activeCall.value = null
            _messages.value = emptyMap()

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
     * Observa cambios en Matrix (rooms, mensajes, eventos de llamada)
     */
    private fun observeMatrixChanges() {
        val client = matrixClient ?: return

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
                        roomFlow.map { room ->
                            room?.let {
                                // Auto-join: si recibimos una invitación, aceptarla
                                // automáticamente. Sin esto, sendTextMessage falla con
                                // "missing permissions" porque el room está en estado
                                // INVITED, no JOINED. Element acepta automáticamente
                                // las invitaciones de DM; replicamos ese comportamiento.
                                if (it.membership == Membership.INVITE) {
                                    autoJoinInvitedRoom(roomId.full)
                                }

                                // Si ya estamos joineados, cargar el timeline persistido del
                                // store. Idempotente: solo carga la primera vez por sesión.
                                if (it.membership == Membership.JOIN) {
                                    loadHistoricalTimeline(roomId.full)
                                }

                                // Resolver nombre de sala correctamente.
                                // Para DMs sin nombre explícito usamos heroes del room
                                // (los otros miembros). Esto evita que la UI muestre
                                // el room ID en bruto ("tuoDDUkoEHPEnDNtZj") y muestre
                                // el display name del otro participante.
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
                                // Calcular ultimo mensaje y timestamp desde el cache de mensajes
                                val roomMessages = _messages.value[roomId.full]
                                val lastMsg = roomMessages?.lastOrNull()
                                // Avatar mxc:// → URL HTTPS (con token si está disponible).
                                // null si el room no tiene avatar — la UI muestra placeholder.
                                val avatarHttp = it.avatarUrl
                                    ?.takeIf { mxc -> mxc.startsWith("mxc://") }
                                    ?.let { mxc -> resolveMxcToHttpUrl(mxc) }

                                MatrixRoom(
                                    id = roomId.full,
                                    name = resolvedName,
                                    avatarUrl = avatarHttp,
                                    isDirect = it.isDirect,
                                    isEncrypted = false,
                                    unreadCount = 0,
                                    lastMessage = lastMsg?.content,
                                    lastMessageTime = lastMsg?.timestamp,
                                )
                            }
                        }
                    }
                    combine(roomFlows) { rooms ->
                        rooms.filterNotNull()
                    }.collect { roomsList ->
                        _rooms.value = roomsList
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

                        when (content) {
                            // Manejar eventos de llamada Matrix (solo de otros usuarios)
                            is CallEventContent.Invite -> {
                                if (senderId != myUserId) {
                                    handleCallInvite(eventRoomId, senderId, content)
                                    // Mostrar evento de llamada en el chat
                                    addCallEventMessage(eventRoomId, event.id.full, senderId, event.originTimestamp, MessageType.CALL_INVITE)
                                }
                            }
                            is CallEventContent.Answer -> {
                                if (senderId != myUserId) {
                                    handleCallAnswer(eventRoomId, senderId, content)
                                    addCallEventMessage(eventRoomId, event.id.full, senderId, event.originTimestamp, MessageType.CALL_ANSWER)
                                }
                            }
                            is CallEventContent.Hangup -> {
                                if (senderId != myUserId) {
                                    handleCallHangup(eventRoomId, senderId, content)
                                    addCallEventMessage(eventRoomId, event.id.full, senderId, event.originTimestamp, MessageType.CALL_HANGUP)
                                }
                            }
                            is CallEventContent.Candidates -> {
                                if (senderId != myUserId) {
                                    handleCallCandidates(eventRoomId, senderId, content)
                                }
                            }
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

            val callId = content.callId
            val sdp = content.offer.sdp

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
            val callData = CallData(
                callId = callId,
                from = senderId,
                to = matrixClient?.userId?.full ?: "",
                direction = CallDirections.INCOMING,
                startTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                callType = CallType.MATRIX_INTERNAL,
                roomId = roomId,
                remoteSdp = sdp
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
     * Branch por tipo concreto de RoomMessageEventContent. Los TextBased se
     * tratan como TEXT; los FileBased (Image/Video/Audio/File) se procesan
     * con su mxc URL resuelta a HTTPS para que la UI pueda mostrarla.
     */
    private fun processRoomMessageContent(
        roomId: String,
        eventId: String,
        senderId: String,
        timestamp: Long,
        content: RoomMessageEventContent,
    ) {
        when (content) {
            is RoomMessageEventContent.FileBased.Image ->
                upsertMediaMessage(roomId, eventId, senderId, timestamp,
                    type = MessageType.IMAGE, mxcUrl = content.url, fileName = content.body)
            is RoomMessageEventContent.FileBased.Video ->
                upsertMediaMessage(roomId, eventId, senderId, timestamp,
                    type = MessageType.VIDEO, mxcUrl = content.url, fileName = content.body)
            is RoomMessageEventContent.FileBased.Audio ->
                upsertMediaMessage(roomId, eventId, senderId, timestamp,
                    type = MessageType.AUDIO, mxcUrl = content.url, fileName = content.body)
            is RoomMessageEventContent.FileBased.File ->
                upsertMediaMessage(roomId, eventId, senderId, timestamp,
                    type = MessageType.FILE, mxcUrl = content.url, fileName = content.body)
            else -> {
                // TextBased y cualquier otro tipo de body textual
                upsertTextMessage(roomId, eventId, senderId, content.body, timestamp)
            }
        }
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
            fileName = fileName,
        )

        // Reemplazar optimista local (igual que upsertTextMessage)
        val replaceIdx = current.indexOfFirst { existing ->
            existing.id.startsWith("local_") &&
                existing.senderId == senderId &&
                existing.type == type &&
                existing.fileName == fileName &&
                kotlin.math.abs(existing.timestamp - timestamp) < 30_000L
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
            type = MessageType.TEXT,
        )

        // 2) Buscar optimista local del mismo sender+content+timestamp cercano
        val replaceIdx = current.indexOfFirst { existing ->
            existing.id.startsWith("local_") &&
                existing.senderId == senderId &&
                existing.content == body &&
                kotlin.math.abs(existing.timestamp - timestamp) < 10_000L
        }

        val updated = if (replaceIdx >= 0) {
            current.toMutableList().apply { set(replaceIdx, newMessage) }
        } else {
            // 3) Insertar manteniendo orden cronológico (mayor timestamp al final)
            val combined = (current + newMessage).sortedBy { it.timestamp }
            combined
        }
        _messages.value = _messages.value + (roomId to updated)
    }

    // Cache de rooms cuyo timeline histórico ya cargamos en esta sesión, para
    // no relanzar la carga cada vez que el observer re-emite.
    private val historicalLoaded = mutableSetOf<String>()

    /**
     * Carga los últimos N eventos persistidos del store de Trixnity para una
     * room y los inyecta en `_messages` vía `upsertTextMessage`. Esto permite
     * que al re-loguear o al entrar a una room por primera vez en la sesión
     * el usuario vea sus propios mensajes anteriores y los recibidos antes
     * de subscribirse al sync.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun loadHistoricalTimeline(roomId: String, limit: Int = 50) {
        if (!historicalLoaded.add(roomId)) return
        val client = matrixClient ?: return
        scope.launch {
            try {
                val rid = RoomId(roomId)
                val last = client.room.getLastTimelineEvent(rid)
                    .firstOrNull()
                    ?.firstOrNull()
                    ?: return@launch

                val history = mutableListOf(last)
                var current: net.folivo.trixnity.client.store.TimelineEvent? = last
                while (history.size < limit) {
                    val prevFlow = current?.let { client.room.getPreviousTimelineEvent(it) }
                        ?: break
                    val prev = prevFlow.firstOrNull() ?: break
                    history.add(prev)
                    current = prev
                }
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
     * Iniciar llamada de voz - envia m.call.invite
     *
     * @deprecated Matrix calls están deshabilitadas. Las llamadas reales usan
     * el módulo de conferencias / LiveKit. Esta función retorna early con
     * Result.failure si `config.enableVoip = false` (que es el default).
     */
    @Deprecated(
        message = "Matrix calls deshabilitadas. Usa el módulo de conferencias/LiveKit.",
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun startVoiceCall(roomId: String): Result<MatrixCall> {
        if (!config.enableVoip) {
            log.w(TAG) { "startVoiceCall: bloqueado, MatrixConfig.enableVoip=false (chat-only)" }
            return Result.failure(IllegalStateException("Matrix VoIP disabled by config"))
        }
        return try {
            log.d { "Starting Matrix voice call in room: $roomId" }

            val client = matrixClient ?: throw Exception("Not logged in to Matrix")
            val myUserId = client.userId.full

            // Inicializar WebRTC y crear oferta
            webRtcManager.initialize()
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
            val callData = CallData(
                callId = callId,
                from = myUserId,
                to = roomId,
                direction = CallDirections.OUTGOING,
                startTime = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                callType = CallType.MATRIX_INTERNAL,
                roomId = roomId,
                localSdp = offerSdp
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
     * Iniciar videollamada - envia m.call.invite con video
     *
     * @deprecated Matrix video calls están deshabilitadas. Usa conference/LiveKit.
     */
    @Deprecated(
        message = "Matrix video calls deshabilitadas. Usa el módulo de conferencias/LiveKit.",
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    suspend fun startVideoCall(roomId: String): Result<MatrixCall> {
        if (!config.enableVideo) {
            log.w(TAG) { "startVideoCall: bloqueado, MatrixConfig.enableVideo=false (chat-only)" }
            return Result.failure(IllegalStateException("Matrix video disabled by config"))
        }
        return try {
            log.d { "Starting Matrix video call in room: $roomId" }

            val client = matrixClient ?: throw Exception("Not logged in to Matrix")

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

            // Enviar m.call.invite
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

            log.d(TAG) { "m.call.invite (video) sent for call $callId" }
            Result.success(call)

        } catch (e: Exception) {
            log.e(TAG, { "Error starting video call: $e" })
            _activeCall.value = _activeCall.value?.copy(state = MatrixCallState.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Responder llamada - envia m.call.answer
     *
     * @deprecated Matrix calls deshabilitadas. Usa conference/LiveKit.
     */
    @Deprecated(
        message = "Matrix calls deshabilitadas. Usa el módulo de conferencias/LiveKit.",
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
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
            // Inicializar WebRTC antes de crear la respuesta (necesario para peer connection)
            webRtcManager.initialize()
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
     * Colgar llamada - envia m.call.hangup
     *
     * @deprecated Matrix calls deshabilitadas. Conservada para cleanup defensivo
     * por si alguna versión anterior dejó una llamada activa.
     */
    @Deprecated(
        message = "Matrix calls deshabilitadas. Usa el módulo de conferencias/LiveKit.",
        level = DeprecationLevel.WARNING
    )
    @Suppress("DEPRECATION")
    suspend fun hangupCall(callId: String): Result<Unit> {
        // Excepción al gate: hangupCall siempre se permite por cleanup defensivo —
        // si la app encuentra una _activeCall heredada queremos poder terminarla.
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
     *   - image/* → `m.image`
     *   - video/* → `m.video`
     *   - audio/* → `m.audio`
     *   - resto → `m.file`
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
