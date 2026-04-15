package com.eddyslarez.kmpsiprtc.services.conference

import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import cocoapods.LiveKit.*
import platform.AVFoundation.AVAudioSession
import platform.AVFoundation.AVAudioSessionCategoryPlayAndRecord
import platform.AVFoundation.AVAudioSessionPortOverrideSpeaker
import platform.AVFoundation.AVAudioSessionPortOverrideNone
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSData
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

actual class ConferenceLiveKitManager actual constructor() {

    private val TAG = "ConferenceLkManager"

    private var room: LKRoom? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var roomDelegate: ConferenceRoomDelegate? = null

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

    actual suspend fun connect(url: String, token: String, participantName: String) {
        if (_connectionState.value == LkConnectionState.CONNECTED) {
            log.w(tag = TAG) { "Ya conectado a conferencia" }
            return
        }

        _connectionState.value = LkConnectionState.CONNECTING

        try {
            val lkRoom = LKRoom()
            room = lkRoom

            // Configurar delegate para eventos
            roomDelegate = ConferenceRoomDelegate(
                onParticipantChanged = { updateParticipants() },
                onTrackChanged = { updateParticipants(); updateVideoTracks() },
                onConnectionChanged = { state -> _connectionState.value = state },
                onDataReceived = { data, participant -> handleDataReceived(data, participant) },
            )
            lkRoom.addDelegate(roomDelegate!!)

            // Conectar usando la API de LiveKit iOS
            lkRoom.connectWithUrl(url, token = token) { error ->
                if (error != null) {
                    NSLog("$TAG: Error conectando: ${error.localizedDescription}")
                    _connectionState.value = LkConnectionState.ERROR
                } else {
                    NSLog("$TAG: Conectado exitosamente")
                    _connectionState.value = LkConnectionState.CONNECTED
                    updateParticipants()
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error conectando a LiveKit iOS: ${e.message}" }
            _connectionState.value = LkConnectionState.ERROR
            room = null
            throw e
        }
    }

    actual suspend fun disconnect() {
        try {
            room?.disconnectWithCompletionHandler { }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error en disconnect: ${e.message}" }
        } finally {
            if (roomDelegate != null) {
                room?.removeDelegate(roomDelegate!!)
            }
            room = null
            roomDelegate = null
            _connectionState.value = LkConnectionState.DISCONNECTED
            _participants.value = emptyList()
            _videoTracks.value = emptyList()
            _mediaState.value = LkMediaState()
        }
    }

    actual suspend fun setMicrophoneEnabled(enabled: Boolean) {
        room?.localParticipant()?.setMicrophoneEnabled(enabled) { _ -> }
        _mediaState.value = _mediaState.value.copy(microphoneEnabled = enabled)
        updateParticipants()
    }

    actual suspend fun setCameraEnabled(enabled: Boolean) {
        room?.localParticipant()?.setCameraEnabled(enabled) { _ -> }
        _mediaState.value = _mediaState.value.copy(cameraEnabled = enabled)
        updateParticipants()
        updateVideoTracks()
    }

    actual suspend fun setScreenShareEnabled(enabled: Boolean) {
        room?.localParticipant()?.setScreenShareEnabled(enabled) { _ -> }
        _mediaState.value = _mediaState.value.copy(screenShareEnabled = enabled)
        updateParticipants()
        updateVideoTracks()
    }

    actual suspend fun loadDevices(): LkDevices {
        val cameras = mutableListOf<LkDevice>()
        val microphones = mutableListOf<LkDevice>()
        val speakers = mutableListOf<LkDevice>()

        // Enumerar camaras via AVCaptureDevice
        cameras.add(LkDevice("front", "Camara frontal"))
        cameras.add(LkDevice("back", "Camara trasera"))

        microphones.add(LkDevice("default", "Microfono por defecto"))
        speakers.add(LkDevice("speaker", "Altavoz"))
        speakers.add(LkDevice("earpiece", "Auricular"))

        return LkDevices(
            cameras = cameras,
            microphones = microphones,
            speakers = speakers,
            selectedCameraId = "front",
            selectedMicrophoneId = "default",
            selectedSpeakerId = "speaker"
        )
    }

    actual suspend fun selectCamera(deviceId: String) {
        val lkRoom = room ?: return
        val lp = lkRoom.localParticipant() ?: return

        // Buscar la publicación de cámara del participante local
        @Suppress("UNCHECKED_CAST")
        val publications = lp.trackPublications() as? Map<String, LKTrackPublication> ?: return
        val cameraPub = publications.values.firstOrNull { it.source() == LKTrackSourceCamera }
        val cameraTrack = cameraPub?.track() as? LKLocalVideoTrack

        if (cameraTrack != null) {
            val position = if (deviceId == "back") LKCameraPositionBack else LKCameraPositionFront
            cameraTrack.setCameraPosition(position)
            log.d(tag = TAG) { "Camera cambiada a: $deviceId" }
        }
    }

    actual suspend fun selectMicrophone(deviceId: String) {
        // En iOS, el micrófono es gestionado automáticamente por AVAudioSession
        log.d(tag = TAG) { "selectMicrophone: $deviceId (gestionado por AVAudioSession)" }
    }

    actual suspend fun selectSpeaker(deviceId: String) {
        try {
            val audioSession = AVAudioSession.sharedInstance()
            if (deviceId == "speaker") {
                audioSession.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, null)
            } else {
                audioSession.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, null)
            }
            log.d(tag = TAG) { "Speaker cambiado a: $deviceId" }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error cambiando speaker: ${e.message}" }
        }
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

    actual suspend fun sendChatMessage(text: String) {
        val lkRoom = room ?: return
        val lp = lkRoom.localParticipant() ?: return
        val localIdentity = lp.identity() ?: ""
        val localName = lp.name() ?: localIdentity

        val json = """{"author":"$localName","message":"$text"}"""
        val nsString = NSString.create(string = json)
        val nsData = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return

        try {
            lp.publishDataWithData(nsData, reliability = LKDataPublishReliabilityReliable) { error ->
                if (error != null) {
                    NSLog("$TAG: Error enviando chat: ${error.localizedDescription}")
                }
            }

            // Agregar mensaje local al historial
            val msg = LkChatMessage(
                id = "local-${platform.Foundation.NSDate().timeIntervalSince1970.toLong()}",
                senderIdentity = localIdentity,
                senderName = localName,
                text = text,
                timestamp = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000,
                isLocal = true,
                isSystem = false
            )
            _chatMessages.value = _chatMessages.value + msg
            log.d(tag = TAG) { "Chat enviado: $text" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error enviando chat: ${e.message}" }
        }
    }

    // ==================== INTERNAL ====================

    private fun handleDataReceived(data: NSData, participant: LKRemoteParticipant?) {
        try {
            val text = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: return
            val jsonObj = Json.parseToJsonElement(text).jsonObject
            val author = jsonObj["author"]?.jsonPrimitive?.contentOrNull ?: participant?.name() ?: "?"
            val message = jsonObj["message"]?.jsonPrimitive?.contentOrNull ?: return

            val senderIdentity = participant?.identity() ?: ""
            val localIdentity = room?.localParticipant()?.identity() ?: ""
            if (senderIdentity == localIdentity) return

            val msg = LkChatMessage(
                id = "remote-${platform.Foundation.NSDate().timeIntervalSince1970.toLong()}",
                senderIdentity = senderIdentity,
                senderName = author,
                text = message,
                timestamp = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000,
                isLocal = false,
                isSystem = false
            )
            _chatMessages.value = _chatMessages.value + msg
            log.d(tag = TAG) { "Chat recibido de $author: $message" }
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error parseando data recibida: ${e.message}" }
        }
    }

    private fun updateParticipants() {
        val lkRoom = room ?: return
        val all = mutableListOf<LkParticipant>()

        // Local participant
        lkRoom.localParticipant()?.let { lp ->
            all.add(
                LkParticipant(
                    identity = lp.identity() ?: "",
                    name = lp.name() ?: lp.identity() ?: "",
                    sid = lp.sid() ?: "",
                    isLocal = true,
                    isSpeaking = lp.isSpeaking(),
                    isAudioEnabled = lp.isMicrophoneEnabled(),
                    isVideoEnabled = lp.isCameraEnabled(),
                    isScreenSharing = lp.isScreenShareEnabled(),
                )
            )
        }

        // Remote participants
        @Suppress("UNCHECKED_CAST")
        val remotes = lkRoom.remoteParticipants() as? Map<String, LKRemoteParticipant> ?: emptyMap()
        remotes.values.forEach { rp ->
            all.add(
                LkParticipant(
                    identity = rp.identity() ?: "",
                    name = rp.name() ?: rp.identity() ?: "",
                    sid = rp.sid() ?: "",
                    isLocal = false,
                    isSpeaking = rp.isSpeaking(),
                    isAudioEnabled = rp.isMicrophoneEnabled(),
                    isVideoEnabled = rp.isCameraEnabled(),
                    isScreenSharing = rp.isScreenShareEnabled(),
                )
            )
        }

        _participants.value = all
    }

    private fun updateVideoTracks() {
        val lkRoom = room ?: return
        val tracks = mutableListOf<LkVideoTrackHandle>()

        fun addTracksFromParticipant(participant: LKParticipant) {
            @Suppress("UNCHECKED_CAST")
            val publications = participant.trackPublications() as? Map<String, LKTrackPublication> ?: return
            publications.values.forEach { pub ->
                val track = pub.track()
                if (track is LKVideoTrack) {
                    val isScreen = pub.source() == LKTrackSourceScreenShareVideo
                    tracks.add(
                        LkVideoTrackHandle(
                            participantIdentity = participant.identity() ?: "",
                            trackSid = pub.sid() ?: "",
                            nativeTrack = track,
                            isScreenShare = isScreen,
                        )
                    )
                }
            }
        }

        lkRoom.localParticipant()?.let { addTracksFromParticipant(it) }
        @Suppress("UNCHECKED_CAST")
        val remotes = lkRoom.remoteParticipants() as? Map<String, LKRemoteParticipant> ?: emptyMap()
        remotes.values.forEach { addTracksFromParticipant(it) }

        _videoTracks.value = tracks
    }
}

/**
 * Delegate para eventos del Room de LiveKit iOS.
 */
private class ConferenceRoomDelegate(
    private val onParticipantChanged: () -> Unit,
    private val onTrackChanged: () -> Unit,
    private val onConnectionChanged: (LkConnectionState) -> Unit,
    private val onDataReceived: ((NSData, LKRemoteParticipant?) -> Unit)? = null,
) : LKRoomDelegateProtocol {

    // Implementar metodos del protocolo delegate
    // Los nombres exactos dependen del header ObjC generado por el pod

    override fun room(room: LKRoom, participantDidConnect: LKRemoteParticipant) {
        onParticipantChanged()
    }

    override fun room(room: LKRoom, participantDidDisconnect: LKRemoteParticipant) {
        onParticipantChanged()
        onTrackChanged()
    }

    override fun room(room: LKRoom, participant: LKRemoteParticipant, didSubscribeTrack: LKTrackPublication) {
        onTrackChanged()
    }

    override fun room(room: LKRoom, participant: LKRemoteParticipant, didUnsubscribeTrack: LKTrackPublication) {
        onTrackChanged()
    }

    override fun roomDidDisconnect(room: LKRoom, error: platform.Foundation.NSError?) {
        onConnectionChanged(LkConnectionState.DISCONNECTED)
    }

    override fun roomIsReconnecting(room: LKRoom) {
        onConnectionChanged(LkConnectionState.RECONNECTING)
    }

    override fun roomDidReconnect(room: LKRoom) {
        onConnectionChanged(LkConnectionState.CONNECTED)
    }

    fun room(room: LKRoom, participant: LKRemoteParticipant, didReceiveData: NSData) {
        onDataReceived?.invoke(didReceiveData, participant)
    }
}
