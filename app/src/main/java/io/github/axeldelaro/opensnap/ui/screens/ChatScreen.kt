package io.github.axeldelaro.opensnap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.daemon.DaemonIpcClient
import io.github.axeldelaro.opensnap.data.db.DeliveryState
import io.github.axeldelaro.opensnap.data.db.FriendEntity
import io.github.axeldelaro.opensnap.data.db.MessageContentType
import io.github.axeldelaro.opensnap.data.db.MessageEntity
import io.github.axeldelaro.opensnap.data.media.EncryptedMediaVault
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import io.github.axeldelaro.opensnap.network.protocol.PresenceKind
import io.github.axeldelaro.opensnap.ui.components.CompletedVoiceRecording
import io.github.axeldelaro.opensnap.ui.components.IdenticonAvatar
import io.github.axeldelaro.opensnap.ui.components.PushToTalkMicControl
import io.github.axeldelaro.opensnap.ui.components.VoiceMessagePlayerContent
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.ObsidianSurface
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import io.github.axeldelaro.opensnap.ui.theme.VerifiedEmerald
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    myIdentity: CryptoEngine.SovereignIdentityBundle,
    friend: FriendEntity,
    messages: List<MessageEntity>,
    nowClockMs: Long,
    presenceEvent: DaemonIpcClient.PeerPresenceEvent?,
    isFastLaneDirect: Boolean,
    onBack: () -> Unit,
    onOpenSnapCamera: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onVoiceRecordingStateChanged: (Boolean) -> Unit,
    onSendText: (String, MessageEntity?) -> Unit,
    onSendVoice: (CompletedVoiceRecording, MessageEntity?) -> Unit,
    onSendReaction: (MessageEntity, String) -> Unit,
    onConsumeViewOnce: (MessageEntity) -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onToggleSafetyVerified: (Boolean) -> Unit,
    onBlockFriend: () -> Unit
) {
    val context = LocalContext.current
    var draftText by remember { mutableStateOf("") }
    var quotedReplyMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var reactionTargetMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var activeViewOnceSnap by remember { mutableStateOf<MessageEntity?>(null) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    // Filter strictly non-expired messages on every clock tick
    val liveMessages = remember(messages, nowClockMs) {
        messages.filter { it.expiresAtMs > nowClockMs }
    }

    LaunchedEffect(liveMessages.size) {
        if (liveMessages.isNotEmpty()) {
            listState.animateScrollToItem(liveMessages.lastIndex)
        }
    }

    val headerSubtitle = when {
        presenceEvent?.presenceState == PresenceKind.TYPING &&
            nowClockMs - presenceEvent.updatedAtMs < 8_000L -> "✍️ En train d'écrire..."

        presenceEvent?.presenceState == PresenceKind.RECORDING_VOICE &&
            nowClockMs - presenceEvent.updatedAtMs < 15_000L -> "🎤 En train d'enregistrer un vocal..."

        isFastLaneDirect -> "⚡ En ligne • P2P WebRTC Direct"

        presenceEvent != null && presenceEvent.presenceState == PresenceKind.ONLINE &&
            nowClockMs - presenceEvent.updatedAtMs < 90_000L -> "🟢 En ligne • Tunnel E2EE"

        else -> "🔒 E2EE XChaCha20 • Expiration 24h00"
    }

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianSurface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IdenticonAvatar(
                            ed25519PubHex = friend.ed25519PubHex,
                            displayName = friend.displayName,
                            size = 38.dp,
                            isOnline = isFastLaneDirect || presenceEvent?.presenceState == PresenceKind.ONLINE
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = friend.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = headerSubtitle,
                                fontSize = 11.5.sp,
                                color = if (isFastLaneDirect || headerSubtitle.startsWith("✍️") || headerSubtitle.startsWith("🎤")) {
                                    CyberCyan
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onStartAudioCall) {
                        Icon(Icons.Default.Call, contentDescription = "Appel vocal P2P", tint = VerifiedEmerald)
                    }
                    IconButton(onClick = onStartVideoCall) {
                        Icon(Icons.Default.Videocam, contentDescription = "Appel vidéo P2P", tint = CyberCyan)
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Empreinte de sécurité (60 chiffres)") },
                                leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null, tint = VerifiedEmerald) },
                                onClick = {
                                    showOverflowMenu = false
                                    showSafetyDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Bloquer ce contact", color = FlameCoral) },
                                leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = FlameCoral) },
                                onClick = {
                                    showOverflowMenu = false
                                    onBlockFriend()
                                    onBack()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Quoted Swipe-to-Reply Preview Banner
                quotedReplyMessage?.let { quoted ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianCard)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = SnapElectricYellow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (quoted.isOutgoing) "Réponse à vous-même" else "Réponse à ${friend.displayName}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = SnapElectricYellow
                            )
                            Text(
                                text = quoted.textContent.ifBlank { "Média chiffré" },
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { quotedReplyMessage = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer réponse", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenSnapCamera,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(SnapElectricYellow)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Snap Photo", tint = ObsidianBg)
                    }

                    Spacer(Modifier.width(8.dp))

                    OutlinedTextField(
                        value = draftText,
                        onValueChange = {
                            draftText = it
                            onDraftChanged(it)
                        },
                        placeholder = { Text("Message chiffré 24h...", fontSize = 14.sp) },
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(8.dp))

                    if (draftText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val toSend = draftText
                                val reply = quotedReplyMessage
                                draftText = ""
                                quotedReplyMessage = null
                                onSendText(toSend, reply)
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(SnapElectricYellow)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer", tint = ObsidianBg)
                        }
                    } else {
                        PushToTalkMicControl(
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onRecordingStateChanged = onVoiceRecordingStateChanged,
                            onVoiceNoteReady = { recording ->
                                val reply = quotedReplyMessage
                                quotedReplyMessage = null
                                onSendVoice(recording, reply)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🛡️ Chiffré E2EE (XChaCha20-Poly1305 + Forward Secrecy)\n⏱ Tous les messages et médias s'auto-détruisent après 24h00",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            items(liveMessages, key = { it.messageId }) { msg ->
                EphemeralMessageBubble(
                    message = msg,
                    nowClockMs = nowClockMs,
                    onSwipeReply = { quotedReplyMessage = msg },
                    onLongPressReact = { reactionTargetMessage = msg },
                    onOpenViewOnceSnap = { activeViewOnceSnap = msg }
                )
            }
        }
    }

    // Emoji Reaction Picker Dialog
    reactionTargetMessage?.let { targetMsg ->
        val emojis = listOf("❤️", "😂", "🔥", "👍", "😮", "😢")
        Dialog(onDismissRequest = { reactionTargetMessage = null }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = ObsidianCard
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    emojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 26.sp,
                            modifier = Modifier.clickable {
                                onSendReaction(targetMsg, emoji)
                                reactionTargetMessage = null
                            }
                        )
                    }
                }
            }
        }
    }

    // Full-Screen View-Once Photo Snap Viewer (Cryptographically Shreds Upon Closing!)
    activeViewOnceSnap?.let { snapMsg ->
        ViewOnceFullScreenModal(
            message = snapMsg,
            onCloseAndShred = {
                if (!snapMsg.isOutgoing) {
                    onConsumeViewOnce(snapMsg)
                }
                activeViewOnceSnap = null
            }
        )
    }

    // 60-Digit Safety Number Verification Dialog
    if (showSafetyDialog) {
        val safetyDigits = remember(myIdentity.ed25519PublicHex, friend.ed25519PubHex) {
            CryptoEngine.computeSafetyNumber(myIdentity.ed25519PublicHex, friend.ed25519PubHex)
        }
        AlertDialog(
            onDismissRequest = { showSafetyDialog = false },
            containerColor = ObsidianSurface,
            title = { Text("Empreinte de Sécurité E2EE", color = SnapElectricYellow, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Comparez ces 60 chiffres avec ${friend.displayName} (en personne ou par appel vocal) pour garantir l'absence totale d'interception (MitM) :",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianBg)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = safetyDigits,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = CyberCyan,
                            lineHeight = 23.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onToggleSafetyVerified(!friend.isSafetyVerified)
                        showSafetyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VerifiedEmerald,
                        contentColor = ObsidianBg
                    )
                ) {
                    Text(
                        text = if (friend.isSafetyVerified) "Retirer la vérification" else "Marquer comme vérifié",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafetyDialog = false }) {
                    Text("Fermer")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EphemeralMessageBubble(
    message: MessageEntity,
    nowClockMs: Long,
    onSwipeReply: () -> Unit,
    onLongPressReact: () -> Unit,
    onOpenViewOnceSnap: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val isMine = message.isOutgoing

    val bubbleBg = if (isMine) Color(0xFF212B3F) else ObsidianCard
    val borderCol = if (isMine) SnapElectricYellow.copy(alpha = 0.35f) else CyberCyan.copy(alpha = 0.25f)
    val countdownLabel = TtlPurgeEngine.formatRemainingCountdown(message.expiresAtMs, nowClockMs)

    val reactionsList = remember(message.reactionsJson) {
        runCatching {
            val obj = CryptoEngine.canonicalJson.parseToJsonElement(message.reactionsJson).jsonObject
            obj.entries.map { "${it.value.jsonPrimitive.content}" }
        }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(message.messageId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(offsetX) > 95f) {
                            onSwipeReply()
                        }
                        offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount).coerceIn(-140f, 140f)
                    }
                )
            },
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 140.dp, max = 305.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isMine) 18.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 18.dp
                    )
                )
                .background(bubbleBg)
                .border(
                    width = 1.dp,
                    color = borderCol,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isMine) 18.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 18.dp
                    )
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPressReact
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            // Quoted Reply Header inside Bubble
            if (!message.replyPreviewText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianBg.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "↩ ${message.replyPreviewText}",
                        fontSize = 11.5.sp,
                        color = SnapElectricYellow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when (message.contentType) {
                MessageContentType.TEXT -> {
                    Text(
                        text = message.textContent,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }

                MessageContentType.VOICE -> {
                    VoiceMessagePlayerContent(
                        messageId = message.messageId,
                        encryptedMediaPath = message.encryptedMediaPath,
                        mediaKeyHex = message.mediaKeyHex,
                        durationMs = message.voiceDurationMs,
                        waveformCsv = message.waveformCsv,
                        accentColor = if (isMine) SnapElectricYellow else CyberCyan
                    )
                }

                MessageContentType.PHOTO -> {
                    if (message.isViewOnce) {
                        if (message.isViewOnceConsumed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = MutedFlameColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Snap Vue unique détruit", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (isMine) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = FlameCoral, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Snap Vue unique envoyé", fontWeight = FontWeight.Bold, color = FlameCoral)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(FlameCoral.copy(alpha = 0.18f))
                                    .clickable { onOpenViewOnceSnap() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = FlameCoral)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Toucher pour ouvrir (Vue unique)",
                                    fontWeight = FontWeight.Bold,
                                    color = FlameCoral,
                                    fontSize = 13.5.sp
                                )
                            }
                        }
                    } else {
                        // 24h In-Chat Decrypted RAM Photo Snap
                        val decodedBitmap = remember(message.messageId, message.encryptedMediaPath) {
                            val bytes = EncryptedMediaVault.readDecryptedMediaBytes(
                                messageId = message.messageId,
                                encryptedPath = message.encryptedMediaPath,
                                mediaKeyHex = message.mediaKeyHex
                            )
                            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                        if (decodedBitmap != null) {
                            Image(
                                bitmap = decodedBitmap.asImageBitmap(),
                                contentDescription = "Snap Photo 24h",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 260.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                        if (message.textContent.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(text = message.textContent, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(5.dp))

            // Footer: Live 24h TTL Countdown + Delivery Status ("Envoyé" / "Reçu" / "Ouvert")
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = countdownLabel,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isMine) {
                    val (statusLabel, statusColor) = when (message.deliveryStatus) {
                        DeliveryState.SENDING -> "Envoi..." to MaterialTheme.colorScheme.onSurfaceVariant
                        DeliveryState.SENT -> "Envoyé" to MaterialTheme.colorScheme.onSurfaceVariant
                        DeliveryState.DELIVERED -> "Reçu" to CyberCyan
                        DeliveryState.OPENED -> "Ouvert" to VerifiedEmerald
                        else -> "Envoyé" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "• $statusLabel",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
        }

        // Emoji Reaction Pills
        if (reactionsList.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ObsidianCard, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                reactionsList.forEach { emoji ->
                    Text(text = emoji, fontSize = 13.sp)
                }
            }
        }
    }
}

private val MutedFlameColor = Color(0xFF94A3B8)

@Composable
private fun ViewOnceFullScreenModal(
    message: MessageEntity,
    onCloseAndShred: () -> Unit
) {
    val bitmap = remember(message.messageId) {
        val bytes = EncryptedMediaVault.readDecryptedMediaBytes(
            messageId = message.messageId,
            encryptedPath = message.encryptedMediaPath,
            mediaKeyHex = message.mediaKeyHex
        )
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    DisposableEffect(message.messageId) {
        onDispose {
            bitmap?.recycle()
        }
    }

    Dialog(
        onDismissRequest = onCloseAndShred,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onCloseAndShred() },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Snap Vue unique",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (message.textContent.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = message.textContent,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(FlameCoral)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Toucher pour détruire", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
