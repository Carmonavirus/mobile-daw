package com.mobiledaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A light, paper-like palette: ink on off-white with one soft blue accent. */
object DawColors {
    val Paper = Color(0xFFF4F3F0)
    val Card = Color(0xFFFBFAF8)
    val Ink = Color(0xFF1D1D1F)
    val InkSoft = Color(0xFF3A3A3D)
    val Muted = Color(0xFF8E8E93)
    val Line = Color(0xFFE4E2DD)
    val LineStrong = Color(0xFFD2CFC8)
    val Chip = Color(0xFFE9E7E2)
    val ChipActive = Color(0xFFD9D6CF)
    val Accent = Color(0xFF4F74C9)
    val AccentSoft = Color(0xFFC9D7F3)
    val Ghost = Color(0xFFD5DFF3)
    val Mute = Color(0xFFE0675A)
    val Solo = Color(0xFFE4B33D)

    /** Soft colours used to tell patterns apart on the playlist. */
    val PatternColors = listOf(
        Color(0xFF2B2B2E),
        Color(0xFF7D9BDB),
        Color(0xFFB7B3AA),
        Color(0xFF8DB59A),
        Color(0xFFD69E7C),
        Color(0xFFA894CF),
    )
}

@Composable
fun DawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = DawColors.Ink,
            onPrimary = DawColors.Paper,
            secondary = DawColors.Accent,
            background = DawColors.Paper,
            onBackground = DawColors.Ink,
            surface = DawColors.Card,
            onSurface = DawColors.Ink,
            surfaceVariant = DawColors.Chip,
            outline = DawColors.LineStrong,
        ),
        content = content,
    )
}
