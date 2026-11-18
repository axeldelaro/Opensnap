package io.github.axeldelaro.opensnap.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.axeldelaro.opensnap.data.media.SecureFileShredder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore (StrongBox / TEE) Vault Manager.
 * Safely wraps the SQLCipher database passphrase and active SovereignIdentityBundle so both
 * the UI process and `:network_daemon` process (sharing UID) can operate securely across reboots,
 * while supporting Unlock PIN, BiometricPrompt, and Duress PIN (Panic Wipe).
 */
object HardwareKeystoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_WRAP_KEY_ALIAS = "opensnap_hw_master_aes256_v1"
    private const val VAULT_DIR_NAME = "sovereign_vault"
    private const val SESSION_FILE_NAME = "daemon_identity.hwenc"
    private const val DB_KEY_FILE_NAME = "sqlcipher_passphrase.hwenc"
    private const val LOCK_CONFIG_FILE_NAME = "lock_policy.hwenc"

    private val secureRandom = SecureRandom()

    enum class PinCheckResult {
        VALID_PRIMARY_PIN,
        DURESS_PANIC_PIN,
        INVALID_PIN
    }

    @Serializable
    data class LockPolicyConfig(
        val pinSaltHex: String,
        val primaryPinVerifierHex: String,
        val duressPinVerifierHex: String? = null,
        val biometricEnabled: Boolean = true
    )

    fun isStrongBoxAvailable(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    @Synchronized
    private fun getOrCreateHardwareKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(MASTER_WRAP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val tryStrongBox = isStrongBoxAvailable(context)
        if (tryStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKeystoreKey(useStrongBox = true)
            } catch (_: Exception) {
                // Fallback cleanly to TEE if StrongBox key slot is full or unsupported for GCM
            }
        }
        return generateKeystoreKey(useStrongBox = false)
    }

    private fun generateKeystoreKey(useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val specBuilder = KeyGenParameterSpec.Builder(
            MASTER_WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }
        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    private fun encryptWithHardwareKey(context: Context, plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateHardwareKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        // Format: [1 byte IV length][IV bytes][Ciphertext + 128-bit GCM Tag]
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)
        return packed
    }

    private fun decryptWithHardwareKey(context: Context, packed: ByteArray): ByteArray {
        require(packed.size > 14) { "Invalid hardware-wrapped blob" }
        val ivLen = packed[0].toInt() and 0xFF
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)
        val secretKey = getOrCreateHardwareKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun vaultDir(context: Context): File =
        File(context.noBackupFilesDir, VAULT_DIR_NAME).apply { mkdirs() }

    /**
     * Retrieves or creates the hardware-wrapped 256-bit SQLCipher passphrase.
     */
    @Synchronized
    fun getOrCreateSqlCipherPassphrase(context: Context): ByteArray {
        val file = File(vaultDir(context), DB_KEY_FILE_NAME)
        if (file.exists()) {
            return try {
                decryptWithHardwareKey(context, file.readBytes())
            } catch (_: Exception) {
                // Corrupted or wiped key: regenerate cleanly
                val fresh = ByteArray(32).also { secureRandom.nextBytes(it) }
                writeAtomically(file, encryptWithHardwareKey(context, fresh))
                fresh
            }
        }
        val freshKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        writeAtomically(file, encryptWithHardwareKey(context, freshKey))
        return freshKey
    }

    /**
     * Persists the active SovereignIdentityBundle wrapped under the StrongBox/TEE AES-256-GCM key.
     */
    @Synchronized
    fun saveIdentitySession(context: Context, identity: CryptoEngine.SovereignIdentityBundle) {
        val jsonBytes = CryptoEngine.canonicalJson
            .encodeToString(identity)
            .toByteArray(StandardCharsets.UTF_8)
        val wrapped = encryptWithHardwareKey(context, jsonBytes)
        jsonBytes.fill(0)
        writeAtomically(File(vaultDir(context), SESSION_FILE_NAME), wrapped)
    }

    /**
     * Reads the active SovereignIdentityBundle (callable from both UI and `:network_daemon`).
     */
    fun loadIdentitySession(context: Context): CryptoEngine.SovereignIdentityBundle? {
        val file = File(vaultDir(context), SESSION_FILE_NAME)
        if (!file.exists()) return null
        return try {
            val plainBytes = decryptWithHardwareKey(context, file.readBytes())
            try {
                CryptoEngine.canonicalJson.decodeFromString<CryptoEngine.SovereignIdentityBundle>(
                    String(plainBytes, StandardCharsets.UTF_8)
                )
            } finally {
                plainBytes.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hasActiveAccount(context: Context): Boolean =
        File(vaultDir(context), SESSION_FILE_NAME).exists()

    /**
     * Saves the Primary PIN + optional Duress PIN (Panic Wipe) policy wrapped under Android Keystore.
     */
    @Synchronized
    fun saveLockPolicy(
        context: Context,
        primaryPin: String,
        duressPin: String?,
        biometricEnabled: Boolean
    ) {
        val cleanPrimary = primaryPin.trim()
        val cleanDuress = duressPin?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanDuress != null) {
            require(cleanDuress != cleanPrimary) {
                "Le code PIN leurre doit être différent du code PIN principal"
            }
        }

        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }.toHex()
        val primaryVerifier = hashPin(cleanPrimary, salt)
        val duressVerifier = cleanDuress?.let { hashPin(it, salt) }

        val config = LockPolicyConfig(
            pinSaltHex = salt,
            primaryPinVerifierHex = primaryVerifier,
            duressPinVerifierHex = duressVerifier,
            biometricEnabled = biometricEnabled
        )
        val bytes = CryptoEngine.canonicalJson.encodeToString(config).toByteArray(StandardCharsets.UTF_8)
        writeAtomically(File(vaultDir(context), LOCK_CONFIG_FILE_NAME), encryptWithHardwareKey(context, bytes))
    }

    fun loadLockPolicy(context: Context): LockPolicyConfig? {
        val file = File(vaultDir(context), LOCK_CONFIG_FILE_NAME)
        if (!file.exists()) return null
        return try {
            val plain = decryptWithHardwareKey(context, file.readBytes())
            CryptoEngine.canonicalJson.decodeFromString<LockPolicyConfig>(
                String(plain, StandardCharsets.UTF_8)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Constant-time evaluation of entered PIN against Primary PIN and Duress PIN (Panic Wipe).
     */
    fun verifyPin(context: Context, candidatePin: String): PinCheckResult {
        val config = loadLockPolicy(context) ?: return PinCheckResult.VALID_PRIMARY_PIN
        val candidateHash = hashPin(candidatePin.trim(), config.pinSaltHex)

        if (config.duressPinVerifierHex != null &&
            MessageDigest.isEqual(
                candidateHash.toByteArray(StandardCharsets.UTF_8),
                config.duressPinVerifierHex.toByteArray(StandardCharsets.UTF_8)
            )
        ) {
            return PinCheckResult.DURESS_PANIC_PIN
        }

        if (MessageDigest.isEqual(
                candidateHash.toByteArray(StandardCharsets.UTF_8),
                config.primaryPinVerifierHex.toByteArray(StandardCharsets.UTF_8)
            )
        ) {
            return PinCheckResult.VALID_PRIMARY_PIN
        }

        return PinCheckResult.INVALID_PIN
    }

    private fun hashPin(pin: String, saltHex: String): String {
        var digest = CryptoEngine.sha256("opensnap:pin:v1:$saltHex:$pin".toByteArray(StandardCharsets.UTF_8))
        repeat(4096) {
            digest = CryptoEngine.sha256(digest + saltHex.hexToBytes())
        }
        return digest.toHex()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    /**
     * Destroys Android Keystore keys and cryptographically shreds all wrapped key blobs.
     */
    @Synchronized
    fun destroyAllKeystoreMaterial(context: Context) {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(MASTER_WRAP_KEY_ALIAS)) {
                ks.deleteEntry(MASTER_WRAP_KEY_ALIAS)
            }
        } catch (_: Exception) {
        }
        val dir = vaultDir(context)
        dir.listFiles()?.forEach { file ->
            SecureFileShredder.shredFile(file)
        }
        dir.delete()
    }
}
