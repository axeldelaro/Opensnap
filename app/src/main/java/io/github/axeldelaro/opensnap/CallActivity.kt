package io.github.axeldelaro.opensnap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.daemon.DaemonIpcClient
import io.github.axeldelaro.opensnap.daemon.DaemonIpcContract
import io.github.axeldelaro.opensnap.daemon.NotificationHelper
import io.github.axeldelaro.opensnap.network.protocol.PacketType
import io.github.axeldelaro.opensnap.network.protocol.WebRtcSignalPayload
import io.github.axeldelaro.opensnap.network.webrtc.WebRtcMeshEngine
import io.github.axeldelaro.opensnap.ui.components.IdenticonAvatar
import io.github.axeldelaro.opensnap.ui.components.formatDurationMmSs
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.OpenSnapTheme
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import io.github.axeldelaro.opensnap.ui.theme.VerifiedEmerald
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-Screen Encrypted P2P WebRTC Audio & Video Call Activity.
 * Wakes over the lock screen (`setShowWhenLocked(true)` + `setTurnScreenOn(true)`) via `fullScreenIntent`.
 */
class CallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OUTGOING_CALL = "extra_outgoing_call"
        const val EXTRA_IS_VIDEO_CALL = "extra_is_video_call"
    }

    private lateinit var ipcClient: DaemonIpcClient
    private lateinit var rtcEngine: WebRtcMeshEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        super.onCreate(savedInstanceState)

        val peerPubHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_PUB_HEX).orEmpty()
        val peerX25519Hex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_X25519_HEX).orEmpty()
        val peerRoutingHex = intent.getStringExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX).orEmpty()
        val peerName = intent.getStringExtra(DaemonIpcContract.KEY_PEER_DISPLAY_NAME) ?: "Ami OpenSnap"
        val isOutgoing = intent.getBooleanExtra(EXTRA_OUTGOING_CALL, false)
        val isVideoOutgoing = intent.getBooleanExtra(EXTRA_IS_VIDEO_CALL, false)
        val incomingOfferJson = intent.getStringExtra(DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON)

        if (peerPubHex.isBlank()) {
            finish()
            return
        }

        ipcClient = DaemonIpcClient(applicationContext).also { it.startAndBindDaemon() }
        rtcEngine = WebRtcMeshEngine(
            context = applicationContext,
            onSignalReadyToSend = { _, signal ->
                val sigJson = CryptoEngine.canonicalJson.encodeToString(signal)
                ipcClient.dispatchOutboundPacket(
                    peerEd25519PubHex = peerPubHex,
                    peerX25519PubHex = peerX25519Hex,
                    peerRoutingPubHex = peerRoutingHex,
                    peerRatchetPubHex = null,
                    packetType = PacketType.WEBRTC_SIGNAL,
                    bodyJson = sigJson
                )
            }
        )

        if (!incomingOfferJson.isNullOrBlank()) {
            runCatching {
                CryptoEngine.canonicalJson.decodeFromString<WebRtcSignalPayload>(incomingOfferJson)
            }.getOrNull()?.let { offer ->
                rtcEngine.registerIncomingCallOffer(peerPubHex, peerName, offer)
            }
        }

        setContent {
            OpenSnapTheme {
                P2pCallScreen(
                    rtcEngine = rtcEngine,
                    ipcClient = ipcClient,
                    peerPubHex = peerPubHex,
                    peerName = peerName,
                    startOutgoingImmediately = isOutgoing,
                    outgoingIsVideo = isVideoOutgoing,
                    onCloseCallScreen = {
                        NotificationHelper.clearCallNotification(applicationContext)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        NotificationHelper.clearCallNotification(applicationContext)
        rtcEngine.shutdownAll()
        ipcClient.unbind()
        super.onDestroy()
    }
}

@Composable
private fun P2pCallScreen(
    rtcEngine: WebRtcMeshEngine,
    ipcClient: DaemonIpcClient,
    peerPubHex: String,
    peerName: String,
    startOutgoingImmediately: Boolean,
    outgoingIsVideo: Boolean,
    onCloseCallScreen: () -> Unit
) {
    val callState by rtcEngine.callState.collectAsState()
    var durationMs by remember { mutableLongStateOf(0L) }
    var startedOnce by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (startOutgoingImmediately && !startedOnce) {
            startedOnce = true
            rtcEngine.startOutgoingCall(
                callId = CryptoEngine.randomHex(12),
                peerEd25519PubHex = peerPubHex,
                peerDisplayName = peerName,
                isVideoCall = outgoingIsVideo
            )
        }
    }

    LaunchedEffect(Unit) {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (outgoingIsVideo || callState.isVideoCall) {
                add(Manifest.permission.CAMERA)
            }
        }
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            if (startOutgoingImmediately && !startedOnce) {
                startedOnce = true
                rtcEngine.startOutgoingCall(
                    callId = CryptoEngine.randomHex(12),
                    peerEd25519PubHex = peerPubHex,
                    peerDisplayName = peerName,
                    isVideoCall = outgoingIsVideo
                )
            }
        } else {
            permLauncher.launch(required.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        ipcClient.callSignals.collect { evt ->
            if (evt.peerEd25519PubHex.equals(peerPubHex, ignoreCase = true)) {
                rtcEngine.handleIncomingSignal(evt.peerEd25519PubHex, evt.peerDisplayName, evt.signal)
                if (evt.signal.signalType == "HANGUP" || evt.signal.signalType == "BUSY") {
                    onCloseCallScreen()
                }
            }
        }
    }

    LaunchedEffect(callState.status, callState.connectedSinceMs) {
        if (callState.status == WebRtcMeshEngine.CallStatus.CONNECTED && callState.connectedSinceMs > 0L) {
            while (isActive) {
                durationMs = System.currentTimeMillis() - callState.connectedSinceMs
                delay(500L)
            }
        }
    }

    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(localRenderer, remoteRenderer) {
        rtcEngine.bindVideoRenderers(localRenderer, remoteRenderer)
        onDispose {
            localRenderer?.release()
            remoteRenderer?.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Remote Full-Screen Video Renderer
        if (callState.isVideoCall) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(rtcEngine.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        remoteRenderer = this
                    }
                }
            )

            // Local Picture-in-Picture Self Video
            AndroidView(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(width = 110.dp, height = 155.dp)
                    .clip(RoundedCornerShape(16.dp)),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(rtcEngine.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(true)
                        setZOrderMediaOverlay(true)
                        localRenderer = this
                    }
                }
            )
        }

        // Peer Identity & Encrypted Status Header
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!callState.isVideoCall || callState.status != WebRtcMeshEngine.CallStatus.CONNECTED) {
                IdenticonAvatar(ed25519PubHex = peerPubHex, displayName = peerName, size = 92.dp, isOnline = true)
                Spacer(Modifier.height(14.dp))
            }

            Text(
                text = peerName,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))

            val subtitle = when (callState.status) {
                WebRtcMeshEngine.CallStatus.INCOMING_RINGING ->
                    if (callState.isVideoCall) "Appel vidéo P2P chiffré entrant..." else "Appel vocal P2P chiffré entrant..."

                WebRtcMeshEngine.CallStatus.OUTGOING_RINGING -> "Sonnerie chiffrée en cours..."
                WebRtcMeshEngine.CallStatus.CONNECTING -> "Négociation DTLS-SRTP P2P..."
                WebRtcMeshEngine.CallStatus.CONNECTED -> "🔒 P2P Direct • ${formatDurationMmSs(durationMs)}"
                else -> "Appel terminé"
            }

            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = CyberCyan,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Bottom Call Action Bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 36.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (callState.status == WebRtcMeshEngine.CallStatus.INCOMING_RINGING) {
                // Decline Call
                IconButton(
                    onClick = {
                        NotificationHelper.clearCallNotification(context)
                        rtcEngine.hangupCurrentCall(sendRemoteHangup = true)
                        onCloseCallScreen()
                    },
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(FlameCoral)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Refuser", tint = Color.White, modifier = Modifier.size(30.dp))
                }

                // Answer Call
                IconButton(
                    onClick = {
                        NotificationHelper.clearCallNotification(context)
                        rtcEngine.acceptIncomingCall()
                    },
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(VerifiedEmerald)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Répondre", tint = ObsidianBg, modifier = Modifier.size(30.dp))
                }
            } else {
                IconButton(
                    onClick = { rtcEngine.toggleMicMute() },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (callState.isMicMuted) FlameCoral else ObsidianCard)
                ) {
                    Icon(
                        imageVector = if (callState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Micro",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = { rtcEngine.toggleSpeakerphone() },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (callState.isSpeakerOn) SnapElectricYellow else ObsidianCard)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Haut-parleur",
                        tint = if (callState.isSpeakerOn) ObsidianBg else Color.White
                    )
                }

                if (callState.isVideoCall) {
                    IconButton(
                        onClick = { rtcEngine.toggleCameraEnabled() },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(ObsidianCard)
                    ) {
                        Icon(
                            imageVector = if (callState.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Caméra",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { rtcEngine.switchCameraFacing() },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(ObsidianCard)
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Retourner", tint = Color.White)
                    }
                }

                IconButton(
                    onClick = {
                        rtcEngine.hangupCurrentCall(sendRemoteHangup = true)
                        onCloseCallScreen()
                    },
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(FlameCoral)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Raccrocher", tint = Color.White)
                }
            }
        }
    }
}
