package com.della.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Della {
    val Background = Color(0xFF07110D)
    val Panel = Color(0xFF10261B)
    val PanelHigh = Color(0xFF153223)
    val Border = Color(0xFF1F3D2D)
    val Primary = Color(0xFF247A50)
    val Accent = Color(0xFF72D6A0)
    val Text = Color(0xFFF2EEE3)       // warm white
    val TextMuted = Color(0xFF93A99B)  // muted sage
    val TextFaint = Color(0xFF5E7466)
    val Danger = Color(0xFFE38B78)
    val Warning = Color(0xFFDDB86C)
    val Info = Color(0xFF8CB8D9)
}

private val scheme = darkColorScheme(
    primary = Della.Primary,
    onPrimary = Della.Text,
    primaryContainer = Della.PanelHigh,
    onPrimaryContainer = Della.Accent,
    secondary = Della.Accent,
    onSecondary = Della.Background,
    secondaryContainer = Della.PanelHigh,
    onSecondaryContainer = Della.Text,
    tertiary = Della.Warning,
    background = Della.Background,
    onBackground = Della.Text,
    surface = Della.Background,
    onSurface = Della.Text,
    surfaceVariant = Della.Panel,
    onSurfaceVariant = Della.TextMuted,
    surfaceContainer = Della.Panel,
    surfaceContainerHigh = Della.PanelHigh,
    surfaceContainerHighest = Della.PanelHigh,
    surfaceContainerLow = Della.Panel,
    surfaceContainerLowest = Della.Background,
    outline = Della.Border,
    outlineVariant = Della.Border,
    error = Della.Danger,
    onError = Della.Background,
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 23.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

@Composable
fun DellaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
