package io.github.axeldelaro.opensnap.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager

/**
 * Automatically relaunches the OpenSnap push daemon (`:network_daemon`) immediately upon:
 * - Device boot (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`)
 * - Self-update APK installation (`MY_PACKAGE_REPLACED`)
 * - Airplane mode exit / 4G-5G-Wi-Fi restoration (`CONNECTIVITY_CHANGE`)
 */
class BootAndNetworkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        if (!HardwareKeystoreManager.hasActiveAccount(appContext)) return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                OpenSnapDaemonService.ensureRunning(appContext)
            }

            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                val restoreIntent = Intent(appContext, OpenSnapDaemonService::class.java).apply {
                    this.action = OpenSnapDaemonService.ACTION_NETWORK_RESTORED
                }
                try {
                    ContextCompat.startForegroundService(appContext, restoreIntent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
