package io.github.axeldelaro.opensnap.network.protocol

import io.github.axeldelaro.opensnap.crypto.Bip340Schnorr
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.hexToBytes
import io.github.axeldelaro.opensnap.crypto.toHex
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Wire-compatible Nostr Protocol Implementation:
 * - NIP-01: Canonical event serialization, SHA-256 ID, BIP-340 Schnorr signature
 * - NIP-13: Lightweight anti-DDoS Proof-of-Work (`["nonce", counter, targetBits]`)
 * - NIP-40: Strict 24h relay Store-and-Forward expiration (`["expiration", ts + 86400]`)
 * - NIP-59: Gift Wrap / Sealed Sender (`kind = 1059`) signed by ephemeral random secp256k1 keys
 * - NIP-78: Application-specific data (`kind = 30078`) for self-certifying Tag lookup & encrypted friend backup
 */
object NostrModels {

    const val KIND_GIFT_WRAP = 1059
    const val KIND_APP_DATA = 30078

    @Serializable
    data class NostrEvent(
        val id: String,
        val pubkey: String,
        val created_at: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String
    )

    /**
     * Computes canonical NIP-01 SHA-256 commitment over `[0, pubkey, created_at, kind, tags, content]`.
     */
    fun computeEventIdBytes(
        pubkeyHex: String,
        createdAtSec: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): ByteArray {
        val canonicalArray: JsonArray = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkeyHex.lowercase()))
            add(JsonPrimitive(createdAtSec))
            add(JsonPrimitive(kind))
            add(
                buildJsonArray {
                    tags.forEach { tagRow ->
                        add(
                            buildJsonArray {
                                tagRow.forEach { item -> add(JsonPrimitive(item)) }
                            }
                        )
                    }
                }
            )
            add(JsonPrimitive(content))
        }
        val serialized = CryptoEngine.canonicalJson.encodeToString(JsonArray.serializer(), canonicalArray)
        return CryptoEngine.sha256(serialized.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Creates, mines NIP-13 Proof-of-Work (if [powBits] > 0), and BIP-340 signs a NostrEvent.
     */
    fun createSignedEvent(
        privateKey32: ByteArray,
        publicKeyXOnly32: ByteArray,
        kind: Int,
        baseTags: List<List<String>>,
        content: String,
        createdAtSec: Long = System.currentTimeMillis() / 1000L,
        powBits: Int = 8
    ): NostrEvent {
        val pubHex = publicKeyXOnly32.toHex()
        var counter = Random.nextLong(0L, 1_000_000L)
        var finalTags: List<List<String>>
        var idBytes: ByteArray

        if (powBits <= 0) {
            finalTags = baseTags
            idBytes = computeEventIdBytes(pubHex, createdAtSec, kind, finalTags, content)
        } else {
            val targetStr = powBits.toString()
            while (true) {
                finalTags = baseTags + listOf(listOf("nonce", counter.toString(), targetStr))
                idBytes = computeEventIdBytes(pubHex, createdAtSec, kind, finalTags, content)
                if (CryptoEngine.countLeadingZeroBits(idBytes) >= powBits) {
                    break
                }
                counter++
            }
        }

        val sigBytes = Bip340Schnorr.sign(idBytes, privateKey32)
        return NostrEvent(
            id = idBytes.toHex(),
            pubkey = pubHex,
            created_at = createdAtSec,
            kind = kind,
            tags = finalTags,
            content = content,
            sig = sigBytes.toHex()
        )
    }

    /**
     * Wraps an already E2EE-encrypted OpenSnap envelope inside a NIP-59 Gift Wrap (`kind = 1059`)
     * signed by a brand-new one-time random secp256k1 keypair (100% Sealed Sender metadata privacy)
     * with NIP-40 24-hour relay store-and-forward expiration and NIP-13 anti-flood PoW.
     */
    fun buildGiftWrap24hEvent(
        recipientRoutingPubHex: String,
        sealedInnerEnvelopeJson: String,
        powBits: Int = 8
    ): NostrEvent {
        val oneTimeKeyPair = Bip340Schnorr.generateKeyPair()
        val nowSec = System.currentTimeMillis() / 1000L
        val expirationSec = nowSec + TtlPurgeEngine.TTL_24H_SEC
        // Slight backward timestamp jitter (0..120s) hides exact send second from passive relay observers
        val jitteredCreatedAt = nowSec - Random.nextLong(0L, 90L)

        val tags = listOf(
            listOf("p", recipientRoutingPubHex.lowercase()),
            listOf("expiration", expirationSec.toString()),
            listOf("t", "opensnap-v1")
        )

        return try {
            createSignedEvent(
                privateKey32 = oneTimeKeyPair.privateKey32,
                publicKeyXOnly32 = oneTimeKeyPair.publicKeyXOnly32,
                kind = KIND_GIFT_WRAP,
                baseTags = tags,
                content = sealedInnerEnvelopeJson,
                createdAtSec = jitteredCreatedAt,
                powBits = powBits
            )
        } finally {
            oneTimeKeyPair.privateKey32.fill(0)
        }
    }

    /**
     * Verifies outer Nostr NIP-01 ID commitment, BIP-340 Schnorr signature, NIP-40 expiration,
     * and minimum NIP-13 Proof-of-Work anti-spam threshold.
     */
    fun verifyOuterEvent(event: NostrEvent, minPowBits: Int = 6): Boolean {
        return try {
            if (event.id.length != 64 || event.pubkey.length != 64 || event.sig.length != 128) {
                return false
            }
            val expectedId = computeEventIdBytes(
                pubkeyHex = event.pubkey,
                createdAtSec = event.created_at,
                kind = event.kind,
                tags = event.tags,
                content = event.content
            )
            if (!expectedId.toHex().equals(event.id, ignoreCase = true)) {
                return false
            }
            if (minPowBits > 0 && CryptoEngine.countLeadingZeroBits(expectedId) < minPowBits) {
                return false
            }
            val nowSec = System.currentTimeMillis() / 1000L
            val expirationTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "expiration" }
            if (expirationTag != null) {
                val expSec = expirationTag[1].toLongOrNull() ?: return false
                if (expSec <= nowSec) return false
            }
            Bip340Schnorr.verify(
                msg32 = expectedId,
                pubKeyX32 = event.pubkey.hexToBytes(),
                sig64 = event.sig.hexToBytes()
            )
        } catch (_: Exception) {
            false
        }
    }

    fun formatEventCommand(event: NostrEvent): String {
        val eventJson = CryptoEngine.canonicalJson.encodeToString(NostrEvent.serializer(), event)
        return """["EVENT",$eventJson]"""
    }

    fun parseRelayFrame(rawFrame: String): Pair<String, List<JsonElement>>? {
        return try {
            val arr = CryptoEngine.canonicalJson.parseToJsonElement(rawFrame).jsonArray
            if (arr.isEmpty()) return null
            val verb = arr[0].jsonPrimitive.content
            verb to arr.drop(1)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeEventElement(element: JsonElement): NostrEvent? {
        return try {
            CryptoEngine.canonicalJson.decodeFromJsonElement(
                NostrEvent.serializer(),
                element.jsonObject
            )
        } catch (_: Exception) {
            null
        }
    }
}
