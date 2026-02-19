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
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.model.events.m.call.CallEventContent
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.generateId
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.media.Media
import net.folivo.trixnity.client.store.repository.createInMemoryRepositoriesModule
import net.folivo.trixnity.client.media.createInMemoryMediaStoreModule
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

            val reposModule = createInMemoryRepositoriesModule()
            val mediaModule = createInMemoryMediaStoreModule()
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
            val reposModule = createInMemoryRepositoriesModule()
            val mediaModule = createInMemoryMediaStoreModule()

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
                    val roomFlows: List<Flow<MatrixRoom?>> = roomsMap.entries.map { (roomId, roomFlow) ->
                        roomFlow.map { room ->
                            room?.let {
                                MatrixRoom(
                                    id = roomId.full,
                                    name = it.name?.explicitName ?: it.name.toString().takeIf { n -> n.isNotBlank() } ?: "Sin nombre",
                                    avatarUrl = null,
                                    isDirect = it.isDirect,
                                    isEncrypted = false,
                                    unreadCount = 0
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
                            // Manejar mensajes de texto de OTROS usuarios
                            // (los propios aparecen via actualización optimista en sendTextMessage)
                            is RoomMessageEventContent -> {
                                if (senderId != myUserId) {
                                    val currentMessages = _messages.value[eventRoomId] ?: emptyList()
                                    // Evitar duplicados comparando ID del evento
                                    val alreadyExists = currentMessages.any { it.id == event.id.full }
                                    if (!alreadyExists) {
                                        val newMessage = MatrixMessage(
                                            id = event.id.full,
                                            roomId = eventRoomId,
                                            senderId = senderId,
                                            senderDisplayName = extractDisplayName(senderId),
                                            content = content.body,
                                            timestamp = event.originTimestamp,
                                            type = MessageType.TEXT
                                        )
                                        _messages.value = _messages.value + (eventRoomId to (currentMessages + newMessage))
                                    }
                                }
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
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun startVoiceCall(roomId: String): Result<MatrixCall> {
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
     */
    suspend fun startVideoCall(roomId: String): Result<MatrixCall> {
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
     */
    suspend fun answerCall(callId: String): Result<Unit> {
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
     * Subir archivo
     */
    suspend fun sendFile(
        roomId: String,
        fileData: ByteArray,
        mimeType: String,
        fileName: String
    ): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")

            val media = Media(
                content = io.ktor.utils.io.ByteReadChannel(fileData),
                contentLength = fileData.size.toLong(),
                contentType = io.ktor.http.ContentType.parse(mimeType),
                contentDisposition = io.ktor.http.ContentDisposition.Attachment.withParameter(
                    io.ktor.http.ContentDisposition.Parameters.FileName,
                    fileName
                )
            )

            val uploadResult = client.api.media.upload(
                media = media
            )

            uploadResult.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { error ->
                    log.e(TAG, { "Error uploading file: $error" })
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            log.e(TAG, { "Error sending file: $e" })
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
