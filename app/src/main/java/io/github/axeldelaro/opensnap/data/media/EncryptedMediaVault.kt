package io.github.axeldelaro.opensnap.data.media

import android.content.Context
import android.media.MediaDataSource
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.crypto.XChaCha20Poly1305
import io.github.axeldelaro.opensnap.crypto.hexToBytes
import io.github.axeldelaro.opensnap.crypto.toHex
import java.io.File
import kotlin.math.min

/**
 * Local 24h Encrypted Media Vault.
 * Every voice note and photo snap stored locally is encrypted with its own unique 256-bit
 * XChaCha20-Poly1305 key. Decryption happens strictly in RAM (including `InMemoryMediaDataSource`
 * for Android `MediaPlayer`), ensuring zero plaintext audio or image files ever touch flash storage.
 */
object EncryptedMediaVault {

    private const val MEDIA_DIR = "encrypted_media_24h"

    data class StoredEncryptedMedia(
        val absolutePath: String,
        val mediaKeyHex: String
    )

    fun vaultDirectory(context: Context): File =
        File(context.noBackupFilesDir, MEDIA_DIR).apply { mkdirs() }

    /**
     * Encrypts [plaintextBytes] with a fresh per-file XChaCha20-Poly1305 key and writes `.osenc` to vault.
     */
    fun storeEncryptedMedia(
        context: Context,
        messageId: String,
        plaintextBytes: ByteArray
    ): StoredEncryptedMedia {
        val mediaKey = XChaCha20Poly1305.randomKey()
        val aad = "opensnap:media:v1:$messageId".encodeToByteArray()
        val sealed = XChaCha20Poly1305.sealWithRandomNonce(mediaKey, plaintextBytes, aad)
        val safeFileName = "${CryptoEngine.sha256(messageId.encodeToByteArray()).toHex()}.osenc"
        val targetFile = File(vaultDirectory(context), safeFileName)
        targetFile.outputStream().use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        val keyHex = mediaKey.toHex()
        mediaKey.fill(0)
        return StoredEncryptedMedia(
            absolutePath = targetFile.absolutePath,
            mediaKeyHex = keyHex
        )
    }

    /**
     * Decrypts a vault `.osenc` file directly into a RAM byte array.
     */
    fun readDecryptedMediaBytes(
        messageId: String,
        encryptedPath: String?,
        mediaKeyHex: String?
    ): ByteArray? {
        if (encryptedPath.isNullOrBlank() || mediaKeyHex.isNullOrBlank()) return null
        val file = File(encryptedPath)
        if (!file.exists()) return null
        return try {
            val key = mediaKeyHex.hexToBytes()
            val aad = "opensnap:media:v1:$messageId".encodeToByteArray()
            try {
                XChaCha20Poly1305.openCombined(key, file.readBytes(), aad)
            } finally {
                key.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates a zero-disk-footprint Android `MediaDataSource` over in-memory decrypted audio bytes.
     */
    fun createRamAudioDataSource(decryptedAudioBytes: ByteArray): MediaDataSource {
        return object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= decryptedAudioBytes.size) return -1
                val available = decryptedAudioBytes.size - position.toInt()
                val toCopy = min(size, available)
                System.arraycopy(decryptedAudioBytes, position.toInt(), buffer, offset, toCopy)
                return toCopy
            }

            override fun getSize(): Long = decryptedAudioBytes.size.toLong()

            override fun close() {
                // Keep buffer intact for replay until player releases or screen disposes
            }
        }
    }

    fun shredMediaFile(encryptedPath: String?) {
        if (!encryptedPath.isNullOrBlank()) {
            SecureFileShredder.shredFile(File(encryptedPath))
        }
    }

    fun shredEntireVault(context: Context) {
        SecureFileShredder.shredFile(vaultDirectory(context))
    }
}
