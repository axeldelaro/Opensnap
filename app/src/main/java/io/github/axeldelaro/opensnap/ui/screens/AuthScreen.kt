package io.github.axeldelaro.opensnap.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.FlameCoral
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow
import io.github.axeldelaro.opensnap.ui.theme.VerifiedEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthScreen(
    isBusy: Boolean,
    onGenerateBip39: () -> List<String>,
    onSubmitAuth: (
        username: String,
        displayName: String,
        password: String,
        mnemonic12Words: String?,
        primaryPin: String,
        duressPin: String?,
        biometricEnabled: Boolean,
        isRecoveryMode: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Inscription, 1 = Connexion / Récupération
    var useBip39Mode by remember { mutableStateOf(false) }

    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var generatedWords by remember { mutableStateOf(onGenerateBip39()) }
    var recoveryPhraseInput by remember { mutableStateOf("") }

    var primaryPin by remember { mutableStateOf("") }
    var duressPin by remember { mutableStateOf("") }
    var biometricEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = SnapElectricYellow,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "OpenSnap",
            style = MaterialTheme.typography.headlineMedium,
            color = SnapElectricYellow
        )
        Text(
            text = "Messagerie Éphémère 24h • E2EE Souveraine • Sans Serveur",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = ObsidianCard,
            contentColor = SnapElectricYellow,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Créer un compte", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Connexion / BIP-39", fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Secret derivation mode selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = !useBip39Mode,
                onClick = { useBip39Mode = false },
                label = { Text("Mot de passe fort (Argon2id)") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = useBip39Mode,
                onClick = { useBip39Mode = true },
                label = { Text("Phrase 12 mots BIP-39") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Identifiant utilisateur unique (ex: axel_sec)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Pseudo d'affichage") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        if (!useBip39Mode) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe maître E2EE (min. 8 caractères)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            val strength = computePasswordStrength(password)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { strength },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    strength >= 0.75f -> VerifiedEmerald
                    strength >= 0.45f -> SnapElectricYellow
                    else -> FlameCoral
                }
            )
        } else {
            if (selectedTab == 0) {
                // Show generated 12-word BIP-39 recovery phrase
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianCard)
                        .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Phrase de récupération BIP-39", fontWeight = FontWeight.Bold, color = CyberCyan)
                        }
                        Row {
                            IconButton(onClick = { generatedWords = onGenerateBip39() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Régénérer", tint = SnapElectricYellow)
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("BIP39", generatedWords.joinToString(" ")))
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copier", tint = VerifiedEmerald)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        generatedWords.forEachIndexed { idx, w ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ObsidianBg)
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${idx + 1}. $w",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Notez ces 12 mots hors-ligne : ils dérivent vos clés Ed25519 & X25519 sur n'importe quel téléphone.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                OutlinedTextField(
                    value = recoveryPhraseInput,
                    onValueChange = { recoveryPhraseInput = it },
                    label = { Text("Vos 12 mots BIP-39 séparés par un espace") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Hardware Keystore Lock & Duress PIN Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianCard)
                .padding(14.dp)
        ) {
            Text(
                text = "Verrouillage Matériel StrongBox / TEE & PIN Leurre",
                fontWeight = FontWeight.Bold,
                color = SnapElectricYellow
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = primaryPin,
                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) primaryPin = it },
                label = { Text("Code PIN de déverrouillage (4 à 8 chiffres)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = duressPin,
                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) duressPin = it },
                label = { Text("Code PIN Leurre / Panic Wipe (optionnel)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Saisir le PIN Leurre écrase instantanément la base SQLCipher et les clés du Keystore.",
                fontSize = 11.sp,
                color = FlameCoral.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = CyberCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Déverrouillage Biométrique", fontSize = 14.sp)
                }
                Switch(checked = biometricEnabled, onCheckedChange = { biometricEnabled = it })
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val chosenMnemonic = when {
                    !useBip39Mode -> null
                    selectedTab == 0 -> generatedWords.joinToString(" ")
                    else -> recoveryPhraseInput
                }
                onSubmitAuth(
                    username,
                    displayName,
                    password,
                    chosenMnemonic,
                    primaryPin,
                    duressPin.takeIf { it.isNotBlank() },
                    biometricEnabled,
                    selectedTab == 1
                )
            },
            enabled = !isBusy,
            colors = ButtonDefaults.buttonColors(
                containerColor = SnapElectricYellow,
                contentColor = ObsidianBg
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    color = ObsidianBg,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Dérivation Argon2id (Ed25519 + X25519)...", fontWeight = FontWeight.Bold)
            } else {
                Text(
                    text = if (selectedTab == 0) {
                        "Générer mon Compte Souverain E2EE"
                    } else {
                        "Restaurer mon Compte & mes Amis"
                    },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.5.sp
                )
            }
        }
    }
}

private fun computePasswordStrength(pw: String): Float {
    if (pw.isEmpty()) return 0f
    var score = (pw.length / 16f).coerceAtMost(0.55f)
    if (pw.any { it.isUpperCase() }) score += 0.12f
    if (pw.any { it.isDigit() }) score += 0.15f
    if (pw.any { !it.isLetterOrDigit() }) score += 0.18f
    return score.coerceIn(0f, 1f)
}
