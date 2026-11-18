package io.github.axeldelaro.opensnap.network.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.github.axeldelaro.opensnap.network.protocol.WebRtcSignalPayload
import io.github.axeldelaro.opensnap.network.relay.NetworkConfigVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Native WebRTC Engine:
 * 1. Multiplexed DTLS-encrypted `DataChannel` ("opensnap-fastlane") for sub-30ms P2P packet exchange
 *    when two friends are online simultaneously.
 * 2. Direct P2P DTLS-SRTP Audio & Video Calls with Camera2 hardware capture, hardware AEC/NS,
 *    speakerphone routing, front/back camera switching, and Trickle ICE.
 */
class WebRtcMeshEngine(
    context: Context,
    private val stunUrls: List<String> = NetworkConfigVerifier.DEFAULT_STUN_SERVERS,
    private val onSignalReadyToSend: (peerEd25519PubHex: String, signal: WebRtcSignalPayload) -> Unit,
    private val onFastLaneEnvelopeReceived: (peerEd25519PubHex: String, sealedJson: String) -> Unit = { _, _ -> }
) {

    enum class CallStatus {
        IDLE,
        OUTGOING_RINGING,
        INCOMING_RINGING,
        CONNECTING,
        CONNECTED,
        ENDED
    }

    data class CallUiState(
        val status: CallStatus = CallStatus.IDLE,
        val callId: String = "",
        val peerEd25519PubHex: String = "",
        val peerDisplayName: String = "",
        val isVideoCall: Boolean = false,
        val isMicMuted: Boolean = false,
        val isCameraEnabled: Boolean = true,
        val isSpeakerOn: Boolean = false,
        val connectedSinceMs: Long = 0L
    )

    private val appContext = context.applicationContext
    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile
    private var factoryInitialized = false

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        ensureFactoryInitialized()
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    // Fast-Lane background DataChannel PeerConnections keyed by peerEd25519PubHex
    private val fastLanePeers = ConcurrentHashMap<String, PeerConnection>()
    private val fastLaneChannels = ConcurrentHashMap<String, DataChannel>()

    private val _activeFastLanePeers = MutableStateFlow<Set<String>>(emptySet())
    val activeFastLanePeers: StateFlow<Set<String>> = _activeFastLanePeers.asStateFlow()

    // Active Audio/Video Call state
    private var callPeerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localRendererRef: SurfaceViewRenderer? = null
    private var remoteRendererRef: SurfaceViewRenderer? = null
    private var pendingOfferSdp: String? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private fun ensureFactoryInitialized() {
        if (!factoryInitialized) {
            synchronized(WebRtcMeshEngine::class.java) {
                if (!factoryInitialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                    )
                    factoryInitialized = true
                }
            }
        }
    }

    private fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    // =========================================================================
    // 1. P2P Fast-Lane DataChannel (Sub-30ms Direct Messaging When Both Online)
    // =========================================================================

    fun sendViaFastLaneIfOpen(peerEd25519PubHex: String, sealedEnvelopeJson: String): Boolean {
        val channel = fastLaneChannels[peerEd25519PubHex] ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val bytes = sealedEnvelopeJson.toByteArray(StandardCharsets.UTF_8)
        // Fragment threshold safety: send frames <= 60 KB over DataChannel directly
        if (bytes.size > 60_000) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun initiateFastLaneDataChannel(peerEd25519PubHex: String) {
        if (fastLaneChannels[peerEd25519PubHex]?.state() == DataChannel.State.OPEN) return
        if (fastLanePeers.containsKey(peerEd25519PubHex)) return

        val pc = createFastLanePeerConnection(peerEd25519PubHex) ?: return
        val dcInit = DataChannel.Init().apply {
            ordered = true
        }
        val channel = pc.createDataChannel("opensnap-fastlane", dcInit)
        attachFastLaneChannelObserver(peerEd25519PubHex, channel)

        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    pc.setLocalDescription(SimpleSdpObserver(), desc)
                    onSignalReadyToSend(
                        peerEd25519PubHex,
                        WebRtcSignalPayload(
                            callId = "fastlane-$peerEd25519PubHex",
                            signalType = "FASTLANE_OFFER",
                            sdp = desc.description
                        )
                    )
                }
            },
            MediaConstraints()
        )
    }

    private fun createFastLanePeerConnection(peerEd25519PubHex: String): PeerConnection? {
        val pc = peerConnectionFactory.createPeerConnection(
            buildRtcConfig(),
            object : SimplePeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        onSignalReadyToSend(
                            peerEd25519PubHex,
                            WebRtcSignalPayload(
                                callId = "fastlane-$peerEd25519PubHex",
                                signalType = "FASTLANE_ICE",
                                iceCandidate = candidate.sdp,
                                sdpMid = candidate.sdpMid,
                                sdpMLineIndex = candidate.sdpMLineIndex
                            )
                        )
                    }
                }

                override fun onDataChannel(channel: DataChannel?) {
                    if (channel != null) {
                        attachFastLaneChannelObserver(peerEd25519PubHex, channel)
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    if (newState == PeerConnection.PeerConnectionState.FAILED ||
                        newState == PeerConnection.PeerConnectionState.CLOSED ||
                        newState == PeerConnection.PeerConnectionState.DISCONNECTED
                    ) {
                        closeFastLanePeer(peerEd25519PubHex)
                    }
                }
            }
        ) ?: return null
        fastLanePeers[peerEd25519PubHex] = pc
        return pc
    }

    private fun attachFastLaneChannelObserver(peerEd25519PubHex: String, channel: DataChannel) {
        fastLaneChannels[peerEd25519PubHex] = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    _activeFastLanePeers.value = _activeFastLanePeers.value + peerEd25519PubHex
                } else if (channel.state() == DataChannel.State.CLOSED) {
                    _activeFastLanePeers.value = _activeFastLanePeers.value - peerEd25519PubHex
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val json = String(data, StandardCharsets.UTF_8)
                onFastLaneEnvelopeReceived(peerEd25519PubHex, json)
            }
        })
    }

    private fun closeFastLanePeer(peerEd25519PubHex: String) {
        fastLaneChannels.remove(peerEd25519PubHex)?.let {
            try { it.close() } catch (_: Exception) {}
        }
        fastLanePeers.remove(peerEd25519PubHex)?.let {
            try { it.close() } catch (_: Exception) {}
        }
        _activeFastLanePeers.value = _activeFastLanePeers.value - peerEd25519PubHex
    }

    // =========================================================================
    // 2. Direct P2P Encrypted Audio & Video Calls
    // =========================================================================

    fun startOutgoingCall(
        callId: String,
        peerEd25519PubHex: String,
        peerDisplayName: String,
        isVideoCall: Boolean
    ) {
        hangupCurrentCall(sendRemoteHangup = false)
        configureCallAudioMode(enable = true, speakerphone = isVideoCall)

        _callState.value = CallUiState(
            status = CallStatus.OUTGOING_RINGING,
            callId = callId,
            peerEd25519PubHex = peerEd25519PubHex,
            peerDisplayName = peerDisplayName,
            isVideoCall = isVideoCall,
            isCameraEnabled = isVideoCall,
            isSpeakerOn = isVideoCall
        )

        val pc = createCallPeerConnection(callId, peerEd25519PubHex, isVideoCall) ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo",
                    if (isVideoCall) "true" else "false"
                )
            )
        }
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    pc.setLocalDescription(SimpleSdpObserver(), desc)
                    onSignalReadyToSend(
                        peerEd25519PubHex,
                        WebRtcSignalPayload(
                            callId = callId,
                            signalType = "OFFER",
                            isVideoCall = isVideoCall,
                            sdp = desc.description
                        )
                    )
                }
            },
            constraints
        )
    }

    fun registerIncomingCallOffer(
        peerEd25519PubHex: String,
        peerDisplayName: String,
        signal: WebRtcSignalPayload
    ) {
        if (_callState.value.status != CallStatus.IDLE &&
            _callState.value.status != CallStatus.ENDED
        ) {
            onSignalReadyToSend(
                peerEd25519PubHex,
                WebRtcSignalPayload(callId = signal.callId, signalType = "BUSY")
            )
            return
        }
        pendingOfferSdp = signal.sdp
        pendingIceCandidates.clear()
        _callState.value = CallUiState(
            status = CallStatus.INCOMING_RINGING,
            callId = signal.callId,
            peerEd25519PubHex = peerEd25519PubHex,
            peerDisplayName = peerDisplayName,
            isVideoCall = signal.isVideoCall,
            isCameraEnabled = signal.isVideoCall,
            isSpeakerOn = signal.isVideoCall
        )
    }

    fun acceptIncomingCall() {
        val current = _callState.value
        val offerSdp = pendingOfferSdp ?: return
        if (current.status != CallStatus.INCOMING_RINGING) return

        configureCallAudioMode(enable = true, speakerphone = current.isVideoCall)
        _callState.value = current.copy(status = CallStatus.CONNECTING)

        val pc = createCallPeerConnection(
            callId = current.callId,
            peerEd25519PubHex = current.peerEd25519PubHex,
            isVideoCall = current.isVideoCall
        ) ?: return

        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    synchronized(pendingIceCandidates) {
                        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
                        pendingIceCandidates.clear()
                    }
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(
                            MediaConstraints.KeyValuePair(
                                "OfferToReceiveVideo",
                                if (current.isVideoCall) "true" else "false"
                            )
                        )
                    }
                    pc.createAnswer(
                        object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription?) {
                                if (answer == null) return
                                pc.setLocalDescription(SimpleSdpObserver(), answer)
                                onSignalReadyToSend(
                                    current.peerEd25519PubHex,
                                    WebRtcSignalPayload(
                                        callId = current.callId,
                                        signalType = "ANSWER",
                                        isVideoCall = current.isVideoCall,
                                        sdp = answer.description
                                    )
                                )
                            }
                        },
                        constraints
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        )
    }

    fun handleIncomingSignal(
        peerEd25519PubHex: String,
        peerDisplayName: String,
        signal: WebRtcSignalPayload
    ) {
        when (signal.signalType) {
            "FASTLANE_OFFER" -> {
                val sdp = signal.sdp ?: return
                closeFastLanePeer(peerEd25519PubHex)
                val pc = createFastLanePeerConnection(peerEd25519PubHex) ?: return
                pc.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            pc.createAnswer(
                                object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(ans: SessionDescription?) {
                                        if (ans == null) return
                                        pc.setLocalDescription(SimpleSdpObserver(), ans)
                                        onSignalReadyToSend(
                                            peerEd25519PubHex,
                                            WebRtcSignalPayload(
                                                callId = signal.callId,
                                                signalType = "FASTLANE_ANSWER",
                                                sdp = ans.description
                                            )
                                        )
                                    }
                                },
                                MediaConstraints()
                            )
                        }
                    },
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
            }

            "FASTLANE_ANSWER" -> {
                val sdp = signal.sdp ?: return
                fastLanePeers[peerEd25519PubHex]?.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
            }

            "FASTLANE_ICE" -> {
                val candStr = signal.iceCandidate ?: return
                fastLanePeers[peerEd25519PubHex]?.addIceCandidate(
                    IceCandidate(signal.sdpMid ?: "0", signal.sdpMLineIndex, candStr)
                )
            }

            "OFFER" -> registerIncomingCallOffer(peerEd25519PubHex, peerDisplayName, signal)

            "ANSWER" -> {
                val sdp = signal.sdp ?: return
                val current = _callState.value
                if (current.callId == signal.callId) {
                    callPeerConnection?.setRemoteDescription(
                        SimpleSdpObserver(),
                        SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    )
                }
            }

            "ICE" -> {
                val candStr = signal.iceCandidate ?: return
                val candidate = IceCandidate(signal.sdpMid ?: "0", signal.sdpMLineIndex, candStr)
                val pc = callPeerConnection
                if (pc != null && pc.remoteDescription != null) {
                    pc.addIceCandidate(candidate)
                } else {
                    synchronized(pendingIceCandidates) {
                        pendingIceCandidates.add(candidate)
                    }
                }
            }

            "HANGUP", "BUSY" -> {
                if (_callState.value.callId == signal.callId) {
                    hangupCurrentCall(sendRemoteHangup = false)
                }
            }
        }
    }

    private fun createCallPeerConnection(
        callId: String,
        peerEd25519PubHex: String,
        isVideoCall: Boolean
    ): PeerConnection? {
        val pc = peerConnectionFactory.createPeerConnection(
            buildRtcConfig(),
            object : SimplePeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        onSignalReadyToSend(
                            peerEd25519PubHex,
                            WebRtcSignalPayload(
                                callId = callId,
                                signalType = "ICE",
                                isVideoCall = isVideoCall,
                                iceCandidate = candidate.sdp,
                                sdpMid = candidate.sdpMid,
                                sdpMLineIndex = candidate.sdpMLineIndex
                            )
                        )
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        remoteRendererRef?.let { track.addSink(it) }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            val st = _callState.value
                            _callState.value = st.copy(
                                status = CallStatus.CONNECTED,
                                connectedSinceMs = if (st.connectedSinceMs == 0L) System.currentTimeMillis() else st.connectedSinceMs
                            )
                        }

                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            hangupCurrentCall(sendRemoteHangup = false)
                        }

                        else -> Unit
                    }
                }
            }
        ) ?: return null

        // Attach encrypted audio track
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        val audioTrack = peerConnectionFactory.createAudioTrack("opensnap_audio_0", audioSource)
        audioTrack.setEnabled(true)
        pc.addTrack(audioTrack, listOf("opensnap_stream_0"))
        localAudioSource = audioSource
        localAudioTrack = audioTrack

        // Attach Camera2 video track when in Video Call mode
        if (isVideoCall) {
            val enumerator = Camera2Enumerator(appContext)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (deviceName != null) {
                val capturer = enumerator.createCapturer(deviceName, null)
                val helper = SurfaceTextureHelper.create("OpenSnapCamThread", eglBase.eglBaseContext)
                val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                capturer.initialize(helper, appContext, videoSource.capturerObserver)
                try {
                    capturer.startCapture(1280, 720, 30)
                } catch (_: Exception) {
                }
                val videoTrack = peerConnectionFactory.createVideoTrack("opensnap_video_0", videoSource)
                videoTrack.setEnabled(true)
                localRendererRef?.let { videoTrack.addSink(it) }
                pc.addTrack(videoTrack, listOf("opensnap_stream_0"))

                cameraCapturer = capturer
                surfaceTextureHelper = helper
                localVideoSource = videoSource
                localVideoTrack = videoTrack
            }
        }

        callPeerConnection = pc
        return pc
    }

    fun bindVideoRenderers(localRenderer: SurfaceViewRenderer?, remoteRenderer: SurfaceViewRenderer?) {
        localRendererRef = localRenderer
        remoteRendererRef = remoteRenderer
        if (localRenderer != null) {
            localVideoTrack?.addSink(localRenderer)
        }
        if (remoteRenderer != null) {
            remoteVideoTrack?.addSink(remoteRenderer)
        }
    }

    fun toggleMicMute() {
        val nextMuted = !_callState.value.isMicMuted
        localAudioTrack?.setEnabled(!nextMuted)
        _callState.value = _callState.value.copy(isMicMuted = nextMuted)
    }

    fun toggleCameraEnabled() {
        val nextEnabled = !_callState.value.isCameraEnabled
        localVideoTrack?.setEnabled(nextEnabled)
        _callState.value = _callState.value.copy(isCameraEnabled = nextEnabled)
    }

    fun switchCameraFacing() {
        cameraCapturer?.switchCamera(null)
    }

    fun toggleSpeakerphone() {
        val nextSpeaker = !_callState.value.isSpeakerOn
        configureCallAudioMode(enable = true, speakerphone = nextSpeaker)
        _callState.value = _callState.value.copy(isSpeakerOn = nextSpeaker)
    }

    fun hangupCurrentCall(sendRemoteHangup: Boolean = true) {
        val current = _callState.value
        if (sendRemoteHangup && current.callId.isNotBlank() && current.peerEd25519PubHex.isNotBlank()) {
            onSignalReadyToSend(
                current.peerEd25519PubHex,
                WebRtcSignalPayload(
                    callId = current.callId,
                    signalType = "HANGUP",
                    isVideoCall = current.isVideoCall
                )
            )
        }

        try {
            cameraCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            cameraCapturer?.dispose()
        } catch (_: Exception) {
        }
        cameraCapturer = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null

        try {
            localVideoTrack?.dispose()
            localVideoSource?.dispose()
            localAudioTrack?.dispose()
            localAudioSource?.dispose()
            callPeerConnection?.close()
        } catch (_: Exception) {
        }

        localVideoTrack = null
        localVideoSource = null
        remoteVideoTrack = null
        localAudioTrack = null
        localAudioSource = null
        callPeerConnection = null
        pendingOfferSdp = null
        pendingIceCandidates.clear()

        configureCallAudioMode(enable = false, speakerphone = false)
        _callState.value = CallUiState(status = CallStatus.IDLE)
    }

    @Suppress("DEPRECATION")
    private fun configureCallAudioMode(enable: Boolean, speakerphone: Boolean) {
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    audioManager.requestAudioFocus(req)
                }
                audioManager.isSpeakerphoneOn = speakerphone
            } else {
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {
        }
    }

    fun shutdownAll() {
        hangupCurrentCall(sendRemoteHangup = false)
        fastLanePeers.keys().toList().forEach { closeFastLanePeer(it) }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private open class SimplePeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
    }
}
