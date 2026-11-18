package io.github.axeldelaro.opensnap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * AES-256 SQLCipher Encrypted Local Room Database.
 * Configured with `enableMultiInstanceInvalidation()` and WAL mode so both `:network_daemon`
 * and the main UI process share instantaneous reactive database updates safely.
 */
@Database(
    entities = [
        FriendEntity::class,
        MessageEntity::class,
        RatchetKeyEntity::class,
        SeenPacketEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OpenSnapDatabase : RoomDatabase() {

    abstract fun dao(): OpenSnapDao

    companion object {
        const val DB_NAME = "opensnap_24h_cipher.db"

        @Volatile
        private var instance: OpenSnapDatabase? = null

        @Volatile
        private var sqlCipherLoaded = false

        private fun ensureSqlCipherNativeLoaded() {
            if (!sqlCipherLoaded) {
                synchronized(this) {
                    if (!sqlCipherLoaded) {
                        System.loadLibrary("sqlcipher")
                        sqlCipherLoaded = true
                    }
                }
            }
        }

        fun getInstance(context: Context): OpenSnapDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                ensureSqlCipherNativeLoaded()
                val appContext = context.applicationContext
                val passphrase = HardwareKeystoreManager.getOrCreateSqlCipherPassphrase(appContext)
                // SupportOpenHelperFactory clears passphrase copy when configured; pass fresh copy
                val factory = SupportOpenHelperFactory(passphrase.copyOf())
                passphrase.fill(0)

                val db = Room.databaseBuilder(
                    appContext,
                    OpenSnapDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(factory)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .enableMultiInstanceInvalidation()
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // Enforce cryptographic overwrite of deleted pages inside SQLCipher
                            db.query("PRAGMA secure_delete = ON;").close()
                            db.query("PRAGMA foreign_keys = ON;").close()
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()

                instance = db
                db
            }
        }

        @Synchronized
        fun closeAndResetInstance() {
            try {
                instance?.close()
            } catch (_: Exception) {
            } finally {
                instance = null
            }
        }
    }
}
