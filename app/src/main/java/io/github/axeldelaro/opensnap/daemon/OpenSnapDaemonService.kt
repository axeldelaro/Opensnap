package io.github.axeldelaro.opensnap.daemon

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import io.github.axeldelaro.opensnap.data.db.DeliveryState
import io.github.axeldelaro.opensnap.data.db.FriendEntity
import io.github.axeldelaro.opensnap.data.db.FriendStatus
import io.github.axeldelaro.opensnap.data.db.MessageContentType
import io.github.axeldelaro.opensnap.data.db.MessageEntity
import io.github.axeldelaro.opensnap.data.db.OpenSnapDatabase
import io.github.axeldelaro.opensnap.data.db.RatchetKeyEntity
import io.github.axeldelaro.opensnap.data.db.SeenPacketEntity
import io.github.axeldelaro.opensnap.data.media.EncryptedMediaVault
import io.github.axeldelaro.opensnap.data.ttl.PanicWipeManager
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import io.github.axeldelaro.opensnap.network.protocol.ChatPhotoPayload
import io.github.axeldelaro.opensnap.network.protocol.ChatTextPayload
import io.github.axeldelaro.opensnap.network.protocol.ChatVoicePayload
import io.github.axeldelaro.opensnap.network.protocol.NostrModels
import io.github.axeldelaro.opensnap.network.protocol.OpenSnapPacketCodec
import io.github.axeldelaro.opensnap.network.protocol.OpenSnapSignedPacket
import io.github.axeldelaro.opensnap.network.protocol.PacketType
import io.github.axeldelaro.opensnap.network.protocol.PresenceKind
import io.github.axeldelaro.opensnap.network.protocol.PresencePayload
import io.github.axeldelaro.opensnap.network.protocol.ReactionPayload
import io.github.axeldelaro.opensnap.network.protocol.ReceiptPayload
import io.github.axeldelaro.opensnap.network.protocol.VaultFriendEntry
import io.github.axeldelaro.opensnap.network.protocol.WebRtcSignalPayload
import io.github.axeldelaro.opensnap.network.relay.NetworkConfigVerifier
import io.github.axeldelaro.opensnap.network.relay.NostrRelayPool
import io.github.axeldelaro.opensnap.network.webrtc.WebRtcMeshEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Dedicated Background Push Daemon (`android:process=":network_daemon"`).
 * Guarantees WhatsApp/Snapchat-grade sub-300ms notification delivery with ZERO random polling:
 * - Persistent `ForegroundService` (`remoteMessaging|specialUse`, `START_STICKY`, `onTaskRemoved` self-heal)
 * - Multiplexed WSS Nostr relay pool + direct P2P WebRTC `DataChannel` fast-lane
 * - Adaptive 4G/5G vs Wi-Fi Ping-Pong keepalives + surgical `PartialWakeLock` & `WifiLock` per packet
 * - Strict cryptographic anti-replay, anti-flood PoW, and mutual-friend whitelist gate
 * - Continuous 24h00m00s cryptographic TTL scrubber
 */
class OpenSnapDaemonService : Service() {

    companion object {
        const val ACTION_START_TUNNEL = "io.github.axeldelaro.opensnap.ACTION_START_TUNNEL"
        const val ACTION_OUTBOUND_PACKET = "io.github.axeldelaro.opensnap.ACTION_OUTBOUND_PACKET"
        const val ACTION_NETWORK_RESTORED = "io.github.axeldelaro.opensnap.ACTION_NETWORK_RESTORED"
        const val ACTION_PANIC_WIPE = "io.github.axeldelaro.opensnap.ACTION_PANIC_WIPE"
        const val EXTRA_OUTBOUND_BUNDLE = "extra_outbound_bundle"

        fun ensureRunning(context: Context) {
            if (!HardwareKeystoreManager.hasActiveAccount(context)) return
            val intent = Intent(context, OpenSnapDaemonService::class.java).apply {
                action = ACTION_START_TUNNEL
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun enqueueOutboundViaIntent(context: Context, bundle: Bundle) {
            val intent = Intent(context, OpenSnapDaemonService::class.java).apply {
                action = ACTION_OUTBOUND_PACKET
                putExtra(EXTRA_OUTBOUND_BUNDLE, bundle)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun dispatchPanicWipeIntent(context: Context) {
            val intent = Intent(context, OpenSnapDaemonService::class.java).apply {
                action = ACTION_PANIC_WIPE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiClients = CopyOnWriteArraySet<Messenger>()

    @Volatile
    private var activeVisibleChatPeerHex: String? = null

    @Volatile
    private var activeIdentity: CryptoEngine.SovereignIdentityBundle? = null

    @Volatile
    private var currentNetConfig: NetworkConfigVerifier.SignedNetworkConfig =
        NetworkConfigVerifier.SignedNetworkConfig()

    @Volatile
    private var connectedRelayCount: Int = 0

    @Volatile
    private var totalRelayCount: Int = NetworkConfigVerifier.DEFAULT_BOOTSTRAP_RELAYS.size

    @Volatile
    private var currentTransportLabel: String = "Wi-Fi / Cellulaire"

    private lateinit var relayPool: NostrRelayPool
    private lateinit var webRtcFastLane: WebRtcMeshEngine
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var partialWakeLock: PowerManager.WakeLock? = null
    private var wifiPacketLock: WifiManager.WifiLock? = null

    private val incomingIpcHandler = Handler(Looper.getMainLooper()) { msg ->
        handleUiIpcMessage(msg)
        true
    }
    private val serviceMessenger = Messenger(incomingIpcHandler)

    override fun onCreate() {
        super.onCreate()
        promoteToForegroundDiscrete()
        initPacketLocks()

        relayPool = NostrRelayPool(
            onGiftWrapReceived = { event, _ ->
                daemonScope.launch {
                    withSurgicalPacketLocks {
                        processIncomingGiftWrapEvent(event)
                    }
                }
            },
            onConnectionCountChanged = { connected, total ->
                connectedRelayCount = connected
                totalRelayCount = total
                refreshForegroundDaemonNotification()
                broadcastStatusToUi()
            }
        )

        webRtcFastLane = WebRtcMeshEngine(
            context = applicationContext,
            onSignalReadyToSend = { peerHex, signalPayload ->
                daemonScope.launch {
                    sendInternalWebRtcSignal(peerHex, signalPayload)
                }
            },
            onFastLaneEnvelopeReceived = { _, sealedJson ->
                daemonScope.launch {
                    withSurgicalPacketLocks {
                        processDecryptedSealedEnvelope(sealedJson)
                    }
                }
            }
        )

        registerAdaptiveNetworkCallback()
        startContinuous24hPurgeAndRatchetLoop()
        reloadIdentityAndOpenMesh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForegroundDiscrete()

        when (intent?.action) {
            ACTION_PANIC_WIPE -> {
                relayPool.stopAll()
                webRtcFastLane.shutdownAll()
                PanicWipeManager.executeEmergencyWipe(applicationContext, notifyDaemon = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_OUTBOUND_PACKET -> {
                val bundle = intent.getBundleExtra(EXTRA_OUTBOUND_BUNDLE)
                if (bundle != null) {
                    daemonScope.launch {
                        dispatchPacketFromBundle(bundle)
                    }
                }
            }

            ACTION_NETWORK_RESTORED -> {
                val transport = detectCurrentTransport()
                relayPool.updateNetworkTransport(transport)
            }

            else -> {
                if (activeIdentity == null) {
                    reloadIdentityAndOpenMesh()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Re-arm tunnel immediately via AlarmManager if user swipes away MainActivity from Recents
        if (HardwareKeystoreManager.hasActiveAccount(applicationContext)) {
            val restartIntent = Intent(applicationContext, OpenSnapDaemonService::class.java).apply {
                action = ACTION_START_TUNNEL
            }
            val pending = PendingIntent.getService(
                applicationContext,
                7701,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 800L,
                pending
            )
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        relayPool.stopAll()
        webRtcFastLane.shutdownAll()
        daemonScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForegroundDiscrete() {
        val notification = NotificationHelper.buildSilentDaemonNotification(
            context = this,
            connectedRelays = connectedRelayCount,
            totalRelays = totalRelayCount,
            transportLabel = currentTransportLabel
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.DAEMON_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            } else {
                startForeground(NotificationHelper.DAEMON_NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NotificationHelper.DAEMON_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NotificationHelper.DAEMON_NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshForegroundDaemonNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NotificationHelper.DAEMON_NOTIFICATION_ID,
                NotificationHelper.buildSilentDaemonNotification(
                    context = this,
                    connectedRelays = connectedRelayCount,
                    totalRelays = totalRelayCount,
                    transportLabel = currentTransportLabel
                )
            )
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun initPacketLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        partialWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenSnap:PacketWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiPacketLock = wm.createWifiLock(wifiMode, "OpenSnap:PacketWifiLock").apply {
            setReferenceCounted(false)
        }
    }

    /**
     * Acquires `PartialWakeLock` + `WifiLock` strictly during packet decryption, DB commit,
     * and notification dispatch, releasing immediately afterwards for zero idle battery drain.
     */
    private suspend fun <T> withSurgicalPacketLocks(block: suspend () -> T): T {
        try {
            partialWakeLock?.acquire(8_000L)
            wifiPacketLock?.acquire()
        } catch (_: Exception) {
        }
        return try {
            block()
        } finally {
            try {
                if (wifiPacketLock?.isHeld == true) wifiPacketLock?.release()
            } catch (_: Exception) {
            }
            try {
                if (partialWakeLock?.isHeld == true) partialWakeLock?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun reloadIdentityAndOpenMesh() {
        daemonScope.launch {
            val loaded = HardwareKeystoreManager.loadIdentitySession(applicationContext)
            activeIdentity = loaded
            if (loaded == null) {
                relayPool.stopAll()
                return@launch
            }

            // Ensure at least one active Forward Secrecy X25519 ratchet key exists
            ensureFreshRatchetKey()

            // Start immediately with bootstrap relays (< 100ms), then refresh verified GitHub config
            val transport = detectCurrentTransport()
            relayPool.start(
                routingPubHex = loaded.routingPublicHex,
                relayUrls = currentNetConfig.relays,
                transport = transport
            )

            // Publish our self-certifying `OS-XXXX-XXXX` Tag card to decentralized NIP-78 directory
            delay(1_200L)
            relayPool.publishPublicTagCard(loaded)

            val verifiedCfg = NetworkConfigVerifier.fetchVerifiedOrBootstrap()
            currentNetConfig = verifiedCfg
            relayPool.start(
                routingPubHex = loaded.routingPublicHex,
                relayUrls = verifiedCfg.relays,
                transport = detectCurrentTransport()
            )
        }
    }

    private fun startContinuous24hPurgeAndRatchetLoop() {
        daemonScope.launch {
            var tick = 0
            while (isActive) {
                try {
                    if (HardwareKeystoreManager.hasActiveAccount(applicationContext)) {
                        TtlPurgeEngine.purgeExpiredContent(applicationContext)
                        if (tick % 120 == 0) {
                            // Rotate Forward Secrecy X25519 ratchet key every ~30 minutes
                            ensureFreshRatchetKey()
                        }
                    }
                } catch (_: Exception) {
                }
                tick++
                delay(15_000L)
            }
        }
    }

    private suspend fun ensureFreshRatchetKey(): String {
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val nowMs = System.currentTimeMillis()
        val existing = dao.getActiveRatchetKeys(nowMs)
        val newest = existing.firstOrNull()
        if (newest != null && nowMs - newest.createdAtMs < 30L * 60L * 1000L) {
            return newest.ratchetPubHex
        }
        val (privHex, pubHex) = CryptoEngine.generateRatchetX25519KeyPair()
        dao.insertRatchetKey(
            RatchetKeyEntity(
                ratchetPubHex = pubHex,
                ratchetPrivHex = privHex,
                createdAtMs = nowMs,
                expiresAtMs = nowMs + TtlPurgeEngine.TTL_24H_MS + 3_600_000L
            )
        )
        return pubHex
    }

    // =========================================================================
    // Incoming E2EE Packet Verification, Whitelist Gate & Instant Notification
    // =========================================================================

    private suspend fun processIncomingGiftWrapEvent(event: NostrModels.NostrEvent) {
        // 1. Verify outer NIP-01 SHA-256 ID, BIP-340 Schnorr signature, NIP-40 24h TTL & NIP-13 PoW
        if (!NostrModels.verifyOuterEvent(event, minPowBits = 6)) {
            return
        }
        processDecryptedSealedEnvelope(event.content)
    }

    private suspend fun processDecryptedSealedEnvelope(sealedEnvelopeJson: String) {
        val identity = activeIdentity ?: HardwareKeystoreManager.loadIdentitySession(applicationContext)
            ?.also { activeIdentity = it } ?: return

        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val nowMs = System.currentTimeMillis()

        // 2. Gather our active 24h Forward Secrecy X25519 ratchet keys
        val myRatchetPrivs = dao.getActiveRatchetKeys(nowMs).map { it.ratchetPrivHex }

        // 3. Decrypt XChaCha20-Poly1305 inner envelope & verify Ed25519 signature + self-certifying Tag
        val packet = OpenSnapPacketCodec.decryptAndVerifyPacket(
            sealedEnvelopeJson = sealedEnvelopeJson,
            myStaticX25519PrivHex = identity.x25519PrivateHex,
            myRatchetPrivHexes = myRatchetPrivs,
            nowMs = nowMs
        ) ?: return

        // Ignore self-echoes
        if (packet.senderEd25519PubHex.equals(identity.ed25519PublicHex, ignoreCase = true)) {
            return
        }

        // 4. Anti-Replay check: atomic insert into SQLCipher `seen_packets`
        if (dao.hasSeenPacket(packet.packetId)) return
        val insertedRow = dao.recordSeenPacket(
            SeenPacketEntity(
                packetId = packet.packetId,
                senderEd25519PubHex = packet.senderEd25519PubHex,
                receivedAtMs = nowMs,
                expiresAtMs = packet.expiresAtMs
            )
        )
        if (insertedRow == -1L) return

        // 5. Anti-Flood per-sender rate limit (max 40 packets / 60s)
        val recentCount = dao.countRecentPacketsFromSender(packet.senderEd25519PubHex, nowMs - 60_000L)
        if (recentCount > 40) return

        // 6. Strict Cryptographic Whitelist Gate
        val existingFriend = dao.getFriendByPubKey(packet.senderEd25519PubHex)
        if (existingFriend?.status == FriendStatus.BLOCKED) {
            return
        }

        when (packet.packetType) {
            PacketType.FRIEND_REQUEST -> {
                handleIncomingFriendRequest(identity, existingFriend, packet, nowMs)
                return
            }

            PacketType.FRIEND_ACCEPT -> {
                if (existingFriend?.status == FriendStatus.PENDING_OUTGOING ||
                    existingFriend?.status == FriendStatus.ACCEPTED
                ) {
                    handleIncomingFriendAccept(identity, existingFriend, packet, nowMs)
                }
                return
            }

            else -> {
                // STRICT WHITELIST: Only mutually ACCEPTED friends can trigger chat, voice, snap,
                // typing, receipt, or WebRTC events!
                if (existingFriend == null || existingFriend.status != FriendStatus.ACCEPTED) {
                    return
                }
            }
        }

        // Update friend's latest Forward Secrecy X25519 ratchet key & lastSeen timestamp
        dao.updateFriendRatchetAndSeen(
            ed25519PubHex = packet.senderEd25519PubHex,
            ratchetPubHex = packet.nextRatchetX25519PubHex ?: existingFriend.peerRatchetX25519PubHex,
            nowMs = nowMs
        )

        val isPeerVisibleInUi = uiClients.isNotEmpty() &&
            activeVisibleChatPeerHex.equals(packet.senderEd25519PubHex, ignoreCase = true)

        when (packet.packetType) {
            PacketType.CHAT_TEXT -> handleIncomingChatText(identity, existingFriend, packet, isPeerVisibleInUi, nowMs)
            PacketType.CHAT_VOICE -> handleIncomingChatVoice(identity, existingFriend, packet, isPeerVisibleInUi, nowMs)
            PacketType.CHAT_PHOTO -> handleIncomingChatPhoto(identity, existingFriend, packet, isPeerVisibleInUi, nowMs)
            PacketType.REACTION -> handleIncomingReaction(packet)
            PacketType.RECEIPT -> handleIncomingReceipt(packet)
            PacketType.PRESENCE_STATE -> handleIncomingPresence(existingFriend, packet)
            PacketType.WEBRTC_SIGNAL -> handleIncomingWebRtcSignal(existingFriend, packet)
        }
    }

    private suspend fun handleIncomingFriendRequest(
        identity: CryptoEngine.SovereignIdentityBundle,
        existing: FriendEntity?,
        packet: OpenSnapSignedPacket,
        nowMs: Long
    ) {
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        if (existing?.status == FriendStatus.ACCEPTED) return

        // Auto-accept if we ALREADY sent a pending outgoing request to this exact friend!
        if (existing?.status == FriendStatus.PENDING_OUTGOING) {
            handleIncomingFriendAccept(identity, existing, packet, nowMs)
            return
        }

        val entity = FriendEntity(
            ed25519PubHex = packet.senderEd25519PubHex,
            x25519PubHex = packet.senderX25519PubHex,
            routingPubHex = packet.senderRoutingPubHex,
            openSnapTag = packet.senderOpenSnapTag,
            username = packet.senderUsername,
            displayName = packet.senderDisplayName,
            status = FriendStatus.PENDING_INCOMING,
            peerRatchetX25519PubHex = packet.nextRatchetX25519PubHex,
            lastSeenAtMs = nowMs,
            lastMessagePreview = "Demande d'ami reçue",
            lastActivityAtMs = nowMs,
            unreadCount = 1
        )
        dao.upsertFriend(entity)

        NotificationHelper.showFriendRequestNotification(
            context = applicationContext,
            peerEd25519PubHex = entity.ed25519PubHex,
            peerX25519PubHex = entity.x25519PubHex,
            peerRoutingPubHex = entity.routingPubHex,
            peerOpenSnapTag = entity.openSnapTag,
            peerDisplayName = entity.displayName
        )
    }

    private suspend fun handleIncomingFriendAccept(
        identity: CryptoEngine.SovereignIdentityBundle,
        existing: FriendEntity,
        packet: OpenSnapSignedPacket,
        nowMs: Long
    ) {
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val updated = existing.copy(
            displayName = packet.senderDisplayName.ifBlank { existing.displayName },
            x25519PubHex = packet.senderX25519PubHex,
            routingPubHex = packet.senderRoutingPubHex,
            status = FriendStatus.ACCEPTED,
            peerRatchetX25519PubHex = packet.nextRatchetX25519PubHex ?: existing.peerRatchetX25519PubHex,
            lastSeenAtMs = nowMs,
            lastMessagePreview = "Demande d'ami acceptée • Chat 24h prêt",
            lastActivityAtMs = nowMs
        )
        dao.upsertFriend(updated)
        syncEncryptedFriendVault(identity)
        webRtcFastLane.initiateFastLaneDataChannel(updated.ed25519PubHex)

        NotificationHelper.showIncomingMessageNotification(
            context = applicationContext,
            myDisplayName = identity.displayName,
            peerEd25519PubHex = updated.ed25519PubHex,
            peerX25519PubHex = updated.x25519PubHex,
            peerRoutingPubHex = updated.routingPubHex,
            peerRatchetPubHex = updated.peerRatchetX25519PubHex,
            peerDisplayName = updated.displayName,
            messageId = packet.packetId,
            displayBody = "🎉 ${updated.displayName} a accepté votre demande d'ami !",
            isVoiceMessage = false
        )
    }

    private suspend fun handleIncomingChatText(
        identity: CryptoEngine.SovereignIdentityBundle,
        friend: FriendEntity,
        packet: OpenSnapSignedPacket,
        isPeerVisibleInUi: Boolean,
        nowMs: Long
    ) {
        val payload = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<ChatTextPayload>(packet.bodyJson)
        }.getOrNull() ?: return

        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val status = if (isPeerVisibleInUi) DeliveryState.OPENED else DeliveryState.DELIVERED
        val msg = MessageEntity(
            messageId = packet.packetId,
            peerEd25519PubHex = friend.ed25519PubHex,
            isOutgoing = false,
            contentType = MessageContentType.TEXT,
            textContent = payload.text,
            replyToMessageId = payload.replyToMessageId,
            replyPreviewText = payload.replyPreviewText,
            deliveryStatus = status,
            sentAtMs = packet.createdAtMs,
            expiresAtMs = packet.createdAtMs + TtlPurgeEngine.TTL_24H_MS
        )
        dao.insertMessage(msg)
        dao.updateFriendConversationSummary(
            ed25519PubHex = friend.ed25519PubHex,
            preview = payload.text,
            activityAtMs = nowMs,
            unreadDelta = if (isPeerVisibleInUi) 0 else 1
        )

        // Immediately send automatic E2EE DELIVERED / OPENED receipt
        sendReceiptToFriend(friend, listOf(packet.packetId), status)

        if (!isPeerVisibleInUi) {
            NotificationHelper.showIncomingMessageNotification(
                context = applicationContext,
                myDisplayName = identity.displayName,
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = packet.nextRatchetX25519PubHex ?: friend.peerRatchetX25519PubHex,
                peerDisplayName = friend.displayName,
                messageId = packet.packetId,
                displayBody = payload.text,
                isVoiceMessage = false
            )
        }
    }

    private suspend fun handleIncomingChatVoice(
        identity: CryptoEngine.SovereignIdentityBundle,
        friend: FriendEntity,
        packet: OpenSnapSignedPacket,
        isPeerVisibleInUi: Boolean,
        nowMs: Long
    ) {
        val payload = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<ChatVoicePayload>(packet.bodyJson)
        }.getOrNull() ?: return

        val rawAudioBytes = runCatching {
            Base64.decode(payload.audioBase64, Base64.NO_WRAP)
        }.getOrNull() ?: return

        val storedMedia = EncryptedMediaVault.storeEncryptedMedia(
            context = applicationContext,
            messageId = packet.packetId,
            plaintextBytes = rawAudioBytes
        )
        rawAudioBytes.fill(0)

        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val status = if (isPeerVisibleInUi) DeliveryState.OPENED else DeliveryState.DELIVERED
        val seconds = (payload.durationMs / 1000L).coerceAtLeast(1L)
        val previewLabel = "🎤 Message vocal (${seconds}s)"

        val msg = MessageEntity(
            messageId = packet.packetId,
            peerEd25519PubHex = friend.ed25519PubHex,
            isOutgoing = false,
            contentType = MessageContentType.VOICE,
            textContent = previewLabel,
            encryptedMediaPath = storedMedia.absolutePath,
            mediaKeyHex = storedMedia.mediaKeyHex,
            voiceDurationMs = payload.durationMs,
            waveformCsv = payload.waveformCsv,
            replyToMessageId = payload.replyToMessageId,
            replyPreviewText = payload.replyPreviewText,
            deliveryStatus = status,
            sentAtMs = packet.createdAtMs,
            expiresAtMs = packet.createdAtMs + TtlPurgeEngine.TTL_24H_MS
        )
        dao.insertMessage(msg)
        dao.updateFriendConversationSummary(
            ed25519PubHex = friend.ed25519PubHex,
            preview = previewLabel,
            activityAtMs = nowMs,
            unreadDelta = if (isPeerVisibleInUi) 0 else 1
        )

        sendReceiptToFriend(friend, listOf(packet.packetId), status)

        if (!isPeerVisibleInUi) {
            NotificationHelper.showIncomingMessageNotification(
                context = applicationContext,
                myDisplayName = identity.displayName,
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = packet.nextRatchetX25519PubHex ?: friend.peerRatchetX25519PubHex,
                peerDisplayName = friend.displayName,
                messageId = packet.packetId,
                displayBody = previewLabel,
                isVoiceMessage = true
            )
        }
    }

    private suspend fun handleIncomingChatPhoto(
        identity: CryptoEngine.SovereignIdentityBundle,
        friend: FriendEntity,
        packet: OpenSnapSignedPacket,
        isPeerVisibleInUi: Boolean,
        nowMs: Long
    ) {
        val payload = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<ChatPhotoPayload>(packet.bodyJson)
        }.getOrNull() ?: return

        val rawImageBytes = runCatching {
            Base64.decode(payload.webpBase64, Base64.NO_WRAP)
        }.getOrNull() ?: return

        val storedMedia = EncryptedMediaVault.storeEncryptedMedia(
            context = applicationContext,
            messageId = packet.packetId,
            plaintextBytes = rawImageBytes
        )
        rawImageBytes.fill(0)

        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val status = if (isPeerVisibleInUi && !payload.isViewOnce) DeliveryState.OPENED else DeliveryState.DELIVERED
        val previewLabel = if (payload.isViewOnce) {
            "🔥 Snap Photo (Vue unique)"
        } else {
            "📸 Snap Photo 24h${if (payload.caption.isNotBlank()) " : ${payload.caption}" else ""}"
        }

        val msg = MessageEntity(
            messageId = packet.packetId,
            peerEd25519PubHex = friend.ed25519PubHex,
            isOutgoing = false,
            contentType = MessageContentType.PHOTO,
            textContent = payload.caption,
            encryptedMediaPath = storedMedia.absolutePath,
            mediaKeyHex = storedMedia.mediaKeyHex,
            imageWidth = payload.width,
            imageHeight = payload.height,
            isViewOnce = payload.isViewOnce,
            isViewOnceConsumed = false,
            deliveryStatus = status,
            sentAtMs = packet.createdAtMs,
            expiresAtMs = packet.createdAtMs + TtlPurgeEngine.TTL_24H_MS
        )
        dao.insertMessage(msg)
        dao.updateFriendConversationSummary(
            ed25519PubHex = friend.ed25519PubHex,
            preview = previewLabel,
            activityAtMs = nowMs,
            unreadDelta = if (isPeerVisibleInUi) 0 else 1
        )

        sendReceiptToFriend(friend, listOf(packet.packetId), status)

        if (!isPeerVisibleInUi) {
            NotificationHelper.showIncomingMessageNotification(
                context = applicationContext,
                myDisplayName = identity.displayName,
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = packet.nextRatchetX25519PubHex ?: friend.peerRatchetX25519PubHex,
                peerDisplayName = friend.displayName,
                messageId = packet.packetId,
                displayBody = previewLabel,
                isVoiceMessage = false
            )
        }
    }

    private suspend fun handleIncomingReaction(packet: OpenSnapSignedPacket) {
        val payload = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<ReactionPayload>(packet.bodyJson)
        }.getOrNull() ?: return
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val target = dao.getMessageById(payload.targetMessageId) ?: return
        val existingObj: JsonObject = runCatching {
            CryptoEngine.canonicalJson.parseToJsonElement(target.reactionsJson).jsonObject
        }.getOrDefault(buildJsonObject {})

        val updatedObj = buildJsonObject {
            existingObj.forEach { (k, v) -> put(k, v) }
            if (payload.emoji.isNotBlank()) {
                put(packet.senderDisplayName.ifBlank { "Ami" }, JsonPrimitive(payload.emoji))
            }
        }
        dao.updateMessageReactions(
            payload.targetMessageId,
            CryptoEngine.canonicalJson.encodeToString(JsonObject.serializer(), updatedObj)
        )
    }

    private suspend fun handleIncomingReceipt(packet: OpenSnapSignedPacket) {
        val payload = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<ReceiptPayload>(packet.bodyJson)
        }.getOrNull() ?: return
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        payload.messageIds.forEach { id ->
            val current = dao.getMessageById(id) ?: return@forEach
            if (current.isOutgoing && current.deliveryStatus != DeliveryState.OPENED) {
                dao.updateMessageDeliveryStatus(id, payload.status)
            }
        }
    }

    private fun handleIncomingPresence(friend: FriendEntity, packet: OpenSnapSignedPacket) {
        val payload = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<PresencePayload>(packet.bodyJson)
        }.getOrNull() ?: return

        val isDirect = webRtcFastLane.activeFastLanePeers.value.contains(friend.ed25519PubHex)
        if (!isDirect && payload.state != PresenceKind.IDLE) {
            webRtcFastLane.initiateFastLaneDataChannel(friend.ed25519PubHex)
        }

        val bundle = Bundle().apply {
            putString(DaemonIpcContract.KEY_PEER_PUB_HEX, friend.ed25519PubHex)
            putString(DaemonIpcContract.KEY_PRESENCE_STATE, payload.state)
            putBoolean(DaemonIpcContract.KEY_IS_P2P_DIRECT, isDirect)
        }
        broadcastEventToUi(DaemonIpcContract.EVT_PEER_PRESENCE, bundle)
    }

    private fun handleIncomingWebRtcSignal(friend: FriendEntity, packet: OpenSnapSignedPacket) {
        val signal = runCatching {
            CryptoEngine.canonicalJson.decodeFromString<WebRtcSignalPayload>(packet.bodyJson)
        }.getOrNull() ?: return

        if (signal.signalType.startsWith("FASTLANE_")) {
            webRtcFastLane.handleIncomingSignal(friend.ed25519PubHex, friend.displayName, signal)
            broadcastStatusToUi()
            return
        }

        if (signal.signalType == "OFFER") {
            NotificationHelper.showIncomingCallFullScreenNotification(
                context = applicationContext,
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerDisplayName = friend.displayName,
                callSignalJson = packet.bodyJson,
                isVideoCall = signal.isVideoCall
            )
        } else if (signal.signalType == "HANGUP" || signal.signalType == "BUSY") {
            NotificationHelper.clearCallNotification(applicationContext)
        }

        val bundle = Bundle().apply {
            putString(DaemonIpcContract.KEY_PEER_PUB_HEX, friend.ed25519PubHex)
            putString(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, friend.displayName)
            putString(DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON, packet.bodyJson)
        }
        broadcastEventToUi(DaemonIpcContract.EVT_WEBRTC_CALL_SIGNAL, bundle)
    }

    // =========================================================================
    // Outbound E2EE Sealing & Dual-Path (P2P WebRTC Fast-Lane + Nostr WSS Mesh)
    // =========================================================================

    private suspend fun sendReceiptToFriend(friend: FriendEntity, messageIds: List<String>, status: String) {
        val bodyJson = CryptoEngine.canonicalJson.encodeToString(ReceiptPayload(messageIds, status))
        sealAndDispatchPacket(
            peerEd25519PubHex = friend.ed25519PubHex,
            peerX25519PubHex = friend.x25519PubHex,
            peerRoutingPubHex = friend.routingPubHex,
            peerRatchetPubHex = friend.peerRatchetX25519PubHex,
            packetType = PacketType.RECEIPT,
            packetId = CryptoEngine.randomHex(16),
            bodyJson = bodyJson,
            powBits = 6
        )
    }

    private suspend fun sendInternalWebRtcSignal(peerEd25519PubHex: String, signal: WebRtcSignalPayload) {
        val friend = OpenSnapDatabase.getInstance(applicationContext).dao().getFriendByPubKey(peerEd25519PubHex)
            ?: return
        val bodyJson = CryptoEngine.canonicalJson.encodeToString(signal)
        sealAndDispatchPacket(
            peerEd25519PubHex = friend.ed25519PubHex,
            peerX25519PubHex = friend.x25519PubHex,
            peerRoutingPubHex = friend.routingPubHex,
            peerRatchetPubHex = friend.peerRatchetX25519PubHex,
            packetType = PacketType.WEBRTC_SIGNAL,
            packetId = CryptoEngine.randomHex(16),
            bodyJson = bodyJson,
            powBits = 6
        )
    }

    private suspend fun dispatchPacketFromBundle(bundle: Bundle) {
        val peerHex = bundle.getString(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
        val peerX25519 = bundle.getString(DaemonIpcContract.KEY_PEER_X25519_HEX) ?: return
        val peerRouting = bundle.getString(DaemonIpcContract.KEY_PEER_ROUTING_HEX) ?: return
        val peerRatchet = bundle.getString(DaemonIpcContract.KEY_PEER_RATCHET_HEX)
        val pktType = bundle.getString(DaemonIpcContract.KEY_PACKET_TYPE) ?: return
        val pktId = bundle.getString(DaemonIpcContract.KEY_PACKET_ID) ?: CryptoEngine.randomHex(16)
        val bodyJson = bundle.getString(DaemonIpcContract.KEY_BODY_JSON) ?: "{}"

        val isEphemeralControl = pktType == PacketType.PRESENCE_STATE || pktType == PacketType.WEBRTC_SIGNAL
        val powTarget = if (isEphemeralControl) 6 else currentNetConfig.powDifficultyBits.coerceIn(6, 12)

        sealAndDispatchPacket(
            peerEd25519PubHex = peerHex,
            peerX25519PubHex = peerX25519,
            peerRoutingPubHex = peerRouting,
            peerRatchetPubHex = peerRatchet,
            packetType = pktType,
            packetId = pktId,
            bodyJson = bodyJson,
            powBits = powTarget
        )

        if (bundle.getBoolean("sync_vault_after", false)) {
            activeIdentity?.let { syncEncryptedFriendVault(it) }
        }
    }

    private suspend fun sealAndDispatchPacket(
        peerEd25519PubHex: String,
        peerX25519PubHex: String,
        peerRoutingPubHex: String,
        peerRatchetPubHex: String?,
        packetType: String,
        packetId: String,
        bodyJson: String,
        powBits: Int
    ) {
        val identity = activeIdentity ?: HardwareKeystoreManager.loadIdentitySession(applicationContext)
            ?.also { activeIdentity = it } ?: return

        val myAdvertisedRatchetPub = ensureFreshRatchetKey()

        val signedPacket = OpenSnapPacketCodec.buildAndSignPacket(
            identity = identity,
            packetType = packetType,
            bodyJson = bodyJson,
            packetId = packetId,
            nextRatchetX25519PubHex = myAdvertisedRatchetPub
        )

        val sealedInnerJson = OpenSnapPacketCodec.encryptSignedPacketForRecipient(
            packet = signedPacket,
            recipientX25519PubHex = peerX25519PubHex,
            recipientRatchetPubHex = peerRatchetPubHex
        )

        // Path A: Instant <30ms P2P WebRTC DataChannel Fast-Lane when open
        val sentViaP2p = webRtcFastLane.sendViaFastLaneIfOpen(peerEd25519PubHex, sealedInnerJson)

        // Path B: Store-and-Forward 24h Nostr NIP-59 Gift Wrap across WSS Relay Pool
        // (For pure typing indicators when P2P is already open, skip relay broadcast to save bandwidth)
        if (!sentViaP2p || packetType != PacketType.PRESENCE_STATE) {
            val giftWrap = NostrModels.buildGiftWrap24hEvent(
                recipientRoutingPubHex = peerRoutingPubHex,
                sealedInnerEnvelopeJson = sealedInnerJson,
                powBits = powBits
            )
            val sentRelays = relayPool.publishEventToMesh(giftWrap)
            if ((sentViaP2p || sentRelays > 0) &&
                (packetType == PacketType.CHAT_TEXT ||
                    packetType == PacketType.CHAT_VOICE ||
                    packetType == PacketType.CHAT_PHOTO)
            ) {
                val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
                val msg = dao.getMessageById(packetId)
                if (msg != null && msg.deliveryStatus == DeliveryState.SENDING) {
                    dao.updateMessageDeliveryStatus(packetId, DeliveryState.SENT)
                }
            }
        }
    }

    // =========================================================================
    // Sovereign Encrypted Friend-List Backup & Recovery (NIP-78)
    // =========================================================================

    private suspend fun syncEncryptedFriendVault(identity: CryptoEngine.SovereignIdentityBundle) {
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val all = dao.getAllFriendsSnapshot()
            .filter { it.status == FriendStatus.ACCEPTED || it.status == FriendStatus.BLOCKED }
            .map {
                VaultFriendEntry(
                    ed25519PubHex = it.ed25519PubHex,
                    x25519PubHex = it.x25519PubHex,
                    routingPubHex = it.routingPubHex,
                    openSnapTag = it.openSnapTag,
                    username = it.username,
                    displayName = it.displayName,
                    status = it.status,
                    isSafetyVerified = it.isSafetyVerified
                )
            }
        val cipherB64 = OpenSnapPacketCodec.sealFriendsBackup(identity, all)
        relayPool.publishEncryptedVaultBackup(identity, cipherB64)
    }

    private suspend fun restoreEncryptedFriendVault(identity: CryptoEngine.SovereignIdentityBundle): Int {
        val cipherB64 = relayPool.fetchEncryptedVaultBackup(identity) ?: return 0
        val backup = OpenSnapPacketCodec.openFriendsBackup(identity, cipherB64) ?: return 0
        val dao = OpenSnapDatabase.getInstance(applicationContext).dao()
        val nowMs = System.currentTimeMillis()
        var restored = 0
        backup.friends.forEach { entry ->
            if (CryptoEngine.verifySelfCertifyingTag(entry.ed25519PubHex, entry.openSnapTag)) {
                val existing = dao.getFriendByPubKey(entry.ed25519PubHex)
                if (existing == null) {
                    dao.upsertFriend(
                        FriendEntity(
                            ed25519PubHex = entry.ed25519PubHex,
                            x25519PubHex = entry.x25519PubHex,
                            routingPubHex = entry.routingPubHex,
                            openSnapTag = entry.openSnapTag,
                            username = entry.username,
                            displayName = entry.displayName,
                            status = entry.status,
                            isSafetyVerified = entry.isSafetyVerified,
                            lastSeenAtMs = nowMs,
                            lastMessagePreview = "Restauré depuis le coffre chiffré",
                            lastActivityAtMs = nowMs
                        )
                    )
                    restored++
                }
            }
        }
        return restored
    }

    // =========================================================================
    // IPC Handling & Adaptive Cellular / Wi-Fi Connectivity Monitoring
    // =========================================================================

    private fun handleUiIpcMessage(msg: Message) {
        val replyTarget = msg.replyTo
        val data = msg.data ?: Bundle.EMPTY

        when (msg.what) {
            DaemonIpcContract.MSG_REGISTER_UI_CLIENT -> {
                if (replyTarget != null) {
                    uiClients.add(replyTarget)
                    broadcastStatusToUi()
                }
            }

            DaemonIpcContract.MSG_UNREGISTER_UI_CLIENT -> {
                if (replyTarget != null) {
                    uiClients.remove(replyTarget)
                }
                if (uiClients.isEmpty()) {
                    activeVisibleChatPeerHex = null
                }
            }

            DaemonIpcContract.MSG_SET_ACTIVE_CHAT_PEER -> {
                val peerHex = data.getString(DaemonIpcContract.KEY_PEER_PUB_HEX)
                activeVisibleChatPeerHex = peerHex
                if (!peerHex.isNullOrBlank()) {
                    NotificationHelper.clearPeerNotification(applicationContext, peerHex)
                }
            }

            DaemonIpcContract.MSG_DISPATCH_OUTBOUND_PACKET -> {
                val copy = Bundle(data)
                daemonScope.launch {
                    dispatchPacketFromBundle(copy)
                }
            }

            DaemonIpcContract.MSG_RELOAD_IDENTITY -> {
                reloadIdentityAndOpenMesh()
            }

            DaemonIpcContract.MSG_LOOKUP_SHORT_TAG -> {
                val reqId = data.getString(DaemonIpcContract.KEY_REQUEST_ID) ?: return
                val tag = data.getString(DaemonIpcContract.KEY_SHORT_TAG) ?: return
                daemonScope.launch {
                    val found = relayPool.lookupContactByShortTag(tag)
                    val outBundle = Bundle().apply {
                        putString(DaemonIpcContract.KEY_REQUEST_ID, reqId)
                        putString(
                            DaemonIpcContract.KEY_CONTACT_CARD_JSON,
                            found?.let { CryptoEngine.canonicalJson.encodeToString(it) }.orEmpty()
                        )
                    }
                    replyTarget?.let { sendSafeIpc(it, DaemonIpcContract.EVT_TAG_LOOKUP_RESULT, outBundle) }
                }
            }

            DaemonIpcContract.MSG_SYNC_VAULT_BACKUP -> {
                daemonScope.launch {
                    activeIdentity?.let {
                        relayPool.publishPublicTagCard(it)
                        syncEncryptedFriendVault(it)
                    }
                }
            }

            DaemonIpcContract.MSG_RESTORE_VAULT_BACKUP -> {
                daemonScope.launch {
                    val id = activeIdentity ?: HardwareKeystoreManager.loadIdentitySession(applicationContext)
                    if (id != null) {
                        val count = restoreEncryptedFriendVault(id)
                        broadcastEventToUi(
                            DaemonIpcContract.EVT_VAULT_RESTORED,
                            Bundle().apply { putInt(DaemonIpcContract.KEY_RESTORED_COUNT, count) }
                        )
                    }
                }
            }

            DaemonIpcContract.MSG_FORCE_RECONNECT -> {
                relayPool.reconnectAllNow()
            }
        }
    }

    private fun broadcastStatusToUi() {
        val fastPeersCsv = webRtcFastLane.activeFastLanePeers.value.joinToString(",")
        val bundle = Bundle().apply {
            putInt(DaemonIpcContract.KEY_CONNECTED_RELAYS, connectedRelayCount)
            putInt(DaemonIpcContract.KEY_TOTAL_RELAYS, totalRelayCount)
            putString(DaemonIpcContract.KEY_TRANSPORT_LABEL, currentTransportLabel)
            putString(DaemonIpcContract.KEY_FASTLANE_PEERS_CSV, fastPeersCsv)
        }
        broadcastEventToUi(DaemonIpcContract.EVT_DAEMON_STATUS, bundle)
    }

    private fun broadcastEventToUi(what: Int, bundle: Bundle) {
        val dead = mutableListOf<Messenger>()
        uiClients.forEach { client ->
            if (!sendSafeIpc(client, what, bundle)) {
                dead.add(client)
            }
        }
        if (dead.isNotEmpty()) {
            uiClients.removeAll(dead.toSet())
        }
    }

    private fun sendSafeIpc(target: Messenger, what: Int, bundle: Bundle): Boolean {
        return try {
            val m = Message.obtain(null, what).apply { data = bundle }
            target.send(m)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun registerAdaptiveNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val transport = detectCurrentTransport()
                relayPool.updateNetworkTransport(transport)
                refreshForegroundDaemonNotification()
                broadcastStatusToUi()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val transport = classifyCapabilities(networkCapabilities)
                relayPool.updateNetworkTransport(transport)
                refreshForegroundDaemonNotification()
                broadcastStatusToUi()
            }

            override fun onLost(network: Network) {
                val transport = detectCurrentTransport()
                relayPool.updateNetworkTransport(transport)
                refreshForegroundDaemonNotification()
                broadcastStatusToUi()
            }
        }
        networkCallback = callback
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, callback)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun detectCurrentTransport(): NostrRelayPool.TransportKind {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork ?: run {
            currentTransportLabel = "Hors-ligne"
            return NostrRelayPool.TransportKind.OFFLINE
        }
        val caps = cm.getNetworkCapabilities(activeNet) ?: run {
            currentTransportLabel = "Hors-ligne"
            return NostrRelayPool.TransportKind.OFFLINE
        }
        return classifyCapabilities(caps)
    }

    private fun classifyCapabilities(caps: NetworkCapabilities): NostrRelayPool.TransportKind {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            currentTransportLabel = "Hors-ligne"
            return NostrRelayPool.TransportKind.OFFLINE
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                currentTransportLabel = "Wi-Fi Ping 45s"
                NostrRelayPool.TransportKind.WIFI_OR_ETHERNET
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                currentTransportLabel = "4G/5G Ping 25s"
                NostrRelayPool.TransportKind.CELLULAR_4G_5G
            }

            else -> {
                currentTransportLabel = "Tunnel Actif"
                NostrRelayPool.TransportKind.CELLULAR_4G_5G
            }
        }
    }
}
