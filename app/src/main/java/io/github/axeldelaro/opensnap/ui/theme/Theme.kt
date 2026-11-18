package io.github.axeldelaro.opensnap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ObsidianBg = Color(0xFF090B10)
val ObsidianSurface = Color(0xFF121722)
val ObsidianCard = Color(0xFF182030)
val SnapElectricYellow = Color(0xFFFFE600)
val CyberCyan = Color(0xFF00E5FF)
val VerifiedEmerald = Color(0xFF00E676)
val FlameCoral = Color(0xFFFF4D6D)
val MutedText = Color(0xFF94A3B8)

private val OpenSnapColorScheme = darkColorScheme(
    primary = SnapElectricYellow,
    onPrimary = Color(0xFF090B10),
    secondary = CyberCyan,
    onSecondary = Color(0xFF090B10),
    tertiary = VerifiedEmerald,
    background = ObsidianBg,
    onBackground = Color(0xFFF8FAFC),
    surface = ObsidianSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = MutedText,
    error = FlameCoral,
    onError = Color.White
)

private val OpenSnapTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp
    )
)

@Composable
fun OpenSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenSnapColorScheme,
        typography = OpenSnapTypography,
        content = content
    )
}
