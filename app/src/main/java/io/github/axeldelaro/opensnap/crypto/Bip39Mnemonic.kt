package io.github.axeldelaro.opensnap.crypto

import android.content.Context
import io.github.axeldelaro.opensnap.R
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Complete BIP-39 12-word recovery phrase generator, checksum validator, and 512-bit seed deriver.
 */
object Bip39Mnemonic {

    @Volatile
    private var cachedWords: List<String>? = null
    private val secureRandom = SecureRandom()

    fun loadWordList(context: Context? = null): List<String> {
        cachedWords?.let { return it }
        synchronized(this) {
            cachedWords?.let { return it }
            val lines = if (context != null) {
                context.resources.openRawResource(R.raw.bip39_english).bufferedReader().use {
                    it.readLines().map(String::trim).filter(String::isNotEmpty)
                }
            } else {
                val stream = Bip39Mnemonic::class.java.classLoader
                    ?.getResourceAsStream("res/raw/bip39_english.txt")
                    ?: java.io.File("src/main/res/raw/bip39_english.txt")
                        .takeIf { it.exists() }?.inputStream()
                    ?: java.io.File("app/src/main/res/raw/bip39_english.txt")
                        .takeIf { it.exists() }?.inputStream()
                    ?: error("BIP-39 English wordlist not found")
                stream.bufferedReader().use {
                    it.readLines().map(String::trim).filter(String::isNotEmpty)
                }
            }
            check(lines.size == 2048) { "BIP-39 wordlist must contain 2048 words, got ${lines.size}" }
            cachedWords = lines
            return lines
        }
    }

    /**
     * Generates a fresh cryptographically random 12-word BIP-39 mnemonic (128-bit entropy + 4-bit checksum).
     */
    fun generate12Words(context: Context? = null): List<String> {
        val entropy16 = ByteArray(16).also { secureRandom.nextBytes(it) }
        return entropyToMnemonic(entropy16, loadWordList(context))
    }

    fun entropyToMnemonic(entropy16: ByteArray, wordList: List<String>): List<String> {
        require(entropy16.size == 16) { "12-word BIP-39 requires 16 bytes (128 bits) of entropy" }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(entropy16)
        val bits = BooleanArray(132)
        for (i in 0 until 128) {
            bits[i] = ((entropy16[i / 8].toInt() ushr (7 - (i % 8))) and 1) == 1
        }
        // Append first 4 checksum bits of SHA-256(entropy)
        for (i in 0 until 4) {
            bits[128 + i] = ((sha256[0].toInt() ushr (7 - i)) and 1) == 1
        }

        return (0 until 12).map { wordIdx ->
            var index = 0
            for (b in 0 until 11) {
                index = (index shl 1) or if (bits[wordIdx * 11 + b]) 1 else 0
            }
            wordList[index]
        }
    }

    /**
     * Validates a 12-word BIP-39 mnemonic (both dictionary membership and 4-bit SHA-256 checksum).
     */
    fun validateMnemonic(phrase: String, context: Context? = null): Boolean {
        val words = normalizeWords(phrase)
        if (words.size != 12) return false
        val dict = loadWordList(context)
        val wordToIndex = dict.withIndex().associate { it.value to it.index }

        val bits = BooleanArray(132)
        words.forEachIndexed { idx, w ->
            val wordIndex = wordToIndex[w] ?: return false
            for (b in 0 until 11) {
                bits[idx * 11 + b] = ((wordIndex ushr (10 - b)) and 1) == 1
            }
        }

        val entropy = ByteArray(16)
        for (i in 0 until 128) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(entropy)
        for (i in 0 until 4) {
            val expected = ((sha256[0].toInt() ushr (7 - i)) and 1) == 1
            if (bits[128 + i] != expected) return false
        }
        return true
    }

    /**
     * Standard BIP-39 PBKDF2-HMAC-SHA512 64-byte seed derivation.
     */
    fun mnemonicToSeed64(phrase: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = Normalizer.normalize(
            normalizeWords(phrase).joinToString(" "),
            Normalizer.Form.NFKD
        )
        val normalizedSalt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
        val spec = PBEKeySpec(
            normalizedMnemonic.toCharArray(),
            normalizedSalt.toByteArray(Charsets.UTF_8),
            2048,
            512
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    fun normalizeWords(phrase: String): List<String> =
        phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
