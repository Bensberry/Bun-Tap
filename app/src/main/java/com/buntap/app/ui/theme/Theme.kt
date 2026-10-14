package com.buntap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.settings.ThemeMode

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0A1F44),
    primaryContainer = Color(0xFF1E3A66),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFFFC27A),
    onSecondary = Color(0xFF3D2500),
    secondaryContainer = Color(0xFF25395A),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFF6FDDB0),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF124A3B),
    onTertiaryContainer = Color(0xFFB8F5DC),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1B2330),
    onSurfaceVariant = Color(0xFFA8B3C2),
    surfaceContainerLowest = Color(0xFF0A0E13),
    surfaceContainerLow = Color(0xFF121820),
    surfaceContainer = Color(0xFF151C25),
    surfaceContainerHigh = Color(0xFF1B2330),
    surfaceContainerHighest = Color(0xFF232D3B),
    outline = Color(0xFF3A4757),
    outlineVariant = Color(0xFF263040),
    inverseSurface = Color(0xFFE6EAF0),
    inverseOnSurface = Color(0xFF1B2330),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2F5BD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0A1F44),
    secondary = Color(0xFF8A5300),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE5FF),
    onSecondaryContainer = Color(0xFF0A1F44),
    tertiary = Color(0xFF00785A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8F5DC),
    onTertiaryContainer = Color(0xFF002118),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF151A22),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF151A22),
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF4A5566),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF1F7),
    surfaceContainerHighest = Color(0xFFE6EAF2),
    outline = Color(0xFF8A95A6),
    outlineVariant = Color(0xFFD5DBE5),
)

private val BunTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Monospaced digits for counters and coordinates. */
val NumberStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.sp)

private val BunShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Colors for session states; chosen to stay readable on both themes. */
object StatusColors {
    val running = Color(0xFF43C08A)
    val paused = Color(0xFFF2B35C)
    val countdown = Color(0xFF6F9CF2)
    val failed = Color(0xFFE5534B)
    val idle = Color(0xFF8A95A6)

    fun of(status: RunStatus): Color = when (status) {
        RunStatus.RUNNING -> running
        RunStatus.PAUSED -> paused
        RunStatus.COUNTDOWN, RunStatus.STOPPING -> countdown
        RunStatus.FAILED -> failed
        RunStatus.COMPLETED -> running
        RunStatus.STOPPED, RunStatus.IDLE -> idle
    }
}

/** True when the active color scheme is dark (follows the in-app theme, not the system). */
@Composable
fun isAppInDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Emergency stop red, identical on every theme. */
val EmergencyRed = Color(0xFFD93A3A)

@Composable
fun BunTapTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BunTypography,
        shapes = BunShapes,
        content = content,
    )
}
