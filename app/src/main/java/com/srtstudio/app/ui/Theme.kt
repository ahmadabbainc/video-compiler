package com.srtstudio.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Violet = Color(0xFF8B7CFF)
val Mint = Color(0xFF35E0C2)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF0B0620),
    secondary = Mint,
    onSecondary = Color(0xFF00201A),
    background = Color(0xFF0B0D14),
    onBackground = Color(0xFFE9ECF5),
    surface = Color(0xFF12151F),
    onSurface = Color(0xFFE9ECF5),
    surfaceVariant = Color(0xFF1B2030),
    onSurfaceVariant = Color(0xFF9AA3B8),
    outline = Color(0xFF2A3045),
    error = Color(0xFFFF8A8A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    onPrimary = Color.White,
    secondary = Color(0xFF007F6C),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF12151F),
    surface = Color.White,
    onSurface = Color(0xFF12151F),
    surfaceVariant = Color(0xFFECEEF6),
    onSurfaceVariant = Color(0xFF596079),
    outline = Color(0xFFD9DCEA),
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SrtStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
