package com.eddyslarez.kmpsiprtc.services.conference

import android.app.Application
import com.eddyslarez.kmpsiprtc.platform.log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.DataChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

actual class ConferenceLiveKitManager actual constructor() {

    private val TAG = "ConferenceLkManager"

    private var room: Room? = null
    private var applicationContext: Application? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    /**
     * Debe llamarse antes de connect() para proveer el contexto Android.
     */
    fun setApplicationContext(context: Application) {
        applicationContext = context
    }

    actual suspend fun connect(url: String, token: String, participantName: String) {
        val ctx = applicationContext
            ?: throw IllegalStateException("ApplicationContext no configurado. Llamar setApplicationContext() antes de connect()")

        if (_connectionState.value == LkConnectionState.CONNECTED) {
            log.w(tag = TAG) { "Ya conectado a conferencia" }
            return
        }

        _connectionState.value = LkConnectionState.CONNECTING
        log.d(tag = TAG) { "Conectando a LiveKit: $url" }

        try {
            // Crear Room con el SDK oficial
            val lkRoom = LiveKit.create(ctx)
            room = lkRoom

            // Observar eventos del room
            collectRoomEvents(lkRoom)

            // Conectar
            lkRoom.connect(
                url = url,
                token = token,
            )

            _connectionState.value = LkConnectionState.CONNECTED
            log.d(tag = TAG) { "Conectado exitosamente a LiveKit" }

            // Actualizar participantes iniciales
            updateParticipants()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error conectando a LiveKit: ${e.message}" }
            _connectionState.value = LkConnectionState.ERROR
            room?.disconnect()
            room = null
            throw e
        }
    }

    actual suspend fun disconnect() {
        log.d(tag = TAG) { "Desconectando de conferencia" }
        try {
            room?.disconnect()
        } catch (e: Exception) {
            log.w(tag = TAG) { "Error en disconnect: ${e.message}" }
        } finally {
            room = null
            _connectionState.value = LkConnectionState.DISCONNECTED
            _participants.value = emptyList()
            _videoTracks.value = emptyList()
            _mediaState.value = LkMediaState()
        }
    }

    actual suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val lp = room?.localParticipant ?: return
        lp.setMicrophoneEnabled(enabled)
        _mediaState.value = _mediaState.value.copy(microphoneEnabled = enabled)
        updateParticipants()
    }

    actual suspend fun setCameraEnabled(enabled: Boolean) {
        val lp = room?.localParticipant ?: return
        lp.setCameraEnabled(enabled)
        _mediaState.value = _mediaState.value.copy(cameraEnabled = enabled)
        updateParticipants()
        updateVideoTracks()
    }

    actual suspend fun setScreenShareEnabled(enabled: Boolean) {
        val lp = room?.localParticipant ?: return
        lp.setScreenShareEnabled(enabled)
        _mediaState.value = _mediaState.value.copy(screenShareEnabled = enabled)
        updateParticipants()
        updateVideoTracks()
    }

    actual suspend fun loadDevices(): LkDevices {
        // Enumerar dispositivos via Android APIs
        val cameras = mutableListOf<LkDevice>()
        val microphones = mutableListOf<LkDevice>()
        val speakers = mutableListOf<LkDevice>()

        // Camaras: frontal y trasera
        cameras.add(LkDevice("front", "Camara frontal"))
        cameras.add(LkDevice("back", "Camara trasera"))

        // Mic y speaker default
        microphones.add(LkDevice("default", "Microfono por defecto"))
        speakers.add(LkDevice("earpiece", "Auricular"))
        speakers.add(LkDevice("speaker", "Altavoz"))

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
        val lp = room?.localParticipant ?: return
        val videoTrack = lp.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val position = if (deviceId == "back") CameraPosition.BACK else CameraPosition.FRONT
        videoTrack.switchCamera(position = position)
    }

    actual suspend fun selectMicrophone(deviceId: String) {
        // LiveKit Android SDK gestiona el microfono automaticamente
        log.d(tag = TAG) { "selectMicrophone: $deviceId (gestionado por SDK)" }
    }

    actual suspend fun selectSpeaker(deviceId: String) {
        val ctx = applicationContext ?: return
        val audioManager = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return
        val useSpeaker = deviceId == "speaker"
        audioManager.isSpeakerphoneOn = useSpeaker
        log.d(tag = TAG) { "selectSpeaker: $deviceId, speakerOn=$useSpeaker" }
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
        val localIdentity = lkRoom.localParticipant.identity?.value ?: ""
        val localName = lkRoom.localParticipant.name ?: localIdentity

        val json = """{"author":"$localName","message":"$text"}"""
        val bytes = json.encodeToByteArray()

        try {
            lkRoom.localParticipant.publishData(
                data = bytes,
                reliability = DataChannel.DataReliability.RELIABLE
            )

            // Agregar mensaje local al historial
            val msg = LkChatMessage(
                id = "local-${System.currentTimeMillis()}",
                senderIdentity = localIdentity,
                senderName = localName,
                text = text,
                timestamp = System.currentTimeMillis(),
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

    private fun collectRoomEvents(lkRoom: Room) {
        scope.launch {
            lkRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> {
                        log.d(tag = TAG) { "Participante conectado: ${event.participant.identity}" }
                        updateParticipants()
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        log.d(tag = TAG) { "Participante desconectado: ${event.participant.identity}" }
                        updateParticipants()
                        updateVideoTracks()
                    }
                    is RoomEvent.TrackSubscribed -> {
                        log.d(tag = TAG) { "Track suscrito: ${event.track.sid} de ${event.participant.identity}" }
                        updateParticipants()
                        updateVideoTracks()
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        log.d(tag = TAG) { "Track desuscrito: ${event.track.sid}" }
                        updateParticipants()
                        updateVideoTracks()
                    }
                    is RoomEvent.TrackPublished -> {
                        log.d(tag = TAG) { "Track publicado: ${event.publication.sid}" }
                        updateParticipants()
                        updateVideoTracks()
                    }
                    is RoomEvent.TrackUnpublished -> {
                        log.d(tag = TAG) { "Track despublicado" }
                        updateParticipants()
                        updateVideoTracks()
                    }
                    is RoomEvent.TrackMuted -> {
                        updateParticipants()
                    }
                    is RoomEvent.TrackUnmuted -> {
                        updateParticipants()
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        updateParticipants()
                    }
                    is RoomEvent.Reconnecting -> {
                        _connectionState.value = LkConnectionState.RECONNECTING
                    }
                    is RoomEvent.Reconnected -> {
                        _connectionState.value = LkConnectionState.CONNECTED
                    }
                    is RoomEvent.DataReceived -> {
                        handleDataReceived(event.data, event.participant)
                    }
                    is RoomEvent.Disconnected -> {
                        _connectionState.value = LkConnectionState.DISCONNECTED
                        _participants.value = emptyList()
                        _videoTracks.value = emptyList()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleDataReceived(data: ByteArray, participant: Participant?) {
        try {
            val text = data.decodeToString()
            val jsonObj = Json.parseToJsonElement(text).jsonObject
            val author = jsonObj["author"]?.jsonPrimitive?.contentOrNull ?: participant?.name ?: "?"
            val message = jsonObj["message"]?.jsonPrimitive?.contentOrNull ?: return

            val senderIdentity = participant?.identity?.value ?: ""
            val localIdentity = room?.localParticipant?.identity?.value ?: ""

            // Ignorar mensajes propios (ya agregados en sendChatMessage)
            if (senderIdentity == localIdentity) return

            val msg = LkChatMessage(
                id = "remote-${System.currentTimeMillis()}",
                senderIdentity = senderIdentity,
                senderName = author,
                text = message,
                timestamp = System.currentTimeMillis(),
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
        val allParticipants = mutableListOf<LkParticipant>()

        // Local participant
        lkRoom.localParticipant.let { lp ->
            allParticipants.add(lp.toLkParticipant(isLocal = true))
        }

        // Remote participants
        lkRoom.remoteParticipants.values.forEach { rp ->
            allParticipants.add(rp.toLkParticipant(isLocal = false))
        }

        _participants.value = allParticipants
    }

    private fun updateVideoTracks() {
        val lkRoom = room ?: return
        val tracks = mutableListOf<LkVideoTrackHandle>()

        fun addTracksFrom(participant: Participant) {
            participant.trackPublications.values.forEach { pub ->
                val track = pub.track
                if (track is VideoTrack) {
                    val isScreen = pub.source == Track.Source.SCREEN_SHARE
                    tracks.add(
                        LkVideoTrackHandle(
                            participantIdentity = participant.identity?.value ?: "",
                            trackSid = pub.sid,
                            nativeTrack = track,
                            isScreenShare = isScreen,
                        )
                    )
                }
            }
        }

        addTracksFrom(lkRoom.localParticipant)
        lkRoom.remoteParticipants.values.forEach { addTracksFrom(it) }

        _videoTracks.value = tracks
    }

    private fun Participant.toLkParticipant(isLocal: Boolean): LkParticipant {
        val identityStr = identity?.value ?: ""
        val nameStr = name ?: identityStr
        val sidStr = sid.value

        val hasAudio = trackPublications.values.any {
            it.source == Track.Source.MICROPHONE && it.track?.enabled == true && !it.muted
        }
        val hasVideo = trackPublications.values.any {
            it.source == Track.Source.CAMERA && it.track != null && !it.muted
        }
        val hasScreenShare = trackPublications.values.any {
            it.source == Track.Source.SCREEN_SHARE && it.track != null
        }
        val videoSid = trackPublications.values.firstOrNull {
            it.source == Track.Source.CAMERA && it.track is VideoTrack
        }?.sid
        val screenSid = trackPublications.values.firstOrNull {
            it.source == Track.Source.SCREEN_SHARE && it.track is VideoTrack
        }?.sid

        return LkParticipant(
            identity = identityStr,
            name = nameStr,
            sid = sidStr,
            isLocal = isLocal,
            isSpeaking = isSpeaking,
            isAudioEnabled = hasAudio,
            isVideoEnabled = hasVideo,
            isScreenSharing = hasScreenShare,
            videoTrackSid = videoSid,
            screenShareTrackSid = screenSid,
        )
    }
}
