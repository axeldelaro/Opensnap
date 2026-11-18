package io.github.axeldelaro.opensnap.network.protocol

import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.XChaCha20Poly1305
import io.github.axeldelaro.opensnap.crypto.hexToBytes
import io.github.axeldelaro.opensnap.crypto.toHex
import io.github.axeldelaro.opensnap.data.ttl.TtlPurgeEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import kotlin.math.abs

object PacketType {
    const val FRIEND_REQUEST = "FRIEND_REQUEST"
    const val FRIEND_ACCEPT = "FRIEND_ACCEPT"
    const val CHAT_TEXT = "CHAT_TEXT"
    const val CHAT_VOICE = "CHAT_VOICE"
    const val CHAT_PHOTO = "CHAT_PHOTO"
    const val REACTION = "REACTION"
    const val RECEIPT = "RECEIPT"
    const val PRESENCE_STATE = "PRESENCE_STATE"
    const val WEBRTC_SIGNAL = "WEBRTC_SIGNAL"
}

object PresenceKind {
    const val ONLINE = "ONLINE"
    const val IDLE = "IDLE"
    const val TYPING = "TYPING"
    const val RECORDING_VOICE = "RECORDING_VOICE"
}

/**
 * Inner E2EE OpenSnap Protocol Frame (protected inside XChaCha20-Poly1305 + NIP-59 Gift Wrap
 * or P2P WebRTC DTLS DataChannel).
 * Authenticates sender via Ed25519 signature, rotates X25519 Forward Secrecy ratchet keys,
 * and enforces strict timestamp & nonce anti-replay checks.
 */
@Serializable
data class OpenSnapSignedPacket(
    val version: Int = 1,
    val packetId: String,
    val packetType: String,
    val senderEd25519PubHex: String,
    val senderX25519PubHex: String,
    val senderRoutingPubHex: String,
    val senderOpenSnapTag: String,
    val senderUsername: String,
    val senderDisplayName: String,
    val nextRatchetX25519PubHex: String? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val bodyJson: String,
    val signatureEd25519Hex: String
)

@Serializable
data class ChatTextPayload(
    val text: String,
    val replyToMessageId: String? = null,
    val replyPreviewText: String? = null
)

@Serializable
data class ChatVoicePayload(
    val audioBase64: String,
    val durationMs: Long,
    val waveformCsv: String,
    val replyToMessageId: String? = null,
    val replyPreviewText: String? = null
)

@Serializable
data class ChatPhotoPayload(
    val webpBase64: String,
    val width: Int,
    val height: Int,
    val caption: String = "",
    val isViewOnce: Boolean = false
)

@Serializable
data class ReactionPayload(
    val targetMessageId: String,
    val emoji: String
)

@Serializable
data class ReceiptPayload(
    val messageIds: List<String>,
    val status: String // "DELIVERED" | "OPENED"
)

@Serializable
data class PresencePayload(
    val state: String // "ONLINE" | "IDLE" | "TYPING" | "RECORDING_VOICE"
)

@Serializable
data class WebRtcSignalPayload(
    val callId: String,
    val signalType: String, // "OFFER" | "ANSWER" | "ICE" | "HANGUP" | "BUSY" | "FASTLANE_OFFER" | "FASTLANE_ANSWER" | "FASTLANE_ICE"
    val isVideoCall: Boolean = false,
    val sdp: String? = null,
    val iceCandidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0
)

@Serializable
data class EncryptedContactVaultBackup(
    val version: Int = 1,
    val updatedAtMs: Long,
    val friends: List<VaultFriendEntry>,
    val signatureEd25519Hex: String
)

@Serializable
data class VaultFriendEntry(
    val ed25519PubHex: String,
    val x25519PubHex: String,
    val routingPubHex: String,
    val openSnapTag: String,
    val username: String,
    val displayName: String,
    val status: String,
    val isSafetyVerified: Boolean
)

object OpenSnapPacketCodec {

    private const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L // 5 minutes clock drift tolerance

    fun buildAndSignPacket(
        identity: CryptoEngine.SovereignIdentityBundle,
        packetType: String,
        bodyJson: String,
        packetId: String = CryptoEngine.randomHex(16),
        nextRatchetX25519PubHex: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): OpenSnapSignedPacket {
        val expiresAtMs = nowMs + TtlPurgeEngine.TTL_24H_MS
        val signingMaterial = canonicalSigningBytes(
            version = 1,
            packetId = packetId,
            packetType = packetType,
            senderEd25519PubHex = identity.ed25519PublicHex,
            senderX25519PubHex = identity.x25519PublicHex,
            senderRoutingPubHex = identity.routingPublicHex,
            senderOpenSnapTag = identity.openSnapTag,
            senderUsername = identity.username,
            senderDisplayName = identity.displayName,
            nextRatchetX25519PubHex = nextRatchetX25519PubHex,
            createdAtMs = nowMs,
            expiresAtMs = expiresAtMs,
            bodyJson = bodyJson
        )
        val sigHex = CryptoEngine.signEd25519(identity.ed25519PrivateHex, signingMaterial).toHex()

        return OpenSnapSignedPacket(
            version = 1,
            packetId = packetId,
            packetType = packetType,
            senderEd25519PubHex = identity.ed25519PublicHex,
            senderX25519PubHex = identity.x25519PublicHex,
            senderRoutingPubHex = identity.routingPublicHex,
            senderOpenSnapTag = identity.openSnapTag,
            senderUsername = identity.username,
            senderDisplayName = identity.displayName,
            nextRatchetX25519PubHex = nextRatchetX25519PubHex,
            createdAtMs = nowMs,
            expiresAtMs = expiresAtMs,
            bodyJson = bodyJson,
            signatureEd25519Hex = sigHex
        )
    }

    /**
     * Validates:
     * 1. Self-certifying OpenSnap Tag matches `senderEd25519PubHex`
     * 2. Strict timestamp bounds (not skewed > 5m in the future, not expired > 24h00)
     * 3. Authentic Ed25519 signature over every header and payload field
     */
    fun verifyPacketIntegrity(
        packet: OpenSnapSignedPacket,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (packet.packetId.length < 16) return false
        if (!CryptoEngine.verifySelfCertifyingTag(packet.senderEd25519PubHex, packet.senderOpenSnapTag)) {
            return false
        }
        if (packet.createdAtMs > nowMs + MAX_FUTURE_SKEW_MS) return false
        if (packet.expiresAtMs <= nowMs) return false
        if (abs(packet.expiresAtMs - packet.createdAtMs) > TtlPurgeEngine.TTL_24H_MS + 60_000L) return false

        val signingMaterial = canonicalSigningBytes(
            version = packet.version,
            packetId = packet.packetId,
            packetType = packet.packetType,
            senderEd25519PubHex = packet.senderEd25519PubHex,
            senderX25519PubHex = packet.senderX25519PubHex,
            senderRoutingPubHex = packet.senderRoutingPubHex,
            senderOpenSnapTag = packet.senderOpenSnapTag,
            senderUsername = packet.senderUsername,
            senderDisplayName = packet.senderDisplayName,
            nextRatchetX25519PubHex = packet.nextRatchetX25519PubHex,
            createdAtMs = packet.createdAtMs,
            expiresAtMs = packet.expiresAtMs,
            bodyJson = packet.bodyJson
        )
        return CryptoEngine.verifyEd25519(
            publicKeyHex = packet.senderEd25519PubHex,
            message = signingMaterial,
            signatureBytes = packet.signatureEd25519Hex.hexToBytes()
        )
    }

    /**
     * Seals an [OpenSnapSignedPacket] for a specific recipient using per-message Forward Secrecy
     * X25519 ECDH + XChaCha20-Poly1305 AEAD.
     */
    fun encryptSignedPacketForRecipient(
        packet: OpenSnapSignedPacket,
        recipientX25519PubHex: String,
        recipientRatchetPubHex: String?
    ): String {
        val packetJsonBytes = CryptoEngine.canonicalJson
            .encodeToString(OpenSnapSignedPacket.serializer(), packet)
            .toByteArray(StandardCharsets.UTF_8)
        val aad = "opensnap:inner:v1".toByteArray(StandardCharsets.UTF_8)
        val envelope = CryptoEngine.sealForRecipient(
            recipientX25519PubHex = recipientX25519PubHex,
            recipientRatchetPubHex = recipientRatchetPubHex,
            plaintext = packetJsonBytes,
            associatedData = aad
        )
        return CryptoEngine.canonicalJson.encodeToString(
            CryptoEngine.SealedInnerCipherEnvelope.serializer(),
            envelope
        )
    }

    /**
     * Decrypts and verifies an incoming sealed envelope JSON.
     */
    fun decryptAndVerifyPacket(
        sealedEnvelopeJson: String,
        myStaticX25519PrivHex: String,
        myRatchetPrivHexes: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): OpenSnapSignedPacket? {
        return try {
            val envelope = CryptoEngine.canonicalJson.decodeFromString(
                CryptoEngine.SealedInnerCipherEnvelope.serializer(),
                sealedEnvelopeJson
            )
            val aad = "opensnap:inner:v1".toByteArray(StandardCharsets.UTF_8)
            val plainBytes = CryptoEngine.openFromSender(
                myStaticX25519PrivHex = myStaticX25519PrivHex,
                candidateRatchetPrivHexes = myRatchetPrivHexes,
                envelope = envelope,
                associatedData = aad
            )
            val packet = CryptoEngine.canonicalJson.decodeFromString(
                OpenSnapSignedPacket.serializer(),
                String(plainBytes, StandardCharsets.UTF_8)
            )
            if (verifyPacketIntegrity(packet, nowMs)) packet else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encrypts and signs the user's friend list for serverless cross-device NIP-78 vault recovery.
     */
    fun sealFriendsBackup(
        identity: CryptoEngine.SovereignIdentityBundle,
        entries: List<VaultFriendEntry>
    ): String {
        val nowMs = System.currentTimeMillis()
        val rawToSign = "opensnap:vault:v1|$nowMs|${
            CryptoEngine.canonicalJson.encodeToString(entries)
        }".toByteArray(StandardCharsets.UTF_8)
        val sigHex = CryptoEngine.signEd25519(identity.ed25519PrivateHex, rawToSign).toHex()
        val backup = EncryptedContactVaultBackup(
            version = 1,
            updatedAtMs = nowMs,
            friends = entries,
            signatureEd25519Hex = sigHex
        )
        val plain = CryptoEngine.canonicalJson.encodeToString(backup).toByteArray(StandardCharsets.UTF_8)
        val key = identity.vaultSymmetricKeyHex.hexToBytes()
        val cipher = try {
            XChaCha20Poly1305.sealWithRandomNonce(
                key = key,
                plaintext = plain,
                associatedData = "opensnap:vault-aead:${identity.ed25519PublicHex}".toByteArray(StandardCharsets.UTF_8)
            )
        } finally {
            key.fill(0)
        }
        return java.util.Base64.getEncoder().encodeToString(cipher)
    }

    fun openFriendsBackup(
        identity: CryptoEngine.SovereignIdentityBundle,
        ciphertextBase64: String
    ): EncryptedContactVaultBackup? {
        return try {
            val cipher = java.util.Base64.getDecoder().decode(ciphertextBase64)
            val key = identity.vaultSymmetricKeyHex.hexToBytes()
            val plain = try {
                XChaCha20Poly1305.openCombined(
                    key = key,
                    combined = cipher,
                    associatedData = "opensnap:vault-aead:${identity.ed25519PublicHex}".toByteArray(StandardCharsets.UTF_8)
                )
            } finally {
                key.fill(0)
            }
            val backup = CryptoEngine.canonicalJson.decodeFromString<EncryptedContactVaultBackup>(
                String(plain, StandardCharsets.UTF_8)
            )
            val rawToSign = "opensnap:vault:v1|${backup.updatedAtMs}|${
                CryptoEngine.canonicalJson.encodeToString(backup.friends)
            }".toByteArray(StandardCharsets.UTF_8)
            val valid = CryptoEngine.verifyEd25519(
                identity.ed25519PublicHex,
                rawToSign,
                backup.signatureEd25519Hex.hexToBytes()
            )
            if (valid) backup else null
        } catch (_: Exception) {
            null
        }
    }

    private fun canonicalSigningBytes(
        version: Int,
        packetId: String,
        packetType: String,
        senderEd25519PubHex: String,
        senderX25519PubHex: String,
        senderRoutingPubHex: String,
        senderOpenSnapTag: String,
        senderUsername: String,
        senderDisplayName: String,
        nextRatchetX25519PubHex: String?,
        createdAtMs: Long,
        expiresAtMs: Long,
        bodyJson: String
    ): ByteArray {
        val canonical = buildString {
            append("opensnap:pkt:")
            append(version).append('|')
            append(packetId).append('|')
            append(packetType).append('|')
            append(senderEd25519PubHex).append('|')
            append(senderX25519PubHex).append('|')
            append(senderRoutingPubHex).append('|')
            append(senderOpenSnapTag).append('|')
            append(senderUsername).append('|')
            append(senderDisplayName).append('|')
            append(nextRatchetX25519PubHex.orEmpty()).append('|')
            append(createdAtMs).append('|')
            append(expiresAtMs).append('|')
            append(bodyJson)
        }
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }
}
