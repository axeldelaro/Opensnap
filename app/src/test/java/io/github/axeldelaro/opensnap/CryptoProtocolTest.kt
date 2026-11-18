package io.github.axeldelaro.opensnap

import io.github.axeldelaro.opensnap.crypto.Bip340Schnorr
import io.github.axeldelaro.opensnap.crypto.Bip39Mnemonic
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.XChaCha20Poly1305
import io.github.axeldelaro.opensnap.crypto.hexToBytes
import io.github.axeldelaro.opensnap.crypto.toHex
import io.github.axeldelaro.opensnap.network.protocol.ChatTextPayload
import io.github.axeldelaro.opensnap.network.protocol.NostrModels
import io.github.axeldelaro.opensnap.network.protocol.OpenSnapPacketCodec
import io.github.axeldelaro.opensnap.network.protocol.PacketType
import io.github.axeldelaro.opensnap.network.protocol.VaultFriendEntry
import io.github.axeldelaro.opensnap.network.relay.NetworkConfigVerifier
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class CryptoProtocolTest {

    @Test
    fun testHChaCha20AndXChaCha20Poly1305RfcVector() {
        // Official IETF draft-irtf-cfrg-xchacha Section 2.2.1 HChaCha20 Test Vector
        val key = ("000102030405060708090a0b0c0d0e0f" +
            "101112131415161718191a1b1c1d1e1f").hexToBytes()
        val nonce16 = "000000090000004a0000000031415927".hexToBytes()
        val expectedSubKey = ("82413b4227b27bfed30e42508a877d73" +
            "a0f9e4d58a74a853c12ec41326d3ecdc").hexToBytes()

        val actualSubKey = XChaCha20Poly1305.hChaCha20(key, nonce16)
        assertArrayEquals(expectedSubKey, actualSubKey)

        // Official Section 2.3.1 XChaCha20-Poly1305 AEAD Test Vector
        val aeadKey = ("808182838485868788898a8b8c8d8e8f" +
            "909192939495969798999a9b9c9d9e9f").hexToBytes()
        val aeadNonce24 = "404142434445464748494a4b4c4d4e4f5051525354555657".hexToBytes()
        val aad = "50515253c0c1c2c3c4c5c6c7".hexToBytes()
        val plaintext = ("Ladies and Gentlemen of the class of '99: " +
            "If I could offer you only one tip for the future, sunscreen would be it.")
            .toByteArray(StandardCharsets.UTF_8)

        val encrypted = XChaCha20Poly1305.encrypt(aeadKey, aeadNonce24, plaintext, aad)
        val expectedCiphertextPrefix = ("bd6d179d3e83d43b9576579493c0e939" +
            "572a1700252bfaccbed2902c21396cbb").hexToBytes()
        assertArrayEquals(expectedCiphertextPrefix, encrypted.copyOfRange(0, 32))

        val decrypted = XChaCha20Poly1305.decrypt(aeadKey, aeadNonce24, encrypted, aad)
        assertArrayEquals(plaintext, decrypted)

        // Tamper detection check
        val tampered = encrypted.copyOf().also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
        val rejected = runCatching { XChaCha20Poly1305.decrypt(aeadKey, aeadNonce24, tampered, aad) }.isFailure
        assertTrue("Modified ciphertext must fail Poly1305 authentication", rejected)
    }

    @Test
    fun testBip340SchnorrSignAndVerify() {
        val kp = Bip340Schnorr.generateKeyPair()
        val msg32 = CryptoEngine.sha256("opensnap-nostr-nip59-test".toByteArray())
        val sig = Bip340Schnorr.sign(msg32, kp.privateKey32)
        assertEquals(64, sig.size)
        assertTrue(Bip340Schnorr.verify(msg32, kp.publicKeyXOnly32, sig))

        val wrongMsg = CryptoEngine.sha256("forged-message".toByteArray())
        assertFalse(Bip340Schnorr.verify(wrongMsg, kp.publicKeyXOnly32, sig))
    }

    @Test
    fun testBip39MnemonicGenerationValidationAndDeterministicArgon2id() {
        val words = Bip39Mnemonic.generate12Words()
        assertEquals(12, words.size)
        val phrase = words.joinToString(" ")
        assertTrue(Bip39Mnemonic.validateMnemonic(phrase))

        // Corrupt last word -> checksum should fail (or test known invalid phrase)
        assertFalse(Bip39Mnemonic.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zoo"))
        assertTrue(Bip39Mnemonic.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))

        // Deterministic Argon2id across 2 simulated devices
        val deviceA = CryptoEngine.deriveSovereignIdentity(
            username = "Alice_Cyber",
            displayName = "Alice",
            passwordOrEmpty = "",
            mnemonic12Words = phrase,
            memoryKiB = 8_192,
            iterations = 2
        )
        val deviceB = CryptoEngine.deriveSovereignIdentity(
            username = "alice_cyber",
            displayName = "Alice New Phone",
            passwordOrEmpty = "",
            mnemonic12Words = phrase,
            memoryKiB = 8_192,
            iterations = 2
        )

        assertEquals(deviceA.ed25519PublicHex, deviceB.ed25519PublicHex)
        assertEquals(deviceA.x25519PublicHex, deviceB.x25519PublicHex)
        assertEquals(deviceA.routingPublicHex, deviceB.routingPublicHex)
        assertEquals(deviceA.openSnapTag, deviceB.openSnapTag)
        assertTrue(CryptoEngine.verifySelfCertifyingTag(deviceA.ed25519PublicHex, deviceA.openSnapTag))
    }

    @Test
    fun testEndToEndGiftWrapForwardSecrecyAndVaultBackup() {
        val alice = CryptoEngine.deriveSovereignIdentity(
            username = "alice",
            displayName = "Alice",
            passwordOrEmpty = "UltraStrongPass!2026",
            memoryKiB = 8_192,
            iterations = 2
        )
        val bob = CryptoEngine.deriveSovereignIdentity(
            username = "bob",
            displayName = "Bob",
            passwordOrEmpty = "BobSovereignKey#99",
            memoryKiB = 8_192,
            iterations = 2
        )

        // Contact invite URI verification
        val bobCard = CryptoEngine.createSignedContactCard(bob)
        val uri = CryptoEngine.encodeContactInviteUri(bobCard)
        val decodedCard = CryptoEngine.decodeContactInviteUri(uri)
        assertNotNull(decodedCard)
        assertEquals(bob.openSnapTag, decodedCard!!.openSnapTag)

        // Symmetric 60-digit Safety Number check
        assertEquals(
            CryptoEngine.computeSafetyNumber(alice.ed25519PublicHex, bob.ed25519PublicHex),
            CryptoEngine.computeSafetyNumber(bob.ed25519PublicHex, alice.ed25519PublicHex)
        )

        // Bob advertises a rotating Forward Secrecy X25519 ratchet key
        val (bobRatchetPriv, bobRatchetPub) = CryptoEngine.generateRatchetX25519KeyPair()

        // Alice builds, signs, seals, and NIP-59 Gift-Wraps a 24h message for Bob with NIP-13 PoW
        val bodyJson = CryptoEngine.canonicalJson.encodeToString(
            ChatTextPayload(text = "Salut Bob ! Message éphémère 24h E2EE.")
        )
        val innerPacket = OpenSnapPacketCodec.buildAndSignPacket(
            identity = alice,
            packetType = PacketType.CHAT_TEXT,
            bodyJson = bodyJson
        )
        val sealedEnvelopeJson = OpenSnapPacketCodec.encryptSignedPacketForRecipient(
            packet = innerPacket,
            recipientX25519PubHex = bob.x25519PublicHex,
            recipientRatchetPubHex = bobRatchetPub
        )
        val giftWrapEvent = NostrModels.buildGiftWrap24hEvent(
            recipientRoutingPubHex = bob.routingPublicHex,
            sealedInnerEnvelopeJson = sealedEnvelopeJson,
            powBits = 8
        )

        // Outer Nostr relay verification (NIP-01 + NIP-13 PoW + NIP-40 24h TTL + Sealed Sender)
        assertTrue(NostrModels.verifyOuterEvent(giftWrapEvent, minPowBits = 8))
        assertFalse(
            "NIP-59 Gift Wrap outer pubkey must be a random one-time key, never Alice's key",
            giftWrapEvent.pubkey.equals(alice.routingPublicHex, ignoreCase = true)
        )

        // Eve cannot decrypt Bob's Gift Wrap
        val eveAttempt = OpenSnapPacketCodec.decryptAndVerifyPacket(
            sealedEnvelopeJson = giftWrapEvent.content,
            myStaticX25519PrivHex = alice.x25519PrivateHex,
            myRatchetPrivHexes = emptyList()
        )
        assertNull(eveAttempt)

        // Bob decrypts using his static + rotating ratchet key and verifies Alice's Ed25519 signature
        val decryptedPacket = OpenSnapPacketCodec.decryptAndVerifyPacket(
            sealedEnvelopeJson = giftWrapEvent.content,
            myStaticX25519PrivHex = bob.x25519PrivateHex,
            myRatchetPrivHexes = listOf(bobRatchetPriv)
        )
        assertNotNull(decryptedPacket)
        assertEquals(alice.ed25519PublicHex, decryptedPacket!!.senderEd25519PubHex)
        val chat = CryptoEngine.canonicalJson.decodeFromString<ChatTextPayload>(decryptedPacket.bodyJson)
        assertEquals("Salut Bob ! Message éphémère 24h E2EE.", chat.text)

        // Sovereign Encrypted Friend Vault Backup round-trip
        val backupB64 = OpenSnapPacketCodec.sealFriendsBackup(
            identity = alice,
            entries = listOf(
                VaultFriendEntry(
                    ed25519PubHex = bob.ed25519PublicHex,
                    x25519PubHex = bob.x25519PublicHex,
                    routingPubHex = bob.routingPublicHex,
                    openSnapTag = bob.openSnapTag,
                    username = bob.username,
                    displayName = bob.displayName,
                    status = "ACCEPTED",
                    isSafetyVerified = true
                )
            )
        )
        val restoredVault = OpenSnapPacketCodec.openFriendsBackup(alice, backupB64)
        assertNotNull(restoredVault)
        assertEquals(1, restoredVault!!.friends.size)
        assertEquals(bob.openSnapTag, restoredVault.friends.first().openSnapTag)
    }

    @Test
    fun testRepositorySignedNetworkConfigVerification() {
        val cfgFile = File("../config/network-config.json").takeIf { it.exists() }
            ?: File("config/network-config.json")
        assertTrue("config/network-config.json must exist in repository", cfgFile.exists())

        val parsed = CryptoEngine.canonicalJson.decodeFromString<NetworkConfigVerifier.SignedNetworkConfig>(
            cfgFile.readText(StandardCharsets.UTF_8)
        )
        assertTrue(
            "config/network-config.json must carry a valid Ed25519 signature matching PINNED_REPO_CONFIG_PUBKEY_ED25519_HEX",
            NetworkConfigVerifier.verifySignedConfig(parsed)
        )
    }
}
