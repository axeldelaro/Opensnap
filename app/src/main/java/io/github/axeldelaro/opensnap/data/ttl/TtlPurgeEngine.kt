package io.github.axeldelaro.opensnap.data.ttl

import android.content.Context
import io.github.axeldelaro.opensnap.data.db.OpenSnapDatabase
import io.github.axeldelaro.opensnap.data.media.EncryptedMediaVault
import io.github.axeldelaro.opensnap.data.media.SecureFileShredder
import java.io.File

/**
 * Strict 24-Hour Cryptographic TTL Purge Engine.
 * Continuously enforces the 24h00m00s retention ceiling across SQLCipher tables and encrypted media files:
 * - Cryptographically overwrites and unlinks expired voice/photo `.osenc` files
 * - Deletes expired `messages_24h`, `ratchet_keys`, and `seen_packets` rows
 * -Shreds any orphaned `.osenc` files older than 24h00m00s
 */
object TtlPurgeEngine {

    const val TTL_24H_MS: Long = 24L * 60L * 60L * 1000L // 86,400,000 ms (exact 24h00)
    const val TTL_24H_SEC: Long = 86_400L

    suspend fun purgeExpiredContent(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val db = OpenSnapDatabase.getInstance(context)
        val dao = db.dao()

        // 1. Locate all messages that have reached or exceeded 24h00m00s
        val expiredMessages = dao.getExpiredMessages(nowMs)
        if (expiredMessages.isNotEmpty()) {
            expiredMessages.forEach { msg ->
                EncryptedMediaVault.shredMediaFile(msg.encryptedMediaPath)
            }
            dao.deleteMessagesByIds(expiredMessages.map { it.messageId })
        }

        // 2. Purge expired Forward Secrecy ratchet keys and Anti-Replay nonces
        dao.deleteExpiredRatchetKeys(nowMs)
        dao.deleteExpiredSeenPackets(nowMs)

        // 3. Sweep vault directory for any orphaned encrypted media file older than 24h00m00s
        val vaultDir = EncryptedMediaVault.vaultDirectory(context)
        vaultDir.listFiles()?.forEach { file ->
            if (nowMs - file.lastModified() >= TTL_24H_MS) {
                SecureFileShredder.shredFile(file)
            }
        }

        return expiredMessages.size
    }

    /**
     * Immediately shreds a View-Once photo snap once the recipient closes the full-screen viewer.
     */
    suspend fun consumeAndShredViewOnceSnap(context: Context, messageId: String) {
        val dao = OpenSnapDatabase.getInstance(context).dao()
        val msg = dao.getMessageById(messageId) ?: return
        EncryptedMediaVault.shredMediaFile(msg.encryptedMediaPath)
        dao.markViewOnceConsumed(messageId)
    }

    /**
     * Formats remaining time before 24h expiration (e.g. `23h 42m` or `08m 14s`).
     */
    fun formatRemainingCountdown(expiresAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val remainingSec = ((expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L)
        val hours = remainingSec / 3600L
        val minutes = (remainingSec % 3600L) / 60L
        val seconds = remainingSec % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
            minutes > 0L -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "${seconds}s"
        }
    }
}
