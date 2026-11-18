package io.github.axeldelaro.opensnap.daemon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.network.protocol.WebRtcSignalPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Reactive IPC Client connecting the UI Process (`MainActivity` / `CallActivity` / `OpenSnapViewModel`)
 * to the dedicated background `:network_daemon` process (`OpenSnapDaemonService`).
 */
class DaemonIpcClient(context: Context) {

    data class DaemonLiveStatus(
        val isBound: Boolean = false,
        val connectedRelays: Int = 0,
        val totalRelays: Int = 5,
        val transportLabel: String = "Initialisation...",
        val fastLanePeerHexes: Set<String> = emptySet()
    )

    data class PeerPresenceEvent(
        val peerEd25519PubHex: String,
        val presenceState: String,
        val isP2pDirect: Boolean,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    data class IncomingCallSignalEvent(
        val peerEd25519PubHex: String,
        val peerDisplayName: String,
        val signal: WebRtcSignalPayload
    )

    private val appContext = context.applicationContext
    private var serviceMessenger: Messenger? = null
    private var isBound = false

    private val pendingTagLookups = ConcurrentHashMap<String, CompletableDeferred<CryptoEngine.PublicContactCard?>>()

    private val _daemonStatus = MutableStateFlow(DaemonLiveStatus())
    val daemonStatus: StateFlow<DaemonLiveStatus> = _daemonStatus.asStateFlow()

    private val _peerPresenceMap = MutableStateFlow<Map<String, PeerPresenceEvent>>(emptyMap())
    val peerPresenceMap: StateFlow<Map<String, PeerPresenceEvent>> = _peerPresenceMap.asStateFlow()

    private val _callSignals = MutableSharedFlow<IncomingCallSignalEvent>(extraBufferCapacity = 32)
    val callSignals: SharedFlow<IncomingCallSignalEvent> = _callSignals.asSharedFlow()

    private val _vaultRestoredEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val vaultRestoredEvents: SharedFlow<Int> = _vaultRestoredEvents.asSharedFlow()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        handleDaemonEvent(msg)
        true
    }
    private val clientMessenger = Messenger(incomingHandler)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val messenger = Messenger(service)
            serviceMessenger = messenger
            isBound = true
            _daemonStatus.value = _daemonStatus.value.copy(isBound = true)
            sendToDaemon(DaemonIpcContract.MSG_REGISTER_UI_CLIENT)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            isBound = false
            _daemonStatus.value = _daemonStatus.value.copy(isBound = false)
        }
    }

    fun startAndBindDaemon() {
        OpenSnapDaemonService.ensureRunning(appContext)
        if (!isBound) {
            val intent = Intent(appContext, OpenSnapDaemonService::class.java)
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (isBound) {
            sendToDaemon(DaemonIpcContract.MSG_UNREGISTER_UI_CLIENT)
            try {
                appContext.unbindService(connection)
            } catch (_: Exception) {
            }
            isBound = false
            serviceMessenger = null
        }
    }

    fun setActiveVisibleChatPeer(peerEd25519PubHex: String?) {
        sendToDaemon(
            DaemonIpcContract.MSG_SET_ACTIVE_CHAT_PEER,
            Bundle().apply {
                putString(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            }
        )
    }

    fun dispatchOutboundPacket(
        peerEd25519PubHex: String,
        peerX25519PubHex: String,
        peerRoutingPubHex: String,
        peerRatchetPubHex: String?,
        packetType: String,
        bodyJson: String,
        packetId: String = CryptoEngine.randomHex(16)
    ) {
        val bundle = Bundle().apply {
            putString(DaemonIpcContract.KEY_PEER_PUB_HEX, peerEd25519PubHex)
            putString(DaemonIpcContract.KEY_PEER_X25519_HEX, peerX25519PubHex)
            putString(DaemonIpcContract.KEY_PEER_ROUTING_HEX, peerRoutingPubHex)
            putString(DaemonIpcContract.KEY_PEER_RATCHET_HEX, peerRatchetPubHex)
            putString(DaemonIpcContract.KEY_PACKET_TYPE, packetType)
            putString(DaemonIpcContract.KEY_PACKET_ID, packetId)
            putString(DaemonIpcContract.KEY_BODY_JSON, bodyJson)
        }
        if (!sendToDaemon(DaemonIpcContract.MSG_DISPATCH_OUTBOUND_PACKET, bundle)) {
            // Fallback via explicit internal service Intent if binder is still connecting
            OpenSnapDaemonService.enqueueOutboundViaIntent(appContext, bundle)
        }
    }

    fun notifyIdentityReloaded() {
        OpenSnapDaemonService.ensureRunning(appContext)
        if (!sendToDaemon(DaemonIpcContract.MSG_RELOAD_IDENTITY)) {
            startAndBindDaemon()
        }
    }

    fun triggerVaultBackupSync() {
        sendToDaemon(DaemonIpcContract.MSG_SYNC_VAULT_BACKUP)
    }

    fun triggerVaultRestoreFromRelays() {
        sendToDaemon(DaemonIpcContract.MSG_RESTORE_VAULT_BACKUP)
    }

    fun forceReconnectRelays() {
        sendToDaemon(DaemonIpcContract.MSG_FORCE_RECONNECT)
    }

    suspend fun lookupFriendByShortTag(shortTag: String, timeoutMs: Long = 6_000L): CryptoEngine.PublicContactCard? {
        val reqId = CryptoEngine.randomHex(6)
        val deferred = CompletableDeferred<CryptoEngine.PublicContactCard?>()
        pendingTagLookups[reqId] = deferred

        val sent = sendToDaemon(
            DaemonIpcContract.MSG_LOOKUP_SHORT_TAG,
            Bundle().apply {
                putString(DaemonIpcContract.KEY_REQUEST_ID, reqId)
                putString(DaemonIpcContract.KEY_SHORT_TAG, shortTag.trim())
            }
        )
        if (!sent) {
            pendingTagLookups.remove(reqId)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingTagLookups.remove(reqId)
        }
    }

    private fun sendToDaemon(what: Int, data: Bundle? = null): Boolean {
        val target = serviceMessenger ?: return false
        return try {
            val msg = Message.obtain(null, what).apply {
                replyTo = clientMessenger
                if (data != null) this.data = data
            }
            target.send(msg)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleDaemonEvent(msg: Message) {
        val bundle = msg.data ?: Bundle.EMPTY
        when (msg.what) {
            DaemonIpcContract.EVT_DAEMON_STATUS -> {
                val connected = bundle.getInt(DaemonIpcContract.KEY_CONNECTED_RELAYS, 0)
                val total = bundle.getInt(DaemonIpcContract.KEY_TOTAL_RELAYS, 5)
                val transport = bundle.getString(DaemonIpcContract.KEY_TRANSPORT_LABEL, "Actif")
                val fastCsv = bundle.getString(DaemonIpcContract.KEY_FASTLANE_PEERS_CSV, "")
                val fastSet = fastCsv.split(',').filter { it.isNotBlank() }.toSet()
                _daemonStatus.value = DaemonLiveStatus(
                    isBound = true,
                    connectedRelays = connected,
                    totalRelays = total,
                    transportLabel = transport,
                    fastLanePeerHexes = fastSet
                )
            }

            DaemonIpcContract.EVT_PEER_PRESENCE -> {
                val peerHex = bundle.getString(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                val state = bundle.getString(DaemonIpcContract.KEY_PRESENCE_STATE, "ONLINE")
                val isDirect = bundle.getBoolean(DaemonIpcContract.KEY_IS_P2P_DIRECT, false)
                _peerPresenceMap.value = _peerPresenceMap.value + (
                    peerHex to PeerPresenceEvent(
                        peerEd25519PubHex = peerHex,
                        presenceState = state,
                        isP2pDirect = isDirect
                    )
                )
            }

            DaemonIpcContract.EVT_TAG_LOOKUP_RESULT -> {
                val reqId = bundle.getString(DaemonIpcContract.KEY_REQUEST_ID) ?: return
                val cardJson = bundle.getString(DaemonIpcContract.KEY_CONTACT_CARD_JSON)
                val card = cardJson?.takeIf { it.isNotBlank() }?.let {
                    runCatching {
                        CryptoEngine.canonicalJson.decodeFromString<CryptoEngine.PublicContactCard>(it)
                    }.getOrNull()
                }
                pendingTagLookups.remove(reqId)?.complete(card)
            }

            DaemonIpcContract.EVT_VAULT_RESTORED -> {
                val count = bundle.getInt(DaemonIpcContract.KEY_RESTORED_COUNT, 0)
                _vaultRestoredEvents.tryEmit(count)
            }

            DaemonIpcContract.EVT_WEBRTC_CALL_SIGNAL -> {
                val peerHex = bundle.getString(DaemonIpcContract.KEY_PEER_PUB_HEX) ?: return
                val peerName = bundle.getString(DaemonIpcContract.KEY_PEER_DISPLAY_NAME, "Ami")
                val sigJson = bundle.getString(DaemonIpcContract.KEY_WEBRTC_SIGNAL_JSON) ?: return
                val signal = runCatching {
                    CryptoEngine.canonicalJson.decodeFromString<WebRtcSignalPayload>(sigJson)
                }.getOrNull() ?: return
                _callSignals.tryEmit(IncomingCallSignalEvent(peerHex, peerName, signal))
            }
        }
    }
}
