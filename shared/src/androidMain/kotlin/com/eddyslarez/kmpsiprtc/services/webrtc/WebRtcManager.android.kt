package com.eddyslarez.kmpsiprtc.services.webrtc


//
//
//import android.app.Application
//import android.content.Context
//import android.media.AudioAttributes
//import android.media.AudioDeviceInfo
//import android.media.AudioFocusRequest
//import android.media.AudioManager
//import android.os.Build
//import android.os.Handler
//import android.os.Looper
//import com.eddyslarez.kmpsiprtc.data.models.AccountInfo
//import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
//import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
//import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
//import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
//import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
//import com.eddyslarez.kmpsiprtc.data.models.SdpType
//import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
//import com.eddyslarez.kmpsiprtc.platform.AndroidContext
//import com.eddyslarez.kmpsiprtc.platform.log
//import com.shepeliev.webrtckmp.AudioStreamTrack
//import com.shepeliev.webrtckmp.IceCandidate
//import com.shepeliev.webrtckmp.IceServer
//import com.shepeliev.webrtckmp.MediaDeviceInfo
//import com.shepeliev.webrtckmp.MediaDevices
//import com.shepeliev.webrtckmp.MediaStreamTrackKind
//import com.shepeliev.webrtckmp.OfferAnswerOptions
//import com.shepeliev.webrtckmp.PeerConnection
//import com.shepeliev.webrtckmp.PeerConnectionState
//import com.shepeliev.webrtckmp.RtcConfiguration
//import com.shepeliev.webrtckmp.SessionDescription
//import com.shepeliev.webrtckmp.SessionDescriptionType
//import com.shepeliev.webrtckmp.audioTracks
//import com.shepeliev.webrtckmp.onConnectionStateChange
//import com.shepeliev.webrtckmp.onIceCandidate
//import com.shepeliev.webrtckmp.onTrack
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.flow.launchIn
//import kotlinx.coroutines.flow.onEach
//import kotlinx.coroutines.launch
//import org.webrtc.*
//import kotlin.invoke
//

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.eddyslarez.kmpsiprtc.data.models.AudioDevice
import com.eddyslarez.kmpsiprtc.data.models.AudioUnit
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitCompatibilities
import com.eddyslarez.kmpsiprtc.data.models.AudioUnitTypes
import com.eddyslarez.kmpsiprtc.data.models.DeviceConnectionState
import com.eddyslarez.kmpsiprtc.data.models.SdpType
import com.eddyslarez.kmpsiprtc.data.models.WebRtcConnectionState
import com.eddyslarez.kmpsiprtc.platform.AndroidContext
import com.eddyslarez.kmpsiprtc.platform.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume

actual fun createWebRtcManager(): WebRtcManager = AndroidWebRtcManager()



class AndroidWebRtcManager(
) : WebRtcManager {
    val appContext: Context = AndroidContext.get()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var listener: WebRtcEventListener? = null
    private var isInitialized = false
    private var isMuted = false
    private var isSettingRemoteDescription = false
    private var remoteAudioCapture: RemoteAudioCapture? = null
    var onRemoteAudioData: ((ByteArray) -> Unit)? = null

    private val eglBase = EglBase.create()
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    // Audio management
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false

    // Audio device tracking
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null

    // Audio device states
    private val audioDeviceState = AudioDeviceState()

    override fun initialize() {
        if (isInitialized) return

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )

            val options = PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            }

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(createAudioDeviceModule())
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            createPeerConnection()
            isInitialized = true

            log.d("AndroidWebRtcManager") { "WebRTC initialized successfully" }
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Failed to initialize WebRTC: ${e.message}" }
            throw e
        }
    }

    private fun createAudioDeviceModule(): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // ✅ CORRECCIÓN CRÍTICA: Configuración BUNDLE más flexible
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL

        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                log.d("AndroidWebRtcManager") { "ICE candidate: ${candidate.sdpMid}:${candidate.sdpMLineIndex}" }
                listener?.onIceCandidate(candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                log.d("AndroidWebRtcManager") { "Connection state changed: $newState" }
                listener?.onConnectionStateChange(
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            startAudioManager()
                            WebRtcConnectionState.CONNECTED
                        }
                        PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            stopAudioManager()
                            WebRtcConnectionState.DISCONNECTED
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            stopAudioManager()
                            WebRtcConnectionState.FAILED
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            stopAudioManager()
                            WebRtcConnectionState.CLOSED
                        }
                        else -> WebRtcConnectionState.NEW
                    }
                )
            }
            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                log.d("AndroidWebRtcManager") { "Remote track added" }
                val track = receiver?.track()
                if (track is AudioTrack) {
                    remoteAudioTrack = track
                    remoteAudioTrack?.setEnabled(true)

                    // ✅ AGREGAR: Iniciar captura de audio remoto
                    startRemoteAudioCapture()

                    listener?.onRemoteAudioTrack()
                }
            }
//            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
//                log.d("AndroidWebRtcManager") { "Remote track added" }
//                val track = receiver?.track()
//                if (track is AudioTrack) {
//                    remoteAudioTrack = track
//                    remoteAudioTrack?.setEnabled(true)
//                    listener?.onRemoteAudioTrack()
//                }
//            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                log.d("AndroidWebRtcManager") { "ICE connection state: $state" }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                log.d("AndroidWebRtcManager") { "ICE connection receiving: $receiving" }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                log.d("AndroidWebRtcManager") { "ICE gathering state: $state" }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                log.d("AndroidWebRtcManager") { "Signaling state: $state" }
                when (state) {
                    PeerConnection.SignalingState.STABLE -> {
                        isSettingRemoteDescription = false
                    }
                    PeerConnection.SignalingState.HAVE_REMOTE_OFFER -> {
                        isSettingRemoteDescription = false
                    }
                    else -> {}
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                log.d("AndroidWebRtcManager") { "ICE candidates removed" }
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })

        prepareAudioForCall()
    }


    // ✅ AGREGAR: Iniciar captura de audio remoto
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRemoteAudioCapture() {
        if (remoteAudioCapture != null) {
            log.w("AndroidWebRtcManager") { "Remote audio capture already started" }
            return
        }

        remoteAudioCapture = RemoteAudioCapture { audioBytes ->
            // Callback cuando llega audio remoto del otro usuario
            onRemoteAudioData?.invoke(audioBytes)
        }

        // Pequeño delay para que WebRTC establezca el audio
        Handler(Looper.getMainLooper()).postDelayed({
            remoteAudioCapture?.startCapture()
            log.d("AndroidWebRtcManager") { "Remote audio capture initialized" }
        }, 500)
    }

    // ✅ AGREGAR: Detener captura remota
    private fun stopRemoteAudioCapture() {
        remoteAudioCapture?.stopCapture()
        remoteAudioCapture = null
        log.d("AndroidWebRtcManager") { "Remote audio capture stopped" }
    }

    override suspend fun createOffer(): String = withContext(Dispatchers.IO) {
        log.d("AndroidWebRtcManager") { "Creating offer..." }

        if (!isInitialized) {
            initialize()
        }

        if (peerConnection == null) {
            log.w("AndroidWebRtcManager") { "PeerConnection is null, creating now..." }
            createPeerConnection()
        }

        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")

        startAudioManager()

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        val sdp = suspendCancellableCoroutine<String> { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    log.d("AndroidWebRtcManager") { "Offer created successfully" }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            log.d("AndroidWebRtcManager") { "Local description set successfully" }
                            cont.resume(desc.description)
                        }
                        override fun onSetFailure(error: String?) {
                            log.e("AndroidWebRtcManager") { "Failed to set local description: $error" }
                            cont.resumeWith(Result.failure(Exception("Failed to set local description: $error")))
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String?) {
                    log.e("AndroidWebRtcManager") { "Failed to create offer: $error" }
                    cont.resumeWith(Result.failure(Exception("Failed to create offer: $error")))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }
        sdp
    }


    override suspend fun createAnswer(offerSdp: String): String = withContext(Dispatchers.IO) {
        log.d("AndroidWebRtcManager") { "Creating answer..." }

        if (offerSdp.isBlank()) {
            throw IllegalArgumentException("Offer SDP cannot be empty")
        }

        // Inicializar PeerConnectionFactory y PeerConnection si no está
        if (!isInitialized) {
            initialize()
        }

        // 1️⃣ Aplicar remote SDP y suspender hasta que termine
        setRemoteDescription(offerSdp, SdpType.OFFER)

        // 2️⃣ Obtener PeerConnection
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")

        // 3️⃣ Verificar estado de señalización
        val currentState = pc.signalingState()
        log.d("AndroidWebRtcManager") { "Current signaling state: $currentState" }
        if (currentState != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
            throw IllegalStateException("Cannot create answer in state: $currentState. Expected HAVE_REMOTE_OFFER")
        }

        // 4️⃣ Iniciar audio local
        startAudioManager()

        // 5️⃣ Configurar constraints para respuesta
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        // 6️⃣ Crear answer suspendido
        suspendCancellableCoroutine<String> { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    log.d("AndroidWebRtcManager") { "Answer created successfully" }

                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            log.d("AndroidWebRtcManager") { "Local description set successfully" }
                            cont.resume(desc.description)
                        }

                        override fun onSetFailure(error: String?) {
                            log.e("AndroidWebRtcManager") { "Failed to set local description: $error" }
                            cont.resumeWith(Result.failure(Exception("Failed to set local description: $error")))
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String?) {
                    log.e("AndroidWebRtcManager") { "Failed to create answer: $error" }
                    cont.resumeWith(Result.failure(Exception("Failed to create answer: $error")))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }
    }
    private fun prepareLocalAudio() {
        val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        val audioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack = audioTrack
        localAudioSource = audioSource

        val stream = peerConnectionFactory?.createLocalMediaStream("ARDAMS")
        stream?.addTrack(audioTrack)
        peerConnection?.addStream(stream)

        startAudioManager() // Inicia captura/reproducción
    }

    // 🔹 Suspende hasta que el remote SDP esté aplicado
    override suspend fun setRemoteDescription(offerSdp: String, type: SdpType) = suspendCancellableCoroutine<Unit> { cont ->
        isSettingRemoteDescription = true
        val sdp = SessionDescription(
            if (type == SdpType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
            offerSdp
        )
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isSettingRemoteDescription = false
                cont.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                isSettingRemoteDescription = false
                cont.resumeWith(Result.failure(Exception("Failed to set remote description: $error")))
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }


//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
//        withContext(Dispatchers.IO) {
//            log.d("AndroidWebRtcManager") { "Setting remote description: $type" }
//
//            val sdpType = when (type) {
//                SdpType.OFFER -> SessionDescription.Type.OFFER
//                SdpType.ANSWER -> SessionDescription.Type.ANSWER
//            }
//            val sessionDescription = SessionDescription(sdpType, sdp)
//
//            // ✅ CORRECCIÓN: Manejar mejor el setting de remote description
//            isSettingRemoteDescription = true
//
//            try {
//                val result = suspendCancellableCoroutine<Boolean> { cont ->
//                    peerConnection?.setRemoteDescription(object : SdpObserver {
//                        override fun onSetSuccess() {
//                            log.d("AndroidWebRtcManager") { "Remote description set successfully" }
//                            isSettingRemoteDescription = false
//                            cont.resume(true)
//                        }
//
//                        override fun onSetFailure(error: String?) {
//                            log.e("AndroidWebRtcManager") { "Failed to set remote description: $error" }
//                            isSettingRemoteDescription = false
//
//                            // ✅ CORRECCIÓN CRÍTICA: Si falla por BUNDLE, recrear conexión con configuración diferente
//                            if (error?.contains("bundle") == true || error?.contains("BUNDLE") == true) {
//                                log.d("AndroidWebRtcManager") { "BUNDLE error detected, recreating peer connection..." }
//                                recreatePeerConnectionWithCompatConfig()
//                                // Reintentar después de recrear
//                                Handler(Looper.getMainLooper()).postDelayed({
//                                    try {
//                                        peerConnection?.setRemoteDescription(this, sessionDescription)
//                                    } catch (e: Exception) {
//                                        cont.resume(false)
//                                    }
//                                }, 100)
//                            } else {
//                                cont.resume(false)
//                            }
//                        }
//
//                        override fun onCreateSuccess(p0: SessionDescription?) {}
//                        override fun onCreateFailure(p0: String?) {}
//                    }, sessionDescription)
//                }
//
//                if (!result) {
//                    throw Exception("Failed to set remote description")
//                }
//            } catch (e: Exception) {
//                isSettingRemoteDescription = false
//                throw e
//            }
//        }
//    }
    suspend fun waitForRemoteOfferState(timeoutMs: Long = 2000) {
        val pc = peerConnection ?: return
        var waited = 0L
        while (pc.signalingState() != PeerConnection.SignalingState.HAVE_REMOTE_OFFER && waited < timeoutMs) {
            delay(50)
            waited += 50
        }
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
            throw IllegalStateException("Timeout waiting for remote offer state")
        }
    }

    private fun recreatePeerConnectionWithCompatConfig() {
        log.d("AndroidWebRtcManager") { "Recreating peer connection with compatible configuration..." }

        closePeerConnection()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // ✅ CONFIGURACIÓN COMPATIBLE: No forzar BUNDLE
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL


        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                log.d("AndroidWebRtcManager") { "ICE candidate from new PC: ${candidate.sdpMid}:${candidate.sdpMLineIndex}" }
                listener?.onIceCandidate(candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                log.d("AndroidWebRtcManager") { "New PC connection state: $newState" }
                listener?.onConnectionStateChange(
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                        PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                        PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                        PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                        PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                        else -> WebRtcConnectionState.NEW
                    }
                )
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                log.d("AndroidWebRtcManager") { "Remote track added to new PC" }
                val track = receiver?.track()
                if (track is AudioTrack) {
                    remoteAudioTrack = track
                    remoteAudioTrack?.setEnabled(true)
                    listener?.onRemoteAudioTrack()
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                when (state) {
                    PeerConnection.SignalingState.STABLE -> {
                        isSettingRemoteDescription = false
                    }
                    PeerConnection.SignalingState.HAVE_REMOTE_OFFER -> {
                        isSettingRemoteDescription = false
                    }
                    else -> {}
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })

        // Re-preparar audio
        prepareAudioForCall()
    }

    private fun recreatePeerConnection() {
        log.d("AndroidWebRtcManager") { "Recreating peer connection..." }

        // ✅ FIX: NO cerrar la conexión, solo crear una nueva instancia
        val wasInitialized = isInitialized
        peerConnection = null

        createPeerConnection()

        if (wasInitialized) {
            // Restaurar el audio manager si estaba activo
            startAudioManager()
        }
    }


    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        withContext(Dispatchers.IO) {
            log.d("AndroidWebRtcManager") { "Adding ICE candidate: $sdpMid:$sdpMLineIndex" }

            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
        }
    }

    override fun prepareAudioForCall() {
        log.d("AndroidWebRtcManager") { "Preparing audio for call" }

        // ✅ FIX: Limpiar tracks anteriores antes de crear nuevos
        localAudioTrack?.let {
            try {
                it.setEnabled(false)
                it.dispose()
            } catch (e: Exception) {
                log.w("AndroidWebRtcManager") { "Error disposing old audio track: ${e.message}" }
            }
        }

        localAudioSource?.let {
            try {
                it.dispose()
            } catch (e: Exception) {
                log.w("AndroidWebRtcManager") { "Error disposing old audio source: ${e.message}" }
            }
        }

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("AUDIO_TRACK_${System.currentTimeMillis()}", localAudioSource)
        localAudioTrack?.setEnabled(true)

        peerConnection?.addTrack(localAudioTrack, listOf("audio_stream"))

        log.d("AndroidWebRtcManager") { "Audio track created and added to peer connection" }
    }

    // Audio Management
    private fun startAudioManager() {
        log.d("AndroidWebRtcManager") { "Starting audio manager" }

        // Save current state
        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute

        // Request audio focus
        requestAudioFocus()

        // Set communication mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false

        // Update audio devices
        audioDeviceState.updateAvailableDevices(audioManager)
        selectDefaultAudioDevice()
    }

    private fun stopAudioManager() {
        log.d("AndroidWebRtcManager") { "Stopping audio manager" }

        // Restore original settings
        audioManager.mode = savedAudioMode
        audioManager.isSpeakerphoneOn = savedIsSpeakerPhoneOn
        audioManager.isMicrophoneMute = savedIsMicrophoneMute

        // Release audio focus
        abandonAudioFocus()

        audioDeviceState.setCurrentDevice(AudioDeviceType.NONE)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            log.d("AndroidWebRtcManager") { "Audio focus gained" }
                            setAudioEnabled(true)
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            log.d("AndroidWebRtcManager") { "Audio focus lost" }
                            setAudioEnabled(false)
                        }
                    }
                }
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> setAudioEnabled(true)
                        AudioManager.AUDIOFOCUS_LOSS -> setAudioEnabled(false)
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun selectDefaultAudioDevice() {
        val availableDevices = audioDeviceState.getAvailableDevices()

        val defaultDevice = when {
            availableDevices.contains(AudioDeviceType.BLUETOOTH) -> {
                log.d("AndroidWebRtcManager") { "Selecting Bluetooth as default device" }
                AudioDeviceType.BLUETOOTH
            }
            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> {
                log.d("AndroidWebRtcManager") { "Selecting Wired Headset as default device" }
                AudioDeviceType.WIRED_HEADSET
            }
            else -> {
                log.d("AndroidWebRtcManager") { "Selecting Earpiece as default device" }
                AudioDeviceType.EARPIECE
            }
        }

        // ✅ FIX: Pequeño delay para asegurar que el dispositivo está listo
        Handler(Looper.getMainLooper()).postDelayed({
            selectAudioDevice(defaultDevice)
        }, 200)
    }

    private fun selectAudioDevice(device: AudioDeviceType): Boolean {
        if (!audioDeviceState.getAvailableDevices().contains(device)) {
            log.d("AndroidWebRtcManager") { "Audio device not available: $device" }
            return false
        }

        log.d("AndroidWebRtcManager") { "Selecting audio device: $device" }

        return try {
            when (device) {
                AudioDeviceType.SPEAKER_PHONE -> {
                    audioManager.isSpeakerphoneOn = true
                    stopBluetoothSco()
                }
                AudioDeviceType.EARPIECE -> {
                    audioManager.isSpeakerphoneOn = false
                    stopBluetoothSco()
                }
                AudioDeviceType.WIRED_HEADSET -> {
                    audioManager.isSpeakerphoneOn = false
                    stopBluetoothSco()
                }
                AudioDeviceType.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    startBluetoothSco()
                }
                AudioDeviceType.NONE -> return false
            }

            audioDeviceState.setCurrentDevice(device)

            // Update current devices
            currentOutputDevice = when (device) {
                AudioDeviceType.BLUETOOTH -> createBluetoothDevice()
                AudioDeviceType.SPEAKER_PHONE -> createSpeakerDevice()
                AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetDevice()
                AudioDeviceType.EARPIECE -> createEarpieceDevice()
                else -> null
            }

            listener?.onAudioDeviceChanged(currentOutputDevice)
            true
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Error selecting audio device: ${e.message}" }
            false
        }
    }

    private fun startBluetoothSco() {
        if (audioManager.isBluetoothScoAvailableOffCall) {
            log.d("AndroidWebRtcManager") { "Starting Bluetooth SCO..." }

            // ✅ FIX: Asegurar que el modo de comunicación esté activado primero
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Pequeño delay para que el modo se establezca
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    log.d("AndroidWebRtcManager") { "Bluetooth SCO started successfully" }
                } catch (e: Exception) {
                    log.e("AndroidWebRtcManager") { "Error starting Bluetooth SCO: ${e.message}" }
                }
            }, 100)
        } else {
            log.w("AndroidWebRtcManager") { "Bluetooth SCO not available" }
        }
    }

    private fun stopBluetoothSco() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            log.d("AndroidWebRtcManager") { "Bluetooth SCO stopped" }
        }
    }

    override fun closePeerConnection() {
        log.d("AndroidWebRtcManager") { "Closing peer connection" }
        stopAudioManager()
        stopRemoteAudioCapture()

        // ✅ FIX: NO disponer los tracks aquí, solo cerrar la conexión
        peerConnection?.close()
        peerConnection = null
        isSettingRemoteDescription = false

        // Limpiar referencias sin dispose
        remoteAudioTrack = null
    }

    override fun dispose() {
        log.d("AndroidWebRtcManager") { "Disposing WebRTC manager" }
        closePeerConnection()

        // ✅ FIX: Ahora sí disponer los tracks
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            log.w("AndroidWebRtcManager") { "Error disposing local audio track: ${e.message}" }
        }

        try {
            localAudioSource?.dispose()
        } catch (e: Exception) {
            log.w("AndroidWebRtcManager") { "Error disposing local audio source: ${e.message}" }
        }

        try {
            remoteAudioTrack?.dispose()
        } catch (e: Exception) {
            log.w("AndroidWebRtcManager") { "Error disposing remote audio track: ${e.message}" }
        }
        remoteAudioCapture?.dispose()

        localAudioTrack = null
        localAudioSource = null
        remoteAudioTrack = null

        peerConnectionFactory?.dispose()
        eglBase.release()
        isInitialized = false
        isSettingRemoteDescription = false
    }

    override fun setListener(listener: WebRtcEventListener?) {
        this.listener = listener
    }

    override fun isInitialized() = isInitialized

    override fun setMuted(muted: Boolean) {
        isMuted = muted
        localAudioTrack?.setEnabled(!muted)
        audioManager.isMicrophoneMute = muted
    }

    override fun isMuted() = isMuted

    override fun getLocalDescription(): String? = peerConnection?.localDescription?.description

    override fun getConnectionState(): WebRtcConnectionState =
        when (peerConnection?.connectionState()) {
            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
            PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
            else -> WebRtcConnectionState.NEW
        }

    // Audio device management implementations
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== AUDIO DIAGNOSIS ===")
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("PeerConnection: ${peerConnection != null}")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Muted: $isMuted")
            appendLine("Audio Mode: ${audioManager.mode}")
            appendLine("Speaker On: ${audioManager.isSpeakerphoneOn}")
            appendLine("Mic Mute: ${audioManager.isMicrophoneMute}")
            appendLine("Bluetooth SCO: ${audioManager.isBluetoothScoOn}")
            appendLine("Current Audio Device: ${audioDeviceState.getCurrentDevice()}")
            appendLine("Available Devices: ${audioDeviceState.getAvailableDevices()}")
        }
    }

    override fun prepareAudioForIncomingCall() {
        prepareAudioForCall()
        startAudioManager()
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)
            peerConnection?.setLocalDescription(SimpleSdpObserver(), description)
            true
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Failed to apply modified SDP: ${e.message}" }
            false
        }
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        // Implementation for media direction if needed
    }

    override fun setAudioEnabled(enabled: Boolean) {
        try {
            localAudioTrack?.setEnabled(enabled)
            remoteAudioTrack?.setEnabled(enabled)
            audioManager.isMicrophoneMute = !enabled
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Error setting audio enabled: ${e.message}" }
            // ✅ FIX: Si el track está disposed, recrear
            if (e.message?.contains("disposed") == true) {
                log.d("AndroidWebRtcManager") { "Track was disposed, recreating audio..." }
                prepareAudioForCall()
            }
        }
    }

    // Audio device routing
    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
        log.d("AndroidWebRtcManager") { "Setting active audio route to: $audioUnitType" }

        val deviceType = when (audioUnitType) {
            AudioUnitTypes.BLUETOOTH -> AudioDeviceType.BLUETOOTH
            AudioUnitTypes.SPEAKER -> AudioDeviceType.SPEAKER_PHONE
            AudioUnitTypes.HEADSET -> AudioDeviceType.WIRED_HEADSET
            AudioUnitTypes.EARPIECE -> AudioDeviceType.EARPIECE
            else -> {
                log.w("AndroidWebRtcManager") { "Unsupported audio unit type: $audioUnitType" }
                return false
            }
        }

        return selectAudioDevice(deviceType)
    }

    override fun getActiveAudioRoute(): AudioUnitTypes? {
        return when (audioDeviceState.getCurrentDevice()) {
            AudioDeviceType.BLUETOOTH -> AudioUnitTypes.BLUETOOTH
            AudioDeviceType.SPEAKER_PHONE -> AudioUnitTypes.SPEAKER
            AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
            AudioDeviceType.EARPIECE -> AudioUnitTypes.EARPIECE
            AudioDeviceType.NONE -> null
        }
    }

    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
        return audioDeviceState.getAvailableDevices().mapNotNull { deviceType ->
            when (deviceType) {
                AudioDeviceType.BLUETOOTH -> AudioUnitTypes.BLUETOOTH
                AudioDeviceType.SPEAKER_PHONE -> AudioUnitTypes.SPEAKER
                AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
                AudioDeviceType.EARPIECE -> AudioUnitTypes.EARPIECE
                AudioDeviceType.NONE -> null
            }
        }.toSet()
    }

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        audioDeviceState.updateAvailableDevices(audioManager)

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        // Input devices
        inputDevices.add(createBuiltinMicDevice())
        if (audioDeviceState.getAvailableDevices().contains(AudioDeviceType.WIRED_HEADSET)) {
            inputDevices.add(createWiredHeadsetMicDevice())
        }
        if (audioDeviceState.getAvailableDevices().contains(AudioDeviceType.BLUETOOTH)) {
            inputDevices.add(createBluetoothMicDevice())
        }

        // Output devices
        audioDeviceState.getAvailableDevices().forEach { deviceType ->
            when (deviceType) {
                AudioDeviceType.EARPIECE -> outputDevices.add(createEarpieceDevice())
                AudioDeviceType.SPEAKER_PHONE -> outputDevices.add(createSpeakerDevice())
                AudioDeviceType.WIRED_HEADSET -> outputDevices.add(createWiredHeadsetDevice())
                AudioDeviceType.BLUETOOTH -> outputDevices.add(createBluetoothDevice())
                AudioDeviceType.NONE -> {}
            }
        }

        return Pair(inputDevices, outputDevices)
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d("AndroidWebRtcManager") { "Changing audio output to: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            return false
        }

        return try {
            val success = when (device.descriptor) {
                "bluetooth" -> selectAudioDevice(AudioDeviceType.BLUETOOTH)
                "speaker" -> selectAudioDevice(AudioDeviceType.SPEAKER_PHONE)
                "wired_headset" -> selectAudioDevice(AudioDeviceType.WIRED_HEADSET)
                "earpiece" -> selectAudioDevice(AudioDeviceType.EARPIECE)
                else -> false
            }

            if (success) {
                currentOutputDevice = device
                listener?.onAudioDeviceChanged(device)
            }

            success
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Error changing audio output: ${e.message}" }
            false
        }
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d("AndroidWebRtcManager") { "Changing audio input to: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            return false
        }

        return try {
            val success = when {
                device.descriptor.startsWith("bluetooth") -> selectAudioDevice(AudioDeviceType.BLUETOOTH)
                device.descriptor == "wired_headset_mic" -> selectAudioDevice(AudioDeviceType.WIRED_HEADSET)
                else -> selectAudioDevice(AudioDeviceType.EARPIECE)
            }

            if (success) {
                currentInputDevice = device
                listener?.onAudioDeviceChanged(device)
            }

            success
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Error changing audio input: ${e.message}" }
            false
        }
    }

    override fun getCurrentInputDevice(): AudioDevice? {
        return currentInputDevice ?: when (audioDeviceState.getCurrentDevice()) {
            AudioDeviceType.BLUETOOTH -> createBluetoothMicDevice()
            AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetMicDevice()
            else -> createBuiltinMicDevice()
        }
    }

    override fun getCurrentOutputDevice(): AudioDevice? {
        return currentOutputDevice ?: when (audioDeviceState.getCurrentDevice()) {
            AudioDeviceType.BLUETOOTH -> createBluetoothDevice()
            AudioDeviceType.SPEAKER_PHONE -> createSpeakerDevice()
            AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetDevice()
            AudioDeviceType.EARPIECE -> createEarpieceDevice()
            AudioDeviceType.NONE -> null
        }
    }

    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
        log.d("AndroidWebRtcManager") { "Bluetooth connection changed: $isConnected" }

        audioDeviceState.updateAvailableDevices(audioManager)

        if (isConnected) {
            // Small delay to ensure device is ready
            Handler(Looper.getMainLooper()).postDelayed({
                if (audioDeviceState.getAvailableDevices().contains(AudioDeviceType.BLUETOOTH)) {
                    selectAudioDevice(AudioDeviceType.BLUETOOTH)
                }
            }, 300)
        } else {
            // If Bluetooth disconnected, switch to next best option
            selectDefaultAudioDevice()
        }
    }

    override fun refreshAudioDevicesWithBluetoothPriority() {
        log.d("AndroidWebRtcManager") { "Refreshing audio devices with Bluetooth priority" }

        audioDeviceState.updateAvailableDevices(audioManager)
        if (audioDeviceState.getAvailableDevices().contains(AudioDeviceType.BLUETOOTH)) {
            selectAudioDevice(AudioDeviceType.BLUETOOTH)
        }
    }

    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
        return setActiveAudioRoute(audioUnitType)
    }

    override fun getAvailableAudioUnits(): Set<AudioUnit> {
        val routes = getAvailableAudioRoutes()
        val currentRoute = getActiveAudioRoute()

        return routes.map { type ->
            AudioUnit(
                type = type,
                capability = AudioUnitCompatibilities.ALL,
                isCurrent = type == currentRoute,
                isDefault = type == AudioUnitTypes.EARPIECE
            )
        }.toSet()
    }

    override fun getCurrentActiveAudioUnit(): AudioUnit? {
        val activeRoute = getActiveAudioRoute() ?: return null

        return AudioUnit(
            type = activeRoute,
            capability = AudioUnitCompatibilities.ALL,
            isCurrent = true,
            isDefault = activeRoute == AudioUnitTypes.EARPIECE
        )
    }

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        if (!isInitialized || peerConnection == null) return false

        return try {
            val audioSender = peerConnection?.senders?.find { sender ->
                sender.track()?.kind() == "audio"
            }

            // Note: DTMF support may require additional setup
            // This is a simplified implementation
            log.d("AndroidWebRtcManager") { "Sending DTMF tones: $tones" }
            true
        } catch (e: Exception) {
            log.e("AndroidWebRtcManager") { "Error sending DTMF: ${e.message}" }
            false
        }
    }

    // Helper methods to create AudioDevice objects
    private fun createBuiltinMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Built-in Microphone",
            descriptor = "builtin_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.MICROPHONE,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.EARPIECE,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 10,
            vendorInfo = "Built-in"
        )
    }

    private fun createWiredHeadsetMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset Microphone",
            descriptor = "wired_headset_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.WIRED_HEADSET,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = null
        )
    }

    private fun createBluetoothMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Bluetooth Microphone",
            descriptor = "bluetooth_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.BLUETOOTH,
                isDefault = false
            ),
            connectionState = if (audioDeviceState.getAvailableDevices().contains(AudioDeviceType.BLUETOOTH))
                DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = null
        )
    }

    private fun createEarpieceDevice(): AudioDevice {
        return AudioDevice(
            name = "Earpiece",
            descriptor = "earpiece",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.EARPIECE,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 5,
            vendorInfo = "Built-in"
        )
    }

    private fun createSpeakerDevice(): AudioDevice {
        return AudioDevice(
            name = "Speaker",
            descriptor = "speaker",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.SPEAKER,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.SPEAKER_PHONE,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 15,
            vendorInfo = "Built-in"
        )
    }

    private fun createWiredHeadsetDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset",
            descriptor = "wired_headset",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.WIRED_HEADSET,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = null
        )
    }

    private fun createBluetoothDevice(): AudioDevice {
        return AudioDevice(
            name = "Bluetooth",
            descriptor = "bluetooth",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = audioDeviceState.getCurrentDevice() == AudioDeviceType.BLUETOOTH,
                isDefault = false
            ),
            connectionState = if (audioDeviceState.getAvailableDevices().contains(AudioDeviceType.BLUETOOTH))
                DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = null
        )
    }
}

/**
 * Simple SDP observer implementation
 */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
        log.d("SimpleSdpObserver") { "SDP created successfully" }
    }

    override fun onSetSuccess() {
        log.d("SimpleSdpObserver") { "SDP set successfully" }
    }

    override fun onCreateFailure(error: String?) {
        log.e("SimpleSdpObserver") { "SDP creation failed: $error" }
    }

    override fun onSetFailure(error: String?) {
        log.e("SimpleSdpObserver") { "SDP set failed: $error" }
    }
}

/**
 * Audio device state management
 */
class AudioDeviceState {
    private val _currentDevice = MutableStateFlow(AudioDeviceType.NONE)
    private val _availableDevices = MutableStateFlow<List<AudioDeviceType>>(emptyList())

    fun getCurrentDevice(): AudioDeviceType = _currentDevice.value
    fun getAvailableDevices(): List<AudioDeviceType> = _availableDevices.value

    fun setCurrentDevice(device: AudioDeviceType) {
        _currentDevice.value = device
    }

    fun updateAvailableDevices(audioManager: AudioManager) {
        val devices = mutableListOf<AudioDeviceType>()

        // Always available
        devices.add(AudioDeviceType.EARPIECE)
        devices.add(AudioDeviceType.SPEAKER_PHONE)

        // Check for connected devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            for (deviceInfo in audioDevices) {
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        if (!devices.contains(AudioDeviceType.WIRED_HEADSET)) {
                            devices.add(AudioDeviceType.WIRED_HEADSET)
                        }
                    }

                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        if (!devices.contains(AudioDeviceType.BLUETOOTH)) {
                            devices.add(AudioDeviceType.BLUETOOTH)
                        }
                    }
                }
            }
        } else {
            // For older versions
            if (audioManager.isWiredHeadsetOn) {
                devices.add(AudioDeviceType.WIRED_HEADSET)
            }
            if (audioManager.isBluetoothScoAvailableOffCall) {
                devices.add(AudioDeviceType.BLUETOOTH)
            }
        }

        _availableDevices.value = devices
    }
}

enum class AudioDeviceType {
    SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
}

/**
 * Implementación vacía de [SdpObserver] para simplificar el uso.
 * Puedes sobreescribir solo los métodos que necesites.
 */


//
//
//
//class AndroidWebRtcManager() : WebRtcManager {
//    private val TAG = "AndroidWebRtcManager"
//    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
//
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioStreamTrack? = null
//    private var remoteAudioTrack: AudioStreamTrack? = null
//    private var webRtcEventListener: WebRtcEventListener? = null
//    private var isInitialized = false
//    private var isLocalAudioReady = false
//    private val context: Context = AndroidContext.get()
//
//    // Simplified audio management
//    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//    private var audioFocusRequest: AudioFocusRequest? = null
//    private var savedAudioMode = AudioManager.MODE_NORMAL
//    private var savedIsSpeakerPhoneOn = false
//    private var savedIsMicrophoneMute = false
//
//    // Current device tracking
//    private var currentInputDevice: AudioDevice? = null
//    private var currentOutputDevice: AudioDevice? = null
//
//    // Audio device states
//    private val _currentAudioDevice = MutableStateFlow(AudioDeviceType.EARPIECE)
//    val currentAudioDevice: StateFlow<AudioDeviceType> = _currentAudioDevice
//
//    private val _availableAudioDevices = MutableStateFlow<List<AudioDeviceType>>(emptyList())
//    val availableAudioDevices: StateFlow<List<AudioDeviceType>> = _availableAudioDevices
//
//
//    enum class AudioDeviceType {
//        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
//    }
//
//    override fun initialize() {
//        log.d(TAG) { "Initializing WebRTC Manager..." }
//        if (!isInitialized) {
//            initializePeerConnection()
//            coroutineScope.launch {
//                getAudioInputDevices()
//            }
//            isInitialized = true
//        }
//    }
//
//    override fun closePeerConnection() {
//        log.d(TAG) { "Closing peer connection" }
//        cleanupCall()
//    }
//
//    override fun dispose() {
//        log.d(TAG) { "Disposing WebRTC Manager resources..." }
//
//        try {
//            stopAudioManager()
//            cleanupCall()
//            isInitialized = false
//            isLocalAudioReady = false
//            currentInputDevice = null
//            currentOutputDevice = null
//        } catch (e: Exception) {
//            log.e(TAG) { "Error during disposal: ${e.message}" }
//        }
//    }
//
//    override suspend fun createOffer(): String {
//        log.d(TAG) { "Creating SDP offer..." }
//
//        if (!isInitialized) {
//            initialize()
//        } else {
//            startAudioManager()
//        }
//
//        val peerConn = peerConnection ?: run {
//            initializePeerConnection()
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        if (!isLocalAudioReady) {
//            isLocalAudioReady = ensureLocalAudioTrack()
//        }
//
//        selectDefaultAudioDeviceWithPriority()
//
//        val options = OfferAnswerOptions(voiceActivityDetection = true)
//        val sessionDescription = peerConn.createOffer(options)
//        peerConn.setLocalDescription(sessionDescription)
//
//        audioManager.isMicrophoneMute = false
//
//        log.d(TAG) { "Created offer SDP" }
//        return sessionDescription.sdp
//    }
//
//    override suspend fun createAnswer(offerSdp: String): String {
//        log.d(TAG) { "📝 Creating SDP answer..." }
//
//        // ✅ Validación temprana y detallada
//        if (offerSdp.isBlank()) {
//            throw IllegalArgumentException("❌ Offer SDP cannot be null or empty")
//        }
//
//        log.d(TAG) { "✅ Offer SDP validation passed" }
//        log.d(TAG) { "  - Length: ${offerSdp.length} chars" }
//        log.d(TAG) { "  - Preview: ${offerSdp.take(150)}..." }
//
//        if (!isInitialized) {
//            log.d(TAG) { "Initializing WebRTC..." }
//            initialize()
//        } else {
//            log.d(TAG) { "Starting audio manager..." }
//            startAudioManager()
//        }
//
//        val peerConn = peerConnection ?: run {
//            log.d(TAG) { "PeerConnection not found, creating new one..." }
//            initializePeerConnection()
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        if (!isLocalAudioReady) {
//            log.d(TAG) { "Local audio not ready, ensuring audio track..." }
//            isLocalAudioReady = ensureLocalAudioTrack()
//            if (!isLocalAudioReady) {
//                throw IllegalStateException("Failed to initialize local audio track")
//            }
//            log.d(TAG) { "✅ Local audio track ready" }
//        }
//
//        try {
//            // ✅ Crear remote offer con validación
//            log.d(TAG) { "Creating SessionDescription from offer..." }
//            val remoteOffer = SessionDescription(
//                type = SessionDescriptionType.Offer,
//                sdp = offerSdp
//            )
//
//            // ✅ Verificar que SessionDescription es válido
//            if (remoteOffer.sdp.isBlank()) {
//                throw IllegalStateException("SessionDescription SDP is blank after creation")
//            }
//
//            log.d(TAG) { "✅ Remote SessionDescription created successfully" }
//            log.d(TAG) { "Setting remote description on PeerConnection..." }
//
//            peerConn.setRemoteDescription(remoteOffer)
//            log.d(TAG) { "✅ Remote description set successfully" }
//
//            log.d(TAG) { "Creating answer..." }
//            val options = OfferAnswerOptions(voiceActivityDetection = true)
//            val sessionDescription = peerConn.createAnswer(options)
//
//            // ✅ Verificar answer antes de usarlo
//            if (sessionDescription.sdp.isBlank()) {
//                throw IllegalStateException("Created answer SDP is blank")
//            }
//
//            log.d(TAG) { "✅ Answer created, length: ${sessionDescription.sdp.length} chars" }
//            log.d(TAG) { "Setting local description (answer)..." }
//
//            peerConn.setLocalDescription(sessionDescription)
//            log.d(TAG) { "✅ Local description set successfully" }
//
//            setAudioEnabled(true)
//            audioManager.isMicrophoneMute = false
//
//            log.d(TAG) { "✅ Answer SDP created successfully" }
//            log.d(TAG) { "  - Length: ${sessionDescription.sdp.length} chars" }
//            log.d(TAG) { "  - Preview: ${sessionDescription.sdp.take(150)}..." }
//
//            return sessionDescription.sdp
//
//        } catch (e: Exception) {
//            log.e(TAG) { "❌ Error in createAnswer: ${e.message}" }
//            log.e(TAG) { "Stack trace: ${e.stackTraceToString()}" }
//            throw e
//        }
//    }
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
//        log.d(TAG) { "Setting remote description type: $type" }
//
//        if (!isInitialized) {
//            initialize()
//        }
//
//        val peerConn = peerConnection ?: run {
//            initializePeerConnection()
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        if (type == SdpType.OFFER && !isLocalAudioReady) {
//            isLocalAudioReady = ensureLocalAudioTrack()
//        }
//
//        val sdpType = when (type) {
//            SdpType.OFFER -> SessionDescriptionType.Offer
//            SdpType.ANSWER -> SessionDescriptionType.Answer
//        }
//
//        val sessionDescription = SessionDescription(type = sdpType, sdp = sdp)
//        peerConn.setRemoteDescription(sessionDescription)
//
//        if (type == SdpType.ANSWER) {
//            setAudioEnabled(true)
//            audioManager.isMicrophoneMute = false
//        }
//    }
//
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        log.d(TAG) { "Adding ICE candidate" }
//
//        if (!isInitialized) {
//            initialize()
//            if (peerConnection == null) {
//                return
//            }
//        }
//
//        val peerConn = peerConnection ?: return
//
//        val iceCandidate = IceCandidate(
//            sdpMid = sdpMid ?: "",
//            sdpMLineIndex = sdpMLineIndex ?: 0,
//            candidate = candidate
//        )
//
//        peerConn.addIceCandidate(iceCandidate)
//    }
//
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        updateAvailableAudioDevices()
//
//        val inputDevices = mutableListOf<AudioDevice>()
//        val outputDevices = mutableListOf<AudioDevice>()
//
//        // Built-in microphone (always available)
//        inputDevices.add(createBuiltinMicDevice())
//
//        // Add other input devices based on availability
//        if (_availableAudioDevices.value.contains(AudioDeviceType.WIRED_HEADSET)) {
//            inputDevices.add(createWiredHeadsetMicDevice())
//        }
//
//        if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH)) {
//            inputDevices.add(createBluetoothMicDevice())
//        }
//
//        // Output devices
//        _availableAudioDevices.value.forEach { deviceType ->
//            when (deviceType) {
//                AudioDeviceType.EARPIECE -> outputDevices.add(createEarpieceDevice())
//                AudioDeviceType.SPEAKER_PHONE -> outputDevices.add(createSpeakerDevice())
//                AudioDeviceType.WIRED_HEADSET -> outputDevices.add(createWiredHeadsetDevice())
//                AudioDeviceType.BLUETOOTH -> outputDevices.add(createBluetoothDevice())
//                AudioDeviceType.NONE -> {}
//            }
//        }
//
//        return Pair(inputDevices, outputDevices)
//    }
//
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        log.d(TAG) { "Changing audio output to: ${device.name}" }
//
//        if (!isInitialized || peerConnection == null) {
//            return false
//        }
//
//        return try {
//            val success = when (device.descriptor) {
//                "bluetooth" -> selectAudioDevice(AudioDeviceType.BLUETOOTH)
//                "speaker" -> selectAudioDevice(AudioDeviceType.SPEAKER_PHONE)
//                "wired_headset" -> selectAudioDevice(AudioDeviceType.WIRED_HEADSET)
//                "earpiece" -> selectAudioDevice(AudioDeviceType.EARPIECE)
//                else -> false
//            }
//
//            if (success) {
//                currentOutputDevice = device
//                webRtcEventListener?.onAudioDeviceChanged(device)
//            }
//
//            success
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing audio output: ${e.message}" }
//            false
//        }
//    }
//
//
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
//        log.d(TAG) { "Changing audio input to: ${device.name}" }
//
//        if (!isInitialized || peerConnection == null) {
//            return false
//        }
//
//        return try {
//            val success = when {
//                device.descriptor.startsWith("bluetooth") -> selectAudioDevice(AudioDeviceType.BLUETOOTH)
//                device.descriptor == "wired_headset_mic" -> selectAudioDevice(AudioDeviceType.WIRED_HEADSET)
//                else -> selectAudioDevice(AudioDeviceType.EARPIECE)
//            }
//
//            if (success) {
//                currentInputDevice = device
//                webRtcEventListener?.onAudioDeviceChanged(device)
//            }
//
//            success
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing audio input: ${e.message}" }
//            false
//        }
//    }
//
//
//    override fun getCurrentInputDevice(): AudioDevice? {
//        return currentInputDevice ?: when (_currentAudioDevice.value) {
//            AudioDeviceType.BLUETOOTH -> createBluetoothMicDevice()
//            AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetMicDevice()
//            else -> createBuiltinMicDevice()
//        }
//    }
//
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        return currentOutputDevice ?: when (_currentAudioDevice.value) {
//            AudioDeviceType.BLUETOOTH -> createBluetoothDevice()
//            AudioDeviceType.SPEAKER_PHONE -> createSpeakerDevice()
//            AudioDeviceType.WIRED_HEADSET -> createWiredHeadsetDevice()
//            AudioDeviceType.EARPIECE -> createEarpieceDevice()
//            AudioDeviceType.NONE -> null
//        }
//    }
//
//    override fun setAudioEnabled(enabled: Boolean) {
//        audioManager.isMicrophoneMute = !enabled
//        localAudioTrack?.enabled = enabled
//    }
//
//    override fun setMuted(muted: Boolean) {
//        audioManager.isMicrophoneMute = muted
//        localAudioTrack?.enabled = !muted
//    }
//
//    override fun isMuted(): Boolean {
//        return audioManager.isMicrophoneMute
//    }
//
//    override fun getLocalDescription(): String? {
//        return peerConnection?.localDescription?.sdp
//    }
//
//    override fun setActiveAudioRoute(audioUnitType: AudioUnitTypes): Boolean {
//        log.d(TAG) { "Setting active audio route to: $audioUnitType" }
//
//        val deviceType = when (audioUnitType) {
//            AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> AudioDeviceType.BLUETOOTH
//            AudioUnitTypes.SPEAKER -> AudioDeviceType.SPEAKER_PHONE
//            AudioUnitTypes.HEADSET, AudioUnitTypes.HEADPHONES -> AudioDeviceType.WIRED_HEADSET
//            AudioUnitTypes.EARPIECE -> AudioDeviceType.EARPIECE
//            else -> {
//                log.w(TAG) { "Unsupported audio unit type: $audioUnitType" }
//                return false
//            }
//        }
//
//        return selectAudioDevice(deviceType)
//    }
//
//    override fun getActiveAudioRoute(): AudioUnitTypes? {
//        val currentDevice = _currentAudioDevice.value
//
//        return when (currentDevice) {
//            AudioDeviceType.BLUETOOTH -> AudioUnitTypes.BLUETOOTH
//            AudioDeviceType.SPEAKER_PHONE -> AudioUnitTypes.SPEAKER
//            AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
//            AudioDeviceType.EARPIECE -> AudioUnitTypes.EARPIECE
//            AudioDeviceType.NONE -> null
//        }
//    }
//
//    override fun getAvailableAudioRoutes(): Set<AudioUnitTypes> {
//        updateAvailableAudioDevices()
//
//        return _availableAudioDevices.value.mapNotNull { deviceType ->
//            when (deviceType) {
//                AudioDeviceType.BLUETOOTH -> AudioUnitTypes.BLUETOOTH
//                AudioDeviceType.SPEAKER_PHONE -> AudioUnitTypes.SPEAKER
//                AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
//                AudioDeviceType.EARPIECE -> AudioUnitTypes.EARPIECE
//                AudioDeviceType.NONE -> null
//            }
//        }.toSet()
//    }
//
//
//    override fun diagnoseAudioIssues(): String {
//        return buildString {
//            appendLine("=== AUDIO DIAGNOSIS ===")
//            appendLine("WebRTC Initialized: $isInitialized")
//            appendLine("Local Audio Ready: $isLocalAudioReady")
//            appendLine("Local Audio Track: ${localAudioTrack != null}")
//            appendLine("Audio Mode: ${audioManager.mode}")
//            appendLine("Speaker On: ${audioManager.isSpeakerphoneOn}")
//            appendLine("Mic Muted: ${audioManager.isMicrophoneMute}")
//            appendLine("Current Device: ${_currentAudioDevice.value}")
//            appendLine("Available Devices: ${_availableAudioDevices.value}")
//            appendLine("Bluetooth SCO On: ${audioManager.isBluetoothScoOn}")
//            appendLine("Wired Headset On: ${audioManager.isWiredHeadsetOn}")
//        }
//    }
//
//    override fun getConnectionState(): WebRtcConnectionState {
//        if (!isInitialized || peerConnection == null) {
//            return WebRtcConnectionState.NEW
//        }
//
//        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
//        return mapConnectionState(state)
//    }
//
//    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
//        // Implementation for media direction
//    }
//
//    override fun setListener(listener: WebRtcEventListener?) {
//        webRtcEventListener = listener
//    }
//
//    override fun prepareAudioForIncomingCall() {
//        startAudioManager()
//    }
//
//    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
//        return try {
//            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
//            peerConnection?.setLocalDescription(description)
//            true
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    override fun isInitialized(): Boolean = isInitialized
//
//    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
//        if (!isInitialized || peerConnection == null) return false
//
//        return try {
//            val audioSender = peerConnection?.getSenders()?.find { sender ->
//                sender.track?.kind == MediaStreamTrackKind.Audio
//            }
//
//            val dtmfSender = audioSender?.dtmf ?: return false
//            dtmfSender.insertDtmf(tones, duration, gap)
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    // Audio Management Methods (Simplified approach)
//
//    fun startAudioManager() {
//        log.d(TAG) { "Starting audio manager for call" }
//
//        // Save current state
//        savedAudioMode = audioManager.mode
//        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
//        savedIsMicrophoneMute = audioManager.isMicrophoneMute
//
//        // Request audio focus
//        requestAudioFocus()
//
//        // Set communication mode
//        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//
//        // Update available devices and select with priority
//        updateAvailableAudioDevices()
//
//        // ✅ CORRECCIÓN: Asegurar que siempre se selecciona un dispositivo
//        Handler(Looper.getMainLooper()).postDelayed({
//            selectDefaultAudioDeviceWithPriority()
//        }, 100) // Pequeño delay para asegurar que todo esté inicializado
//    }
//
//    fun stopAudioManager() {
//        log.d(TAG) { "Stopping audio manager" }
//
//        // Restore original settings
//        audioManager.mode = savedAudioMode
//        audioManager.isSpeakerphoneOn = savedIsSpeakerPhoneOn
//        audioManager.isMicrophoneMute = savedIsMicrophoneMute
//
//        // Release audio focus
//        abandonAudioFocus()
//
//        _currentAudioDevice.value = AudioDeviceType.NONE
//    }
//
//    private fun selectDefaultAudioDeviceWithPriority() {
//        val availableDevices = _availableAudioDevices.value
//
//        log.d(TAG) { "Selecting audio device with Bluetooth priority. Available: $availableDevices" }
//
//        val defaultDevice = when {
//            // Prioridad 1: Bluetooth (SIEMPRE tiene máxima prioridad)
//            availableDevices.contains(AudioDeviceType.BLUETOOTH) -> {
//                log.d(TAG) { "✅ Selecting Bluetooth as priority device" }
//                AudioDeviceType.BLUETOOTH
//            }
//            // Prioridad 2: Wired headset (audífonos por cable)
//            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> {
//                log.d(TAG) { "✅ Selecting Wired Headset as priority device" }
//                AudioDeviceType.WIRED_HEADSET
//            }
//            // Prioridad 3: Speaker (corneta del dispositivo)
//            availableDevices.contains(AudioDeviceType.EARPIECE) -> {
//                log.d(TAG) { "✅ Selecting Earpiece as priority device" }
//                AudioDeviceType.EARPIECE
//            }
//            // Prioridad 4: Speaker como fallback
//            else -> {
//                log.d(TAG) { "✅ Selecting Speaker as default device" }
//                AudioDeviceType.SPEAKER_PHONE
//            }
//        }
//
//        val success = selectAudioDevice(defaultDevice)
//        if (success) {
//            log.d(TAG) { "✅ Audio device selected successfully: $defaultDevice" }
//        } else {
//            log.e(TAG) { "❌ Failed to select audio device: $defaultDevice" }
//        }
//    }
//
////    /**
////     * Selección de dispositivo por defecto con prioridad mejorada
////     */
////    private fun selectDefaultAudioDeviceWithPriority() {
////        val availableDevices = _availableAudioDevices.value
////
////        val defaultDevice = when {
////            // Prioridad 1: Bluetooth (si la auto-prioridad está habilitada)
////            _isBluetoothPriorityEnabled.value && availableDevices.contains(AudioDeviceType.BLUETOOTH) -> {
////                log.d(TAG) { "Selecting Bluetooth as default (auto-priority enabled)" }
////                AudioDeviceType.BLUETOOTH
////            }
////            // Prioridad 2: Wired headset
////            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> {
////                log.d(TAG) { "Selecting Wired Headset as default" }
////                AudioDeviceType.WIRED_HEADSET
////            }
////            // Prioridad 3: Earpiece
////            else -> {
////                log.d(TAG) { "Selecting Earpiece as default" }
////                AudioDeviceType.EARPIECE
////            }
////        }
////
////        selectAudioDevice(defaultDevice)
////    }
//
//    private fun requestAudioFocus() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val audioAttributes = AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                .build()
//
//            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
//                .setAudioAttributes(audioAttributes)
//                .setAcceptsDelayedFocusGain(true)
//                .setOnAudioFocusChangeListener { focusChange ->
//                    when (focusChange) {
//                        AudioManager.AUDIOFOCUS_GAIN -> {
//                            log.d(TAG) { "Audio focus gained" }
//                            setAudioEnabled(true)
//                        }
//
//                        AudioManager.AUDIOFOCUS_LOSS -> {
//                            log.d(TAG) { "Audio focus lost" }
//                        }
//                    }
//                }
//                .build()
//
//            audioManager.requestAudioFocus(audioFocusRequest!!)
//        } else {
//            @Suppress("DEPRECATION")
//            audioManager.requestAudioFocus(
//                { focusChange ->
//                    when (focusChange) {
//                        AudioManager.AUDIOFOCUS_GAIN -> setAudioEnabled(true)
//                    }
//                },
//                AudioManager.STREAM_VOICE_CALL,
//                AudioManager.AUDIOFOCUS_GAIN
//            )
//        }
//    }
//
//    private fun abandonAudioFocus() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            audioFocusRequest?.let { request ->
//                audioManager.abandonAudioFocusRequest(request)
//            }
//        } else {
//            @Suppress("DEPRECATION")
//            audioManager.abandonAudioFocus(null)
//        }
//    }
//
//    private fun updateAvailableAudioDevices() {
//        val devices = mutableListOf<AudioDeviceType>()
//
//        // Always available
//        devices.add(AudioDeviceType.EARPIECE)
//        devices.add(AudioDeviceType.SPEAKER_PHONE)
//
//        // Check for connected devices
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
//
//            for (deviceInfo in audioDevices) {
//                when (deviceInfo.type) {
//                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
//                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
//                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
//                        if (!devices.contains(AudioDeviceType.WIRED_HEADSET)) {
//                            devices.add(AudioDeviceType.WIRED_HEADSET)
//                        }
//                    }
//
//                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
//                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
//                        if (!devices.contains(AudioDeviceType.BLUETOOTH)) {
//                            devices.add(AudioDeviceType.BLUETOOTH)
//                            log.d(TAG) { "Bluetooth device detected: ${deviceInfo.productName}" }
//                        }
//                    }
//                }
//            }
//        } else {
//            // For older versions
//            if (audioManager.isWiredHeadsetOn) {
//                devices.add(AudioDeviceType.WIRED_HEADSET)
//            }
//            if (audioManager.isBluetoothScoAvailableOffCall) {
//                devices.add(AudioDeviceType.BLUETOOTH)
//                log.d(TAG) { "Bluetooth SCO available" }
//            }
//        }
//
//        val oldDevices = _availableAudioDevices.value
//        _availableAudioDevices.value = devices
//
//        // Log cambios
//        if (oldDevices != devices) {
//            log.d(TAG) { "Audio devices changed from $oldDevices to $devices" }
//
//            // Si Bluetooth se conectó y no estaba antes, activar auto-prioridad
//            if (devices.contains(AudioDeviceType.BLUETOOTH) &&
//                !oldDevices.contains(AudioDeviceType.BLUETOOTH)
//            ) {
//                log.d(TAG) { "New Bluetooth device detected" }
//                onBluetoothConnectionChanged(true)
//            }
//        }
//    }
//
//    /**
//     * Función simplificada que SipManagerImpl puede usar
//     * Maneja automáticamente la prioridad de Bluetooth
//     */
////    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
////        log.d(TAG) { "Applying audio route change to: $audioUnitType" }
////
////        val deviceType = when (audioUnitType) {
////            AudioUnitTypes.BLUETOOTH -> AudioDeviceType.BLUETOOTH
////            AudioUnitTypes.SPEAKER -> AudioDeviceType.SPEAKER_PHONE
////            AudioUnitTypes.HEADSET -> AudioDeviceType.WIRED_HEADSET
////            AudioUnitTypes.EARPIECE -> AudioDeviceType.EARPIECE
////            else -> AudioDeviceType.EARPIECE
////        }
////
////        return selectAudioDevice(deviceType)
////    }
//    override fun applyAudioRouteChange(audioUnitType: AudioUnitTypes): Boolean {
//        return setActiveAudioRoute(audioUnitType)
//    }
//    /**
//     * Obtiene el dispositivo actualmente activo en formato AudioUnit
//     */
//    override fun getCurrentActiveAudioUnit(): AudioUnit? {
//        val activeRoute = getActiveAudioRoute() ?: return null
//
//        return AudioUnit(
//            type = activeRoute,
//            capability = AudioUnitCompatibilities.ALL,
//            isCurrent = true,
//            isDefault = activeRoute == AudioUnitTypes.EARPIECE
//        )
//    }
//
//    /**
//     * Obtiene todos los dispositivos disponibles en formato AudioUnit
//     */
//    override fun getAvailableAudioUnits(): Set<AudioUnit> {
//        val routes = getAvailableAudioRoutes()
//        val currentRoute = getActiveAudioRoute()
//
//        return routes.map { type ->
//            AudioUnit(
//                type = type,
//                capability = AudioUnitCompatibilities.ALL,
//                isCurrent = type == currentRoute,
//                isDefault = type == AudioUnitTypes.EARPIECE
//            )
//        }.toSet()
//    }
//
//    private fun selectDefaultAudioDevice() {
//        val availableDevices = _availableAudioDevices.value
//
//        val defaultDevice = when {
//            availableDevices.contains(AudioDeviceType.BLUETOOTH) -> AudioDeviceType.BLUETOOTH
//            availableDevices.contains(AudioDeviceType.WIRED_HEADSET) -> AudioDeviceType.WIRED_HEADSET
//            else -> AudioDeviceType.EARPIECE
//        }
//
//        selectAudioDevice(defaultDevice)
//    }
//
//    private fun selectAudioDevice(device: AudioDeviceType): Boolean {
//        if (!_availableAudioDevices.value.contains(device)) {
//            log.d(TAG) { "Audio device not available: $device" }
//            return false
//        }
//
//        log.d(TAG) { "Selecting audio device: $device" }
//
//        return try {
//            when (device) {
//                AudioDeviceType.SPEAKER_PHONE -> {
//                    audioManager.isSpeakerphoneOn = true
//                    stopBluetoothSco()
//                }
//
//                AudioDeviceType.EARPIECE -> {
//                    audioManager.isSpeakerphoneOn = false
//                    stopBluetoothSco()
//                }
//
//                AudioDeviceType.WIRED_HEADSET -> {
//                    audioManager.isSpeakerphoneOn = false
//                    stopBluetoothSco()
//                }
//
//                AudioDeviceType.BLUETOOTH -> {
//                    audioManager.isSpeakerphoneOn = false
//                    startBluetoothSco()
//                }
//
//                AudioDeviceType.NONE -> return false
//            }
//
//            _currentAudioDevice.value = device
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error selecting audio device: ${e.message}" }
//            false
//        }
//    }
//
//    private fun startBluetoothSco() {
//        if (audioManager.isBluetoothScoAvailableOffCall) {
//            audioManager.startBluetoothSco()
//            log.d(TAG) { "Bluetooth SCO started" }
//        }
//    }
//
//    private fun stopBluetoothSco() {
//        if (audioManager.isBluetoothScoOn) {
//            audioManager.stopBluetoothSco()
//            log.d(TAG) { "Bluetooth SCO stopped" }
//        }
//    }
//
//    /**
//     * Verifica y activa automáticamente Bluetooth si está disponible y la prioridad está habilitada
//     */
//    private fun ensureBluetoothPriorityIfAvailable() {
//        try {
//            updateAvailableAudioDevices()
//
//            if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH) &&
//                _currentAudioDevice.value != AudioDeviceType.BLUETOOTH
//            ) {
//
//                log.d(TAG) { "Bluetooth available, switching automatically (priority always enabled)" }
//
//                val success = selectAudioDevice(AudioDeviceType.BLUETOOTH)
//                if (success) {
//                    log.d(TAG) { "Successfully switched to Bluetooth with automatic priority" }
//
//                    // Actualizar dispositivos actuales
//                    currentInputDevice = createBluetoothMicDevice()
//                    currentOutputDevice = createBluetoothDevice()
//
//                    // Notificar cambio
//                    webRtcEventListener?.onAudioDeviceChanged(createBluetoothDevice())
//                } else {
//                    log.w(TAG) { "Failed to auto-switch to Bluetooth" }
//                }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error in ensureBluetoothPriorityIfAvailable: ${e.message}" }
//        }
//    }
//
//    /**
//     * Función pública para forzar verificación de prioridad Bluetooth
//     * Esta es la que puede ser llamada desde SipManagerImpl
//     */
//    override fun refreshAudioDevicesWithBluetoothPriority() {
//        log.d(TAG) { "Refreshing audio devices with Bluetooth priority check" }
//        updateAvailableAudioDevices()
//        ensureBluetoothPriorityIfAvailable()
//    }
//
//    /**
//     * Función llamada cuando se inicia una llamada para asegurar el mejor dispositivo
//     */
//    override fun prepareAudioForCall() {
//        log.d(TAG) { "Preparing audio for call with Bluetooth prioritization" }
//        startAudioManager()
//        ensureBluetoothPriorityIfAvailable()
//    }
//
//    /**
//     * Función para ser llamada cuando el estado de Bluetooth cambia
//     */
//    override fun onBluetoothConnectionChanged(isConnected: Boolean) {
//        log.d(TAG) { "Bluetooth connection changed: $isConnected" }
//
//        if (isConnected) {
//            // Pequeño delay para asegurar que el dispositivo esté listo
//            Handler(Looper.getMainLooper()).postDelayed({
//                refreshAudioDevicesWithBluetoothPriority()
//            }, 300)
//        } else if (!isConnected) {
//            // Si Bluetooth se desconecta, cambiar a siguiente mejor opción
//            selectNextBestAudioDevice()
//        }
//    }
//
//    private fun selectNextBestAudioDevice() {
//        updateAvailableAudioDevices()
//
//        val nextBestDevice = when {
//            _availableAudioDevices.value.contains(AudioDeviceType.WIRED_HEADSET) -> AudioDeviceType.WIRED_HEADSET
//            else -> AudioDeviceType.EARPIECE
//        }
//
//        selectAudioDevice(nextBestDevice)
//    }
//
//    fun toggleSpeaker() {
//        val currentDevice = _currentAudioDevice.value
//        val newDevice = if (currentDevice == AudioDeviceType.SPEAKER_PHONE) {
//            when {
//                _availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH) -> AudioDeviceType.BLUETOOTH
//                _availableAudioDevices.value.contains(AudioDeviceType.WIRED_HEADSET) -> AudioDeviceType.WIRED_HEADSET
//                else -> AudioDeviceType.EARPIECE
//            }
//        } else {
//            AudioDeviceType.SPEAKER_PHONE
//        }
//
//        selectAudioDevice(newDevice)
//    }
//
//    // Helper methods to create AudioDevice objects
//
//    private fun createBuiltinMicDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Built-in Microphone",
//            descriptor = "builtin_mic",
//            nativeDevice = null,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.MICROPHONE,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.EARPIECE,
//                isDefault = true
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 10,
//            vendorInfo = "Built-in"
//        )
//    }
//
//    private fun createWiredHeadsetMicDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Wired Headset Microphone",
//            descriptor = "wired_headset_mic",
//            nativeDevice = null,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.HEADSET,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.WIRED_HEADSET,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 20,
//            vendorInfo = null
//        )
//    }
//
//    private fun createBluetoothMicDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Bluetooth Microphone",
//            descriptor = "bluetooth_mic",
//            nativeDevice = null,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.BLUETOOTH,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.BLUETOOTH,
//                isDefault = false
//            ),
//            connectionState = if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH))
//                DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
//            isWireless = true,
//            supportsHDVoice = false,
//            latency = 150,
//            vendorInfo = null
//        )
//    }
//
//    private fun createEarpieceDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Earpiece",
//            descriptor = "earpiece",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.EARPIECE,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.EARPIECE,
//                isDefault = true
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 5,
//            vendorInfo = "Built-in"
//        )
//    }
//
//    private fun createSpeakerDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Speaker",
//            descriptor = "speaker",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.SPEAKER,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.SPEAKER_PHONE,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 15,
//            vendorInfo = "Built-in"
//        )
//    }
//
//    private fun createWiredHeadsetDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Wired Headset",
//            descriptor = "wired_headset",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.HEADSET,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.WIRED_HEADSET,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 20,
//            vendorInfo = null
//        )
//    }
//
//    private fun createBluetoothDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Bluetooth",
//            descriptor = "bluetooth",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.BLUETOOTH,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = _currentAudioDevice.value == AudioDeviceType.BLUETOOTH,
//                isDefault = false
//            ),
//            connectionState = if (_availableAudioDevices.value.contains(AudioDeviceType.BLUETOOTH))
//                DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED,
//            isWireless = true,
//            supportsHDVoice = false,
//            latency = 150,
//            vendorInfo = null
//        )
//    }
//
//    // Helper methods
//
//    private suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
//        return MediaDevices.enumerateDevices()
//    }
//
//    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
//        return when (state) {
//            PeerConnectionState.New -> WebRtcConnectionState.NEW
//            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
//            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
//            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
//            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
//            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
//        }
//    }
//
//    private fun initializePeerConnection() {
//        log.d(TAG) { "Initializing PeerConnection..." }
//        cleanupCall()
//
//        try {
//            val rtcConfig = RtcConfiguration(
//                iceServers = listOf(
//                    IceServer(
//                        urls = listOf(
//                            "stun:stun.l.google.com:19302",
//                            "stun:stun1.l.google.com:19302"
//                        )
//                    )
//                )
//            )
//
//            peerConnection = PeerConnection(rtcConfig).apply {
//                setupPeerConnectionObservers()
//            }
//
//            isLocalAudioReady = false
//        } catch (e: Exception) {
//            log.d(TAG) { "Error initializing PeerConnection: ${e.message}" }
//            peerConnection = null
//            isInitialized = false
//            isLocalAudioReady = false
//        }
//    }
//
//    private fun PeerConnection.setupPeerConnectionObservers() {
//        onIceCandidate.onEach { candidate ->
//            webRtcEventListener?.onIceCandidate(
//                candidate.candidate,
//                candidate.sdpMid,
//                candidate.sdpMLineIndex
//            )
//        }.launchIn(coroutineScope)
//
//        onConnectionStateChange.onEach { state ->
//            when (state) {
//                PeerConnectionState.Connected -> {
//                    setAudioEnabled(true)
//                    audioManager.isMicrophoneMute = false
//                }
//
//                PeerConnectionState.Disconnected,
//                PeerConnectionState.Failed,
//                PeerConnectionState.Closed -> {
//                    stopAudioManager()
//                }
//
//                else -> {}
//            }
//            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
//        }.launchIn(coroutineScope)
//
//        onTrack.onEach { event ->
//            val track = event.receiver.track
//            if (track is AudioStreamTrack) {
//                remoteAudioTrack = track
//                remoteAudioTrack?.enabled = true
//                webRtcEventListener?.onRemoteAudioTrack()
//            }
//        }.launchIn(coroutineScope)
//    }
//
//    private suspend fun ensureLocalAudioTrack(): Boolean {
//        return try {
//            val peerConn = peerConnection ?: return false
//
//            if (localAudioTrack != null) {
//                return true
//            }
//
//            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//            audioManager.isMicrophoneMute = false
//
//            val mediaStream = MediaDevices.getUserMedia(audio = true, video = false)
//            val audioTrack = mediaStream.audioTracks.firstOrNull()
//
//            if (audioTrack != null) {
//                localAudioTrack = audioTrack
//                localAudioTrack?.enabled = true
//                peerConn.addTrack(audioTrack, mediaStream)
//                true
//            } else {
//                false
//            }
//        } catch (e: Exception) {
//            log.d(TAG) { "Error getting audio: ${e.message}" }
//            false
//        }
//    }
//
//    private fun cleanupCall() {
//        try {
//            localAudioTrack?.enabled = false
//
//            peerConnection?.let { pc ->
//                pc.getSenders().forEach { sender ->
//                    try {
//                        pc.removeTrack(sender)
//                    } catch (e: Exception) {
//                        log.d(TAG) { "Error removing track: ${e.message}" }
//                    }
//                }
//            }
//
//            peerConnection?.close()
//            peerConnection = null
//
//            Thread.sleep(100)
//
//            localAudioTrack = null
//            remoteAudioTrack = null
//            isLocalAudioReady = false
//
//            System.gc()
//
//        } catch (e: Exception) {
//            log.d(TAG) { "Error in cleanupCall: ${e.message}" }
//        }
//    }
//}