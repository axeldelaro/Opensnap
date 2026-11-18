package io.github.axeldelaro.opensnap.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * RFC draft-irtf-cfrg-xchacha compliant XChaCha20-Poly1305 AEAD implementation.
 * Uses 256-bit keys (32 bytes), 192-bit nonces (24 bytes), and 128-bit Poly1305 tags (16 bytes).
 */
object XChaCha20Poly1305 {

    const val KEY_SIZE_BYTES = 32
    const val NONCE_SIZE_BYTES = 24
    const val TAG_SIZE_BYTES = 16

    private val SIGMA = intArrayOf(
        0x61707865, // "expa"
        0x3320646e, // "nd 3"
        0x79622d32, // "2-by"
        0x6b206574  // "te k"
    )

    private val secureRandom = SecureRandom()

    fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE_BYTES).also { secureRandom.nextBytes(it) }

    fun randomKey(): ByteArray = ByteArray(KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * Encrypts [plaintext] and returns `nonce (24B) || ciphertext || tag (16B)`.
     */
    fun sealWithRandomNonce(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        val nonce = randomNonce()
        val ciphertextAndTag = encrypt(key, nonce, plaintext, associatedData)
        val combined = ByteArray(NONCE_SIZE_BYTES + ciphertextAndTag.size)
        System.arraycopy(nonce, 0, combined, 0, NONCE_SIZE_BYTES)
        System.arraycopy(ciphertextAndTag, 0, combined, NONCE_SIZE_BYTES, ciphertextAndTag.size)
        return combined
    }

    /**
     * Opens `nonce (24B) || ciphertext || tag (16B)` produced by [sealWithRandomNonce].
     */
    fun openCombined(
        key: ByteArray,
        combined: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        require(combined.size >= NONCE_SIZE_BYTES + TAG_SIZE_BYTES) {
            "Ciphertext envelope is shorter than XChaCha20-Poly1305 minimum overhead"
        }
        val nonce = combined.copyOfRange(0, NONCE_SIZE_BYTES)
        val ciphertextWithTag = combined.copyOfRange(NONCE_SIZE_BYTES, combined.size)
        return decrypt(key, nonce, ciphertextWithTag, associatedData)
    }

    /**
     * Encrypts [plaintext] using explicit 32-byte [key] and 24-byte [nonce].
     * Returns `ciphertext || tag (16B)`.
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "XChaCha20-Poly1305 requires a 32-byte key" }
        require(nonce.size == NONCE_SIZE_BYTES) { "XChaCha20-Poly1305 requires a 24-byte nonce" }

        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val ietfNonce = ByteArray(12)
        // First 4 bytes = 0x00000000, bytes 4..11 = nonce[16..23]
        System.arraycopy(nonce, 16, ietfNonce, 4, 8)

        return try {
            val cipher = ChaCha20Poly1305()
            val params = AEADParameters(KeyParameter(subKey), TAG_SIZE_BYTES * 8, ietfNonce, associatedData)
            cipher.init(true, params)
            val output = ByteArray(cipher.getOutputSize(plaintext.size))
            val len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
            cipher.doFinal(output, len)
            output
        } finally {
            subKey.fill(0)
        }
    }

    /**
     * Decrypts and authenticates `ciphertext || tag (16B)` using 32-byte [key] and 24-byte [nonce].
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray = ByteArray(0)
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "XChaCha20-Poly1305 requires a 32-byte key" }
        require(nonce.size == NONCE_SIZE_BYTES) { "XChaCha20-Poly1305 requires a 24-byte nonce" }
        require(ciphertextWithTag.size >= TAG_SIZE_BYTES) { "Missing Poly1305 authentication tag" }

        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val ietfNonce = ByteArray(12)
        System.arraycopy(nonce, 16, ietfNonce, 4, 8)

        return try {
            val cipher = ChaCha20Poly1305()
            val params = AEADParameters(KeyParameter(subKey), TAG_SIZE_BYTES * 8, ietfNonce, associatedData)
            cipher.init(false, params)
            val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
            val len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
            cipher.doFinal(output, len)
            output
        } finally {
            subKey.fill(0)
        }
    }

    /**
     * HChaCha20 subkey derivation per draft-irtf-cfrg-xchacha Section 2.2.
     */
    internal fun hChaCha20(key32: ByteArray, nonce16: ByteArray): ByteArray {
        require(key32.size == 32 && nonce16.size == 16)
        val state = IntArray(16)
        state[0] = SIGMA[0]
        state[1] = SIGMA[1]
        state[2] = SIGMA[2]
        state[3] = SIGMA[3]

        val keyBuf = ByteBuffer.wrap(key32).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 8) {
            state[4 + i] = keyBuf.int
        }

        val nonceBuf = ByteBuffer.wrap(nonce16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            state[12 + i] = nonceBuf.int
        }

        // 20 rounds (10 column-diagonal double-rounds), WITHOUT final feed-forward addition
        repeat(10) {
            // Column rounds
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        val out = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(state[0])
        out.putInt(state[1])
        out.putInt(state[2])
        out.putInt(state[3])
        out.putInt(state[12])
        out.putInt(state[13])
        out.putInt(state[14])
        out.putInt(state[15])
        state.fill(0)
        return out.array()
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }
}
