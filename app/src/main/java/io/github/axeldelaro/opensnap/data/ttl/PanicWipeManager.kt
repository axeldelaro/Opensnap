package io.github.axeldelaro.opensnap.data.ttl

import android.app.NotificationManager
import android.content.Context
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import io.github.axeldelaro.opensnap.daemon.OpenSnapDaemonService
import io.github.axeldelaro.opensnap.data.db.OpenSnapDatabase
import io.github.axeldelaro.opensnap.data.media.EncryptedMediaVault
import io.github.axeldelaro.opensnap.data.media.SecureFileShredder
import java.io.File

/**
 * Duress PIN (Code PIN Leurre) & Emergency Panic Wipe Engine.
 * Irreversibly overwrites SQLCipher database files, encrypted media vault, cache,
 * and destroys all StrongBox / TEE Android Keystore keys within milliseconds.
 */
object PanicWipeManager {

    fun executeEmergencyWipe(context: Context, notifyDaemon: Boolean = true) {
        val appContext = context.applicationContext

        // 1. Dismiss all system notifications immediately
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancelAll()
        } catch (_: Exception) {
        }

        // 2. Signal `:network_daemon` process to sever WSS/WebRTC tunnels & wipe its own DB handle
        if (notifyDaemon) {
            OpenSnapDaemonService.dispatchPanicWipeIntent(appContext)
        }

        // 3. Close local Room/SQLCipher instance and shred `.db`, `-wal`, and `-shm` files
        OpenSnapDatabase.closeAndResetInstance()
        val mainDbFile = appContext.getDatabasePath(OpenSnapDatabase.DB_NAME)
        val walFile = File("${mainDbFile.absolutePath}-wal")
        val shmFile = File("${mainDbFile.absolutePath}-shm")
        val journalFile = File("${mainDbFile.absolutePath}-journal")
        SecureFileShredder.shredFile(walFile)
        SecureFileShredder.shredFile(shmFile)
        SecureFileShredder.shredFile(journalFile)
        SecureFileShredder.shredFile(mainDbFile)

        // 4. Cryptographically shred all 24h encrypted voice/photo blobs
        EncryptedMediaVault.shredEntireVault(appContext)

        // 5. Destroy StrongBox/TEE hardware master key and wrapped session files
        HardwareKeystoreManager.destroyAllKeystoreMaterial(appContext)

        // 6. Shred temporary caches
        appContext.cacheDir.listFiles()?.forEach { SecureFileShredder.shredFile(it) }
    }
}
