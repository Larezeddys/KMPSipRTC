package com.eddyslarez.kmpsiprtc.services.conference

import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.IceServerInfo
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.log
import com.eddyslarez.kmpsiprtc.services.screencapture.DesktopScreenCaptureSupport
import com.eddyslarez.kmpsiprtc.services.screencapture.PortalScreenCastSession
import com.eddyslarez.kmpsiprtc.services.screencapture.ScreenShareUnavailableException
import com.eddyslarez.kmpsiprtc.services.webrtc.PORTAL_SOURCE_ID
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
    private var selectedCameraId: String? = null
    private var selectedMicrophoneId: String? = null
    private var selectedSpeakerId: String? = null

    // Estado interno
    private val _participants = MutableStateFlow<List<LkParticipant>>(emptyList())
    actual val participants: StateFlow<List<LkParticipant>> = _participants.asStateFlow()

    private val _connectionState = MutableStateFlow(LkConnectionState.IDLE)
    actual val connectionState: StateFlow<LkConnectionState> = _connectionState.asStateFlow()

    private val _lastDisconnectReason = MutableStateFlow<LkDisconnectReason?>(null)
    actual val lastDisconnectReason: StateFlow<LkDisconnectReason?> = _lastDisconnectReason.asStateFlow()

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

    /**
     * Mapea el valor numerico crudo del campo protobuf
     * `livekit.DisconnectReason` (leido tal cual del varint del wire, sin
     * generar clases protobuf completas) al enum compartido de dominio.
     */
    private fun Int.toLkDisconnectReason(): LkDisconnectReason = when (this) {
        1 -> LkDisconnectReason.CLIENT_INITIATED
        2 -> LkDisconnectReason.DUPLICATE_IDENTITY
        3 -> LkDisconnectReason.SERVER_SHUTDOWN
        4 -> LkDisconnectReason.PARTICIPANT_REMOVED
        5 -> LkDisconnectReason.ROOM_DELETED
        6 -> LkDisconnectReason.STATE_MISMATCH
        7 -> LkDisconnectReason.JOIN_FAILURE
        9 -> LkDisconnectReason.SIGNAL_CLOSE
        10 -> LkDisconnectReason.ROOM_CLOSED
        14 -> LkDisconnectReason.CONNECTION_TIMEOUT
        15 -> LkDisconnectReason.MEDIA_FAILURE
        else -> LkDisconnectReason.UNKNOWN
    }

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

            // NO marcar CONNECTED aquí — solo el handshake de signaling terminó.
            // El estado pasa a CONNECTED cuando el publisher PeerConnection llegue a
            // WebRtcConnectionState.CONNECTED (ver listener en setupPublisher).
            localIdentity = joinResponse?.participantIdentity ?: participantName
            if (localName.isEmpty()) localName = joinResponse?.participantName ?: participantName
            log.d(tag = TAG) { "Signaling listo (Identity: $localIdentity, name: $localName). Esperando publisher RTC..." }

            // Cargar participantes que ya estaban en la sala
            joinResponse?.otherParticipants?.forEach { info ->
                if (info.state != 3) { // no DISCONNECTED
                    val handFromState = handRaisedFromParticipant(info)
                    if (handFromState == true) {
                        raisedHands[info.identity] = raisedHands[info.identity] ?: currentTimeMs()
                    } else if (handFromState == false) {
                        raisedHands.remove(info.identity)
                    }
                    val remoteScreenSharing = info.tracks.any {
                        it.source == LiveKitTrackSource.SCREEN_SHARE.value
                    }
                    remoteParticipants[info.identity] = LkParticipant(
                        identity = info.identity,
                        name = info.name.ifEmpty { info.identity },
                        sid = info.sid,
                        isLocal = false,
                        isAudioEnabled = true,
                        isScreenSharing = remoteScreenSharing,
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
        // cleanup() ya resetea raisedHands, joinResponse, subscriberReady, pendingOffers, etc.
        // Conservamos selectedCameraId / selectedMicrophoneId / selectedSpeakerId /
        // selectedScreenShareSourceId para que la próxima conferencia respete las prefs.
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
            // Resolver dispositivo de cámara: el persistido, si existe; si no, la primera.
            val availableDevices = try { pub.enumerateVideoDevices() } catch (_: Exception) { emptyList() }
            val targetDevice = selectedCameraId
                ?.let { id -> availableDevices.firstOrNull { it.name == id } }
                ?: availableDevices.firstOrNull()
            if (targetDevice == null && availableDevices.isEmpty()) {
                log.w(tag = TAG) { "No hay cámaras disponibles en el sistema" }
                return
            }
            log.d(tag = TAG) { "Activando cámara: ${targetDevice?.name ?: "(default)"}" }

            // Agregar video track y publicar
            val videoTrack = if (targetDevice != null) pub.addLocalVideoTrack(targetDevice) else pub.addLocalVideoTrack()
            if (videoTrack == null) {
                log.w(tag = TAG) { "No se pudo activar la cámara (addLocalVideoTrack devolvió null)" }
                return
            }
            selectedCameraId = targetDevice?.name ?: selectedCameraId

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
            // Si no hay fuente persistida, intentar usar la primera disponible.
            val sourceId = selectedScreenShareSourceId ?: run {
                try {
                    val sources = pub.enumerateScreenShareSources()
                    sources.firstOrNull()?.first?.also { selectedScreenShareSourceId = it }
                } catch (e: Exception) {
                    log.w(tag = TAG) { "Error enumerando screen sources: ${e.message}" }
                    null
                }
            }
            log.d(tag = TAG) { "Activando screen share: source=${sourceId ?: "(default)"}" }

            // Cualquier fallo tiene que propagarse: si esto retorna en silencio, el
            // usuario aprieta el botón y no pasa nada ni ve un motivo.
            val screenTrack = try {
                pub.getLocalScreenShareTrack() ?: pub.addLocalScreenShareTrack(sourceId)
            } catch (e: Throwable) {
                _mediaState.value = _mediaState.value.copy(screenShareEnabled = false)
                rebuildParticipantList()
                throw e
            }
            if (screenTrack == null) {
                _mediaState.value = _mediaState.value.copy(screenShareEnabled = false)
                rebuildParticipantList()
                throw ScreenShareUnavailableException(
                    DesktopScreenCaptureSupport.unavailableReason()
                        ?: "No se pudo iniciar la captura de pantalla"
                )
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

            // Heurística de clasificación: JavaSound expone muchos mixers como
            // duales (target + source lines), lo que hace que el mismo dispositivo
            // físico aparezca en ambas listas. Forzamos por nombre.
            fun nameSuggestsMic(n: String): Boolean {
                val lower = n.lowercase()
                return listOf("micrófono", "microfono", "microphone", "captur",
                    "input", "mic ", "mic(").any { lower.contains(it) }
            }
            fun nameSuggestsSpeaker(n: String): Boolean {
                val lower = n.lowercase()
                return listOf("altavoc", "altavoz", "speaker", "headphone",
                    "audífon", "audifon", "output", "playback").any { lower.contains(it) }
            }

            val seenMics = mutableSetOf<String>()
            val seenSpeakers = mutableSetOf<String>()
            microphones.add(LkDevice("default", "Sistema por defecto"))
            speakers.add(LkDevice("default", "Sistema por defecto"))
            seenMics.add("default")
            seenSpeakers.add("default")

            // 1) Inputs declarados: incluir solo si suenan a mic, excluir si son speakers.
            inputs.forEach { d ->
                val n = d.name
                if (nameSuggestsSpeaker(n) && !nameSuggestsMic(n)) return@forEach
                val label = linuxAudioDeviceLabel(n, isInput = true) ?: return@forEach
                if (seenMics.add(label)) microphones.add(LkDevice(n, label))
            }
            // 2) Outputs declarados: incluir solo si suenan a speaker, excluir si son mics.
            outputs.forEach { d ->
                val n = d.name
                if (nameSuggestsMic(n) && !nameSuggestsSpeaker(n)) return@forEach
                val label = linuxAudioDeviceLabel(n, isInput = false) ?: return@forEach
                if (seenSpeakers.add(label)) speakers.add(LkDevice(n, label))
            }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error enumerando dispositivos: ${e.message}" }
            microphones.add(LkDevice("default", "Sistema por defecto"))
            speakers.add(LkDevice("default", "Sistema por defecto"))
        }

        // Enumerar cámaras vía webrtc-java. Usamos `webRtc` (que es el publisher si
        // existe, o un manager temporal). Las cámaras se enumeran a nivel de
        // PeerConnectionFactory — no requieren publisher establecido.
        try {
            val videoDevices = webRtc.enumerateVideoDevices()
            val seenCams = mutableSetOf<String>()
            videoDevices.forEach { d ->
                // Dedupe: webrtc-java a veces devuelve la misma cámara dos veces
                // (driver físico + virtual) — colapsamos por nombre.
                if (seenCams.add(d.name)) {
                    cameras.add(LkDevice(d.name, d.name))
                }
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
            selectedCameraId = selectedCameraId
                ?: cameras.firstOrNull { it.id != "none" }?.id
                ?: cameras.firstOrNull()?.id,
            selectedMicrophoneId = selectedMicrophoneId ?: microphones.firstOrNull()?.id,
            selectedSpeakerId = selectedSpeakerId ?: speakers.firstOrNull()?.id,
            selectedScreenShareSourceId = selectedScreenShareSourceId ?: screenShareSources.firstOrNull()?.id,
        )
    }

    private fun linuxAudioDeviceLabel(rawName: String, isInput: Boolean): String? {
        val lower = rawName.lowercase()
        if (!isInput && (lower.contains("nvidia") || lower.contains("hdmi"))) return null
        if (lower.contains("alsa_playback") || lower == "default") return null

        val base = rawName
            .replace(Regex("\\s*\\[(plug)?hw:[^]]+]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Port\\s+", RegexOption.IGNORE_CASE), "")
            .replace("Microsoft®", "Microsoft")
            .trim()

        return when {
            lower.contains("vx2000") || lower.contains("life") -> "Microsoft LifeCam VX-2000"
            lower.contains("pch") -> "Audio interno"
            base.equals("device", ignoreCase = true) -> if (isInput) "Micrófono del sistema" else "Salida del sistema"
            base.isBlank() -> null
            else -> base
        }
    }
    actual suspend fun selectCamera(deviceId: String) {
        log.d(tag = TAG) { "selectCamera: $deviceId" }
        selectedCameraId = deviceId
        // Si la cámara está activa, re-aplicar (toggle off+on) para que tome efecto.
        // setCameraEnabled(true) usará el selectedCameraId persistido.
        if (_mediaState.value.cameraEnabled) {
            setCameraEnabled(false)
            delay(400)  // Esperar al SDP del 'off' antes de enviar el 'on'
            setCameraEnabled(true)
        }
    }

    actual suspend fun selectMicrophone(deviceId: String) {
        log.d(tag = TAG) { "selectMicrophone: $deviceId" }
        selectedMicrophoneId = deviceId
        if (deviceId == "default") return
        val ok = publisherWebRtc?.selectAudioInputDeviceByName(deviceId) ?: false
        if (!ok) log.w(tag = TAG) { "selectMicrophone falló para: $deviceId" }
    }

    actual suspend fun selectSpeaker(deviceId: String) {
        log.d(tag = TAG) { "selectSpeaker: $deviceId" }
        selectedSpeakerId = deviceId
        if (deviceId == "default") return
        // Aplicar a ambos PCs por si la salida se enruta por cualquiera de ellos.
        val okSub = subscriberWebRtc?.selectAudioOutputDeviceByName(deviceId) ?: false
        val okPub = publisherWebRtc?.selectAudioOutputDeviceByName(deviceId) ?: false
        if (!okSub && !okPub) log.w(tag = TAG) { "selectSpeaker falló para: $deviceId" }
    }

    actual suspend fun selectScreenShareSource(deviceId: String) {
        // Con el portal no hay lista propia: volver a elegir significa descartar el
        // token guardado para que el escritorio muestre de nuevo su selector.
        if (deviceId == PORTAL_SOURCE_ID) {
            PortalScreenCastSession.clearRestoreToken()
        }
        log.d(tag = TAG) { "selectScreenShareSource: $deviceId" }
        selectedScreenShareSourceId = deviceId
        if (_mediaState.value.screenShareEnabled) {
            // Damos tiempo a que la renegociación SDP del 'off' se procese ANTES
            // de iniciar la del 'on'. Sin este delay el SFU recibe dos offers
            // pisándose y nada termina publicado correctamente.
            setScreenShareEnabled(false)
            delay(400)
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
                        // JOINING/JOINED/ACTIVE — agregar/actualizar.
                        // Mano por atributos/metadata (estado "pegajoso" del participante):
                        // complementa el mensaje por data channel, que es transitorio y se
                        // pierde si el desktop entró tarde a la sala.
                        val handFromState = handRaisedFromParticipant(info)
                        if (handFromState != null) {
                            if (handFromState) {
                                raisedHands[info.identity] = raisedHands[info.identity] ?: currentTimeMs()
                            } else {
                                raisedHands.remove(info.identity)
                            }
                        }
                        val remoteScreenSharing = info.tracks.any {
                            it.source == LiveKitTrackSource.SCREEN_SHARE.value
                        }
                        remoteParticipants[info.identity] = LkParticipant(
                            identity = info.identity,
                            name = info.name.ifEmpty { info.identity },
                            sid = info.sid,
                            isLocal = false,
                            isAudioEnabled = true, // asumimos activo hasta tener track info
                            isScreenSharing = remoteScreenSharing,
                            isHandRaised = raisedHands.containsKey(info.identity),
                            handRaisedAt = raisedHands[info.identity],
                        )
                        log.d(tag = TAG) { "Participant updated: ${info.name} (${info.identity}, state=${info.state}, screen=$remoteScreenSharing)" }
                        publishLocalHandState()
                    }
                }
                rebuildParticipantList()
            }

            override fun onTrackPublished(published: LiveKitTrackPublished) {
                log.d(tag = TAG) { "Track publicado: ${published.trackSid}" }
                // Fallback: el callback de cambio de estado del publisher PC no siempre
                // se dispara en webrtc-java/JVM. Cuando el SFU confirma la publicación
                // del primer track significa que el handshake completo (SDP + ICE +
                // signaling) está estable. Lo usamos como señal definitiva de CONNECTED.
                if (_connectionState.value != LkConnectionState.CONNECTED) {
                    _connectionState.value = LkConnectionState.CONNECTED
                    log.d(tag = TAG) { "Marcando CONNECTED por TrackPublished (fallback)" }
                    requestHandStateSync()
                }
            }

            override fun onLeave(canReconnect: Boolean, reason: Int) {
                val mappedReason = reason.toLkDisconnectReason()
                log.w(tag = TAG) { "Leave recibido: canReconnect=$canReconnect, reason=$reason ($mappedReason)" }
                _lastDisconnectReason.value = mappedReason
                scope.launch {
                    cleanup()
                    _connectionState.value = LkConnectionState.DISCONNECTED
                    _participants.value = emptyList()
                }
            }

            override fun onDisconnected() {
                log.w(tag = TAG) { "Signaling onDisconnected — WebSocket cayó" }
                // Si estábamos en sesión activa, el WebSocket murió por su cuenta
                // (no fue un disconnect intencional desde la app, ya que disconnect()
                // pone el estado a DISCONNECTED ANTES de cerrar el socket).
                // En ese caso, propagamos el estado a la UI para que vuelva al catálogo
                // y el usuario pueda reintentar.
                val current = _connectionState.value
                if (current == LkConnectionState.CONNECTED ||
                    current == LkConnectionState.CONNECTING) {
                    _lastDisconnectReason.value = LkDisconnectReason.SIGNAL_CLOSE
                    scope.launch {
                        cleanup()
                        _connectionState.value = LkConnectionState.DISCONNECTED
                        _participants.value = emptyList()
                        _videoTracks.value = emptyList()
                        _mediaState.value = LkMediaState()
                    }
                }
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

        // CRÍTICO: extraer ICE servers (STUN + TURN con credenciales) del JoinResponse.
        // Sin TURN, los clientes detrás de NAT estricta / CGNAT / VPN nunca consiguen
        // establecer el peer connection — el SFU los expulsa como
        // PEER_CONNECTION_DISCONNECTED tras ~20s sin que ICE complete.
        val iceServersTriples: List<IceServerInfo> =
            joinResponse?.iceServers?.map { srv ->
                IceServerInfo(
                    urls = srv.urls,
                    username = srv.username.ifBlank { null },
                    credential = srv.credential.ifBlank { null },
                )
            } ?: emptyList()
        log.d(tag = TAG) {
            "ICE servers del JoinResponse: ${iceServersTriples.size} entries — " +
            iceServersTriples.joinToString { it.urls.joinToString(",") }
        }

        // 1. Crear subscriber PRIMERO (el offer puede llegar durante setupPublisher)
        val sub = DesktopWebRtcManager()
        subscriberWebRtc = sub
        sub.initialize()
        if (iceServersTriples.isNotEmpty()) sub.setIceServers(iceServersTriples)
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
        // ICE servers también para publisher — DEBE estar antes de createOffer.
        if (iceServersTriples.isNotEmpty()) pub.setIceServers(iceServersTriples)
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
                when (state) {
                    WebRtcConnectionState.CONNECTED -> {
                        if (_connectionState.value != LkConnectionState.CONNECTED) {
                            _connectionState.value = LkConnectionState.CONNECTED
                            log.d(tag = TAG) { "Publisher RTC CONNECTED — sala lista" }
                            requestHandStateSync()
                        }
                    }
                    WebRtcConnectionState.FAILED -> {
                        log.e(tag = TAG) { "Publisher RTC FAILED — marcando ERROR" }
                        _connectionState.value = LkConnectionState.ERROR
                    }
                    // NO actuamos en DISCONNECTED/CLOSED: durante renegociaciones (al añadir
                    // cámara o screen share) el publisher pasa por estados transitorios y
                    // marcar RECONNECTING/ERROR aquí expulsa al usuario de la sala sin
                    // motivo. La señal real de pérdida de conexión es signalingClient.disconnect
                    // (manejado en onLeave/onDisconnected) o el estado FAILED.
                    else -> { /* mantener estado actual */ }
                }
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
        // El SFU de LiveKit exige un livekit.DataPacket protobuf en el data channel;
        // enviar JSON crudo hace que el SFU lo descarte y nadie lo reciba.
        val bytes = LiveKitProto.encodeUserDataPacket(json.encodeToByteArray())

        // Enviar via publisher data channel
        val pub = publisherWebRtc
        var sent = pub?.sendDataChannelMessage(bytes) ?: false
        if (!sent) {
            // Fallback: intentar via subscriber data channel
            val sub = subscriberWebRtc
            sent = sub?.sendDataChannelMessage(bytes) ?: false
        }
        if (!sent) {
            // Ninguno de los dos canales estaba disponible: si igual
            // agregábamos el mensaje al historial local abajo, se veía como
            // "enviado" aunque nadie más lo recibiera.
            log.e(tag = TAG) { "No hay data channel disponible para enviar chat" }
            throw IllegalStateException("No hay canal de datos disponible para enviar el mensaje")
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
        // Los peers reales (web / Android / iOS con el SDK oficial) envían un
        // livekit.DataPacket protobuf, no JSON crudo. Desenvolvemos el UserPacket para
        // obtener el payload y la identity del remitente (que el SFU añade al sobre).
        val packet = LiveKitProto.decodeUserDataPacket(bytes)
        val senderIdentity: String
        val text: String
        if (packet != null) {
            senderIdentity = packet.senderIdentity
            text = packet.payload.decodeToString()
        } else {
            // Fallback: peer legacy que envía JSON crudo (versión desktop antigua).
            senderIdentity = ""
            text = bytes.decodeToString()
        }

        try {
            log.d(tag = TAG) { "Data channel recibido (sender=$senderIdentity): $text" }

            val jsonObj = jsonParser.parseToJsonElement(text).jsonObject
            val type = jsonObj["type"]?.jsonPrimitive?.content
            if (type == "hand/raise" || type == "hand/lower" || type == "hand/sync/request" || type == "hand/sync/state") {
                handleHandDataMessage(jsonObj, senderIdentity)
                return
            }

            // Chat: {"author":"nombre", "message":"texto"} (mismo formato que web/Android)
            val author = jsonObj["author"]?.jsonPrimitive?.content
                ?: senderIdentity.ifEmpty { "Unknown" }
            val message = jsonObj["message"]?.jsonPrimitive?.content ?: return

            val msg = LkChatMessage(
                id = "remote-${currentTimeMs()}",
                senderIdentity = senderIdentity.ifEmpty { author },
                senderName = author,
                text = message,
                timestamp = currentTimeMs(),
                isLocal = false,
            )
            _chatMessages.value = _chatMessages.value + msg
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error parseando data channel message: ${e.message}" }
        }
    }

    private fun publishHandData(payload: String) {
        // Igual que el chat: hay que envolver en livekit.DataPacket/UserPacket.
        val bytes = LiveKitProto.encodeUserDataPacket(payload.encodeToByteArray())
        val sent = publisherWebRtc?.sendDataChannelMessage(bytes) ?: false
        if (!sent) {
            val fallbackSent = subscriberWebRtc?.sendDataChannelMessage(bytes) ?: false
            if (!fallbackSent) {
                log.w(tag = TAG) { "No hay data channel disponible para enviar estado de mano" }
            }
        }
    }

    private fun requestHandStateSync() {
        if (localIdentity.isBlank()) return
        publishHandData("""{"type":"hand/sync/request","at":${currentTimeMs()},"participantIdentity":"$localIdentity"}""")
        publishLocalHandState()
    }

    private fun publishLocalHandState() {
        if (localIdentity.isBlank()) return
        val safeName = localName.replace("\"", "\\\"")
        val at = raisedHands[localIdentity] ?: currentTimeMs()
        publishHandData("""{"type":"hand/sync/state","raised":$localHandRaised,"at":$at,"participantIdentity":"$localIdentity","author":"$safeName"}""")
    }

    /**
     * Deriva el estado de mano de un participante a partir de sus attributes ("handRaised")
     * o su metadata JSON ({"handRaised":true,..}), igual que hace el cliente web/Android.
     * Devuelve null si el participante no expone ese estado.
     */
    private fun handRaisedFromParticipant(info: LiveKitParticipantInfo): Boolean? {
        info.attributes["handRaised"]?.let { v ->
            return v.equals("true", ignoreCase = true) || v == "1"
        }
        if (info.metadata.isNotBlank()) {
            try {
                val obj = jsonParser.parseToJsonElement(info.metadata).jsonObject
                obj["handRaised"]?.jsonPrimitive?.content?.let { v ->
                    return v.equals("true", ignoreCase = true) || v == "1"
                }
            } catch (_: Exception) {
                // metadata no-JSON — ignorar
            }
        }
        return null
    }

    private fun handleHandDataMessage(jsonObj: JsonObject, envelopeIdentity: String) {
        val type = jsonObj["type"]?.jsonPrimitive?.content ?: return
        val at = jsonObj["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: currentTimeMs()
        val target = jsonObj["target"]?.jsonPrimitive?.content
        // La identity del remitente viene del sobre DataPacket (la añade el SFU y es la
        // fuente autoritativa). Los mensajes de mano del SDK oficial (Android/iOS/web) NO
        // incluyen participantIdentity en el JSON, así que sin el sobre se ignorarían.
        val sender = envelopeIdentity.ifEmpty {
            jsonObj["participantIdentity"]?.jsonPrimitive?.content
                ?: jsonObj["author"]?.jsonPrimitive?.content
                ?: target
        } ?: return

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
            "hand/sync/request" -> publishLocalHandState()
            "hand/sync/state" -> {
                val identity = jsonObj["participantIdentity"]?.jsonPrimitive?.content ?: sender
                val raised = jsonObj["raised"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
                if (identity == localIdentity) {
                    localHandRaised = raised
                }
                if (raised) {
                    raisedHands[identity] = at
                    remoteParticipants[identity]?.let { participant ->
                        remoteParticipants[identity] = participant.copy(isHandRaised = true, handRaisedAt = at)
                    }
                } else {
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
        // No reseteamos las preferencias de dispositivos (camera/mic/speaker/screen):
        // se conservan entre reconexiones para no obligar al usuario a re-seleccionar.
    }

    private fun extractJsonField(json: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1)
    }
}
