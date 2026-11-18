package io.github.axeldelaro.opensnap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.axeldelaro.opensnap.daemon.NotificationHelper
import io.github.axeldelaro.opensnap.ui.AppStage
import io.github.axeldelaro.opensnap.ui.OpenSnapViewModel
import io.github.axeldelaro.opensnap.ui.screens.AuthScreen
import io.github.axeldelaro.opensnap.ui.screens.ChatScreen
import io.github.axeldelaro.opensnap.ui.screens.HomeFriendsScreen
import io.github.axeldelaro.opensnap.ui.screens.LockScreen
import io.github.axeldelaro.opensnap.ui.screens.SettingsSecurityScreen
import io.github.axeldelaro.opensnap.ui.screens.SnapCameraScreen
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.OpenSnapTheme
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import kotlinx.coroutines.delay

/**
 * Main Sovereign UI Activity (`FragmentActivity` for BiometricPrompt support).
 * Enforces `FLAG_SECURE` hardware screenshot & screen-recording protection across all screens.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: OpenSnapViewModel by viewModels()
    private val pendingDeepLinkState = mutableStateOf<String?>(null)
    private val pendingOpenPeerState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Activate FLAG_SECURE before super.onCreate & setContent so zero frame is ever unprotected
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        extractNavigationExtras(intent)

        setContent {
            OpenSnapTheme {
                OpenSnapRootApp(
                    viewModel = viewModel,
                    pendingInviteUri = pendingDeepLinkState.value,
                    onConsumeInviteUri = { pendingDeepLinkState.value = null },
                    pendingPeerHex = pendingOpenPeerState.value,
                    onConsumePendingPeerHex = { pendingOpenPeerState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNavigationExtras(intent)
    }

    private fun extractNavigationExtras(intent: Intent?) {
        if (intent == null) return
        val dataUri = intent.dataString
        if (!dataUri.isNullOrBlank() && dataUri.startsWith("opensnap://add/", ignoreCase = true)) {
            pendingDeepLinkState.value = dataUri
        }
        val openPeer = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_CHAT_PEER_HEX)
        if (!openPeer.isNullOrBlank()) {
            pendingOpenPeerState.value = openPeer
        }
    }
}

private enum class UnlockedDestination {
    HOME,
    CHAT,
    SNAP_CAMERA,
    SETTINGS
}

@Composable
private fun OpenSnapRootApp(
    viewModel: OpenSnapViewModel,
    pendingInviteUri: String?,
    onConsumeInviteUri: () -> Unit,
    pendingPeerHex: String?,
    onConsumePendingPeerHex: () -> Unit
) {
    val context = LocalContext.current
    val stage by viewModel.appStage.collectAsState()
    val identity by viewModel.identity.collectAsState()
    val authBusy by viewModel.authBusy.collectAsState()
    val banner by viewModel.toastBanner.collectAsState()
    val daemonStatus by viewModel.daemonClient.daemonStatus.collectAsState()
    val presenceMap by viewModel.daemonClient.peerPresenceMap.collectAsState()
    val friends by viewModel.acceptedFriends.collectAsState()
    val incomingReqs by viewModel.incomingRequests.collectAsState()
    val outgoingReqs by viewModel.outgoingRequests.collectAsState()
    val blockedFriends by viewModel.blockedFriends.collectAsState()
    val nowClockMs by viewModel.nowClockMs.collectAsState()
    val updaterState by viewModel.updaterState.collectAsState()
    val integrityReport by viewModel.integrityReport.collectAsState()

    var currentDest by remember { mutableStateOf(UnlockedDestination.HOME) }
    var selectedFriendHex by remember { mutableStateOf<String?>(null) }

    // Request POST_NOTIFICATIONS runtime permission on Android 13 / 14 / 15+
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(stage) {
        if (stage == AppStage.UNLOCKED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    // Handle `opensnap://add/...` deep links once unlocked
    LaunchedEffect(stage, pendingInviteUri, identity) {
        if (stage == AppStage.UNLOCKED && identity != null && !pendingInviteUri.isNullOrBlank()) {
            viewModel.sendFriendRequestByInviteOrTag(pendingInviteUri)
            onConsumeInviteUri()
        }
    }

    // Handle tapping a MessagingStyle notification -> jump directly into that friend's chat
    LaunchedEffect(stage, pendingPeerHex, friends) {
        if (stage == AppStage.UNLOCKED && !pendingPeerHex.isNullOrBlank()) {
            val match = friends.firstOrNull { it.ed25519PubHex.equals(pendingPeerHex, ignoreCase = true) }
            if (match != null) {
                selectedFriendHex = match.ed25519PubHex
                viewModel.enterConversation(match)
                currentDest = UnlockedDestination.CHAT
                onConsumePendingPeerHex()
            }
        }
    }

    LaunchedEffect(banner) {
        if (banner != null) {
            delay(4_000L)
            viewModel.postBanner(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (stage) {
            AppStage.AUTH_SETUP -> {
                AuthScreen(
                    isBusy = authBusy,
                    onGenerateBip39 = { viewModel.generateFreshBip39Words() },
                    onSubmitAuth = { user, display, pw, mnemonic, pin, duress, bio, recovery ->
                        viewModel.registerOrRecoverAccount(
                            username = user,
                            displayName = display,
                            password = pw,
                            mnemonic12Words = mnemonic,
                            primaryPin = pin,
                            duressPin = duress,
                            biometricEnabled = bio,
                            isRecoveryMode = recovery
                        )
                    }
                )
            }

            AppStage.LOCKED -> {
                LockScreen(
                    onSubmitPin = { viewModel.submitUnlockPin(it) },
                    onBiometricSuccess = { viewModel.onBiometricOrPinSucceeded() }
                )
            }

            AppStage.UNLOCKED -> {
                val activeId = identity
                if (activeId != null) {
                    val selectedFriend = friends.firstOrNull { it.ed25519PubHex == selectedFriendHex }

                    when (currentDest) {
                        UnlockedDestination.HOME -> {
                            HomeFriendsScreen(
                                identity = activeId,
                                daemonStatus = daemonStatus,
                                friends = friends,
                                incomingRequests = incomingReqs,
                                outgoingRequests = outgoingReqs,
                                presenceMap = presenceMap,
                                isBatteryExempt = viewModel.isBatteryOptimizationDisabled(),
                                isBusy = authBusy,
                                onOpenChat = { friend ->
                                    selectedFriendHex = friend.ed25519PubHex
                                    viewModel.enterConversation(friend)
                                    currentDest = UnlockedDestination.CHAT
                                },
                                onOpenQuickSnap = { friend ->
                                    selectedFriendHex = friend.ed25519PubHex
                                    currentDest = UnlockedDestination.SNAP_CAMERA
                                },
                                onAcceptRequest = { viewModel.acceptFriendRequest(it) },
                                onRejectRequest = { viewModel.rejectOrRemoveFriend(it) },
                                onSendFriendInvite = { raw, done ->
                                    viewModel.sendFriendRequestByInviteOrTag(raw, done)
                                },
                                onOpenSettings = { currentDest = UnlockedDestination.SETTINGS },
                                onLockVault = { viewModel.lockAppNow() }
                            )
                        }

                        UnlockedDestination.CHAT -> {
                            if (selectedFriend == null) {
                                currentDest = UnlockedDestination.HOME
                            } else {
                                val messagesFlow = remember(selectedFriend.ed25519PubHex) {
                                    viewModel.observeConversationMessages(selectedFriend.ed25519PubHex)
                                }
                                val messages by messagesFlow.collectAsState(initial = emptyList())

                                DisposableEffect(selectedFriend.ed25519PubHex) {
                                    onDispose {
                                        viewModel.leaveConversation(selectedFriend)
                                    }
                                }

                                BackHandler {
                                    viewModel.leaveConversation(selectedFriend)
                                    currentDest = UnlockedDestination.HOME
                                }

                                ChatScreen(
                                    myIdentity = activeId,
                                    friend = selectedFriend,
                                    messages = messages,
                                    nowClockMs = nowClockMs,
                                    presenceEvent = presenceMap[selectedFriend.ed25519PubHex],
                                    isFastLaneDirect = daemonStatus.fastLanePeerHexes.contains(selectedFriend.ed25519PubHex),
                                    onBack = {
                                        viewModel.leaveConversation(selectedFriend)
                                        currentDest = UnlockedDestination.HOME
                                    },
                                    onOpenSnapCamera = { currentDest = UnlockedDestination.SNAP_CAMERA },
                                    onDraftChanged = { viewModel.onDraftTextChanged(selectedFriend, it) },
                                    onVoiceRecordingStateChanged = {
                                        viewModel.onVoiceRecordingStateChanged(selectedFriend, it)
                                    },
                                    onSendText = { text, reply ->
                                        viewModel.sendTextMessage(selectedFriend, text, reply)
                                    },
                                    onSendVoice = { rec, reply ->
                                        viewModel.sendVoiceMessage(selectedFriend, rec, reply)
                                    },
                                    onSendReaction = { msg, emoji ->
                                        viewModel.sendEmojiReaction(selectedFriend, msg, emoji)
                                    },
                                    onConsumeViewOnce = { msg ->
                                        viewModel.consumeViewOnceSnap(selectedFriend, msg)
                                    },
                                    onStartAudioCall = {
                                        viewModel.startP2pCall(context, selectedFriend, isVideoCall = false)
                                    },
                                    onStartVideoCall = {
                                        viewModel.startP2pCall(context, selectedFriend, isVideoCall = true)
                                    },
                                    onToggleSafetyVerified = { verified ->
                                        viewModel.toggleSafetyVerified(selectedFriend, verified)
                                    },
                                    onBlockFriend = {
                                        viewModel.toggleBlockFriend(selectedFriend, blocked = true)
                                    }
                                )
                            }
                        }

                        UnlockedDestination.SNAP_CAMERA -> {
                            if (selectedFriend == null) {
                                currentDest = UnlockedDestination.HOME
                            } else {
                                BackHandler {
                                    currentDest = UnlockedDestination.CHAT
                                }
                                SnapCameraScreen(
                                    recipientDisplayName = selectedFriend.displayName,
                                    onClose = { currentDest = UnlockedDestination.CHAT },
                                    onSendSnapBytes = { bytes, caption, viewOnce ->
                                        viewModel.sendPhotoSnap(selectedFriend, bytes, caption, viewOnce)
                                    }
                                )
                            }
                        }

                        UnlockedDestination.SETTINGS -> {
                            BackHandler { currentDest = UnlockedDestination.HOME }
                            SettingsSecurityScreen(
                                identity = activeId,
                                integrityReport = integrityReport,
                                daemonStatus = daemonStatus,
                                updaterState = updaterState,
                                blockedFriends = blockedFriends,
                                isBatteryExempt = viewModel.isBatteryOptimizationDisabled(),
                                onBack = { currentDest = UnlockedDestination.HOME },
                                onForceReconnectRelays = { viewModel.daemonClient.forceReconnectRelays() },
                                onSyncVaultBackup = {
                                    viewModel.daemonClient.triggerVaultBackupSync()
                                    viewModel.postBanner("Sauvegarde chiffrée des amis publiée sur le pool WSS")
                                },
                                onRestoreVaultBackup = {
                                    viewModel.daemonClient.triggerVaultRestoreFromRelays()
                                    viewModel.postBanner("Recherche de votre sauvegarde chiffrée sur les relais...")
                                },
                                onCheckUpdates = { viewModel.checkForGitHubUpdates() },
                                onDownloadAndInstallUpdate = { viewModel.downloadAndInstallLatestApk(context) },
                                onUnblockFriend = { viewModel.toggleBlockFriend(it, blocked = false) },
                                onPanicWipeConfirmed = {
                                    currentDest = UnlockedDestination.HOME
                                    viewModel.triggerEmergencyPanicWipe()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Non-intrusive Feedback Banner
        banner?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                containerColor = ObsidianCard,
                contentColor = SnapElectricYellow,
                action = {
                    TextButton(onClick = { viewModel.postBanner(null) }) {
                        Text("OK", color = SnapElectricYellow)
                    }
                }
            ) {
                Text(text = message)
            }
        }
    }
}
