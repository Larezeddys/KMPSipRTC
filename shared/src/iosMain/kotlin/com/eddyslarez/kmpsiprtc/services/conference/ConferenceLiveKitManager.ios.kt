package com.eddyslarez.kmpsiprtc.services.conference

import cocoapods.LiveKitClient.ConnectionStateConnected
import cocoapods.LiveKitClient.ConnectionStateConnecting
import cocoapods.LiveKitClient.ConnectionStateDisconnected
import cocoapods.LiveKitClient.ConnectionStateReconnecting
import cocoapods.LiveKitClient.LocalParticipant
import cocoapods.MCNLiveKitDataBridge.LKDataPublisher
import cocoapods.LiveKitClient.LocalTrackPublication
import cocoapods.LiveKitClient.LocalVideoTrack
import cocoapods.LiveKitClient.Participant
import cocoapods.LiveKitClient.RemoteParticipant
import cocoapods.LiveKitClient.RemoteTrackPublication
import cocoapods.LiveKitClient.RemoteVideoTrack
import cocoapods.LiveKitClient.Room
import cocoapods.LiveKitClient.RoomDelegateProtocol
import cocoapods.LiveKitClient.TrackPublication
import cocoapods.LiveKitClient.TrackSourceCamera
import cocoapods.LiveKitClient.TrackSourceMicrophone
import cocoapods.LiveKitClient.TrackSourceScreenShareVideo
import cocoapods.LiveKitClient.addDelegate
import cocoapods.LiveKitClient.removeAllDelegates
import cocoapods.LiveKitClient.setCameraWithEnabled
import cocoapods.LiveKitClient.setMicrophoneWithEnabled
import cocoapods.LiveKitClient.setScreenShareWithEnabled
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementacion iOS real para conferencias LiveKit usando el CocoaPod LiveKitClient.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ConferenceLiveKitManager actual constructor() {

    private val tag = "ConferenceLkManager"

    private var room: Room? = null
    private var roomDelegate: IosRoomDelegate? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateRefreshJob: Job? = null

    private val raisedHands = mutableMapOf<String, Long>()
    private var localHandRaised = false

    // Plataforma anunciada por cada participante remoto (identity -> marcador).
    // Se indexa por identity, la misma clave que usan raisedHands y los tracks.
    private val platformByIdentity = mutableMapOf<String, String>()

    private val _participants = MutableStateFlow<List<LkParticipant>>(emptyList())
    actual val participants: StateFlow<List<LkParticipant>> = _participants.asStateFlow()

    private val _connectionState = MutableStateFlow(LkConnectionState.IDLE)
    actual val connectionState: StateFlow<LkConnectionState> = _connectionState.asStateFlow()

    // El wrapper CocoaPod LiveKitClient no expone el DisconnectReason
    // estructurado del protocolo (solo NSError), asi que aqui unicamente
    // distinguimos "hubo un error" de "sin motivo conocido".
    private val _lastDisconnectReason = MutableStateFlow<LkDisconnectReason?>(null)
    actual val lastDisconnectReason: StateFlow<LkDisconnectReason?> = _lastDisconnectReason.asStateFlow()

    private val _mediaState = MutableStateFlow(LkMediaState())
    actual val mediaState: StateFlow<LkMediaState> = _mediaState.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<LkVideoTrackHandle>>(emptyList())
    actual val videoTracks: StateFlow<List<LkVideoTrackHandle>> = _videoTracks.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<LkChatMessage>>(emptyList())
    actual val chatMessages: StateFlow<List<LkChatMessage>> = _chatMessages.asStateFlow()

    actual suspend fun connect(url: String, token: String, participantName: String) {
        if (_connectionState.value == LkConnectionState.CONNECTED) {
            log.w(tag = tag) { "Ya conectado a conferencia iOS" }
            return
        }

        if (url.isBlank() || token.isBlank()) {
            stopStateRefreshLoop()
            _connectionState.value = LkConnectionState.ERROR
            throw LiveKitIosException("LiveKit URL/token vacio para iOS")
        }

        _connectionState.value = LkConnectionState.CONNECTING
        log.d(tag = tag) { "Conectando a LiveKit iOS: $url, participant=$participantName" }

        val delegate = IosRoomDelegate(this)
        val lkRoom = Room(delegate = delegate, connectOptions = null, roomOptions = null)
        roomDelegate = delegate
        room = lkRoom

        try {
            awaitNSError { completion ->
                lkRoom.connectWithUrl(
                    url = url,
                    token = token,
                    connectOptions = null,
                    roomOptions = null,
                    completionHandler = completion,
                )
            }
            _connectionState.value = LkConnectionState.CONNECTED
            refreshRoomState()
            requestHandStateSync()
            announcePlatformRepeatedly()
            startStateRefreshLoop()
            log.d(tag = tag) { "Conectado exitosamente a LiveKit iOS" }
        } catch (error: Throwable) {
            log.e(tag = tag) { "Error conectando a LiveKit iOS: ${error.message}" }
            stopStateRefreshLoop()
            _connectionState.value = LkConnectionState.ERROR
            room?.disconnectWithCompletionHandler {}
            room = null
            roomDelegate = null
            throw error
        }
    }

    actual suspend fun disconnect() {
        val lkRoom = room
        stopStateRefreshLoop()
        log.d(tag = tag) { "Desconectando de conferencia iOS" }
        if (lkRoom != null) {
            suspendCancellableCoroutine { continuation ->
                lkRoom.disconnectWithCompletionHandler {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            lkRoom.removeAllDelegates()
        }
        room = null
        roomDelegate = null
        raisedHands.clear()
        platformByIdentity.clear()
        localHandRaised = false
        stopStateRefreshLoop()
        _connectionState.value = LkConnectionState.DISCONNECTED
        _participants.value = emptyList()
        _videoTracks.value = emptyList()
        _chatMessages.value = emptyList()
        _mediaState.value = LkMediaState()
    }

    actual suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val lp = room?.localParticipant() ?: return
        awaitPublication { completion ->
            lp.setMicrophoneWithEnabled(
                enabled = enabled,
                captureOptions = null,
                publishOptions = null,
                completionHandler = completion,
            )
        }
        _mediaState.value = _mediaState.value.copy(microphoneEnabled = enabled)
        refreshRoomState()
    }

    actual suspend fun setCameraEnabled(enabled: Boolean) {
        val lp = room?.localParticipant() ?: return
        awaitPublication { completion ->
            lp.setCameraWithEnabled(
                enabled = enabled,
                captureOptions = null,
                publishOptions = null,
                completionHandler = completion,
            )
        }
        _mediaState.value = _mediaState.value.copy(cameraEnabled = enabled)
        refreshRoomState()
    }

    actual suspend fun setScreenShareEnabled(enabled: Boolean) {
        val lp = room?.localParticipant() ?: return
        awaitPublication { completion ->
            lp.setScreenShareWithEnabled(
                enabled = enabled,
                completionHandler = completion,
            )
        }
        _mediaState.value = _mediaState.value.copy(screenShareEnabled = enabled)
        refreshRoomState()
    }

    actual suspend fun setHandRaised(raised: Boolean) {
        val lkRoom = room ?: return
        val lp = lkRoom.localParticipant()
        val identity = lp.identity()?.stringValue() ?: return
        val name = (lp.name() ?: identity).replace("\"", "\\\"")
        val now = nowMs()

        localHandRaised = raised
        if (raised) {
            raisedHands[identity] = now
        } else {
            raisedHands.remove(identity)
        }

        val type = if (raised) "hand/raise" else "hand/lower"
        val payload = """{"type":"$type","at":$now,"participantIdentity":"$identity","author":"$name"}"""
        publishData(payload)
        refreshRoomState()
    }

    actual suspend fun loadDevices(): LkDevices = LkDevices(
        cameras = listOf(
            LkDevice("front", "Front camera"),
            LkDevice("back", "Back camera"),
        ),
        microphones = listOf(LkDevice("default", "Default microphone")),
        speakers = listOf(
            LkDevice("speaker", "Speaker"),
            LkDevice("earpiece", "Earpiece"),
        ),
        selectedCameraId = "front",
        selectedMicrophoneId = "default",
        selectedSpeakerId = "speaker",
    )

    actual suspend fun selectCamera(deviceId: String) {
        log.d(tag = tag) { "selectCamera iOS: $deviceId" }
    }

    actual suspend fun selectMicrophone(deviceId: String) {
        log.d(tag = tag) { "selectMicrophone iOS: $deviceId (gestionado por AVAudioSession/LiveKit)" }
    }

    actual suspend fun selectSpeaker(deviceId: String) {
        log.d(tag = tag) { "selectSpeaker iOS: $deviceId (gestionado por AVAudioSession/LiveKit)" }
    }

    actual suspend fun selectScreenShareSource(deviceId: String) {
        log.d(tag = tag) { "selectScreenShareSource iOS: $deviceId (in-app capture)" }
    }

    actual fun getVideoTrackHandle(participantIdentity: String): LkVideoTrackHandle? =
        _videoTracks.value.firstOrNull {
            it.participantIdentity == participantIdentity && !it.isScreenShare
        }

    actual fun getScreenShareTrackHandle(participantIdentity: String): LkVideoTrackHandle? =
        _videoTracks.value.firstOrNull {
            it.participantIdentity == participantIdentity && it.isScreenShare
        }

    actual suspend fun sendChatMessage(text: String) {
        val lkRoom = room ?: return
        val localIdentity = lkRoom.localParticipant().identity()?.stringValue() ?: ""
        val localName = lkRoom.localParticipant().name() ?: localIdentity
        val safeAuthor = localName.replace("\"", "\\\"")
        val safeText = text.replace("\"", "\\\"")
        val payload = """{"author":"$safeAuthor","message":"$safeText"}"""

        publishData(payload)

        val msg = LkChatMessage(
            id = "local-${nowMs()}",
            senderIdentity = localIdentity,
            senderName = localName,
            text = text,
            timestamp = nowMs(),
            isLocal = true,
            isSystem = false,
        )
        _chatMessages.value = _chatMessages.value + msg
    }

    fun onConnectionStateChanged(state: Long) {
        scope.launch {
            _connectionState.value = when (state) {
                ConnectionStateConnecting -> LkConnectionState.CONNECTING
                ConnectionStateReconnecting -> LkConnectionState.RECONNECTING
                ConnectionStateConnected -> LkConnectionState.CONNECTED
                ConnectionStateDisconnected -> LkConnectionState.DISCONNECTED
                else -> _connectionState.value
            }
            if (_connectionState.value == LkConnectionState.CONNECTED) {
                startStateRefreshLoop()
            } else if (_connectionState.value == LkConnectionState.DISCONNECTED || _connectionState.value == LkConnectionState.ERROR) {
                stopStateRefreshLoop()
            }
            refreshRoomState()
        }
    }

    fun onRoomContentChanged() {
        scope.launch { refreshRoomState() }
    }

    /** Un remoto acaba de entrar: re-anunciamos la plataforma para que la reciba. */
    fun onRemoteParticipantConnected() {
        scope.launch {
            announcePlatform()
            refreshRoomState()
        }
    }

    fun onRemoteParticipantDisconnected(participant: RemoteParticipant?) {
        scope.launch {
            participant?.identity()?.stringValue()?.let { platformByIdentity.remove(it) }
            refreshRoomState()
        }
    }

    fun onRoomDisconnected(error: NSError?) {
        scope.launch {
            if (error != null) {
                log.w(tag = tag) { "LiveKit iOS desconectado: ${error.localizedDescription}" }
            }
            _lastDisconnectReason.value = if (error != null) LkDisconnectReason.UNKNOWN else LkDisconnectReason.CLIENT_INITIATED
            stopStateRefreshLoop()
            _connectionState.value = LkConnectionState.DISCONNECTED
            _participants.value = emptyList()
            _videoTracks.value = emptyList()
        }
    }

    fun onRoomFailed(error: NSError?) {
        scope.launch {
            log.e(tag = tag) { "LiveKit iOS fallo de conexion: ${error?.localizedDescription}" }
            stopStateRefreshLoop()
            _connectionState.value = LkConnectionState.ERROR
        }
    }

    fun onDataReceived(data: NSData, participant: RemoteParticipant?) {
        val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: return
        scope.launch { handleDataReceived(text, participant) }
    }

    private suspend fun publishData(text: String) {
        val lkRoom = room ?: return
        val data = (NSString.create(string = text) as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        // DataPublishOptions(reliable = true) no es construible desde
        // Kotlin/Native (su init no esta @objc). Se publica via el puente
        // Swift MCNLiveKitDataBridge, que si puede construirla y llama al SDK
        // — mismo comportamiento que Android, que fuerza DataPublishReliability.RELIABLE.
        //
        // El cast a objcnames.classes.Room es necesario: MCNLiveKitDataBridge
        // y LiveKitClient se cinterop-ean por separado, asi que el header
        // ObjC generado del bridge solo tiene un forward-declare de `Room`
        // (no lo importa), y Kotlin/Native no unifica ese tipo con el
        // cocoapods.LiveKitClient.Room ya conocido — lo ve como un tipo
        // opaco propio. Ambos representan la misma clase ObjC en runtime,
        // asi que el cast es seguro (patron documentado de Kotlin/Native
        // para tipos de otro framework no expuestos en este cinterop).
        awaitNSError { completion ->
            LKDataPublisher.publishReliableWithRoom(
                room = lkRoom as objcnames.classes.Room,
                data = data,
                topic = null,
                completion = completion,
            )
        }
    }

    private fun startStateRefreshLoop() {
        if (stateRefreshJob?.isActive == true) return
        stateRefreshJob = scope.launch {
            while (room != null && _connectionState.value == LkConnectionState.CONNECTED) {
                refreshRoomState()
                delay(500)
            }
        }
    }

    private fun stopStateRefreshLoop() {
        stateRefreshJob?.cancel()
        stateRefreshJob = null
    }

    private fun refreshRoomState() {
        updateParticipants()
        updateVideoTracks()
    }

    private fun updateParticipants() {
        val lkRoom = room ?: return
        val allParticipants = mutableListOf<LkParticipant>()
        allParticipants.add(lkRoom.localParticipant().toLkParticipant(isLocal = true))
        lkRoom.remoteParticipants().values.forEach { participant ->
            (participant as? RemoteParticipant)?.let {
                allParticipants.add(it.toLkParticipant(isLocal = false))
            }
        }
        _participants.value = allParticipants
    }

    private fun updateVideoTracks() {
        val lkRoom = room ?: return
        val tracks = mutableListOf<LkVideoTrackHandle>()

        fun addTracksFrom(participant: Participant) {
            participant.trackPublications().values.forEach { publicationAny ->
                val publication = publicationAny as? TrackPublication ?: return@forEach
                val track = publication.track() ?: return@forEach
                val isVideoTrack = track is LocalVideoTrack || track is RemoteVideoTrack
                if (!isVideoTrack) return@forEach

                val identity = participant.identity()?.stringValue() ?: ""
                val sid = publication.sid().stringValue()
                tracks.add(
                    LkVideoTrackHandle(
                        participantIdentity = identity,
                        trackSid = sid,
                        nativeTrack = track,
                        isScreenShare = publication.source() == TrackSourceScreenShareVideo,
                    )
                )
            }
        }

        addTracksFrom(lkRoom.localParticipant())
        lkRoom.remoteParticipants().values.forEach { participant ->
            (participant as? RemoteParticipant)?.let(::addTracksFrom)
        }

        _videoTracks.value = tracks
    }

    private fun Participant.toLkParticipant(isLocal: Boolean): LkParticipant {
        val identityStr = identity()?.stringValue() ?: ""
        val nameStr = name() ?: identityStr
        val sidStr = sid()?.stringValue() ?: ""

        val publications = trackPublications().values.mapNotNull { it as? TrackPublication }
        val hasAudio = publications.any {
            it.source() == TrackSourceMicrophone && !it.isMuted()
        }
        val hasVideo = publications.any {
            it.source() == TrackSourceCamera && !it.isMuted()
        }
        val hasScreenShare = publications.any {
            it.source() == TrackSourceScreenShareVideo && !it.isMuted()
        }
        val videoSid = publications.firstOrNull {
            it.source() == TrackSourceCamera && !it.isMuted()
        }?.sid()?.stringValue()
        val screenSid = publications.firstOrNull {
            it.source() == TrackSourceScreenShareVideo && !it.isMuted()
        }?.sid()?.stringValue()

        return LkParticipant(
            identity = identityStr,
            name = nameStr,
            sid = sidStr,
            isLocal = isLocal,
            isSpeaking = isSpeaking(),
            isAudioEnabled = hasAudio,
            isVideoEnabled = hasVideo,
            isScreenSharing = hasScreenShare,
            isHandRaised = if (isLocal) localHandRaised else raisedHands.containsKey(identityStr),
            handRaisedAt = raisedHands[identityStr],
            videoTrackSid = videoSid,
            screenShareTrackSid = screenSid,
            platform = if (isLocal) currentPlatformMarker() else platformByIdentity[identityStr],
        )
    }

    private fun handleDataReceived(text: String, participant: RemoteParticipant?) {
        try {
            val jsonObj = Json.parseToJsonElement(text).jsonObject
            val type = jsonObj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "hand/raise" || type == "hand/lower" || type == "hand/sync/request" || type == "hand/sync/state") {
                handleHandDataMessage(jsonObj, participant)
                return
            }

            // Orden de parseo: hand -> platform -> chat (igual que la app Android nativa).
            val platformMarker = parsePlatformDataMessage(text)
            if (platformMarker != null) {
                handlePlatformDataMessage(platformMarker, participant)
                return
            }

            val senderIdentity = participant?.identity()?.stringValue() ?: ""
            if (participant == null) {
                // Diagnóstico: si el SDK entrega el data packet sin participant (payload
                // "broadcast" del servidor), senderIdentity queda "" — no debería
                // filtrarse como eco propio salvo que localIdentity también esté vacío.
                log.w(tag = tag) { "onDataReceived: participant=null, payload=$text" }
            }
            val localIdentity = room?.localParticipant()?.identity()?.stringValue() ?: ""
            if (senderIdentity == localIdentity) return

            val author = jsonObj["author"]?.jsonPrimitive?.contentOrNull
                ?: participant?.name()
                ?: "?"
            val message = jsonObj["message"]?.jsonPrimitive?.contentOrNull ?: return

            val msg = LkChatMessage(
                id = "remote-${nowMs()}",
                senderIdentity = senderIdentity,
                senderName = author,
                text = message,
                timestamp = nowMs(),
                isLocal = false,
                isSystem = false,
            )
            _chatMessages.value = _chatMessages.value + msg
        } catch (error: Throwable) {
            log.w(tag = tag) { "Error parseando data LiveKit iOS: ${error.message}" }
        }
    }

    private fun handleHandDataMessage(jsonObj: JsonObject, participant: RemoteParticipant?) {
        val senderIdentity = participant?.identity()?.stringValue() ?: return
        val localIdentity = room?.localParticipant()?.identity()?.stringValue() ?: ""
        val type = jsonObj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val at = jsonObj["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: nowMs()

        when (type) {
            "hand/raise" -> if (senderIdentity != localIdentity) raisedHands[senderIdentity] = at
            "hand/lower" -> {
                val target = jsonObj["target"]?.jsonPrimitive?.contentOrNull
                if (target == localIdentity) {
                    localHandRaised = false
                    raisedHands.remove(localIdentity)
                } else {
                    raisedHands.remove(target ?: senderIdentity)
                }
            }
            "hand/sync/request" -> publishLocalHandState()
            "hand/sync/state" -> {
                val target = jsonObj["participantIdentity"]?.jsonPrimitive?.contentOrNull ?: senderIdentity
                val raised = jsonObj["raised"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
                if (target == localIdentity) {
                    localHandRaised = raised
                }
                if (raised) {
                    raisedHands[target] = at
                } else {
                    raisedHands.remove(target)
                }
            }
        }
        refreshRoomState()
    }

    /**
     * Guarda la plataforma del emisor y, si es su PRIMER marcador, responde con el
     * nuestro (respond-on-receipt): si su mensaje llego, su canal ya puede recibir
     * el nuestro, asi que el intercambio es fiable aunque hayan entrado en momentos
     * distintos. Solo se responde la primera vez para no entrar en ping-pong.
     */
    private fun handlePlatformDataMessage(marker: String, participant: RemoteParticipant?) {
        val senderIdentity = participant?.identity()?.stringValue() ?: return
        val localIdentity = room?.localParticipant()?.identity()?.stringValue() ?: ""
        if (senderIdentity == localIdentity) return

        val isFirstMarker = platformByIdentity.put(senderIdentity, marker) == null
        log.d(tag = tag) { "Plataforma de $senderIdentity: $marker (primera=$isFirstMarker)" }
        if (isFirstMarker) {
            scope.launch { announcePlatform() }
        }
        refreshRoomState()
    }

    /** Difunde el marcador de plataforma propio a toda la sala. */
    private suspend fun announcePlatform() {
        // No debe tumbar el flujo que lo invoca si el canal aun no esta listo.
        runCatching { publishData(buildPlatformDataMessagePayload()) }
            .onFailure { error -> log.w(tag = tag) { "Fallo anunciando plataforma: ${error.message}" } }
    }

    /**
     * El data channel puede no estar listo justo tras conectar, asi que repetimos
     * el anuncio unas pocas veces con intervalo en vez de perderlo.
     */
    private fun announcePlatformRepeatedly() {
        scope.launch {
            repeat(PLATFORM_ANNOUNCE_ATTEMPTS) {
                if (room == null || _connectionState.value != LkConnectionState.CONNECTED) return@launch
                announcePlatform()
                delay(PLATFORM_ANNOUNCE_RETRY_INTERVAL_MS)
            }
        }
    }

    private fun requestHandStateSync() {
        scope.launch {
            val identity = room?.localParticipant()?.identity()?.stringValue() ?: return@launch
            publishData("""{"type":"hand/sync/request","at":${nowMs()},"participantIdentity":"$identity"}""")
            publishLocalHandState()
        }
    }

    private fun publishLocalHandState() {
        scope.launch {
            val lp = room?.localParticipant() ?: return@launch
            val identity = lp.identity()?.stringValue() ?: return@launch
            val name = (lp.name() ?: identity).replace("\"", "\\\"")
            val at = raisedHands[identity] ?: nowMs()
            publishData("""{"type":"hand/sync/state","raised":$localHandRaised,"at":$at,"participantIdentity":"$identity","author":"$name"}""")
        }
    }

    private suspend fun awaitNSError(
        block: (completion: (NSError?) -> Unit) -> Unit,
    ) {
        suspendCancellableCoroutine { continuation ->
            block { error ->
                if (!continuation.isActive) return@block
                if (error != null) {
                    continuation.resumeWithException(LiveKitIosException(error.localizedDescription))
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private suspend fun awaitPublication(
        block: (completion: (LocalTrackPublication?, NSError?) -> Unit) -> Unit,
    ): LocalTrackPublication? = suspendCancellableCoroutine { continuation ->
        block { publication, error ->
            if (!continuation.isActive) return@block
            if (error != null) {
                continuation.resumeWithException(LiveKitIosException(error.localizedDescription))
            } else {
                continuation.resume(publication)
            }
        }
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}

private const val PLATFORM_ANNOUNCE_ATTEMPTS = 4
private const val PLATFORM_ANNOUNCE_RETRY_INTERVAL_MS = 1500L

@OptIn(ExperimentalForeignApi::class)
private class IosRoomDelegate(
    private val manager: ConferenceLiveKitManager,
) : NSObject(), RoomDelegateProtocol {

    
    @ObjCSignatureOverride
    override fun room(room: Room, didUpdateConnectionState: Long, from: Long) {
        manager.onConnectionStateChanged(didUpdateConnectionState)
    }

    override fun roomDidConnect(room: Room) {
        manager.onConnectionStateChanged(ConnectionStateConnected)
    }

    override fun roomIsReconnecting(room: Room) {
        manager.onConnectionStateChanged(ConnectionStateReconnecting)
    }

    override fun roomDidReconnect(room: Room) {
        manager.onConnectionStateChanged(ConnectionStateConnected)
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, didFailToConnectWithError: cocoapods.LiveKitClient.LiveKitError?) {
        manager.onRoomFailed(didFailToConnectWithError)
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, didDisconnectWithError: cocoapods.LiveKitClient.LiveKitError?) {
        manager.onRoomDisconnected(didDisconnectWithError)
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, participantDidConnect: RemoteParticipant) {
        manager.onRemoteParticipantConnected()
    }


    @ObjCSignatureOverride
    override fun room(room: Room, participantDidDisconnect: RemoteParticipant) {
        manager.onRemoteParticipantDisconnected(participantDidDisconnect)
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, didUpdateSpeakingParticipants: List<*>) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, localParticipant: LocalParticipant, didPublishTrack: LocalTrackPublication) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, localParticipant: LocalParticipant, didUnpublishTrack: LocalTrackPublication) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, remoteParticipant: RemoteParticipant, didPublishTrack: RemoteTrackPublication) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, remoteParticipant: RemoteParticipant, didUnpublishTrack: RemoteTrackPublication) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, participant: RemoteParticipant, didSubscribeTrack: RemoteTrackPublication) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, participant: RemoteParticipant, didUnsubscribeTrack: RemoteTrackPublication) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, participant: Participant, trackPublication: TrackPublication, didUpdateIsMuted: Boolean) {
        manager.onRoomContentChanged()
    }

    
    @ObjCSignatureOverride
    override fun room(room: Room, participant: RemoteParticipant?, didReceiveData: NSData, forTopic: String) {
        manager.onDataReceived(didReceiveData, participant)
    }
}

private class LiveKitIosException(message: String) : RuntimeException(message)
