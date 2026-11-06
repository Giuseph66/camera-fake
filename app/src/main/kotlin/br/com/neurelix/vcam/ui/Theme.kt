package br.com.neurelix.vcam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

val Teal = Color(0xFF00E5C7)
val Violet = Color(0xFF7C5CFF)
val Amber = Color(0xFFFFB74D)
val Red = Color(0xFFFF6B6B)
val Green = Color(0xFF4ADE80)
val Blue = Color(0xFF5AB0FF)

private val Dark = darkColorScheme(
    primary = Teal, onPrimary = Color(0xFF00382F),
    secondary = Violet, onSecondary = Color.White,
    background = Color(0xFF0E1118), onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF151A24), onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E2533), onSurfaceVariant = Color(0xFFA9B3C5),
    surfaceContainer = Color(0xFF181E2A), surfaceContainerHigh = Color(0xFF1E2533),
    error = Red, outline = Color(0xFF3A4458), outlineVariant = Color(0xFF283042),
)
private val Light = lightColorScheme(
    primary = Color(0xFF007F6E), secondary = Color(0xFF5B3FE0),
    background = Color(0xFFF5F7FB), surface = Color.White, error = Color(0xFFB3261E),
)

private val Type = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
)

@Composable
fun NeurelixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme() || true) Dark else Light,
        typography = Type,
        shapes = Shapes(medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}
