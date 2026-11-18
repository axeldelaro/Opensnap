package io.github.axeldelaro.opensnap.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.ui.components.CameraXQrScannerView
import io.github.axeldelaro.opensnap.ui.components.OpenSnapQrCodeImage
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.ObsidianSurface
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow

@Composable
fun AddFriendDialog(
    identity: CryptoEngine.SovereignIdentityBundle,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSubmitTagOrInvite: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Mon QR / Tag, 1 = Ajouter / Scanner QR
    var tagOrUriInput by remember { mutableStateOf("") }
    var showLiveScanner by remember { mutableStateOf(false) }

    val myInviteUri = remember(identity) {
        val card = CryptoEngine.createSignedContactCard(identity)
        CryptoEngine.encodeContactInviteUri(card)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) showLiveScanner = true
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Annuaire Chiffré OpenSnap",
                    style = MaterialTheme.typography.titleLarge,
                    color = SnapElectricYellow
                )
                Spacer(Modifier.height(12.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = ObsidianCard,
                    contentColor = SnapElectricYellow,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Mon QR & Tag") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Ajouter un ami") }
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (selectedTab == 0) {
                    OpenSnapQrCodeImage(content = myInviteUri, size = 205.dp)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = identity.openSnapTag,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyberCyan
                    )
                    Text(
                        text = "Tag auto-certifié Ed25519 • @${identity.username}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("OpenSnapTag", identity.openSnapTag))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Tag")
                        }

                        Button(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Ajoute-moi sur OpenSnap (${identity.openSnapTag}) :\n$myInviteUri"
                                    )
                                }
                                context.startActivity(Intent.createChooser(share, "Partager mon invitation OpenSnap"))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SnapElectricYellow,
                                contentColor = ObsidianBg
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Partager", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    if (showLiveScanner && hasCameraPermission) {
                        CameraXQrScannerView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(230.dp),
                            onQrCodeScanned = { scanned ->
                                showLiveScanner = false
                                tagOrUriInput = scanned
                                onSubmitTagOrInvite(scanned)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showLiveScanner = false }) {
                            Text("Saisir un Tag manuellement", color = CyberCyan)
                        }
                    } else {
                        OutlinedTextField(
                            value = tagOrUriInput,
                            onValueChange = { tagOrUriInput = it },
                            label = { Text("Tag court (OS-XXXX-XXXX) ou lien opensnap://") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                if (hasCameraPermission) {
                                    showLiveScanner = true
                                } else {
                                    camPermLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = CyberCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Scanner un QR Code (CameraX)", color = CyberCyan)
                        }

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = { onSubmitTagOrInvite(tagOrUriInput) },
                            enabled = !isBusy && tagOrUriInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SnapElectricYellow,
                                contentColor = ObsidianBg
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    color = ObsidianBg,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Résolution cryptographique...")
                            } else {
                                Text("Envoyer une demande d'ami", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Fermer")
                }
            }
        }
    }
}
