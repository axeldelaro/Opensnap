package io.github.axeldelaro.opensnap.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.axeldelaro.opensnap.CallActivity
import io.github.axeldelaro.opensnap.crypto.ApkIntegrityGuard
import io.github.axeldelaro.opensnap.crypto.Bip39Mnemonic
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.ExifSanitizer
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import io.github.axeldelaro.opensnap.daemon.DaemonIpcClient
import io.github.axeldelaro.opensnap.daemon.DaemonIpcContract
import io.github.axeldelaro.opensnap.daemon.NotificationHelper
import io.github.axeldelaro.opensnap.data.db.DeliveryState
import io.github.axeldelaro.opensnap.data.db.FriendEntity
import io.github.axeldelaro.opensnap.data.db.FriendStatus
import io.github.axeldelaro.opensnap.data.db.MessageContentType
import io.github.axeldelaro.opensnap.data.db.MessageEntity
import io.github.axeldelaro.opensnap.data.db.OpenSnapDatabase
import io.github.axeldelaro.opensnap.data.media.EncryptedMediaVault
import io.github.axeldelaro.opensnap.data.ttl.PanicWipeManager
import io.github.axeldelaro.opensnap.data.ttl.TtlCleanupWorker
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import io.github.axeldelaro.opensnap.network.protocol.ChatPhotoPayload
import io.github.axeldelaro.opensnap.network.protocol.ChatTextPayload
import io.github.axeldelaro.opensnap.network.protocol.ChatVoicePayload
import io.github.axeldelaro.opensnap.network.protocol.PacketType
import io.github.axeldelaro.opensnap.network.protocol.PresenceKind
import io.github.axeldelaro.opensnap.network.protocol.PresencePayload
import io.github.axeldelaro.opensnap.network.protocol.ReactionPayload
import io.github.axeldelaro.opensnap.network.protocol.ReceiptPayload
import io.github.axeldelaro.opensnap.network.updater.GitHubUpdater
import io.github.axeldelaro.opensnap.ui.components.CompletedVoiceRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

enum class AppStage {
    AUTH_SETUP,
    LOCKED,
    UNLOCKED
}

data class UpdaterUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadPercent: Int = 0,
    val statusMessage: String = "",
    val lastResult: GitHubUpdater.UpdateCheckResult? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class OpenSnapViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val daemonClient = DaemonIpcClient(appContext)

    private val _appStage = MutableStateFlow(
        if (HardwareKeystoreManager.hasActiveAccount(appContext)) {
            if (HardwareKeystoreManager.loadLockPolicy(appContext) != null) {
                AppStage.LOCKED
            } else {
                AppStage.UNLOCKED
            }
        } else {
            AppStage.AUTH_SETUP
        }
    )
    val appStage: StateFlow<AppStage> = _appStage.asStateFlow()

    private val _identity = MutableStateFlow<CryptoEngine.SovereignIdentityBundle?>(null)
    val identity: StateFlow<CryptoEngine.SovereignIdentityBundle?> = _identity.asStateFlow()

    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy.asStateFlow()

    private val _toastBanner = MutableStateFlow<String?>(null)
    val toastBanner: StateFlow<String?> = _toastBanner.asStateFlow()

    private val _activeChatPeerHex = MutableStateFlow<String?>(null)
    val activeChatPeerHex: StateFlow<String?> = _activeChatPeerHex.asStateFlow()

    private val _nowClockMs = MutableStateFlow(System.currentTimeMillis())
    val nowClockMs: StateFlow<Long> = _nowClockMs.asStateFlow()

    private val _updaterState = MutableStateFlow(UpdaterUiState())
    val updaterState: StateFlow<UpdaterUiState> = _updaterState.asStateFlow()

    private val _integrityReport = MutableStateFlow(ApkIntegrityGuard.inspect(appContext))
    val integrityReport: StateFlow<ApkIntegrityGuard.IntegrityReport> = _integrityReport.asStateFlow()

    private var lastTypingSentAtMs = 0L

    // Reactive Database Flows (active once unlocked)
    val acceptedFriends: StateFlow<List<FriendEntity>> = _appStage
        .flatMapLatest { stage ->
            if (stage == AppStage.UNLOCKED) {
                OpenSnapDatabase.getInstance(appContext).dao().observeAcceptedFriends()
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomingRequests: StateFlow<List<FriendEntity>> = _appStage
        .flatMapLatest { stage ->
            if (stage == AppStage.UNLOCKED) {
                OpenSnapDatabase.getInstance(appContext).dao().observeIncomingRequests()
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outgoingRequests: StateFlow<List<FriendEntity>> = _appStage
        .flatMapLatest { stage ->
            if (stage == AppStage.UNLOCKED) {
                OpenSnapDatabase.getInstance(appContext).dao().observeOutgoingRequests()
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blockedFriends: StateFlow<List<FriendEntity>> = _appStage
        .flatMapLatest { stage ->
            if (stage == AppStage.UNLOCKED) {
                OpenSnapDatabase.getInstance(appContext).dao().observeBlockedFriends()
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (_appStage.value == AppStage.UNLOCKED) {
            completeUnlockInitialization()
        } else if (_appStage.value == AppStage.LOCKED) {
            // Ensure background push daemon keeps running even while UI is locked!
            daemonClient.startAndBindDaemon()
        }

        // Real-time 1s clock ticker for live 24h message countdowns & foreground TTL purge
        viewModelScope.launch {
            var seconds = 0
            while (isActive) {
                _nowClockMs.value = System.currentTimeMillis()
                if (seconds % 20 == 0 && _appStage.value == AppStage.UNLOCKED) {
                    withContext(Dispatchers.IO) {
                        TtlPurgeEngine.purgeExpiredContent(appContext)
                    }
                }
                seconds++
                delay(1_000L)
            }
        }

        viewModelScope.launch {
            daemonClient.vaultRestoredEvents.collect { count ->
                if (count > 0) {
                    postBanner("✅ $count ami(s) restauré(s) depuis votre coffre chiffré !")
                }
            }
        }

        // Launch incoming call screen if UI is already foregrounded when OFFER signal arrives
        viewModelScope.launch {
            daemonClient.callSignals.collect { evt ->
                if (evt.signal.signalType == "OFFER") {
                    val friend = withContext(Dispatchers.IO) {
                        OpenSnapDatabase.getInstance(appContext).dao().getFriendByPubKey(evt.peerEd25519PubHex)
                    } ?: return@collect
                    val callIntent = Intent(appContext, CallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, friend.ed25519PubHex)
                        putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, friend.x25519PubHex)
                        putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, friend.routingPubHex)
                        putExtra(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, friend.displayName)
                        putExtra(
                            DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON,
                            CryptoEngine.canonicalJson.encodeToString(evt.signal)
                        )
                    }
                    appContext.startActivity(callIntent)
                }
            }
        }
    }

    fun observeConversationMessages(peerEd25519PubHex: String): Flow<List<MessageEntity>> {
        return OpenSnapDatabase.getInstance(appContext).dao()
            .observeActiveMessages(peerEd25519PubHex, System.currentTimeMillis() - 1_000L)
    }

    fun observeFriend(peerEd25519PubHex: String): Flow<FriendEntity?> {
        return OpenSnapDatabase.getInstance(appContext).dao().observeFriendByPubKey(peerEd25519PubHex)
    }

    fun postBanner(msg: String?) {
        _toastBanner.value = msg
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    // =========================================================================
    // Sovereign Account Creation / Login / BIP-39 Recovery & Lock Screen
    // =========================================================================

    fun generateFreshBip39Words(): List<String> = Bip39Mnemonic.generate12Words(appContext)

    fun registerOrRecoverAccount(
        username: String,
        displayName: String,
        password: String,
        mnemonic12Words: String?,
        primaryPin: String,
        duressPin: String?,
        biometricEnabled: Boolean,
        isRecoveryMode: Boolean
    ) {
        viewModelScope.launch {
            _authBusy.value = true
            try {
                if (!mnemonic12Words.isNullOrBlank() && !Bip39Mnemonic.validateMnemonic(mnemonic12Words, appContext)) {
                    postBanner("Phrase secrète BIP-39 invalide (vérifiez les 12 mots)")
                    return@launch
                }
                if (primaryPin.trim().length < 4) {
                    postBanner("Choisissez un code PIN d'au moins 4 chiffres")
                    return@launch
                }
                if (!duressPin.isNullOrBlank() && duressPin.trim() == primaryPin.trim()) {
                    postBanner("Le code PIN Leurre (Panic Wipe) doit être différent du PIN principal")
                    return@launch
                }

                val derived = withContext(Dispatchers.Default) {
                    CryptoEngine.deriveSovereignIdentity(
                        username = username,
                        displayName = displayName,
                        passwordOrEmpty = password,
                        mnemonic12Words = mnemonic12Words
                    )
                }

                withContext(Dispatchers.IO) {
                    HardwareKeystoreManager.saveIdentitySession(appContext, derived)
                    HardwareKeystoreManager.saveLockPolicy(
                        context = appContext,
                        primaryPin = primaryPin,
                        duressPin = duressPin,
                        biometricEnabled = biometricEnabled
                    )
                    // Initialize SQLCipher DB eagerly
                    OpenSnapDatabase.getInstance(appContext)
                }

                _identity.value = derived
                _appStage.value = AppStage.UNLOCKED
                completeUnlockInitialization()

                if (isRecoveryMode) {
                    delay(1_800L)
                    daemonClient.triggerVaultRestoreFromRelays()
                    postBanner("Clés Ed25519/X25519 restaurées • Recherche de votre liste d'amis chiffrée...")
                } else {
                    postBanner("Compte souverain actif : ${derived.openSnapTag}")
                }
            } catch (e: Throwable) {
                postBanner(e.message ?: "Erreur lors de la dérivation Argon2id")
            } finally {
                _authBusy.value = false
            }
        }
    }

    fun submitUnlockPin(enteredPin: String): Boolean {
        return when (HardwareKeystoreManager.verifyPin(appContext, enteredPin)) {
            HardwareKeystoreManager.PinCheckResult.VALID_PRIMARY_PIN -> {
                onBiometricOrPinSucceeded()
                true
            }

            HardwareKeystoreManager.PinCheckResult.DURESS_PANIC_PIN -> {
                triggerEmergencyPanicWipe()
                true
            }

            HardwareKeystoreManager.PinCheckResult.INVALID_PIN -> {
                postBanner("Code PIN incorrect")
                false
            }
        }
    }

    fun onBiometricOrPinSucceeded() {
        _appStage.value = AppStage.UNLOCKED
        completeUnlockInitialization()
    }

    private fun completeUnlockInitialization() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val id = HardwareKeystoreManager.loadIdentitySession(appContext)
                    runCatching { TtlPurgeEngine.purgeExpiredContent(appContext) }
                    id
                }
                if (loaded == null) {
                    _appStage.value = AppStage.AUTH_SETUP
                    return@launch
                }
                _identity.value = loaded
                _integrityReport.value = ApkIntegrityGuard.inspect(appContext)
                runCatching { TtlCleanupWorker.schedule(appContext) }
                daemonClient.startAndBindDaemon()
                daemonClient.notifyIdentityReloaded()
            } catch (t: Throwable) {
                postBanner("Erreur d'initialisation : ${t.message}")
            }
        }
    }

    fun lockAppNow() {
        _activeChatPeerHex.value = null
        daemonClient.setActiveVisibleChatPeer(null)
        if (HardwareKeystoreManager.loadLockPolicy(appContext) != null) {
            _appStage.value = AppStage.LOCKED
        }
    }

    fun triggerEmergencyPanicWipe() {
        viewModelScope.launch(Dispatchers.IO) {
            daemonClient.unbind()
            PanicWipeManager.executeEmergencyWipe(appContext, notifyDaemon = true)
            withContext(Dispatchers.Main) {
                _identity.value = null
                _activeChatPeerHex.value = null
                _appStage.value = AppStage.AUTH_SETUP
                postBanner("Coffre cryptographique et clés détruits définitivement.")
            }
        }
    }

    // =========================================================================
    // Friends Management, QR Scan & Decentralized Tag Resolution
    // =========================================================================

    fun sendFriendRequestByInviteOrTag(rawInput: String, onFinished: () -> Unit = {}) {
        val currentIdentity = _identity.value ?: return
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            _authBusy.value = true
            try {
                val resolvedCard: CryptoEngine.PublicContactCard? = if (
                    trimmed.startsWith("opensnap://add/", ignoreCase = true) || trimmed.length > 40
                ) {
                    CryptoEngine.decodeContactInviteUri(trimmed)
                } else {
                    val normalizedTag = if (trimmed.uppercase().startsWith("OS-")) {
                        trimmed.uppercase()
                    } else {
                        "OS-${trimmed.uppercase()}"
                    }
                    daemonClient.lookupFriendByShortTag(normalizedTag)
                }

                if (resolvedCard == null) {
                    postBanner("Contact introuvable ou signature invalide. Vérifiez le Tag ou scannez le QR Code.")
                    return@launch
                }

                if (resolvedCard.ed25519PubHex.equals(currentIdentity.ed25519PublicHex, ignoreCase = true)) {
                    postBanner("C'est votre propre Tag OpenSnap !")
                    return@launch
                }

                val nowMs = System.currentTimeMillis()
                val dao = OpenSnapDatabase.getInstance(appContext).dao()
                val existing = dao.getFriendByPubKey(resolvedCard.ed25519PubHex)

                val newStatus = if (existing?.status == FriendStatus.ACCEPTED) {
                    FriendStatus.ACCEPTED
                } else if (existing?.status == FriendStatus.PENDING_INCOMING) {
                    FriendStatus.ACCEPTED
                } else {
                    FriendStatus.PENDING_OUTGOING
                }

                val entity = FriendEntity(
                    ed25519PubHex = resolvedCard.ed25519PubHex,
                    x25519PubHex = resolvedCard.x25519PubHex,
                    routingPubHex = resolvedCard.routingPubHex,
                    openSnapTag = resolvedCard.openSnapTag,
                    username = resolvedCard.username,
                    displayName = resolvedCard.displayName,
                    status = newStatus,
                    lastSeenAtMs = nowMs,
                    lastMessagePreview = if (newStatus == FriendStatus.ACCEPTED) {
                        "Ami connecté • Tunnel E2EE actif"
                    } else {
                        "Demande d'ami chiffrée envoyée..."
                    },
                    lastActivityAtMs = nowMs
                )
                withContext(Dispatchers.IO) {
                    dao.upsertFriend(entity)
                }

                val packetType = if (newStatus == FriendStatus.ACCEPTED) {
                    PacketType.FRIEND_ACCEPT
                } else {
                    PacketType.FRIEND_REQUEST
                }

                daemonClient.dispatchOutboundPacket(
                    peerEd25519PubHex = entity.ed25519PubHex,
                    peerX25519PubHex = entity.x25519PubHex,
                    peerRoutingPubHex = entity.routingPubHex,
                    peerRatchetPubHex = entity.peerRatchetX25519PubHex,
                    packetType = packetType,
                    bodyJson = "{}"
                )
                if (newStatus == FriendStatus.ACCEPTED) {
                    daemonClient.triggerVaultBackupSync()
                }
                postBanner("Demande envoyée à ${entity.displayName} (${entity.openSnapTag})")
                onFinished()
            } finally {
                _authBusy.value = false
            }
        }
    }

    fun acceptFriendRequest(friend: FriendEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            dao.updateFriendStatus(friend.ed25519PubHex, FriendStatus.ACCEPTED, nowMs)
            dao.clearFriendUnreadCount(friend.ed25519PubHex)
            dao.updateFriendConversationSummary(
                ed25519PubHex = friend.ed25519PubHex,
                preview = "Ami accepté • Discussion 24h ouverte",
                activityAtMs = nowMs,
                unreadDelta = 0
            )
            NotificationHelper.clearFriendRequestNotification(appContext, friend.ed25519PubHex)

            daemonClient.dispatchOutboundPacket(
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                packetType = PacketType.FRIEND_ACCEPT,
                bodyJson = "{}"
            )
            daemonClient.triggerVaultBackupSync()
        }
    }

    fun rejectOrRemoveFriend(friend: FriendEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            dao.getMessagesForPeer(friend.ed25519PubHex).forEach {
                EncryptedMediaVault.shredMediaFile(it.encryptedMediaPath)
            }
            dao.deleteAllMessagesForPeer(friend.ed25519PubHex)
            dao.deleteFriend(friend.ed25519PubHex)
            NotificationHelper.clearFriendRequestNotification(appContext, friend.ed25519PubHex)
            NotificationHelper.clearPeerNotification(appContext, friend.ed25519PubHex)
            daemonClient.triggerVaultBackupSync()
        }
    }

    fun toggleBlockFriend(friend: FriendEntity, blocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            val targetStatus = if (blocked) FriendStatus.BLOCKED else FriendStatus.ACCEPTED
            dao.updateFriendStatus(friend.ed25519PubHex, targetStatus, System.currentTimeMillis())
            daemonClient.triggerVaultBackupSync()
        }
    }

    fun toggleSafetyVerified(friend: FriendEntity, verified: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            OpenSnapDatabase.getInstance(appContext).dao()
                .updateFriendSafetyVerified(friend.ed25519PubHex, verified)
            daemonClient.triggerVaultBackupSync()
        }
    }

    // =========================================================================
    // 24-Hour Rich Chat, Typing Indicators, Voice Notes, Photo Snaps & Calls
    // =========================================================================

    fun enterConversation(friend: FriendEntity) {
        _activeChatPeerHex.value = friend.ed25519PubHex
        daemonClient.setActiveVisibleChatPeer(friend.ed25519PubHex)
        NotificationHelper.clearPeerNotification(appContext, friend.ed25519PubHex)

        viewModelScope.launch(Dispatchers.IO) {
            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            val nowMs = System.currentTimeMillis()
            val unread = dao.getUnreadIncomingMessages(friend.ed25519PubHex, nowMs)
            unread.forEach { dao.updateMessageDeliveryStatus(it.messageId, DeliveryState.OPENED) }
            dao.clearFriendUnreadCount(friend.ed25519PubHex)

            if (unread.isNotEmpty()) {
                val receiptJson = CryptoEngine.canonicalJson.encodeToString(
                    ReceiptPayload(
                        messageIds = unread.map { it.messageId },
                        status = DeliveryState.OPENED
                    )
                )
                daemonClient.dispatchOutboundPacket(
                    peerEd25519PubHex = friend.ed25519PubHex,
                    peerX25519PubHex = friend.x25519PubHex,
                    peerRoutingPubHex = friend.routingPubHex,
                    peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                    packetType = PacketType.RECEIPT,
                    bodyJson = receiptJson
                )
            }

            sendPresenceSignal(friend, PresenceKind.ONLINE)
        }
    }

    fun leaveConversation(friend: FriendEntity?) {
        _activeChatPeerHex.value = null
        daemonClient.setActiveVisibleChatPeer(null)
        if (friend != null) {
            sendPresenceSignal(friend, PresenceKind.IDLE)
        }
    }

    fun onDraftTextChanged(friend: FriendEntity, draft: String) {
        val now = System.currentTimeMillis()
        if (draft.isNotBlank() && now - lastTypingSentAtMs > 2_500L) {
            lastTypingSentAtMs = now
            sendPresenceSignal(friend, PresenceKind.TYPING)
        } else if (draft.isEmpty()) {
            sendPresenceSignal(friend, PresenceKind.ONLINE)
        }
    }

    fun onVoiceRecordingStateChanged(friend: FriendEntity, isRecording: Boolean) {
        sendPresenceSignal(
            friend,
            if (isRecording) PresenceKind.RECORDING_VOICE else PresenceKind.ONLINE
        )
    }

    private fun sendPresenceSignal(friend: FriendEntity, state: String) {
        val json = CryptoEngine.canonicalJson.encodeToString(PresencePayload(state))
        daemonClient.dispatchOutboundPacket(
            peerEd25519PubHex = friend.ed25519PubHex,
            peerX25519PubHex = friend.x25519PubHex,
            peerRoutingPubHex = friend.routingPubHex,
            peerRatchetPubHex = friend.peerRatchetX25519PubHex,
            packetType = PacketType.PRESENCE_STATE,
            bodyJson = json
        )
    }

    fun sendTextMessage(
        friend: FriendEntity,
        rawText: String,
        replyTarget: MessageEntity? = null
    ) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val msgId = CryptoEngine.randomHex(16)
            val replyPreview = replyTarget?.let {
                when (it.contentType) {
                    MessageContentType.VOICE -> "🎤 Message vocal"
                    MessageContentType.PHOTO -> "📸 Snap photo"
                    else -> it.textContent.take(80)
                }
            }

            val entity = MessageEntity(
                messageId = msgId,
                peerEd25519PubHex = friend.ed25519PubHex,
                isOutgoing = true,
                contentType = MessageContentType.TEXT,
                textContent = text,
                replyToMessageId = replyTarget?.messageId,
                replyPreviewText = replyPreview,
                deliveryStatus = DeliveryState.SENDING,
                sentAtMs = nowMs,
                expiresAtMs = nowMs + TtlPurgeEngine.TTL_24H_MS
            )

            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            dao.insertMessage(entity)
            dao.updateFriendConversationSummary(
                ed25519PubHex = friend.ed25519PubHex,
                preview = "Vous : $text",
                activityAtMs = nowMs,
                unreadDelta = 0
            )

            val payloadJson = CryptoEngine.canonicalJson.encodeToString(
                ChatTextPayload(
                    text = text,
                    replyToMessageId = replyTarget?.messageId,
                    replyPreviewText = replyPreview
                )
            )
            daemonClient.dispatchOutboundPacket(
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                packetType = PacketType.CHAT_TEXT,
                bodyJson = payloadJson,
                packetId = msgId
            )
        }
    }

    fun sendVoiceMessage(
        friend: FriendEntity,
        recording: CompletedVoiceRecording,
        replyTarget: MessageEntity? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val msgId = CryptoEngine.randomHex(16)
            val stored = EncryptedMediaVault.storeEncryptedMedia(
                context = appContext,
                messageId = msgId,
                plaintextBytes = recording.encodedBytes
            )
            val audioB64 = Base64.encodeToString(recording.encodedBytes, Base64.NO_WRAP)
            recording.encodedBytes.fill(0)

            val secs = (recording.durationMs / 1000L).coerceAtLeast(1L)
            val preview = "🎤 Vocal (${secs}s)"
            val replyPreview = replyTarget?.textContent?.take(70)

            val entity = MessageEntity(
                messageId = msgId,
                peerEd25519PubHex = friend.ed25519PubHex,
                isOutgoing = true,
                contentType = MessageContentType.VOICE,
                textContent = preview,
                encryptedMediaPath = stored.absolutePath,
                mediaKeyHex = stored.mediaKeyHex,
                voiceDurationMs = recording.durationMs,
                waveformCsv = recording.waveformCsv,
                replyToMessageId = replyTarget?.messageId,
                replyPreviewText = replyPreview,
                deliveryStatus = DeliveryState.SENDING,
                sentAtMs = nowMs,
                expiresAtMs = nowMs + TtlPurgeEngine.TTL_24H_MS
            )

            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            dao.insertMessage(entity)
            dao.updateFriendConversationSummary(
                ed25519PubHex = friend.ed25519PubHex,
                preview = "Vous : $preview",
                activityAtMs = nowMs,
                unreadDelta = 0
            )

            val payloadJson = CryptoEngine.canonicalJson.encodeToString(
                ChatVoicePayload(
                    audioBase64 = audioB64,
                    durationMs = recording.durationMs,
                    waveformCsv = recording.waveformCsv,
                    replyToMessageId = replyTarget?.messageId,
                    replyPreviewText = replyPreview
                )
            )
            daemonClient.dispatchOutboundPacket(
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                packetType = PacketType.CHAT_VOICE,
                bodyJson = payloadJson,
                packetId = msgId
            )
        }
    }

    fun sendPhotoSnap(
        friend: FriendEntity,
        rawCapturedBytes: ByteArray,
        caption: String,
        isViewOnce: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Strip 100% of EXIF & GPS metadata and compress to clean WebP
                val sanitized = ExifSanitizer.sanitizeAndCompressImage(rawCapturedBytes)
                rawCapturedBytes.fill(0)

                val nowMs = System.currentTimeMillis()
                val msgId = CryptoEngine.randomHex(16)
                val stored = EncryptedMediaVault.storeEncryptedMedia(
                    context = appContext,
                    messageId = msgId,
                    plaintextBytes = sanitized.cleanWebpBytes
                )
                val webpB64 = Base64.encodeToString(sanitized.cleanWebpBytes, Base64.NO_WRAP)
                sanitized.cleanWebpBytes.fill(0)

                val preview = if (isViewOnce) "🔥 Snap envoyé (Vue unique)" else "📸 Snap Photo 24h"
                val entity = MessageEntity(
                    messageId = msgId,
                    peerEd25519PubHex = friend.ed25519PubHex,
                    isOutgoing = true,
                    contentType = MessageContentType.PHOTO,
                    textContent = caption.trim(),
                    encryptedMediaPath = stored.absolutePath,
                    mediaKeyHex = stored.mediaKeyHex,
                    imageWidth = sanitized.width,
                    imageHeight = sanitized.height,
                    isViewOnce = isViewOnce,
                    isViewOnceConsumed = false,
                    deliveryStatus = DeliveryState.SENDING,
                    sentAtMs = nowMs,
                    expiresAtMs = nowMs + TtlPurgeEngine.TTL_24H_MS
                )

                val dao = OpenSnapDatabase.getInstance(appContext).dao()
                dao.insertMessage(entity)
                dao.updateFriendConversationSummary(
                    ed25519PubHex = friend.ed25519PubHex,
                    preview = preview,
                    activityAtMs = nowMs,
                    unreadDelta = 0
                )

                val payloadJson = CryptoEngine.canonicalJson.encodeToString(
                    ChatPhotoPayload(
                        webpBase64 = webpB64,
                        width = sanitized.width,
                        height = sanitized.height,
                        caption = caption.trim(),
                        isViewOnce = isViewOnce
                    )
                )
                daemonClient.dispatchOutboundPacket(
                    peerEd25519PubHex = friend.ed25519PubHex,
                    peerX25519PubHex = friend.x25519PubHex,
                    peerRoutingPubHex = friend.routingPubHex,
                    peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                    packetType = PacketType.CHAT_PHOTO,
                    bodyJson = payloadJson,
                    packetId = msgId
                )
            } catch (e: Exception) {
                postBanner("Erreur d'envoi du Snap : ${e.message}")
            }
        }
    }

    fun sendEmojiReaction(friend: FriendEntity, targetMessage: MessageEntity, emoji: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = OpenSnapDatabase.getInstance(appContext).dao()
            val existingObj: JsonObject = runCatching {
                CryptoEngine.canonicalJson.parseToJsonElement(targetMessage.reactionsJson).jsonObject
            }.getOrDefault(buildJsonObject {})

            val updatedObj = buildJsonObject {
                existingObj.forEach { (k, v) -> put(k, v) }
                put("Moi", JsonPrimitive(emoji))
            }
            dao.updateMessageReactions(
                targetMessage.messageId,
                CryptoEngine.canonicalJson.encodeToString(JsonObject.serializer(), updatedObj)
            )

            val payloadJson = CryptoEngine.canonicalJson.encodeToString(
                ReactionPayload(targetMessageId = targetMessage.messageId, emoji = emoji)
            )
            daemonClient.dispatchOutboundPacket(
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                packetType = PacketType.REACTION,
                bodyJson = payloadJson
            )
        }
    }

    fun consumeViewOnceSnap(friend: FriendEntity, message: MessageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            TtlPurgeEngine.consumeAndShredViewOnceSnap(appContext, message.messageId)
            val receiptJson = CryptoEngine.canonicalJson.encodeToString(
                ReceiptPayload(
                    messageIds = listOf(message.messageId),
                    status = DeliveryState.OPENED
                )
            )
            daemonClient.dispatchOutboundPacket(
                peerEd25519PubHex = friend.ed25519PubHex,
                peerX25519PubHex = friend.x25519PubHex,
                peerRoutingPubHex = friend.routingPubHex,
                peerRatchetPubHex = friend.peerRatchetX25519PubHex,
                packetType = PacketType.RECEIPT,
                bodyJson = receiptJson
            )
        }
    }

    fun startP2pCall(context: Context, friend: FriendEntity, isVideoCall: Boolean) {
        val intent = Intent(context, CallActivity::class.java).apply {
            putExtra(DaemonIpcContract.KEY_PEER_PUB_HEX, friend.ed25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_X25519_HEX, friend.x25519PubHex)
            putExtra(DaemonIpcContract.KEY_PEER_ROUTING_HEX, friend.routingPubHex)
            putExtra(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, friend.displayName)
            putExtra(CallActivity.EXTRA_OUTGOING_CALL, true)
            putExtra(CallActivity.EXTRA_IS_VIDEO_CALL, isVideoCall)
        }
        context.startActivity(intent)
    }

    // =========================================================================
    // GitHub Releases Auto-Updater
    // =========================================================================

    fun checkForGitHubUpdates() {
        viewModelScope.launch {
            _updaterState.value = _updaterState.value.copy(
                isChecking = true,
                statusMessage = "Interrogation de GitHub Releases (axeldelaro/Opensnap)..."
            )
            val res = GitHubUpdater.checkForUpdates()
            res.fold(
                onSuccess = { info ->
                    val msg = if (info.isUpdateAvailable) {
                        "Nouvelle version v${info.latestVersion} disponible !"
                    } else {
                        "Vous utilisez déjà la dernière version (v${info.currentVersion})."
                    }
                    _updaterState.value = UpdaterUiState(
                        isChecking = false,
                        statusMessage = msg,
                        lastResult = info
                    )
                },
                onFailure = { err ->
                    _updaterState.value = UpdaterUiState(
                        isChecking = false,
                        statusMessage = "Vérification impossible : ${err.message}"
                    )
                }
            )
        }
    }

    fun downloadAndInstallLatestApk(context: Context) {
        val url = _updaterState.value.lastResult?.apkDownloadUrl ?: return
        viewModelScope.launch {
            _updaterState.value = _updaterState.value.copy(
                isDownloading = true,
                downloadPercent = 0,
                statusMessage = "Téléchargement de OpenSnap-latest.apk..."
            )
            val dl = GitHubUpdater.downloadApkToCache(appContext, url) { pct ->
                _updaterState.value = _updaterState.value.copy(downloadPercent = pct)
            }
            dl.fold(
                onSuccess = { apkFile ->
                    _updaterState.value = _updaterState.value.copy(
                        isDownloading = false,
                        downloadPercent = 100,
                        statusMessage = "APK téléchargé • Lancement de l'installateur Android..."
                    )
                    GitHubUpdater.promptInstallApk(context, apkFile)
                },
                onFailure = { err ->
                    _updaterState.value = _updaterState.value.copy(
                        isDownloading = false,
                        statusMessage = "Échec du téléchargement : ${err.message}"
                    )
                }
            )
        }
    }

    override fun onCleared() {
        daemonClient.unbind()
        super.onCleared()
    }
}
