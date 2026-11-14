package com.mobiledaw.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** The three colours a person picks; everything else is derived from them. */
data class ThemeChoice(val background: Color, val note: Color, val accent: Color)

data class ThemePreset(val name: String, val choice: ThemeChoice)

object ThemePresets {
    val all = listOf(
        ThemePreset("Paper", ThemeChoice(Color(0xFFF4F3F0), Color(0xFF1D1D1F), Color(0xFF4F74C9))),
        ThemePreset("Night", ThemeChoice(Color(0xFF16171A), Color(0xFFE9E6E0), Color(0xFF7C9CF0))),
        ThemePreset("Sand", ThemeChoice(Color(0xFFEFE6D8), Color(0xFF3B2F2A), Color(0xFFC7774A))),
        ThemePreset("Mint", ThemeChoice(Color(0xFFE6F0EA), Color(0xFF1F3A2E), Color(0xFF3E9C6E))),
        ThemePreset("Rose", ThemeChoice(Color(0xFFF7E9EC), Color(0xFF4A2230), Color(0xFFD0587A))),
        ThemePreset("Studio", ThemeChoice(Color(0xFF0F1418), Color(0xFF5EEAD4), Color(0xFFF59E0B))),
    )
    val default = all.first().choice

    val backgrounds = listOf(
        0xFFF4F3F0, 0xFFFFFFFF, 0xFFEFE6D8, 0xFFE6F0EA, 0xFFE8ECF6, 0xFFF7E9EC,
        0xFF16171A, 0xFF0F1418, 0xFF1C1A24, 0xFF20201E,
    ).map { Color(it) }
    val notes = listOf(
        0xFF1D1D1F, 0xFFE9E6E0, 0xFF4F74C9, 0xFF3E9C6E, 0xFFD0587A, 0xFFC7774A,
        0xFF8B5CF6, 0xFF5EEAD4, 0xFFF59E0B, 0xFFEF4444,
    ).map { Color(it) }
    val accents = notes
}

/**
 * The app palette. Values are Compose state, so changing the theme recolours every screen,
 * including the canvas-drawn piano roll and playlist, without restarting.
 */
object DawColors {
    var Paper by mutableStateOf(Color(0xFFF4F3F0))
        private set
    var Card by mutableStateOf(Color(0xFFFBFAF8))
        private set
    var Ink by mutableStateOf(Color(0xFF1D1D1F))
        private set
    var InkSoft by mutableStateOf(Color(0xFF3A3A3D))
        private set
    var Muted by mutableStateOf(Color(0xFF8E8E93))
        private set
    var Line by mutableStateOf(Color(0xFFE4E2DD))
        private set
    var LineStrong by mutableStateOf(Color(0xFFD2CFC8))
        private set
    var Chip by mutableStateOf(Color(0xFFE9E7E2))
        private set
    var ChipActive by mutableStateOf(Color(0xFFD9D6CF))
        private set

    /** Notes in the piano roll, active steps, the play button. */
    var Note by mutableStateOf(Color(0xFF1D1D1F))
        private set
    var OnNote by mutableStateOf(Color(0xFFF4F3F0))
        private set
    var Accent by mutableStateOf(Color(0xFF4F74C9))
        private set
    var AccentSoft by mutableStateOf(Color(0xFFC9D7F3))
        private set
    var Ghost by mutableStateOf(Color(0xFFD5DFF3))
        private set
    var IsDark by mutableStateOf(false)
        private set

    val Mute = Color(0xFFE0675A)
    val Solo = Color(0xFFE4B33D)

    /** Colours used to tell patterns apart on the playlist; the first follows the note colour. */
    val PatternColors: List<Color>
        get() = listOf(Note, Accent, Color(0xFFB7B3AA), Color(0xFF8DB59A), Color(0xFFD69E7C), Color(0xFFA894CF))

    var choice by mutableStateOf(ThemePresets.default)
        private set

    fun apply(c: ThemeChoice) {
        choice = c
        val bg = c.background
        val dark = bg.luminance() < 0.35f
        val ink = if (dark) Color(0xFFF1EFEA) else Color(0xFF1D1D1F)
        IsDark = dark
        Paper = bg
        Ink = ink
        Card = if (dark) lerp(bg, Color.White, 0.05f) else lerp(bg, Color.White, 0.55f)
        InkSoft = lerp(bg, ink, 0.85f)
        Muted = lerp(bg, ink, 0.5f)
        Line = lerp(bg, ink, if (dark) 0.12f else 0.08f)
        LineStrong = lerp(bg, ink, if (dark) 0.22f else 0.16f)
        Chip = lerp(bg, ink, if (dark) 0.08f else 0.055f)
        ChipActive = lerp(bg, ink, if (dark) 0.16f else 0.12f)
        Note = c.note
        OnNote = if (c.note.luminance() > 0.45f) Color(0xFF1D1D1F) else Color(0xFFF7F6F3)
        Accent = c.accent
        AccentSoft = lerp(bg, c.accent, 0.35f)
        Ghost = lerp(bg, c.accent, if (dark) 0.35f else 0.25f)
    }
}

/** Remembers the chosen theme between launches. */
object ThemeStore {
    private const val PREFS = "theme"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("bg")) {
            DawColors.apply(ThemePresets.default)
            return
        }
        DawColors.apply(
            ThemeChoice(
                Color(p.getInt("bg", 0)),
                Color(p.getInt("note", 0)),
                Color(p.getInt("accent", 0)),
            ),
        )
    }

    fun save(context: Context, c: ThemeChoice) {
        DawColors.apply(c)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("bg", c.background.toArgb())
            .putInt("note", c.note.toArgb())
            .putInt("accent", c.accent.toArgb())
            .apply()
    }
}

@Composable
fun DawTheme(content: @Composable () -> Unit) {
    val scheme = if (DawColors.IsDark) {
        darkColorScheme(
            primary = DawColors.Note, onPrimary = DawColors.OnNote, secondary = DawColors.Accent,
            background = DawColors.Paper, onBackground = DawColors.Ink, surface = DawColors.Card,
            onSurface = DawColors.Ink, surfaceVariant = DawColors.Chip, outline = DawColors.LineStrong,
            surfaceContainer = DawColors.Card, surfaceContainerHigh = DawColors.Card,
        )
    } else {
        lightColorScheme(
            primary = DawColors.Note, onPrimary = DawColors.OnNote, secondary = DawColors.Accent,
            background = DawColors.Paper, onBackground = DawColors.Ink, surface = DawColors.Card,
            onSurface = DawColors.Ink, surfaceVariant = DawColors.Chip, outline = DawColors.LineStrong,
            surfaceContainer = DawColors.Card, surfaceContainerHigh = DawColors.Card,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
