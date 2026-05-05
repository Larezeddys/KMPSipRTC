package com.eddyslarez.kmpsiprtc.services.conference

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.livekit.*
import com.eddyslarez.kmpsiprtc.services.webrtc.DesktopWebRtcManager
import com.eddyslarez.kmpsiprtc.services.webrtc.WebRtcEventListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Implementacion Desktop de ConferenceLiveKitManager usando signaling custom + webrtc-java.
 *
 * Reutiliza LiveKitSignalingClient y LiveKitProto existentes de la SIP lib para la
 * comunicacion WebSocket con el servidor LiveKit, y WebRtcManager para los PeerConnections.
 *
 * Flujo:
 * 1. connect(url, token) → LiveKitSignalingClient conecta WebSocket
 * 2. Recibe JoinResponse → configura publisher y subscriber PeerConnections
 * 3. Publica audio (y opcionalmente video) via publisher
 * 4. Suscribe tracks remotos via subscriber
 * 5. Mapea participantes y tracks a modelos de conference
 */
actual class ConferenceLiveKitManager actual constructor() {

    private val TAG = "ConferenceLkDesktop"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Signaling (reutiliza implementacion existente)
    private val signalingClient = LiveKitSignalingClient()

    // WebRTC PeerConnections
    private var publisherWebRtc: DesktopWebRtcManager? = null
    private var subscriberWebRtc: DesktopWebRtcManager? = null

    private var joinResponse: LiveKitJoinResponse? = null
    private var localIdentity: String = ""
    private var localName: String = ""
    private var subscriberReady = false
    private val pendingOffers = mutableListOf<String>()
    private var selectedScreenShareSourceId: String? = null

    // Estado interno
    private val _participants = MutableStateFlow<List<LkParticipant>>(emptyList())
    actual val participants: StateFlow<List<LkParticipant>> = _participants.asStateFlow()

    private val _connectionState = MutableStateFlow(LkConnectionState.IDLE)
    actual val connectionState: StateFlow<LkConnectionState> = _connectionState.asStateFlow()

    private val _mediaState = MutableStateFlow(LkMediaState())
    actual val mediaState: StateFlow<LkMediaState> = _mediaState.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<LkVideoTrackHandle>>(emptyList())
    actual val videoTracks: StateFlow<List<LkVideoTrackHandle>> = _videoTracks.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<LkChatMessage>>(emptyList())
    actual val chatMessages: StateFlow<List<LkChatMessage>> = _chatMessages.asStateFlow()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    // Tracking de participantes remotos (del signaling)
    private val remoteParticipants = mutableMapOf<String, LkParticipant>()
    private val raisedHands = mutableMapOf<String, Long>()
    private var localHandRaised = false

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()

    actual suspend fun connect(url: String, token: String, participantName: String) {
        if (_connectionState.value == LkConnectionState.CONNECTED) {
            log.w(tag = TAG) { "Ya conectado a conferencia" }
            return
        }

        _connectionState.value = LkConnectionState.CONNECTING
        localName = participantName
        log.d(tag = TAG) { "Conectando a LiveKit Desktop: $url" }

        try {
            // Configurar listener de signaling
            setupSignalingListener()

            // Conectar WebSocket (el URL ya tiene el token)
            signalingClient.connect(url, token)

            // Esperar JoinResponse
            withTimeout(15_000) {
                while (joinResponse == null) {
                    delay(50)
                }
            }

            _connectionState.value = LkConnectionState.CONNECTED
            localIdentity = joinResponse?.participantIdentity ?: participantName
            if (localName.isEmpty()) localName = joinResponse?.participantName ?: participantName
            log.d(tag = TAG) { "Conectado a LiveKit Desktop. Identity: $localIdentity, name: $localName" }

            // Cargar participantes que ya estaban en la sala
            joinResponse?.otherParticipants?.forEach { info ->
                if (info.state != 3) { // no DISCONNECTED
                    remoteParticipants[info.identity] = LkParticipant(
                        identity = info.identity,
                        name = info.name.ifEmpty { info.identity },
                        sid = info.sid,
                        isLocal = false,
                        isAudioEnabled = true,
                        isHandRaised = raisedHands.containsKey(info.identity),
                        handRaisedAt = raisedHands[info.identity],
                    )
                    log.d(tag = TAG) { "Existing participant: ${info.name} (${info.identity})" }
                }
            }

            rebuildParticipantList()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error conectando Desktop: ${e.message}" }
            _connectionState.value = LkConnectionState.ERROR
            cleanup()
            throw e
        }
    }

    actual suspend fun disconnect() {
        log.d(tag = TAG) { "Desconectando de conferencia Desktop" }
        try {
            signalingClient.disconnect()
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error en disconnect signaling: ${e.message}" }
        }
        cleanup()
        _connectionState.value = LkConnectionState.DISCONNECTED
        _participants.value = emptyList()
        _videoTracks.value = emptyList()
        _chatMessages.value = emptyList()
        _mediaState.value = LkMediaState()
        raisedHands.clear()
        localHandRaised = false
    }

    actual suspend fun setMicrophoneEnabled(enabled: Boolean) {
        publisherWebRtc?.setMuted(!enabled)  // muted = !enabled
        _mediaState.value = _mediaState.value.copy(microphoneEnabled = enabled)
        rebuildParticipantList()
    }

    actual suspend fun setCameraEnabled(enabled: Boolean) {
        val pub = publisherWebRtc
        if (pub == null) {
            log.w(tag = TAG) { "setCameraEnabled: publisher no inicializado" }
            return
        }

        if (enabled) {
            // Agregar video track y publicar
            val videoTrack = pub.addLocalVideoTrack()
            if (videoTrack == null) {
                log.w(tag = TAG) { "No se pudo activar la cámara" }
                return
            }

            // Notificar a LiveKit que vamos a publicar un video track
            val cid = "video-${currentTimeMs()}"
            signalingClient.sendAddTrack(
                cid = cid,
                name = "camera",
                trackType = LiveKitTrackType.VIDEO.value,
                source = LiveKitTrackSource.CAMERA.value
            )

            // Renegociar SDP para incluir el video track
            val offer = pub.createOffer()
            signalingClient.sendOffer(offer)
            log.d(tag = TAG) { "Cámara activada, offer renegociado" }

            // Actualizar video tracks
            val handle = LkVideoTrackHandle(
                participantIdentity = localIdentity,
                trackSid = cid,
                nativeTrack = videoTrack,
                isScreenShare = false
            )
            _videoTracks.value = _videoTracks.value + handle
        } else {
            pub.removeLocalVideoTrack()
            _videoTracks.value = _videoTracks.value.filter {
                !(it.participantIdentity == localIdentity && !it.isScreenShare)
            }

            // Renegociar para remover el video
            try {
                val offer = pub.createOffer()
                signalingClient.sendOffer(offer)
            } catch (e: Exception) {
                log.w(tag = TAG) { "Error renegociando sin video: ${e.message}" }
            }
            log.d(tag = TAG) { "Cámara desactivada" }
        }

        _mediaState.value = _mediaState.value.copy(cameraEnabled = enabled)
        rebuildParticipantList()
    }

    actual suspend fun setScreenShareEnabled(enabled: Boolean) {
        val pub = publisherWebRtc
        if (pub == null) {
            log.w(tag = TAG) { "setScreenShareEnabled: publisher no inicializado" }
            return
        }

        if (enabled) {
            val screenTrack = pub.getLocalScreenShareTrack() ?: pub.addLocalScreenShareTrack(selectedScreenShareSourceId)
            if (screenTrack == null) {
                log.w(tag = TAG) { "No se pudo activar screen share Desktop" }
                _mediaState.value = _mediaState.value.copy(screenShareEnabled = false)
                rebuildParticipantList()
                return
            }

            val cid = "screen-${currentTimeMs()}"
            signalingClient.sendAddTrack(
                cid = cid,
                name = "screen",
                trackType = LiveKitTrackType.VIDEO.value,
                source = LiveKitTrackSource.SCREEN_SHARE.value
            )

            val offer = pub.createOffer()
            signalingClient.sendOffer(offer)

            val handle = LkVideoTrackHandle(
                participantIdentity = localIdentity,
                trackSid = cid,
                nativeTrack = screenTrack,
                isScreenShare = true
            )
            _videoTracks.value = _videoTracks.value.filter {
                !(it.participantIdentity == localIdentity && it.isScreenShare)
            } + handle

            log.d(tag = TAG) { "Screen share Desktop activado, offer renegociado" }
        } else {
            pub.removeLocalScreenShareTrack()
            _videoTracks.value = _videoTracks.value.filter {
                !(it.participantIdentity == localIdentity && it.isScreenShare)
            }

            try {
                val offer = pub.createOffer()
                signalingClient.sendOffer(offer)
            } catch (e: Exception) {
                log.w(tag = TAG) { "Error renegociando sin screen share: ${e.message}" }
            }
            log.d(tag = TAG) { "Screen share Desktop desactivado" }
        }

        _mediaState.value = _mediaState.value.copy(screenShareEnabled = enabled)
        rebuildParticipantList()
    }

    actual suspend fun loadDevices(): LkDevices {
        val cameras = mutableListOf<LkDevice>()
        val microphones = mutableListOf<LkDevice>()
        val speakers = mutableListOf<LkDevice>()
        val screenShareSources = mutableListOf<LkDevice>()

        // Obtener dispositivos de audio via WebRtcManager
        val webRtc = publisherWebRtc ?: DesktopWebRtcManager().also { it.initialize() }
        try {
            val (inputs, outputs) = webRtc.getAllAudioDevices()
            inputs.forEach { d ->
                microphones.add(LkDevice(d.descriptor, d.name))
            }
            outputs.forEach { d ->
                speakers.add(LkDevice(d.descriptor, d.name))
            }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error enumerando dispositivos: ${e.message}" }
            microphones.add(LkDevice("default", "Microfono por defecto"))
            speakers.add(LkDevice("default", "Speaker por defecto"))
        }

        // Enumerar cámaras via webrtc-java
        try {
            val pub = publisherWebRtc
            val videoDevices = pub?.enumerateVideoDevices().orEmpty()
            videoDevices.forEach { d ->
                cameras.add(LkDevice(d.name, d.name))
            }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error enumerando cámaras: ${e.message}" }
        }
        if (cameras.isEmpty()) {
            cameras.add(LkDevice("none", "Sin cámara"))
        }

        try {
            webRtc.enumerateScreenShareSources().forEach { (id, name) ->
                screenShareSources.add(LkDevice(id, name))
            }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error enumerando pantallas: ${e.message}" }
        }

        return LkDevices(
            cameras = cameras,
            microphones = microphones,
            speakers = speakers,
            screenShareSources = screenShareSources,
            selectedCameraId = cameras.firstOrNull()?.id,
            selectedMicrophoneId = microphones.firstOrNull()?.id,
            selectedSpeakerId = speakers.firstOrNull()?.id,
            selectedScreenShareSourceId = selectedScreenShareSourceId ?: screenShareSources.firstOrNull()?.id,
        )
    }

    actual suspend fun selectCamera(deviceId: String) {
        log.d(tag = TAG) { "selectCamera: $deviceId" }
        // Si la cámara está activa, apagar y reencender con el nuevo dispositivo
        if (_mediaState.value.cameraEnabled) {
            setCameraEnabled(false)
            // Buscar el dispositivo por nombre
            val pub = publisherWebRtc
            val device = pub?.enumerateVideoDevices().orEmpty().firstOrNull { device -> device.name == deviceId }
            if (device != null) {
                pub?.addLocalVideoTrack(device)
            }
            setCameraEnabled(true)
        }
    }

    actual suspend fun selectMicrophone(deviceId: String) {
        publisherWebRtc?.selectAudioInputDeviceByName(deviceId)
    }

    actual suspend fun selectSpeaker(deviceId: String) {
        subscriberWebRtc?.selectAudioOutputDeviceByName(deviceId)
    }

    actual suspend fun selectScreenShareSource(deviceId: String) {
        selectedScreenShareSourceId = deviceId
        if (_mediaState.value.screenShareEnabled) {
            setScreenShareEnabled(false)
            setScreenShareEnabled(true)
        }
    }

    actual suspend fun setHandRaised(raised: Boolean) {
        if (_connectionState.value != LkConnectionState.CONNECTED) return
        val now = currentTimeMs()
        localHandRaised = raised
        if (raised) {
            raisedHands[localIdentity] = now
        } else {
            raisedHands.remove(localIdentity)
        }

        val safeName = localName.replace("\"", "\\\"")
        publishHandData(
            """{"type":"${if (raised) "hand/raise" else "hand/lower"}","at":$now,"participantIdentity":"$localIdentity","author":"$safeName"}"""
        )
        rebuildParticipantList()
        log.d(tag = TAG) { if (raised) "Mano levantada" else "Mano bajada" }
    }

    actual fun getVideoTrackHandle(participantIdentity: String): LkVideoTrackHandle? {
        return _videoTracks.value.firstOrNull {
            it.participantIdentity == participantIdentity && !it.isScreenShare
        }
    }

    actual fun getScreenShareTrackHandle(participantIdentity: String): LkVideoTrackHandle? {
        return _videoTracks.value.firstOrNull {
            it.participantIdentity == participantIdentity && it.isScreenShare
        }
    }

    // ==================== SIGNALING ====================

    private fun setupSignalingListener() {
        signalingClient.listener = object : LiveKitSignalingListener {

            override fun onJoinResponse(jr: LiveKitJoinResponse) {
                joinResponse = jr
                log.d(tag = TAG) { "JoinResponse: room=${jr.room?.name}, identity=${jr.participantIdentity}" }
                scope.launch {
                    try {
                        setupPublisher()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error configurando publisher: ${e.message}" }
                        _connectionState.value = LkConnectionState.ERROR
                    }
                }
            }

            override fun onAnswer(sdp: LiveKitSessionDescription) {
                scope.launch {
                    try {
                        publisherWebRtc?.setRemoteDescription(sdp.sdp, SdpType.ANSWER)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error aplicando answer: ${e.message}" }
                    }
                }
            }

            override fun onOffer(sdp: LiveKitSessionDescription) {
                scope.launch {
                    try {
                        setupSubscriberAndAnswer(sdp.sdp)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error configurando subscriber: ${e.message}" }
                    }
                }
            }

            override fun onTrickle(trickle: LiveKitTrickle) {
                scope.launch {
                    val target = if (trickle.target == LiveKitSignalTarget.PUBLISHER.value)
                        publisherWebRtc else subscriberWebRtc
                    if (target != null) {
                        val candidate = extractJsonField(trickle.candidateInit, "candidate") ?: ""
                        val sdpMid = extractJsonField(trickle.candidateInit, "sdpMid")
                        val sdpMLineIndex = extractJsonField(trickle.candidateInit, "sdpMLineIndex")?.toIntOrNull()
                        target.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    }
                }
            }

            override fun onParticipantUpdate(update: LiveKitParticipantUpdate) {
                log.d(tag = TAG) { "ParticipantUpdate: ${update.participants.size} participants" }
                update.participants.forEach { info ->
                    if (info.identity == localIdentity) return@forEach // skip local
                    if (info.state == 3) {
                        // DISCONNECTED — remover
                        remoteParticipants.remove(info.identity)
                        raisedHands.remove(info.identity)
                        log.d(tag = TAG) { "Participant left: ${info.name} (${info.identity})" }
                    } else {
                        // JOINING/JOINED/ACTIVE — agregar/actualizar
                        remoteParticipants[info.identity] = LkParticipant(
                            identity = info.identity,
                            name = info.name.ifEmpty { info.identity },
                            sid = info.sid,
                            isLocal = false,
                            isAudioEnabled = true, // asumimos activo hasta tener track info
                            isHandRaised = raisedHands.containsKey(info.identity),
                            handRaisedAt = raisedHands[info.identity],
                        )
                        log.d(tag = TAG) { "Participant updated: ${info.name} (${info.identity}, state=${info.state})" }
                    }
                }
                rebuildParticipantList()
            }

            override fun onTrackPublished(published: LiveKitTrackPublished) {
                log.d(tag = TAG) { "Track publicado: ${published.trackSid}" }
            }

            override fun onLeave(canReconnect: Boolean, reason: Int) {
                log.d(tag = TAG) { "Leave recibido: canReconnect=$canReconnect" }
                scope.launch {
                    cleanup()
                    _connectionState.value = LkConnectionState.DISCONNECTED
                    _participants.value = emptyList()
                }
            }

            override fun onDisconnected() {
                // Solo marcar DISCONNECTED si no fue un disconnect intencional
                // (cuando desconectamos manualmente, disconnect() ya setea el estado)
                log.d(tag = TAG) { "Signaling onDisconnected callback" }
            }

            override fun onError(error: Exception) {
                log.e(tag = TAG) { "Error signaling: ${error.message}" }
                // No cambiar a ERROR automaticamente — dejar que el usuario vea el error
                // pero mantener la conexion activa si es posible
            }
        }
    }

    private suspend fun setupPublisher() {
        log.d(tag = TAG) { "Inicializando publisher + subscriber WebRTC" }

        // 1. Crear subscriber PRIMERO (el offer puede llegar durante setupPublisher)
        val sub = DesktopWebRtcManager()
        subscriberWebRtc = sub
        sub.initialize()
        sub.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                scope.launch {
                    val json = """{"candidate":"$candidate","sdpMid":"$sdpMid","sdpMLineIndex":$sdpMLineIndex}"""
                    signalingClient.sendTrickle(json, LiveKitSignalTarget.SUBSCRIBER.value)
                }
            }
            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                log.d(tag = TAG) { "Subscriber state: $state" }
            }
            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Audio remoto recibido via subscriber" }
            }
            override fun onAudioDeviceChanged(device: AudioDevice?) {}
        })
        // Forzar creacion del PeerConnection del subscriber con un offer dummy
        // para que esté listo cuando llegue el offer real
        // Registrar listener para Data Channel (chat messages del SFU)
        sub.setDataChannelMessageListener { bytes ->
            handleDataChannelMessage(bytes)
        }

        // Registrar listener para video tracks remotos
        sub.setOnRemoteVideoTrack { videoTrack ->
            log.d(tag = TAG) { "Video track remoto recibido" }
            // Por ahora asignar al primer participante remoto que no tenga video
            val remoteId = remoteParticipants.keys.firstOrNull() ?: "unknown"
            val handle = LkVideoTrackHandle(
                participantIdentity = remoteId,
                trackSid = "remote-video-${currentTimeMs()}",
                nativeTrack = videoTrack,
                isScreenShare = false
            )
            _videoTracks.value = _videoTracks.value + handle
            rebuildParticipantList()
        }

        subscriberReady = true
        log.d(tag = TAG) { "Subscriber WebRTC listo (con data channel y video listeners)" }

        // Procesar offers que llegaron antes de que el subscriber estuviera listo
        if (pendingOffers.isNotEmpty()) {
            log.d(tag = TAG) { "Procesando ${pendingOffers.size} offers pendientes" }
            pendingOffers.forEach { offerSdp ->
                processSubscriberOffer(offerSdp)
            }
            pendingOffers.clear()
        }

        // 2. Crear publisher
        val pub = DesktopWebRtcManager()
        publisherWebRtc = pub
        pub.initialize()
        pub.setAudioEnabled(true)
        pub.setMuted(true)

        // Crear data channel ANTES de createOffer para que se incluya en el SDP
        pub.createPublisherDataChannel()

        pub.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                scope.launch {
                    val json = """{"candidate":"$candidate","sdpMid":"$sdpMid","sdpMLineIndex":$sdpMLineIndex}"""
                    signalingClient.sendTrickle(json, LiveKitSignalTarget.PUBLISHER.value)
                }
            }
            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                log.d(tag = TAG) { "Publisher state: $state" }
            }
            override fun onRemoteAudioTrack() {}
            override fun onAudioDeviceChanged(device: AudioDevice?) {}
        })

        // Solicitar publicar audio
        val cid = "audio-${currentTimeMs()}"
        signalingClient.sendAddTrack(
            cid = cid,
            name = "microphone",
            trackType = LiveKitTrackType.AUDIO.value,
            source = LiveKitTrackSource.MICROPHONE.value
        )

        val offer = pub.createOffer()
        signalingClient.sendOffer(offer)
        log.d(tag = TAG) { "Publisher offer enviado" }
    }

    private suspend fun setupSubscriberAndAnswer(offerSdp: String) {
        if (!subscriberReady) {
            log.d(tag = TAG) { "Subscriber no listo, encolando offer" }
            pendingOffers.add(offerSdp)
            return
        }
        processSubscriberOffer(offerSdp)
    }

    private suspend fun processSubscriberOffer(offerSdp: String) {
        val sub = subscriberWebRtc ?: run {
            log.e(tag = TAG) { "subscriberWebRtc es null en processSubscriberOffer" }
            return
        }
        log.d(tag = TAG) { "Procesando subscriber offer" }
        // createAnswer() internamente hace setRemoteDescription + createAnswer + setLocalDescription
        // NO llamar setRemoteDescription por separado (causa doble set y falla)
        val answer = sub.createAnswer(offerSdp)
        signalingClient.sendAnswer(answer)
        log.d(tag = TAG) { "Subscriber answer enviado" }
    }

    // ==================== PARTICIPANT MANAGEMENT ====================

    private fun rebuildParticipantList() {
        val list = mutableListOf<LkParticipant>()

        // Participante local
        list.add(
            LkParticipant(
                identity = localIdentity,
                name = localName.ifEmpty { localIdentity },
                sid = joinResponse?.participantSid ?: "",
                isLocal = true,
                isAudioEnabled = _mediaState.value.microphoneEnabled,
                isVideoEnabled = _mediaState.value.cameraEnabled,
                isScreenSharing = _mediaState.value.screenShareEnabled,
                isHandRaised = localHandRaised,
                handRaisedAt = raisedHands[localIdentity],
            )
        )

        // Participantes remotos
        list.addAll(
            remoteParticipants.values.map { participant ->
                participant.copy(
                    isHandRaised = raisedHands.containsKey(participant.identity),
                    handRaisedAt = raisedHands[participant.identity],
                )
            }
        )

        _participants.value = list
    }

    // ==================== CHAT ====================

    actual suspend fun sendChatMessage(text: String) {
        val json = """{"author":"${localName.replace("\"", "\\\"")}", "message":"${text.replace("\"", "\\\"")}"}"""
        val bytes = json.encodeToByteArray()

        // Enviar via publisher data channel
        val pub = publisherWebRtc
        val sent = pub?.sendDataChannelMessage(bytes) ?: false
        if (!sent) {
            // Fallback: intentar via subscriber data channel
            val sub = subscriberWebRtc
            sub?.sendDataChannelMessage(bytes)
        }

        // Agregar localmente
        val msg = LkChatMessage(
            id = "local-${currentTimeMs()}",
            senderIdentity = localIdentity,
            senderName = localName,
            text = text,
            timestamp = currentTimeMs(),
            isLocal = true,
        )
        _chatMessages.value = _chatMessages.value + msg
        log.d(tag = TAG) { "Chat enviado: $text" }
    }

    private fun handleDataChannelMessage(bytes: ByteArray) {
        try {
            val text = bytes.decodeToString()
            log.d(tag = TAG) { "Data channel recibido: $text" }

            // LiveKit web client envia: {"author":"nombre", "message":"texto"}
            val jsonObj = jsonParser.parseToJsonElement(text).jsonObject
            val type = jsonObj["type"]?.jsonPrimitive?.content
            if (type == "hand/raise" || type == "hand/lower") {
                handleHandDataMessage(jsonObj)
                return
            }

            val author = jsonObj["author"]?.jsonPrimitive?.content ?: "Unknown"
            val message = jsonObj["message"]?.jsonPrimitive?.content ?: text

            val msg = LkChatMessage(
                id = "remote-${currentTimeMs()}",
                senderIdentity = author,
                senderName = author,
                text = message,
                timestamp = currentTimeMs(),
                isLocal = false,
            )
            _chatMessages.value = _chatMessages.value + msg
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error parseando data channel message: ${e.message}" }
            // Fallback: tratar como texto plano
            val msg = LkChatMessage(
                id = "remote-${currentTimeMs()}",
                senderIdentity = "unknown",
                senderName = "Participant",
                text = bytes.decodeToString(),
                timestamp = currentTimeMs(),
                isLocal = false,
            )
            _chatMessages.value = _chatMessages.value + msg
        }
    }

    private fun publishHandData(payload: String) {
        val bytes = payload.encodeToByteArray()
        val sent = publisherWebRtc?.sendDataChannelMessage(bytes) ?: false
        if (!sent) {
            val fallbackSent = subscriberWebRtc?.sendDataChannelMessage(bytes) ?: false
            if (!fallbackSent) {
                log.w(tag = TAG) { "No hay data channel disponible para enviar estado de mano" }
            }
        }
    }

    private fun handleHandDataMessage(jsonObj: JsonObject) {
        val type = jsonObj["type"]?.jsonPrimitive?.content ?: return
        val at = jsonObj["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: currentTimeMs()
        val target = jsonObj["target"]?.jsonPrimitive?.content
        val sender = jsonObj["participantIdentity"]?.jsonPrimitive?.content
            ?: jsonObj["author"]?.jsonPrimitive?.content
            ?: target
            ?: return

        when (type) {
            "hand/raise" -> {
                if (sender != localIdentity) {
                    raisedHands[sender] = at
                    remoteParticipants[sender]?.let { participant ->
                        remoteParticipants[sender] = participant.copy(isHandRaised = true, handRaisedAt = at)
                    }
                }
            }
            "hand/lower" -> {
                if (target == localIdentity) {
                    localHandRaised = false
                    raisedHands.remove(localIdentity)
                } else {
                    val identity = target ?: sender
                    raisedHands.remove(identity)
                    remoteParticipants[identity]?.let { participant ->
                        remoteParticipants[identity] = participant.copy(isHandRaised = false, handRaisedAt = null)
                    }
                }
            }
        }

        rebuildParticipantList()
    }

    // ==================== CLEANUP ====================

    private fun cleanup() {
        publisherWebRtc?.closePeerConnection()
        publisherWebRtc?.dispose()
        publisherWebRtc = null
        subscriberWebRtc?.closePeerConnection()
        subscriberWebRtc?.dispose()
        subscriberWebRtc = null
        subscriberReady = false
        pendingOffers.clear()
        joinResponse = null
        remoteParticipants.clear()
        raisedHands.clear()
        localHandRaised = false
        selectedScreenShareSourceId = null
    }

    private fun extractJsonField(json: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1)
    }
}

