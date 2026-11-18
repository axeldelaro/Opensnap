package io.github.axeldelaro.opensnap.data.media

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlin.math.min

/**
 * Cryptographic File Shredder for strict 24h TTL expiration, View-Once snaps, and Panic Wipe.
 * Overwrites flash blocks with cryptographically random bytes followed by zeros and `fsync()`
 * before unlinking the file descriptor.
 */
object SecureFileShredder {

    private val secureRandom = SecureRandom()

    fun shredFile(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> shredFile(child) }
            file.delete()
            return
        }

        try {
            val length = file.length()
            if (length > 0L) {
                val buffer = ByteArray(8192)
                RandomAccessFile(file, "rws").use { raf ->
                    // Pass 1: CSPRNG random overwrite
                    raf.seek(0L)
                    var remaining = length
                    while (remaining > 0L) {
                        val toWrite = min(buffer.size.toLong(), remaining).toInt()
                        secureRandom.nextBytes(buffer)
                        raf.write(buffer, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()

                    // Pass 2: Zero-fill overwrite
                    buffer.fill(0)
                    raf.seek(0L)
                    remaining = length
                    while (remaining > 0L) {
                        val toWrite = min(buffer.size.toLong(), remaining).toInt()
                        raf.write(buffer, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.setLength(0L)
                    raf.fd.sync()
                }
            }
        } catch (_: Exception) {
            // Best-effort block overwrite before mandatory unlink
        } finally {
            file.delete()
        }
    }
}
