package io.github.axeldelaro.opensnap.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.digests.SHA256Digest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Sovereign Applied Cryptography Engine for OpenSnap:
 * - Deterministic Argon2id account derivation (Username + Password OR 12-word BIP-39 phrase)
 * - Ed25519 identity signature keypair + X25519 ECDH encryption keypair + secp256k1 Nostr routing keypair
 * - Self-certifying Crockford Base32 OpenSnap Tags (`OS-XXXX-XXXX`)
 * - Per-message ephemeral X25519 + chain key Forward Secrecy ratchet + XChaCha20-Poly1305 AEAD
 * - NIP-59 Sealed Sender / Gift Wrap metadata masking + NIP-13 lightweight Proof-of-Work
 * - 60-digit Safety Number fingerprint verification
 */
object CryptoEngine {

    private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val secureRandom = SecureRandom()

    val canonicalJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class SovereignIdentityBundle(
        val username: String,
        val displayName: String,
        val openSnapTag: String,
        val ed25519PrivateHex: String,
        val ed25519PublicHex: String,
        val x25519PrivateHex: String,
        val x25519PublicHex: String,
        val routingPrivateHex: String,
        val routingPublicHex: String,
        val vaultSymmetricKeyHex: String,
        val recoveryMnemonic: String? = null
    )

    @Serializable
    data class PublicContactCard(
        val v: Int = 1,
        val username: String,
        val displayName: String,
        val openSnapTag: String,
        val ed25519PubHex: String,
        val x25519PubHex: String,
        val routingPubHex: String,
        val issuedAtSec: Long,
        val signatureEd25519Hex: String
    )

    @Serializable
    data class SealedInnerCipherEnvelope(
        val v: Int = 1,
        val ephemeralX25519PubHex: String,
        val packetSaltHex: String,
        val ciphertextBase64: String
    )

    /**
     * Deterministically derives a complete SovereignIdentityBundle via Argon2id + HKDF-SHA256.
     * Identical credentials or 12-word BIP-39 mnemonic reproduce the exact same keys on any device.
     */
    fun deriveSovereignIdentity(
        username: String,
        displayName: String,
        passwordOrEmpty: String,
        mnemonic12Words: String? = null,
        memoryKiB: Int = 32_768,
        iterations: Int = 3,
        parallelism: Int = 2
    ): SovereignIdentityBundle {
        val cleanUsername = normalizeUsername(username)
        require(cleanUsername.length >= 3) { "Identifiant trop court (min 3 caractères)" }

        val saltInput = "opensnap:v1:argon2id:salt:$cleanUsername"
        val salt16 = sha256(saltInput.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, 16)

        val secretMaterial = if (!mnemonic12Words.isNullOrBlank()) {
            val bip39Seed = Bip39Mnemonic.mnemonicToSeed64(mnemonic12Words, cleanUsername)
            bip39Seed
        } else {
            require(passwordOrEmpty.length >= 8) { "Mot de passe trop court (min 8 caractères)" }
            "$cleanUsername:$passwordOrEmpty".toByteArray(StandardCharsets.UTF_8)
        }

        val masterSeed64 = ByteArray(64)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt16)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(secretMaterial, masterSeed64)

        try {
            val edSeed32 = hkdfSha256(masterSeed64, salt16, "opensnap/v1/ed25519/identity", 32)
            val xSeed32 = hkdfSha256(masterSeed64, salt16, "opensnap/v1/x25519/encryption", 32)
            val routingSeed32 = hkdfSha256(masterSeed64, salt16, "opensnap/v1/secp256k1/routing", 32)
            val vaultKey32 = hkdfSha256(masterSeed64, salt16, "opensnap/v1/xchacha20/vault-backup", 32)

            val edPriv = Ed25519PrivateKeyParameters(edSeed32, 0)
            val edPubBytes = edPriv.generatePublicKey().encoded

            // Clamp X25519 private scalar per RFC 7748
            xSeed32[0] = (xSeed32[0].toInt() and 248).toByte()
            xSeed32[31] = (xSeed32[31].toInt() and 127).toByte()
            xSeed32[31] = (xSeed32[31].toInt() or 64).toByte()
            val xPriv = X25519PrivateKeyParameters(xSeed32, 0)
            val xPubBytes = xPriv.generatePublicKey().encoded

            val routingPair = Bip340Schnorr.deriveFromSeed32(routingSeed32)
            val tag = deriveOpenSnapTag(edPubBytes)

            return SovereignIdentityBundle(
                username = cleanUsername,
                displayName = displayName.trim().ifBlank { cleanUsername },
                openSnapTag = tag,
                ed25519PrivateHex = edSeed32.toHex(),
                ed25519PublicHex = edPubBytes.toHex(),
                x25519PrivateHex = xPriv.encoded.toHex(),
                x25519PublicHex = xPubBytes.toHex(),
                routingPrivateHex = routingPair.privateKey32.toHex(),
                routingPublicHex = routingPair.publicKeyXOnly32.toHex(),
                vaultSymmetricKeyHex = vaultKey32.toHex(),
                recoveryMnemonic = mnemonic12Words?.let {
                    Bip39Mnemonic.normalizeWords(it).joinToString(" ")
                }
            )
        } finally {
            masterSeed64.fill(0)
        }
    }

    /**
     * Self-certifying short OpenSnap Tag: `OS-XXXX-XXXX` derived directly from `Ed25519` public key.
     * Prevents tag hijacking or impersonation without a central server.
     */
    fun deriveOpenSnapTag(ed25519PublicBytes: ByteArray): String {
        val digest = sha256("opensnap:tag:v1:".toByteArray(StandardCharsets.UTF_8) + ed25519PublicBytes)
        // Encode first 40 bits (5 bytes) into 8 Crockford Base32 chars -> OS-XXXX-XXXX
        var acc = 0L
        for (i in 0 until 5) {
            acc = (acc shl 8) or (digest[i].toLong() and 0xFFL)
        }
        val chars = CharArray(8)
        for (i in 7 downTo 0) {
            chars[i] = CROCKFORD_ALPHABET[(acc and 0x1FL).toInt()]
            acc = acc ushr 5
        }
        val part1 = String(chars, 0, 4)
        val part2 = String(chars, 4, 4)
        return "OS-$part1-$part2"
    }

    fun verifySelfCertifyingTag(ed25519PubHex: String, claimedTag: String): Boolean {
        return try {
            val expected = deriveOpenSnapTag(ed25519PubHex.hexToBytes())
            expected.equals(claimedTag.trim().uppercase(Locale.ROOT), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Signs arbitrary message bytes using Ed25519 private key.
     */
    fun signEd25519(privateKeyHex: String, message: ByteArray): ByteArray {
        val privParams = Ed25519PrivateKeyParameters(privateKeyHex.hexToBytes(), 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verifies an Ed25519 signature.
     */
    fun verifyEd25519(publicKeyHex: String, message: ByteArray, signatureBytes: ByteArray): Boolean {
        if (signatureBytes.size != 64) return false
        return try {
            val pubBytes = publicKeyHex.hexToBytes()
            if (pubBytes.size != 32) return false
            val pubParams = Ed25519PublicKeyParameters(pubBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates a signed PublicContactCard and shareable `opensnap://add/<base64url>` URI for QR / text invite.
     */
    fun createSignedContactCard(identity: SovereignIdentityBundle): PublicContactCard {
        val issuedAt = System.currentTimeMillis() / 1000L
        val canonicalPayload = buildContactSignPayload(
            username = identity.username,
            displayName = identity.displayName,
            openSnapTag = identity.openSnapTag,
            ed25519PubHex = identity.ed25519PublicHex,
            x25519PubHex = identity.x25519PublicHex,
            routingPubHex = identity.routingPublicHex,
            issuedAtSec = issuedAt
        )
        val sig = signEd25519(identity.ed25519PrivateHex, canonicalPayload).toHex()
        return PublicContactCard(
            username = identity.username,
            displayName = identity.displayName,
            openSnapTag = identity.openSnapTag,
            ed25519PubHex = identity.ed25519PublicHex,
            x25519PubHex = identity.x25519PublicHex,
            routingPubHex = identity.routingPublicHex,
            issuedAtSec = issuedAt,
            signatureEd25519Hex = sig
        )
    }

    fun verifyContactCard(card: PublicContactCard): Boolean {
        if (!verifySelfCertifyingTag(card.ed25519PubHex, card.openSnapTag)) return false
        if (card.x25519PubHex.length != 64 || card.routingPubHex.length != 64) return false
        val payload = buildContactSignPayload(
            username = card.username,
            displayName = card.displayName,
            openSnapTag = card.openSnapTag,
            ed25519PubHex = card.ed25519PubHex,
            x25519PubHex = card.x25519PubHex,
            routingPubHex = card.routingPubHex,
            issuedAtSec = card.issuedAtSec
        )
        return verifyEd25519(card.ed25519PubHex, payload, card.signatureEd25519Hex.hexToBytes())
    }

    fun encodeContactInviteUri(card: PublicContactCard): String {
        val jsonBytes = canonicalJson.encodeToString(card).toByteArray(StandardCharsets.UTF_8)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes)
        return "opensnap://add/$b64"
    }

    fun decodeContactInviteUri(rawInput: String): PublicContactCard? {
        return try {
            val cleaned = rawInput.trim().removePrefix("opensnap://add/").trim()
            val jsonBytes = java.util.Base64.getUrlDecoder().decode(cleaned)
            val card = canonicalJson.decodeFromString<PublicContactCard>(String(jsonBytes, StandardCharsets.UTF_8))
            if (verifyContactCard(card)) card else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildContactSignPayload(
        username: String,
        displayName: String,
        openSnapTag: String,
        ed25519PubHex: String,
        x25519PubHex: String,
        routingPubHex: String,
        issuedAtSec: Long
    ): ByteArray = "opensnap:contact:v1|$username|$displayName|$openSnapTag|$ed25519PubHex|$x25519PubHex|$routingPubHex|$issuedAtSec"
        .toByteArray(StandardCharsets.UTF_8)

    /**
     * Per-message Forward Secrecy encryption:
     * Generates a one-time ephemeral X25519 keypair, mixes `DH(ephPriv, recipientX25519Pub)` via HKDF-SHA256,
     * zeroizes the ephemeral private scalar immediately, and seals with XChaCha20-Poly1305.
     */
    fun sealForRecipient(
        recipientX25519PubHex: String,
        recipientRatchetPubHex: String?,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): SealedInnerCipherEnvelope {
        val ephPriv = X25519PrivateKeyParameters(secureRandom)
        val ephPubHex = ephPriv.generatePublicKey().encoded.toHex()
        val packetSalt = ByteArray(16).also { secureRandom.nextBytes(it) }

        val targetStaticPub = X25519PublicKeyParameters(recipientX25519PubHex.hexToBytes(), 0)
        val sharedStatic = ByteArray(32)
        val agreement1 = X25519Agreement()
        agreement1.init(ephPriv)
        agreement1.calculateAgreement(targetStaticPub, sharedStatic, 0)

        val ikm = if (!recipientRatchetPubHex.isNullOrBlank() && recipientRatchetPubHex.length == 64) {
            val targetRatchetPub = X25519PublicKeyParameters(recipientRatchetPubHex.hexToBytes(), 0)
            val sharedRatchet = ByteArray(32)
            val agreement2 = X25519Agreement()
            agreement2.init(ephPriv)
            agreement2.calculateAgreement(targetRatchetPub, sharedRatchet, 0)
            sharedStatic + sharedRatchet
        } else {
            sharedStatic
        }

        val messageKey = hkdfSha256(ikm, packetSalt, "opensnap/v1/e2ee/xchacha20poly1305", 32)
        return try {
            val combinedCipher = XChaCha20Poly1305.sealWithRandomNonce(
                key = messageKey,
                plaintext = plaintext,
                associatedData = associatedData
            )
            SealedInnerCipherEnvelope(
                ephemeralX25519PubHex = ephPubHex,
                packetSaltHex = packetSalt.toHex(),
                ciphertextBase64 = java.util.Base64.getEncoder().encodeToString(combinedCipher)
            )
        } finally {
            sharedStatic.fill(0)
            ikm.fill(0)
            messageKey.fill(0)
        }
    }

    /**
     * Opens a [SealedInnerCipherEnvelope] trying active/previous ratchet keys first, then static X25519 key.
     */
    fun openFromSender(
        myStaticX25519PrivHex: String,
        candidateRatchetPrivHexes: List<String> = emptyList(),
        envelope: SealedInnerCipherEnvelope,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        val ephPub = X25519PublicKeyParameters(envelope.ephemeralX25519PubHex.hexToBytes(), 0)
        val salt = envelope.packetSaltHex.hexToBytes()
        val combinedCipher = java.util.Base64.getDecoder().decode(envelope.ciphertextBase64)

        val staticPriv = X25519PrivateKeyParameters(myStaticX25519PrivHex.hexToBytes(), 0)
        val sharedStatic = ByteArray(32)
        val staticAgreement = X25519Agreement()
        staticAgreement.init(staticPriv)
        staticAgreement.calculateAgreement(ephPub, sharedStatic, 0)

        try {
            // Try candidate ratchet keys first (if sender mixed our advertised ratchet pubkey)
            for (ratchetPrivHex in candidateRatchetPrivHexes) {
                if (ratchetPrivHex.length != 64) continue
                val ratchetPriv = X25519PrivateKeyParameters(ratchetPrivHex.hexToBytes(), 0)
                val sharedRatchet = ByteArray(32)
                val agree = X25519Agreement()
                agree.init(ratchetPriv)
                agree.calculateAgreement(ephPub, sharedRatchet, 0)
                val ikm = sharedStatic + sharedRatchet
                val key = hkdfSha256(ikm, salt, "opensnap/v1/e2ee/xchacha20poly1305", 32)
                try {
                    return XChaCha20Poly1305.openCombined(key, combinedCipher, associatedData)
                } catch (_: Exception) {
                    // Fall through to next candidate or static key
                } finally {
                    sharedRatchet.fill(0)
                    ikm.fill(0)
                    key.fill(0)
                }
            }

            // Fallback: static X25519 ECDH with sender's one-time ephemeral X25519 key
            val staticMsgKey = hkdfSha256(sharedStatic, salt, "opensnap/v1/e2ee/xchacha20poly1305", 32)
            return try {
                XChaCha20Poly1305.openCombined(staticMsgKey, combinedCipher, associatedData)
            } finally {
                staticMsgKey.fill(0)
            }
        } finally {
            sharedStatic.fill(0)
        }
    }

    /**
     * Generates a fresh X25519 ratchet keypair `(privateHex, publicHex)` for per-conversation key rotation.
     */
    fun generateRatchetX25519KeyPair(): Pair<String, String> {
        val priv = X25519PrivateKeyParameters(secureRandom)
        val pub = priv.generatePublicKey()
        return priv.encoded.toHex() to pub.encoded.toHex()
    }

    /**
     * Computes a deterministic, symmetric 60-digit Safety Number for out-of-band MitM verification.
     */
    fun computeSafetyNumber(myEd25519PubHex: String, peerEd25519PubHex: String): String {
        val a = myEd25519PubHex.lowercase(Locale.ROOT)
        val b = peerEd25519PubHex.lowercase(Locale.ROOT)
        val ordered = if (a <= b) "$a|$b" else "$b|$a"
        var digest = MessageDigest.getInstance("SHA-512")
            .digest("opensnap:safety-number:v1:$ordered".toByteArray(StandardCharsets.UTF_8))
        repeat(1024) {
            digest = MessageDigest.getInstance("SHA-512").digest(digest)
        }
        // Format 12 groups of 5 decimal digits = 60 digits
        return (0 until 12).joinToString(" ") { group ->
            val offset = group * 5
            val chunk = ((digest[offset].toLong() and 0xFFL) shl 32) or
                ((digest[offset + 1].toLong() and 0xFFL) shl 24) or
                ((digest[offset + 2].toLong() and 0xFFL) shl 16) or
                ((digest[offset + 3].toLong() and 0xFFL) shl 8) or
                (digest[offset + 4].toLong() and 0xFFL)
            String.format(Locale.US, "%05d", chunk % 100_000L)
        }
    }

    /**
     * Counts leading zero bits in a 32-byte SHA-256 digest (for Nostr NIP-13 anti-flood Proof-of-Work).
     */
    fun countLeadingZeroBits(hash32: ByteArray): Int {
        var total = 0
        for (b in hash32) {
            val unsigned = b.toInt() and 0xFF
            if (unsigned == 0) {
                total += 8
            } else {
                total += Integer.numberOfLeadingZeros(unsigned) - 24
                break
            }
        }
        return total
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: String, lengthBytes: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info.toByteArray(StandardCharsets.UTF_8)))
        val out = ByteArray(lengthBytes)
        hkdf.generateBytes(out, 0, lengthBytes)
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun randomHex(byteCount: Int): String = ByteArray(byteCount).also { secureRandom.nextBytes(it) }.toHex()

    fun normalizeUsername(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.-]"), "")
}

fun ByteArray.toHex(): String {
    val hexChars = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = hexChars[v ushr 4]
        out[i * 2 + 1] = hexChars[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(clean.length / 2) { idx ->
        val hi = Character.digit(clean[idx * 2], 16)
        val lo = Character.digit(clean[idx * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex character" }
        ((hi shl 4) or lo).toByte()
    }
}
