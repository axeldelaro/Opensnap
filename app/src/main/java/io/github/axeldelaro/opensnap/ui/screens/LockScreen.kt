package io.github.axeldelaro.opensnap.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import io.github.axeldelaro.opensnap.ui.theme.CyberCyan
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.ObsidianCard
import io.github.axeldelaro.opensnap.ui.theme.SnapElectricYellow

@Composable
fun LockScreen(
    onSubmitPin: (String) -> Boolean,
    onBiometricSuccess: () -> Unit
) {
    val context = LocalContext.current
    var enteredPin by remember { mutableStateOf("") }
    val policy = remember { HardwareKeystoreManager.loadLockPolicy(context) }

    val canUseBiometric = remember(context, policy) {
        policy?.biometricEnabled == true &&
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun launchBiometricPrompt() {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onBiometricSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller OpenSnap")
            .setSubtitle("Authentification matérielle StrongBox / TEE")
            .setNegativeButtonText("Utiliser mon code PIN")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()
        runCatching { prompt.authenticate(info) }
    }

    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric) {
            launchBiometricPrompt()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = SnapElectricYellow,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Coffre Chiffré Verrouillé",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tunnel arrière-plan actif • Saisissez votre code PIN",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // PIN Dots display
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val count = maxOf(4, enteredPin.length)
            repeat(count) { idx ->
                val filled = idx < enteredPin.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (filled) SnapElectricYellow else ObsidianCard)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Tactile Numeric Pad
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9")
        )
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(vertical = 7.dp)
            ) {
                row.forEach { digit ->
                    PinKeyButton(label = digit) {
                        if (enteredPin.length < 8) enteredPin += digit
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 7.dp)
        ) {
            if (canUseBiometric) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(ObsidianCard)
                        .clickable { launchBiometricPrompt() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = "Biométrie", tint = CyberCyan, modifier = Modifier.size(30.dp))
                }
            } else {
                Spacer(Modifier.size(68.dp))
            }

            PinKeyButton(label = "0") {
                if (enteredPin.length < 8) enteredPin += "0"
            }

            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(ObsidianCard)
                    .clickable {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Effacer",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val ok = onSubmitPin(enteredPin)
                if (!ok) enteredPin = ""
            },
            enabled = enteredPin.length >= 4,
            colors = ButtonDefaults.buttonColors(
                containerColor = SnapElectricYellow,
                contentColor = ObsidianBg
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(50.dp)
        ) {
            Text("Déverrouiller", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        }

        if (canUseBiometric) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { launchBiometricPrompt() }) {
                Text("Empreinte / Visage", color = CyberCyan)
            }
        }
    }
}

@Composable
private fun PinKeyButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(ObsidianCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
