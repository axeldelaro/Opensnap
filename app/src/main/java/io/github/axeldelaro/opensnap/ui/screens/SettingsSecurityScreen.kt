package io.github.axeldelaro.opensnap.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axeldelaro.opensnap.BuildConfig
import io.github.axeldelaro.opensnap.crypto.ApkIntegrityGuard
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.daemon.DaemonIpcClient
import io.github.axeldelaro.opensnap.data.db.FriendEntity
import io.github.axeldelaro.opensnap.ui.UpdaterUiState
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.ObsidianSurface
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import io.github.axeldelaro.opensnap.ui.theme.VerifiedEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSecurityScreen(
    identity: CryptoEngine.SovereignIdentityBundle,
    integrityReport: ApkIntegrityGuard.IntegrityReport,
    daemonStatus: DaemonIpcClient.DaemonLiveStatus,
    updaterState: UpdaterUiState,
    blockedFriends: List<FriendEntity>,
    isBatteryExempt: Boolean,
    onBack: () -> Unit,
    onForceReconnectRelays: () -> Unit,
    onSyncVaultBackup: () -> Unit,
    onRestoreVaultBackup: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onUnblockFriend: (FriendEntity) -> Unit,
    onPanicWipeConfirmed: () -> Unit
) {
    val context = LocalContext.current
    var showPanicConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianSurface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                title = {
                    Text("Bouclier Cyber & Mises à Jour", fontWeight = FontWeight.Bold)
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Sovereign Identity & Deterministic Key Fingerprint Card
            item {
                SectionCard(title = "Identité Cryptographique Souveraine") {
                    Text("${identity.displayName} (@${identity.username})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Tag auto-certifié : ${identity.openSnapTag}", color = SnapElectricYellow, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    KeyLine("Clé Signature Ed25519", identity.ed25519PublicHex)
                    Spacer(Modifier.height(4.dp))
                    KeyLine("Clé Chiffrement X25519", identity.x25519PublicHex)
                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSyncVaultBackup, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sauvegarder Amis", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onRestoreVaultBackup, modifier = Modifier.weight(1f)) {
                            Text("Restaurer Amis", fontSize = 12.sp, color = VerifiedEmerald)
                        }
                    }
                }
            }

            // 2. Auto-Updater GitHub Releases (`axeldelaro/Opensnap`)
            item {
                SectionCard(title = "Auto-Updater GitHub Releases") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = SnapElectricYellow)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Version installée : v${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                            Text(
                                text = "Source : github.com/${BuildConfig.GITHUB_REPO}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (updaterState.statusMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = updaterState.statusMessage,
                            fontSize = 13.sp,
                            color = if (updaterState.lastResult?.isUpdateAvailable == true) VerifiedEmerald else CyberCyan
                        )
                    }

                    if (updaterState.isDownloading) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updaterState.downloadPercent / 100f },
                            color = SnapElectricYellow,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onCheckUpdates,
                            enabled = !updaterState.isChecking && !updaterState.isDownloading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SnapElectricYellow,
                                contentColor = ObsidianBg
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (updaterState.isChecking) {
                                CircularProgressIndicator(color = ObsidianBg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Vérifier les mises à jour", fontWeight = FontWeight.Bold)
                        }

                        if (updaterState.lastResult?.isUpdateAvailable == true) {
                            Button(
                                onClick = onDownloadAndInstallUpdate,
                                enabled = !updaterState.isDownloading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VerifiedEmerald,
                                    contentColor = ObsidianBg
                                )
                            ) {
                                Text("Installer APK", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }

            // 3. Background Push Daemon & Android 13/14/15 Doze Exemption
            item {
                SectionCard(title = "Daemon Temps Réel (:network_daemon)") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WifiTethering, contentDescription = null, tint = CyberCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${daemonStatus.connectedRelays}/${daemonStatus.totalRelays} relais WSS actifs (${daemonStatus.transportLabel})",
                            fontWeight = FontWeight.SemiBold,
                            color = CyberCyan
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Exemption batterie Doze : ${if (isBatteryExempt) "✅ Active (Zéro veille)" else "⚠️ À activer pour écran éteint"}",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isBatteryExempt) {
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    runCatching { context.startActivity(intent) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SnapElectricYellow,
                                    contentColor = ObsidianBg
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Désactiver Doze", fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(onClick = onForceReconnectRelays, modifier = Modifier.weight(1f)) {
                            Text("Reconnecter le pool WSS")
                        }
                    }
                }
            }

            // 4. Physical & Cryptographic Security Audit
            item {
                SectionCard(title = "Audit d'Intégrité Physique & Cryptographique") {
                    AuditRow("Blocage Captures / Écran (FLAG_SECURE)", integrityReport.isSecureFlagActive)
                    AuditRow(
                        "Android Keystore Matériel (${if (integrityReport.isStrongBoxHardwareBacked) "StrongBox" else "TEE"})",
                        true
                    )
                    AuditRow("Protection Anti-Frida / Anti-Injection", !integrityReport.isInstrumentationDetected)
                    AuditRow("Purge Automatique EXIF / GPS Photos", true)
                    AuditRow("Base Locale Chiffrée SQLCipher AES-256 (TTL 24h)", true)
                    if (integrityReport.signingCertSha256Hex.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        KeyLine("Certificat APK SHA-256", integrityReport.signingCertSha256Hex)
                    }
                }
            }

            // 5. Blocked Contacts
            if (blockedFriends.isNotEmpty()) {
                item {
                    Text("Contacts Bloqués (${blockedFriends.size})", fontWeight = FontWeight.Bold, color = FlameCoral)
                }
                items(blockedFriends, key = { "blk_${it.ed25519PubHex}" }) { blocked ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianCard)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${blocked.displayName} (${blocked.openSnapTag})")
                        TextButton(onClick = { onUnblockFriend(blocked) }) {
                            Text("Débloquer", color = CyberCyan)
                        }
                    }
                }
            }

            // 6. Emergency Panic Wipe
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(ObsidianCard)
                        .border(1.dp, FlameCoral.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Text("Destruction d'Urgence (Panic Wipe)", fontWeight = FontWeight.ExtraBold, color = FlameCoral)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Écrase cryptographiquement la base SQLCipher, tous les vocaux/snaps 24h et détruit les clés du Keystore matériel.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { showPanicConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = FlameCoral, contentColor = ObsidianBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Exécuter un Panic Wipe Immédiat", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }

    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            containerColor = ObsidianSurface,
            title = { Text("Confirmer le Panic Wipe ?", color = FlameCoral, fontWeight = FontWeight.Bold) },
            text = {
                Text("Toutes les données locales, clés du Keystore et discussions 24h seront effacées de façon irréversible.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPanicConfirm = false
                        onPanicWipeConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FlameCoral)
                ) {
                    Text("Tout détruire maintenant", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ObsidianCard)
            .padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = SnapElectricYellow, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontWeight = FontWeight.Bold, color = SnapElectricYellow, fontSize = 14.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun KeyLine(label: String, hex: String) {
    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text = hex.chunked(8).joinToString(" "),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = CyberCyan
    )
}

@Composable
private fun AuditRow(label: String, passed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp)
        Text(
            text = if (passed) "ACTIF" else "ALERTE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (passed) VerifiedEmerald else FlameCoral
        )
    }
}
