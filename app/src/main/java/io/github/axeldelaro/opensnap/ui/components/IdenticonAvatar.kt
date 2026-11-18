package io.github.axeldelaro.opensnap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axeldelaro.opensnap.crypto.CryptoEngine
import io.github.axeldelaro.opensnap.ui.theme.ObsidianBg
import io.github.axeldelaro.opensnap.ui.theme.VerifiedEmerald

@Composable
fun IdenticonAvatar(
    ed25519PubHex: String,
    displayName: String,
    size: Dp = 46.dp,
    isOnline: Boolean = false
) {
    val (colorA, colorB, initials) = remember(ed25519PubHex, displayName) {
        val hash = CryptoEngine.sha256(ed25519PubHex.encodeToByteArray())
        val hue1 = ((hash[0].toInt() and 0xFF) * 360f) / 255f
        val hue2 = (hue1 + 55f) % 360f
        val c1 = Color.hsv(hue1, 0.75f, 0.92f)
        val c2 = Color.hsv(hue2, 0.80f, 0.72f)
        val init = displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "OS" }
        Triple(c1, c2, init)
    }

    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colorA, colorB))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = ObsidianBg,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.36f).sp
            )
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.30f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(VerifiedEmerald)
                    .border(2.dp, ObsidianBg, CircleShape)
            )
        }
    }
}
