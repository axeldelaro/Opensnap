package io.github.axeldelaro.opensnap.crypto

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import io.github.axeldelaro.opensnap.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Runtime APK Integrity & Tamper Detection Guard:
 * - Computes APK signing certificate SHA-256 fingerprint
 * - Detects active debugger attachment
 * - Scans `/proc/self/maps` for dynamic instrumentation frameworks (Frida, Xposed, Substrate)
 * - Reports StrongBox / TEE hardware Keystore status
 */
object ApkIntegrityGuard {

    data class IntegrityReport(
        val signingCertSha256Hex: String,
        val isDebuggerAttached: Boolean,
        val isInstrumentationDetected: Boolean,
        val isReleaseSignedBuild: Boolean,
        val isStrongBoxHardwareBacked: Boolean,
        val isSecureFlagActive: Boolean
    ) {
        val isHealthy: Boolean
            get() = !isDebuggerAttached && !isInstrumentationDetected && signingCertSha256Hex.isNotBlank()
    }

    fun inspect(context: Context, secureFlagActive: Boolean = true): IntegrityReport {
        val certHash = getApkSigningCertSha256(context)
        val debugger = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val hooked = detectRuntimeInstrumentation()
        val isDebuggableFlag = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        return IntegrityReport(
            signingCertSha256Hex = certHash,
            isDebuggerAttached = if (BuildConfig.DEBUG) false else debugger,
            isInstrumentationDetected = hooked,
            isReleaseSignedBuild = !isDebuggableFlag,
            isStrongBoxHardwareBacked = HardwareKeystoreManager.isStrongBoxAvailable(context),
            isSecureFlagActive = secureFlagActive
        )
    }

    @Suppress("DEPRECATION")
    fun getApkSigningCertSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo
                val sigs = if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
                sigs?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            } ?: return ""

            MessageDigest.getInstance("SHA-256").digest(certBytes).toHex()
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectRuntimeInstrumentation(): Boolean {
        val suspiciousMarkers = listOf("frida-agent", "frida-gadget", "xposedbridge", "libsubstrate")
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists() || !mapsFile.canRead()) return false
            mapsFile.useLines { lines ->
                lines.any { line ->
                    val lower = line.lowercase()
                    suspiciousMarkers.any { marker -> lower.contains(marker) }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
