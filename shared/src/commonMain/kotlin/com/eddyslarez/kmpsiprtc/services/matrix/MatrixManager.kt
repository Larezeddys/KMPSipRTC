package com.eddyslarez.kmpsiprtc.services.matrix

import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.folivo.trixnity.client.*
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.RoomId
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.utils.generateId
import io.ktor.http.Url
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.media.Media
import org.koin.core.module.Module
import kotlin.time.Duration.Companion.seconds
import net.folivo.trixnity.client.store.repository.createInMemoryRepositoriesModule
import net.folivo.trixnity.client.media.createInMemoryMediaStoreModule


class MatrixManager(
    private val config: MatrixConfig,
    private val webRtcManager: WebRtcManager
) {
    private var matrixClient: MatrixClient? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())



    private val TAG = "MatrixManager"

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
     * Login con password
     * IMPORTANTE: Antes de llamar este método, debes configurar:
     * - repositoriesModule
     * - mediaStoreModule
     */
    /**
     * Login con password
     * IMPORTANTE: Antes de llamar este método, debes configurar:
     * - repositoriesModule
     * - mediaStoreModule
     */
    suspend fun login(userId: String, password: String): Result<Unit> {
        return try {
            log.d { "🔑 Intentando login para el usuario: $userId" }

            _connectionState.value = MatrixConnectionState.Connecting
            log.d { "🌐 Estado de conexión: Connecting..." }

            val reposModule = createInMemoryRepositoriesModule()
            val mediaModule = createInMemoryMediaStoreModule()
            log.d { "📦 Módulos de repositorios y media store creados" }

            // Crear cliente Matrix usando la API correcta
            val loginResult = MatrixClient.loginWithPassword(
                baseUrl = Url(config.homeserverUrl),
                identifier = IdentifierType.User(userId),
                password = password,
                deviceId = null, // null para generar uno nuevo
                initialDeviceDisplayName = config.deviceDisplayName,
                repositoriesModule = reposModule, // Ahora es non-null
                mediaStoreModule = mediaModule,   // Ahora es non-null
                configuration = {
                    // Configuración opcional
                    syncLoopTimeout = config.syncTimeout.seconds
                }
            )

            loginResult.onSuccess { client ->
                matrixClient = client
                log.d { "✅ Login exitoso para $userId" }

                // Iniciar sincronización
                client.startSync()
                _connectionState.value = MatrixConnectionState.Connected
                log.d { "🔄 Sincronización iniciada, estado: Connected" }

                observeMatrixChanges()
                log.d { "👀 Observando cambios de Matrix..." }
            }.onFailure { error ->
                log.e(TAG) { "❌ Login fallido: $error" }
                _connectionState.value = MatrixConnectionState.Error(error.message ?: "Unknown error")
                return Result.failure(error)
            }

            Result.success(Unit)

        } catch (e: Exception) {
            log.e(TAG) { "⚠️ Login fallido con excepción: $e" }
            _connectionState.value = MatrixConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }


    /**
     * Login desde almacenamiento persistente (para reconexión automática)
     */
    suspend fun loginFromStore(): Result<Unit> {
        return try {
            log.d { "Attempting login from store" }

            _connectionState.value = MatrixConnectionState.Connecting
            val reposModule = createInMemoryRepositoriesModule()
            val mediaModule = createInMemoryMediaStoreModule()


            // Intentar recuperar sesión desde el almacenamiento
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
     * Observa cambios en Matrix
     */
    private fun observeMatrixChanges() {
        val client = matrixClient ?: return

        // Observar rooms
        scope.launch {
            // TODO: Implementar observación de rooms
        }

        // Observar mensajes
        scope.launch {
            // TODO: Implementar observación de mensajes
        }

        // Observar eventos de llamadas
        scope.launch {
            observeCallEvents()
        }
    }

    /**
     * Observa eventos de llamadas Matrix
     */
    private suspend fun observeCallEvents() {
        // TODO: Implementar manejo de eventos m.call.*
    }

    /**
     * Enviar mensaje de texto
     */
    suspend fun sendTextMessage(roomId: String, message: String): Result<Unit> {
        return try {
            val client = matrixClient ?: throw Exception("Not logged in")

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

            // Usar la API correcta para crear rooms
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
     * Iniciar llamada de voz
     */
    suspend fun startVoiceCall(roomId: String): Result<MatrixCall> {
        return try {
            log.d { "Starting Matrix voice call in room: $roomId" }

            val offerSdp = webRtcManager.createOffer()
            val callId = generateCallId()

            val call = MatrixCall(
                callId = callId,
                roomId = roomId,
                isVideo = false,
                state = MatrixCallState.INVITING
            )

            // TODO: Enviar m.call.invite

            _activeCall.value = call
            Result.success(call)
        } catch (e: Exception) {
            log.e(TAG, { "Error starting voice call: $e" })
            Result.failure(e)
        }
    }

    /**
     * Iniciar videollamada
     */
    suspend fun startVideoCall(roomId: String): Result<MatrixCall> {
        return try {
            log.d { "Starting Matrix video call in room: $roomId" }

            val offerSdp = webRtcManager.createOffer()
            val callId = generateCallId()

            val call = MatrixCall(
                callId = callId,
                roomId = roomId,
                isVideo = true,
                state = MatrixCallState.INVITING
            )

            // TODO: Enviar m.call.invite

            _activeCall.value = call
            Result.success(call)
        } catch (e: Exception) {
            log.e(TAG, { "Error starting video call: $e" })
            Result.failure(e)
        }
    }

    /**
     * Responder llamada
     */
    suspend fun answerCall(callId: String): Result<Unit> {
        return try {
            val call = _activeCall.value ?: throw Exception("No active call")

            val answerSdp = webRtcManager.createAnswer(call.remoteSdp ?: "")

            // TODO: Enviar m.call.answer

            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG, { "Error answering call: $e" })
            Result.failure(e)
        }
    }

    /**
     * Colgar llamada
     */
    suspend fun hangupCall(callId: String): Result<Unit> {
        return try {
            val call = _activeCall.value ?: throw Exception("No active call")

            // TODO: Enviar m.call.hangup

            _activeCall.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(TAG, { "Error hanging up: $e" })
            Result.failure(e)
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

            // Crear objeto Media con ByteReadChannel
            val media = Media(
                content = io.ktor.utils.io.ByteReadChannel(fileData),
                contentLength = fileData.size.toLong(),
                contentType = io.ktor.http.ContentType.parse(mimeType),
                contentDisposition = io.ktor.http.ContentDisposition.Attachment.withParameter(
                    io.ktor.http.ContentDisposition.Parameters.FileName,
                    fileName
                )
            )

            // Upload file usando la API correcta
            val uploadResult = client.api.media.upload(
                media = media
            )

            uploadResult.fold(
                onSuccess = { response ->
                    // Enviar mensaje con el archivo
                    // TODO: Implementar según tipo MIME (imagen, video, audio, archivo genérico)
                    // val mxcUri = response.contentUri
                    // client.room.sendMessage(RoomId(roomId)) {
                    //     image(mxcUri, fileName)
                    // }

                    Result.success(Unit)
                },
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
        matrixClient = null
    }
}
//import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcManager
//import io.ktor.http.*
//import io.ktor.utils.io.*
//import kotlinx.coroutines.*
//import kotlinx.coroutines.flow.*
//import net.folivo.trixnity.client.*
//import net.folivo.trixnity.client.room.message.text
//import net.folivo.trixnity.client.store.*
//import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
//import net.folivo.trixnity.core.model.UserId
//import net.folivo.trixnity.core.model.RoomId
//import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
//import com.eddyslarez.kmpsiprtc.platform.log
//import com.eddyslarez.kmpsiprtc.utils.generateId
//import kotlin.time.Duration.Companion.seconds
//
//class MatrixManager(
//    private val config: MatrixConfig,
//    private val webRtcManager: WebRtcManager
//) {
//    private var matrixClient: MatrixClient? = null
//    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
//
//    private val TAG = "MatrixManager"
//
//    // Los módulos deben ser proporcionados externamente
//    var repositoriesModule: org.koin.core.module.Module? = null
//    var mediaStoreModule: org.koin.core.module.Module? = null
//
//    // Estados observables
//    private val _connectionState = MutableStateFlow<MatrixConnectionState>(
//        MatrixConnectionState.Disconnected
//    )
//    val connectionState: StateFlow<MatrixConnectionState> = _connectionState.asStateFlow()
//
//    private val _rooms = MutableStateFlow<List<MatrixRoom>>(emptyList())
//    val rooms: StateFlow<List<MatrixRoom>> = _rooms.asStateFlow()
//
//    private val _activeCall = MutableStateFlow<MatrixCall?>(null)
//    val activeCall: StateFlow<MatrixCall?> = _activeCall.asStateFlow()
//
//    private val _messages = MutableStateFlow<Map<String, List<MatrixMessage>>>(emptyMap())
//    val messages: StateFlow<Map<String, List<MatrixMessage>>> = _messages.asStateFlow()
//
//    /**
//     * Inicializa el cliente Matrix
//     */
//    suspend fun initialize() {
//        try {
//            log.d { "Initializing Matrix client..." }
//            _connectionState.value = MatrixConnectionState.Initialized
//            log.d { "Matrix manager initialized" }
//        } catch (e: Exception) {
//            log.e(TAG, { "Error initializing Matrix $e" })
//            throw e
//        }
//    }
//
//    /**
//     * Login con password
//     */
//    suspend fun login(userId: String, password: String): Result<Unit> {
//        return try {
//            log.d { "Attempting login for user: $userId" }
//
//            val repos = repositoriesModule ?: throw Exception("repositoriesModule not set")
//            val media = mediaStoreModule ?: throw Exception("mediaStoreModule not set")
//
//            _connectionState.value = MatrixConnectionState.Connecting
//
//            // Crear cliente Matrix usando la API correcta
//            val loginResult = MatrixClient.loginWithPassword(
//                baseUrl = Url(config.homeserverUrl),
//                identifier = IdentifierType.User(userId),
//                password = password,
//                initialDeviceDisplayName = config.deviceDisplayName,
//                repositoriesModule = repos,
//                mediaStoreModule = media,
//                configuration = {
//                    // Configuración del timeout para sync
//                    syncLoopTimeout = config.syncTimeout
//                }
//            )
//
//            loginResult.onSuccess { client ->
//                matrixClient = client
//
//                // Iniciar sincronización
//                client.startSync()
//                _connectionState.value = MatrixConnectionState.Connected
//
//                observeMatrixChanges()
//
//                log.d { "Login successful" }
//            }.onFailure { error ->
//                log.e(TAG, { "Login failed: $error" })
//                _connectionState.value = MatrixConnectionState.Error(error.message ?: "Unknown error")
//                return Result.failure(error)
//            }
//
//            Result.success(Unit)
//
//        } catch (e: Exception) {
//            log.e(TAG, { "Login failed $e" })
//            _connectionState.value = MatrixConnectionState.Error(e.message ?: "Unknown error")
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Logout
//     */
//    suspend fun logout() {
//        try {
//            log.d { "Logging out from Matrix" }
//
//            matrixClient?.logout()
//            matrixClient = null
//
//            _connectionState.value = MatrixConnectionState.Disconnected
//            _rooms.value = emptyList()
//            _activeCall.value = null
//            _messages.value = emptyMap()
//
//        } catch (e: Exception) {
//            log.e(TAG, { "Logout error $e" })
//        }
//    }
//
//    /**
//     * Observa cambios en Matrix
//     */
//    private fun observeMatrixChanges() {
//        val client = matrixClient ?: return
//
//        // Observar rooms
//        scope.launch {
//            client.room.getAll().collect { roomsMap ->
//                val roomsList = mutableListOf<MatrixRoom>()
//
//                roomsMap.forEach { (roomId, roomFlow) ->
//                    roomFlow.collect { room ->
//                        if (room != null) {
//                            roomsList.add(
//                                MatrixRoom(
//                                    id = roomId.full,
//                                    name = room.displayName ?: "Unnamed Room",
//                                    isDirect = room.isDirect
//                                )
//                            )
//                        }
//                    }
//                }
//
//                _rooms.value = roomsList
//            }
//        }
//
//        // Observar mensajes usando timeline events
//        scope.launch {
//            client.room.getTimelineEventsFromNowOn(
//                decryptionTimeout = 30.seconds
//            ).collect { timelineEvent ->
//                val roomId = timelineEvent.roomId.full
//                val event = timelineEvent.event
//
//                if (event is net.folivo.trixnity.core.model.events.ClientEvent.RoomEvent.MessageEvent) {
//                    val content = timelineEvent.content?.getOrNull()
//                    if (content is RoomMessageEventContent) {
//                        val currentMessages = _messages.value[roomId] ?: emptyList()
//                        val newMessage = MatrixMessage(
//                            id = event.id.full,
//                            roomId = roomId,
//                            senderId = event.sender.full,
//                            content = content.body,
//                            timestamp = event.originTimestamp.toEpochMilliseconds()
//                        )
//
//                        _messages.value = _messages.value + (roomId to (currentMessages + newMessage))
//                    }
//                }
//            }
//        }
//
//        // Observar eventos de llamadas
//        scope.launch {
//            observeCallEvents()
//        }
//    }
//
//    /**
//     * Observa eventos de llamadas Matrix
//     */
//    private suspend fun observeCallEvents() {
//        // TODO: Implementar manejo de eventos m.call.*
//        // Necesitarás suscribirte a eventos específicos del tipo m.call.invite, m.call.answer, etc.
//    }
//
//    /**
//     * Enviar mensaje de texto
//     */
//    suspend fun sendTextMessage(roomId: String, message: String): Result<Unit> {
//        return try {
//            val client = matrixClient ?: throw Exception("Not logged in")
//
//            client.room.sendMessage(RoomId(roomId)) {
//                text(message)
//            }
//
//            Result.success(Unit)
//        } catch (e: Exception) {
//            log.e(TAG, { "Error sending message $e" })
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Crear sala nueva
//     */
//    suspend fun createRoom(
//        name: String,
//        isDirect: Boolean = false,
//        inviteUserIds: List<String> = emptyList()
//    ): Result<String> {
//        return try {
//            val client = matrixClient ?: throw Exception("Not logged in")
//
//            // Usar la API correcta para crear rooms
//            val createRoomResult = client.api.room.createRoom(
//                name = name,
//                isDirect = isDirect,
//                invite = inviteUserIds.map { UserId(it) }.toSet()
//            )
//
//            createRoomResult.fold(
//                onSuccess = { roomId ->
//                    Result.success(roomId.full)
//                },
//                onFailure = { error ->
//                    log.e(TAG, { "Error creating room: $error" })
//                    Result.failure(error)
//                }
//            )
//        } catch (e: Exception) {
//            log.e(TAG, { "Error creating room $e" })
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Iniciar llamada de voz
//     */
//    suspend fun startVoiceCall(roomId: String): Result<MatrixCall> {
//        return try {
//            log.d { "Starting Matrix voice call in room: $roomId" }
//
//            val offerSdp = webRtcManager.createOffer()
//            val callId = generateCallId()
//
//            val call = MatrixCall(
//                callId = callId,
//                roomId = roomId,
//                isVideo = false,
//                state = MatrixCallState.INVITING
//            )
//
//            // TODO: Enviar m.call.invite usando client.api.room.sendStateEvent o sendMessageEvent
//            // Ejemplo:
//            // client.api.room.sendMessageEvent(
//            //     roomId = RoomId(roomId),
//            //     eventContent = CallInviteEventContent(
//            //         callId = callId,
//            //         offer = offerSdp,
//            //         version = "1"
//            //     )
//            // )
//
//            _activeCall.value = call
//            Result.success(call)
//        } catch (e: Exception) {
//            log.e(TAG, { "Error starting voice call $e" })
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Iniciar videollamada
//     */
//    suspend fun startVideoCall(roomId: String): Result<MatrixCall> {
//        return try {
//            log.d { "Starting Matrix video call in room: $roomId" }
//
//            val offerSdp = webRtcManager.createOffer()
//            val callId = generateCallId()
//
//            val call = MatrixCall(
//                callId = callId,
//                roomId = roomId,
//                isVideo = true,
//                state = MatrixCallState.INVITING
//            )
//
//            // TODO: Enviar m.call.invite
//
//            _activeCall.value = call
//            Result.success(call)
//        } catch (e: Exception) {
//            log.e(TAG, { "Error starting video call $e" })
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Responder llamada
//     */
//    suspend fun answerCall(callId: String): Result<Unit> {
//        return try {
//            val call = _activeCall.value ?: throw Exception("No active call")
//
//            val answerSdp = webRtcManager.createAnswer(call.remoteSdp ?: "")
//
//            // TODO: Enviar m.call.answer
//
//            Result.success(Unit)
//        } catch (e: Exception) {
//            log.e(TAG, { "Error answering call $e" })
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Colgar llamada
//     */
//    suspend fun hangupCall(callId: String): Result<Unit> {
//        return try {
//            val call = _activeCall.value ?: throw Exception("No active call")
//
//            // TODO: Enviar m.call.hangup
//
//            _activeCall.value = null
//            Result.success(Unit)
//        } catch (e: Exception) {
//            log.e(TAG, { "Error hanging up $e" })
//            Result.failure(e)
//        }
//    }
//
//    /**
//     * Subir archivo
//     */
//    suspend fun sendFile(
//        roomId: String,
//        fileData: ByteArray,
//        mimeType: String,
//        fileName: String
//    ): Result<Unit> {
//        return try {
//            val client = matrixClient ?: throw Exception("Not logged in")
//
//            // Crear objeto Media con ByteReadChannel
//            val media = net.folivo.trixnity.clientserverapi.model.media.Media(
//                content = io.ktor.utils.io.ByteReadChannel(fileData),
//                contentLength = fileData.size.toLong(),
//                contentType = io.ktor.http.ContentType.parse(mimeType),
//                contentDisposition = io.ktor.http.ContentDisposition.Attachment.withParameter(
//                    io.ktor.http.ContentDisposition.Parameters.FileName,
//                    fileName
//                )
//            )
//
//            // Upload file usando la API correcta
//            val uploadResult = client.api.media.upload(
//                media = media
//            )
//
//            uploadResult.fold(
//                onSuccess = { response ->
//                    // Enviar mensaje con el archivo
//                    // TODO: Implementar según tipo MIME (imagen, video, audio, archivo genérico)
//                    // val mxcUri = response.contentUri
//                    // client.room.sendMessage(RoomId(roomId)) {
//                    //     image(mxcUri, fileName)
//                    // }
//
//                    Result.success(Unit)
//                },
//                onFailure = { error ->
//                    log.e(TAG, { "Error uploading file: $error" })
//                    Result.failure(error)
//                }
//            )
//        } catch (e: Exception) {
//            log.e(TAG, { "Error sending file: $e" })
//            Result.failure(e)
//        }
//    }
//
//    private fun generateCallId(): String {
//        return "mcall_${generateId()}"
//    }
//
//    fun dispose() {
//        scope.cancel()
//        matrixClient = null
//    }
//}
//
//// Clases de datos necesarias (si no las tienes ya definidas)
//data class MatrixConfig(
//    val homeserverUrl: String,
//    val deviceDisplayName: String = "KMP SIP RTC Client",
//    val syncTimeout: kotlin.time.Duration = 30.seconds
//)
//
//sealed class MatrixConnectionState {
//    object Disconnected : MatrixConnectionState()
//    object Initialized : MatrixConnectionState()
//    object Connecting : MatrixConnectionState()
//    object Connected : MatrixConnectionState()
//    data class Error(val message: String) : MatrixConnectionState()
//}
//
//data class MatrixRoom(
//    val id: String,
//    val name: String,
//    val isDirect: Boolean
//)
//
//data class MatrixCall(
//    val callId: String,
//    val roomId: String,
//    val isVideo: Boolean,
//    val state: MatrixCallState,
//    val remoteSdp: String? = null
//)
//
//enum class MatrixCallState {
//    INVITING,
//    RINGING,
//    CONNECTED,
//    ENDED
//}
//
//data class MatrixMessage(
//    val id: String,
//    val roomId: String,
//    val senderId: String,
//    val content: String,
//    val timestamp: Long
//)