package io.github.axeldelaro.opensnap.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.daemon.DaemonIpcClient
import io.github.axeldelaro.opensnap.data.db.FriendEntity
import io.github.axeldelaro.opensnap.network.protocol.PresenceKind
import io.github.axeldelaro.opensnap.ui.components.IdenticonAvatar
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import io.github.axeldelaro.opensnap.ui.theme.VerifiedEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeFriendsScreen(
    identity: CryptoEngine.SovereignIdentityBundle,
    daemonStatus: DaemonIpcClient.DaemonLiveStatus,
    friends: List<FriendEntity>,
    incomingRequests: List<FriendEntity>,
    outgoingRequests: List<FriendEntity>,
    presenceMap: Map<String, DaemonIpcClient.PeerPresenceEvent>,
    isBatteryExempt: Boolean,
    isBusy: Boolean,
    onOpenChat: (FriendEntity) -> Unit,
    onOpenQuickSnap: (FriendEntity) -> Unit,
    onAcceptRequest: (FriendEntity) -> Unit,
    onRejectRequest: (FriendEntity) -> Unit,
    onSendFriendInvite: (String, () -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onLockVault: () -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var dismissedBatteryHint by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianBg),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "OpenSnap",
                                fontWeight = FontWeight.ExtraBold,
                                color = SnapElectricYellow
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ObsidianCard)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                val dot = if (daemonStatus.connectedRelays > 0) "🟢" else "🟡"
                                Text(
                                    text = "$dot ${daemonStatus.connectedRelays}/${daemonStatus.totalRelays} WSS",
                                    fontSize = 11.sp,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Text(
                            text = "${identity.displayName} • ${identity.openSnapTag}",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.QrCode2, contentDescription = "Mon QR Code", tint = CyberCyan)
                    }
                    IconButton(onClick = onLockVault) {
                        Icon(Icons.Default.Lock, contentDescription = "Verrouiller", tint = SnapElectricYellow)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres & Sécurité", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = SnapElectricYellow,
                contentColor = ObsidianBg,
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Ajouter / QR", fontWeight = FontWeight.ExtraBold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Guided Doze Battery Optimization Exemption Banner (Android 13/14/15+)
            if (!isBatteryExempt && !dismissedBatteryHint) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(ObsidianCard)
                            .border(1.dp, SnapElectricYellow.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = SnapElectricYellow)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Notifications Instantanées Écran Éteint",
                                fontWeight = FontWeight.Bold,
                                color = SnapElectricYellow,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { dismissedBatteryHint = true }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Masquer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Désactivez la veille Doze Android sur OpenSnap pour garantir une latence < 300 ms même après des heures écran éteint.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
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
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Autoriser l'exécution temps réel sans restriction", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }
                }
            }

            // Incoming Friend Requests Section
            if (incomingRequests.isNotEmpty()) {
                item {
                    Text(
                        text = "Demandes d'amis reçues (${incomingRequests.size})",
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan,
                        fontSize = 13.5.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(incomingRequests, key = { "in_${it.ed25519PubHex}" }) { req ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(ObsidianCard)
                            .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IdenticonAvatar(ed25519PubHex = req.ed25519PubHex, displayName = req.displayName, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(req.displayName, fontWeight = FontWeight.Bold)
                            Text(req.openSnapTag, fontSize = 12.sp, color = CyberCyan)
                        }
                        IconButton(
                            onClick = { onAcceptRequest(req) },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(VerifiedEmerald)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Accepter", tint = ObsidianBg)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { onRejectRequest(req) },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(FlameCoral.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Refuser", tint = FlameCoral)
                        }
                    }
                }
            }

            // Active 24h Conversations & Friends
            item {
                Text(
                    text = "Discussions Éphémères 24h (${friends.size})",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (friends.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(ObsidianCard)
                            .padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Liste blanche cryptographique active",
                            fontWeight = FontWeight.Bold,
                            color = SnapElectricYellow,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Seuls vos amis mutuellement acceptés peuvent vous envoyer un Snap, un vocal ou déclencher une notification. Partagez votre Tag ${identity.openSnapTag} ou scannez un QR Code pour commencer.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(friends, key = { it.ed25519PubHex }) { friend ->
                    val presence = presenceMap[friend.ed25519PubHex]
                    val isRecentOnline = (presence != null && System.currentTimeMillis() - presence.updatedAtMs < 90_000L &&
                        presence.presenceState != PresenceKind.IDLE) ||
                        daemonStatus.fastLanePeerHexes.contains(friend.ed25519PubHex)

                    val liveSubtitle = when (presence?.presenceState) {
                        PresenceKind.TYPING -> "✍️ En train d'écrire..."
                        PresenceKind.RECORDING_VOICE -> "🎤 Enregistre un message vocal..."
                        else -> friend.lastMessagePreview.ifBlank { "Appuyez pour discuter (TTL 24h)" }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(ObsidianCard)
                            .clickable { onOpenChat(friend) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IdenticonAvatar(
                            ed25519PubHex = friend.ed25519PubHex,
                            displayName = friend.displayName,
                            size = 50.dp,
                            isOnline = isRecentOnline
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = friend.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (friend.isSafetyVerified) {
                                    Spacer(Modifier.width(5.dp))
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = "Empreinte vérifiée",
                                        tint = VerifiedEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = liveSubtitle,
                                fontSize = 13.sp,
                                color = if (presence?.presenceState == PresenceKind.TYPING ||
                                    presence?.presenceState == PresenceKind.RECORDING_VOICE
                                ) {
                                    CyberCyan
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (friend.unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(SnapElectricYellow)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = friend.unreadCount.toString(),
                                    color = ObsidianBg,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }

                        IconButton(
                            onClick = { onOpenQuickSnap(friend) },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ObsidianBg)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Envoyer un Snap", tint = SnapElectricYellow)
                        }
                    }
                }
            }

            if (outgoingRequests.isNotEmpty()) {
                item {
                    Text(
                        text = "Invitations en attente (${outgoingRequests.size})",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(outgoingRequests, key = { "out_${it.ed25519PubHex}" }) { pending ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianCard.copy(alpha = 0.65f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IdenticonAvatar(ed25519PubHex = pending.ed25519PubHex, displayName = pending.displayName, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pending.displayName, fontWeight = FontWeight.SemiBold)
                            Text("En attente d'acceptation • ${pending.openSnapTag}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onRejectRequest(pending) }) {
                            Icon(Icons.Default.Close, contentDescription = "Annuler", tint = FlameCoral)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddDialog) {
        AddFriendDialog(
            identity = identity,
            isBusy = isBusy,
            onDismiss = { showAddDialog = false },
            onSubmitTagOrInvite = { input ->
                onSendFriendInvite(input) {
                    showAddDialog = false
                }
            }
        )
    }
}
