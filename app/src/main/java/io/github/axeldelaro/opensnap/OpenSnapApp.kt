package io.github.axeldelaro.opensnap

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import io.github.axeldelaro.opensnap.daemon.NotificationHelper
import io.github.axeldelaro.opensnap.daemon.OpenSnapDaemonService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Multi-Process Aware Application Entry Point (`io.github.axeldelaro.opensnap` & `:network_daemon`).
 */
class OpenSnapApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Ensure modern BouncyCastle provider is available without overriding AndroidKeyStore
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        } catch (_: Exception) {
        }

        NotificationHelper.ensureChannelsCreated(this)

        // Only bootstrap the daemon from the primary UI process to avoid recursive process spawns
        if (!isDaemonProcess() && HardwareKeystoreManager.hasActiveAccount(this)) {
            OpenSnapDaemonService.ensureRunning(this)
        }
    }

    private fun isDaemonProcess(): Boolean {
        val currentName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = Process.myPid()
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
        }
        return currentName.endsWith(":network_daemon")
    }
}
