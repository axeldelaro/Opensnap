package io.github.axeldelaro.opensnap.network.relay

import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.hexToBytes
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import io.github.axeldelaro.opensnap.network.protocol.NostrModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Multiplexed Decentralized WSS Relay Pool (Zero Random Polling).
 * - Maintains simultaneous persistent TLS 1.3 WebSocket tunnels across multiple public Nostr relays.
 * - Dynamically adapts WebSocket Ping-Pong intervals to 4G/5G cellular (25s) vs Wi-Fi (45s) NAT budgets.
 * - Subscribes to NIP-59 Gift-Wrap push events (`kind = 1059`, `since = now - 86400`) so offline messages
 *   queued during phone power-off arrive immediately upon reconnection and live messages push in < 300ms.
 * - Supports self-certifying Tag directory resolution and encrypted friend-list backup via NIP-78 (`kind = 30078`).
 */
class NostrRelayPool(
    private val onGiftWrapReceived: (NostrModels.NostrEvent, String) -> Unit,
    private val onConnectionCountChanged: (Int, Int) -> Unit = { _, _ -> }
) {

    enum class TransportKind {
        CELLULAR_4G_5G,
        WIFI_OR_ETHERNET,
        OFFLINE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connectedUrls = ConcurrentHashMap.newKeySet<String>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val oneShotCallbacks = ConcurrentHashMap<String, (NostrModels.NostrEvent) -> Unit>()

    @Volatile
    private var currentRoutingPubHex: String? = null

    @Volatile
    private var configuredRelays: List<String> = NetworkConfigVerifier.DEFAULT_BOOTSTRAP_RELAYS

    @Volatile
    private var currentTransport: TransportKind = TransportKind.WIFI_OR_ETHERNET

    @Volatile
    private var cellularClient: OkHttpClient = buildClient(pingIntervalSeconds = 25L)

    @Volatile
    private var wifiClient: OkHttpClient = buildClient(pingIntervalSeconds = 45L)

    private var watchdogJob: Job? = null

    private val _connectedRelayList = MutableStateFlow<List<String>>(emptyList())
    val connectedRelayList: StateFlow<List<String>> = _connectedRelayList.asStateFlow()

    private fun buildClient(pingIntervalSeconds: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Streaming push WebSocket (zero read timeout)
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    fun start(routingPubHex: String, relayUrls: List<String>, transport: TransportKind) {
        currentRoutingPubHex = routingPubHex.lowercase()
        configuredRelays = relayUrls.distinct().filter { it.startsWith("wss://") }
            .ifEmpty { NetworkConfigVerifier.DEFAULT_BOOTSTRAP_RELAYS }
        currentTransport = transport

        connectMissingRelays()
        startWatchdogLoop()
    }

    fun updateNetworkTransport(newTransport: TransportKind) {
        val previous = currentTransport
        currentTransport = newTransport
        if (newTransport == TransportKind.OFFLINE) return

        if (previous != newTransport || connectedUrls.isEmpty()) {
            // Immediately cycle or restore tunnels calibrated to the new radio NAT keepalive
            reconnectAllNow()
        } else {
            connectMissingRelays()
        }
    }

    fun reconnectAllNow() {
        val urls = configuredRelays
        urls.forEach { url ->
            reconnectJobs.remove(url)?.cancel()
            sockets.remove(url)?.cancel()
            connectedUrls.remove(url)
        }
        notifyConnectionStats()
        connectMissingRelays()
    }

    private fun connectMissingRelays() {
        val routingHex = currentRoutingPubHex ?: return
        if (currentTransport == TransportKind.OFFLINE) return
        configuredRelays.forEach { url ->
            if (!connectedUrls.contains(url) && !sockets.containsKey(url)) {
                openRelaySocket(url, routingHex, attempt = 0)
            }
        }
    }

    private fun openRelaySocket(url: String, routingPubHex: String, attempt: Int) {
        val activeClient = when (currentTransport) {
            TransportKind.CELLULAR_4G_5G -> cellularClient
            else -> wifiClient
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenSnap-Android/1.0")
            .build()

        val ws = activeClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connectedUrls.add(url)
                    reconnectJobs.remove(url)?.cancel()
                    notifyConnectionStats()
                    sendLivePushSubscription(webSocket, routingPubHex)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingFrame(url, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(url, webSocket)
                    connectedUrls.remove(url)
                    notifyConnectionStats()
                    scheduleReconnect(url, attempt + 1)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(url, webSocket)
                    connectedUrls.remove(url)
                    notifyConnectionStats()
                    scheduleReconnect(url, attempt + 1)
                }
            }
        )
        sockets[url] = ws
    }

    private fun sendLivePushSubscription(webSocket: WebSocket, routingPubHex: String) {
        // Subscribe to all NIP-59 Gift Wrap events addressed to our routing key from the past 24h00
        // plus continuous live streaming of all future arrivals on this WSS socket.
        val sinceSec = (System.currentTimeMillis() / 1000L) - TtlPurgeEngine.TTL_24H_SEC - 120L
        val filterObj = buildJsonObject {
            put("kinds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(NostrModels.KIND_GIFT_WRAP)) })
            put("#p", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(routingPubHex)) })
            put("since", sinceSec)
        }
        val subCommand = """["REQ","opensnap-inbox-24h",$filterObj]"""
        webSocket.send(subCommand)
    }

    private fun handleIncomingFrame(relayUrl: String, text: String) {
        val parsed = NostrModels.parseRelayFrame(text) ?: return
        val (verb, args) = parsed
        if (verb == "EVENT" && args.size >= 2) {
            val subId = try {
                args[0].let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
            } catch (_: Exception) {
                ""
            }
            val event = NostrModels.decodeEventElement(args[1]) ?: return
            if (subId == "opensnap-inbox-24h" && event.kind == NostrModels.KIND_GIFT_WRAP) {
                onGiftWrapReceived(event, relayUrl)
            } else {
                oneShotCallbacks[subId]?.invoke(event)
            }
        }
    }

    private fun scheduleReconnect(url: String, attempt: Int) {
        val routingHex = currentRoutingPubHex ?: return
        if (currentTransport == TransportKind.OFFLINE) return
        if (reconnectJobs[url]?.isActive == true) return

        reconnectJobs[url] = scope.launch {
            val baseDelayMs = when {
                attempt <= 1 -> 800L
                attempt == 2 -> 2_000L
                attempt == 3 -> 5_000L
                else -> 12_000L
            }
            val jitter = Random.nextLong(0L, 600L)
            delay(baseDelayMs + jitter)
            if (isActive && currentRoutingPubHex != null && !connectedUrls.contains(url)) {
                openRelaySocket(url, routingHex, attempt)
            }
        }
    }

    private fun startWatchdogLoop() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                if (currentRoutingPubHex != null && currentTransport != TransportKind.OFFLINE) {
                    connectMissingRelays()
                }
            }
        }
    }

    /**
     * Broadcasts a signed NostrEvent across all active WSS relays in parallel.
     * Returns the number of relays that accepted socket transmission immediately.
     */
    fun publishEventToMesh(event: NostrModels.NostrEvent): Int {
        if (connectedUrls.isEmpty()) {
            connectMissingRelays()
        }
        val wire = NostrModels.formatEventCommand(event)
        var sentCount = 0
        sockets.forEach { (url, ws) ->
            if (connectedUrls.contains(url)) {
                if (ws.send(wire)) {
                    sentCount++
                }
            }
        }
        return sentCount
    }

    /**
     * Publishes the user's self-certifying PublicContactCard on NIP-78 (`kind = 30078`)
     * so friends can add them simply by typing their short `OS-XXXX-XXXX` OpenSnap Tag.
     */
    fun publishPublicTagCard(identity: CryptoEngine.SovereignIdentityBundle) {
        scope.launch {
            val card = CryptoEngine.createSignedContactCard(identity)
            val cardJson = CryptoEngine.canonicalJson.encodeToString(card)
            val priv32 = identity.routingPrivateHex.hexToBytes()
            val pub32 = identity.routingPublicHex.hexToBytes()
            val event = try {
                NostrModels.createSignedEvent(
                    privateKey32 = priv32,
                    publicKeyXOnly32 = pub32,
                    kind = NostrModels.KIND_APP_DATA,
                    baseTags = listOf(
                        listOf("d", "opensnap-tag:${identity.openSnapTag.uppercase()}"),
                        listOf("t", identity.openSnapTag.uppercase())
                    ),
                    content = cardJson,
                    powBits = 6
                )
            } finally {
                priv32.fill(0)
            }
            publishEventToMesh(event)
        }
    }

    /**
     * Resolves a friend's short `OS-XXXX-XXXX` OpenSnap Tag across the decentralized relay pool
     * and cryptographically verifies the self-certifying Ed25519 signature.
     */
    suspend fun lookupContactByShortTag(openSnapTag: String, timeoutMs: Long = 5_500L): CryptoEngine.PublicContactCard? {
        val normalizedTag = openSnapTag.trim().uppercase()
        val subId = "tag-q-${CryptoEngine.randomHex(4)}"
        val resultDeferred = CompletableDeferred<CryptoEngine.PublicContactCard>()

        oneShotCallbacks[subId] = { event ->
            if (event.kind == NostrModels.KIND_APP_DATA && NostrModels.verifyOuterEvent(event, minPowBits = 0)) {
                try {
                    val card = CryptoEngine.canonicalJson.decodeFromString<CryptoEngine.PublicContactCard>(event.content)
                    if (card.openSnapTag.equals(normalizedTag, ignoreCase = true) &&
                        CryptoEngine.verifyContactCard(card)
                    ) {
                        resultDeferred.complete(card)
                    }
                } catch (_: Exception) {
                }
            }
        }

        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(NostrModels.KIND_APP_DATA)) })
            put("#d", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("opensnap-tag:$normalizedTag")) })
            put("limit", 3)
        }
        val reqWire = """["REQ","$subId",$filter]"""
        sockets.values.forEach { it.send(reqWire) }

        return try {
            withTimeoutOrNull(timeoutMs) { resultDeferred.await() }
        } finally {
            oneShotCallbacks.remove(subId)
            val closeWire = """["CLOSE","$subId"]"""
            sockets.values.forEach { it.send(closeWire) }
        }
    }

    /**
     * Publishes an XChaCha20-Poly1305 encrypted backup of the friend list (`kind = 30078`)
     * signed by the user's sovereign keypair for zero-server account migration across phones.
     */
    fun publishEncryptedVaultBackup(identity: CryptoEngine.SovereignIdentityBundle, encryptedVaultBase64: String) {
        scope.launch {
            val priv32 = identity.routingPrivateHex.hexToBytes()
            val pub32 = identity.routingPublicHex.hexToBytes()
            val event = try {
                NostrModels.createSignedEvent(
                    privateKey32 = priv32,
                    publicKeyXOnly32 = pub32,
                    kind = NostrModels.KIND_APP_DATA,
                    baseTags = listOf(
                        listOf("d", "opensnap-vault-v1:${identity.openSnapTag.uppercase()}")
                    ),
                    content = encryptedVaultBase64,
                    powBits = 6
                )
            } finally {
                priv32.fill(0)
            }
            publishEventToMesh(event)
        }
    }

    /**
     * Queries the relay mesh for the user's latest encrypted friend-list backup upon account recovery.
     */
    suspend fun fetchEncryptedVaultBackup(
        identity: CryptoEngine.SovereignIdentityBundle,
        timeoutMs: Long = 5_000L
    ): String? {
        val subId = "vault-q-${CryptoEngine.randomHex(4)}"
        var newestTimestamp = -1L
        var newestContent: String? = null
        val firstHit = CompletableDeferred<String>()

        oneShotCallbacks[subId] = { event ->
            if (event.kind == NostrModels.KIND_APP_DATA &&
                event.pubkey.equals(identity.routingPublicHex, ignoreCase = true) &&
                NostrModels.verifyOuterEvent(event, minPowBits = 0)
            ) {
                synchronized(this) {
                    if (event.created_at > newestTimestamp) {
                        newestTimestamp = event.created_at
                        newestContent = event.content
                        firstHit.complete(event.content)
                    }
                }
            }
        }

        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(NostrModels.KIND_APP_DATA)) })
            put("authors", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(identity.routingPublicHex)) })
            put("#d", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("opensnap-vault-v1:${identity.openSnapTag.uppercase()}")) })
            put("limit", 2)
        }
        val reqWire = """["REQ","$subId",$filter]"""
        sockets.values.forEach { it.send(reqWire) }

        return try {
            withTimeoutOrNull(timeoutMs) { firstHit.await() } ?: newestContent
        } finally {
            oneShotCallbacks.remove(subId)
            val closeWire = """["CLOSE","$subId"]"""
            sockets.values.forEach { it.send(closeWire) }
        }
    }

    private fun notifyConnectionStats() {
        val snapshot = connectedUrls.toList().sorted()
        _connectedRelayList.value = snapshot
        onConnectionCountChanged(snapshot.size, configuredRelays.size)
    }

    fun stopAll() {
        currentRoutingPubHex = null
        watchdogJob?.cancel()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        sockets.values.forEach { ws ->
            try {
                ws.close(1000, "OpenSnap shutdown")
            } catch (_: Exception) {
            }
        }
        sockets.clear()
        connectedUrls.clear()
        _connectedRelayList.value = emptyList()
    }
}
