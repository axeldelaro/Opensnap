package io.github.axeldelaro.opensnap.network.relay

import io.github.axeldelaro.opensnap.BuildConfig
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.hexToBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Fetches and cryptographically verifies `config/network-config.json` from the static GitHub
 * repository (`https://github.com/axeldelaro/Opensnap`) using a pinned Ed25519 repository key,
 * with verified built-in bootstrap relays for immediate zero-latency startup.
 */
object NetworkConfigVerifier {

    // Pinned Ed25519 public key for `config/network-config.json` in `axeldelaro/Opensnap`
    const val PINNED_REPO_CONFIG_PUBKEY_ED25519_HEX =
        "6d4a6c5a12222e59a35c9f6369a2adf5232eea4c4e970608d3de7d9975d74220"

    val DEFAULT_BOOTSTRAP_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://nostr.mom",
        "wss://offchain.pub"
    )

    val DEFAULT_STUN_SERVERS = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    )

    @Serializable
    data class SignedNetworkConfig(
        val schemaVersion: Int = 1,
        val updatedAt: String = "",
        val repository: String = "https://github.com/axeldelaro/Opensnap",
        val ttlSeconds: Long = 86_400L,
        val powDifficultyBits: Int = 8,
        val relays: List<String> = DEFAULT_BOOTSTRAP_RELAYS,
        val stunServers: List<String> = DEFAULT_STUN_SERVERS,
        val configPublicKeyEd25519Hex: String = PINNED_REPO_CONFIG_PUBKEY_ED25519_HEX,
        val signatureEd25519Hex: String = ""
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun canonicalConfigPayload(config: SignedNetworkConfig): ByteArray {
        val relaysCsv = config.relays.joinToString(",")
        val stunCsv = config.stunServers.joinToString(",")
        val canonical = "opensnap:netcfg:${config.schemaVersion}|${config.updatedAt}|${config.repository}|" +
            "${config.ttlSeconds}|${config.powDifficultyBits}|$relaysCsv|$stunCsv"
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    fun verifySignedConfig(config: SignedNetworkConfig): Boolean {
        if (!config.configPublicKeyEd25519Hex.equals(PINNED_REPO_CONFIG_PUBKEY_ED25519_HEX, ignoreCase = true)) {
            return false
        }
        if (config.signatureEd25519Hex.length != 128) return false
        val validRelays = config.relays.filter { it.startsWith("wss://") }
        if (validRelays.isEmpty()) return false
        return CryptoEngine.verifyEd25519(
            publicKeyHex = PINNED_REPO_CONFIG_PUBKEY_ED25519_HEX,
            message = canonicalConfigPayload(config),
            signatureBytes = config.signatureEd25519Hex.hexToBytes()
        )
    }

    suspend fun fetchVerifiedOrBootstrap(): SignedNetworkConfig = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(BuildConfig.NETWORK_CONFIG_URL)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext SignedNetworkConfig()
                val body = resp.body?.string() ?: return@withContext SignedNetworkConfig()
                val parsed = CryptoEngine.canonicalJson.decodeFromString<SignedNetworkConfig>(body)
                if (verifySignedConfig(parsed)) {
                    parsed.copy(relays = parsed.relays.filter { it.startsWith("wss://") })
                } else {
                    SignedNetworkConfig()
                }
            }
        } catch (_: Exception) {
            SignedNetworkConfig()
        }
    }
}
