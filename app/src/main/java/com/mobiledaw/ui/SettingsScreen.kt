package com.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.AppInfo
import com.mobiledaw.DawViewModel
import com.mobiledaw.ui.theme.DawColors
import com.mobiledaw.ui.theme.ThemeChoice
import com.mobiledaw.ui.theme.ThemePresets
import com.mobiledaw.ui.theme.ThemeStore

@Composable
fun SettingsScreen(vm: DawViewModel, onOpenProjects: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { AppInfo.versionName(context) }
    val choice = DawColors.choice
    fun pick(c: ThemeChoice) = ThemeStore.save(context, c)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                TextAction("Done", strong = true, onClick = onDismiss)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                // ---------------------------------------------------------------- projects
                SectionCard {
                    SettingRow("Projects", "Open, rename or start a project (\"${vm.project.name}\" is open)", onOpenProjects)
                }

                // ---------------------------------------------------------------- editing
                SectionTitle("Editing")
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hear sounds when placing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                            Text("Plays a step or note as you place it, only while stopped.", fontSize = 12.sp, color = DawColors.Muted)
                        }
                        androidx.compose.material3.Switch(
                            checked = vm.previewOnEdit,
                            onCheckedChange = vm::changePreviewOnEdit,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedTrackColor = DawColors.Note,
                                checkedThumbColor = DawColors.OnNote,
                            ),
                        )
                    }
                }

                // ---------------------------------------------------------------- drum kits
                SectionTitle("Drum kits")
                SectionCard { DrumKitsSection(vm) }

                // ---------------------------------------------------------------- appearance
                SectionTitle("Appearance")
                SectionCard {
                    Text("Themes", fontSize = 13.sp, color = DawColors.Muted)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemePresets.all.forEach { preset ->
                            PresetTile(preset.name, preset.choice, selected = preset.choice == choice) { pick(preset.choice) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SwatchRow("Background", ThemePresets.backgrounds, choice.background) { pick(choice.copy(background = it)) }
                    Spacer(Modifier.height(14.dp))
                    SwatchRow("Notes & steps", ThemePresets.notes, choice.note) { pick(choice.copy(note = it)) }
                    Spacer(Modifier.height(14.dp))
                    SwatchRow("Accent (playhead, ghost notes, meters)", ThemePresets.accents, choice.accent) { pick(choice.copy(accent = it)) }
                }

                // ---------------------------------------------------------------- versions
                SectionTitle("Versions")
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Version $version", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                            Text("Update downloads the newest build. Open it and tap Install.", fontSize = 12.sp, color = DawColors.Muted)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextAction("Update", strong = true) { AppInfo.openLatestDownload(context) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                AppInfo.releases.forEachIndexed { i, release ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Version ${release.version}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                            if (i == 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(50)).background(DawColors.AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                                ) { Text("Latest", fontSize = 11.sp, color = DawColors.Ink) }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(release.date, fontSize = 12.sp, color = DawColors.Muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        release.changes.forEach { change ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(DawColors.Note))
                                Spacer(Modifier.width(10.dp))
                                Text(change, fontSize = 14.sp, color = DawColors.InkSoft)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // ---------------------------------------------------------------- gestures
                SectionTitle("How to get around")
                GUIDE.forEach { (screen, tips) ->
                    SectionCard {
                        Text(screen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                        Spacer(Modifier.height(6.dp))
                        tips.forEach { (gesture, action) ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text(gesture, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DawColors.InkSoft, modifier = Modifier.width(132.dp))
                                Text(action, fontSize = 13.sp, color = DawColors.InkSoft, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Gesture guide shown in Settings, screen by screen. */
private val GUIDE = listOf(
    "Everywhere" to listOf(
        "Tap the song name" to "Open, rename or start a project.",
        "Tap BPM · key" to "Change tempo and key.",
        "PAT / SONG" to "Loop the current pattern, or play the whole playlist.",
        "Play button" to "Start / stop (just above the tabs).",
    ),
    "Rack" to listOf(
        "Tap a step" to "Turn it on or off.",
        "Tap a drum's name" to "Choose its sound from your drum kits.",
        "Long-press a name" to "Fill every 2/4/8 steps, clear, loop the pattern, open the piano roll, delete.",
        "‹  ›  and dots" to "Move between bars. + on the last bar adds one.",
        "Tap a pattern" to "Select it. Tap it again for Save as template, Loop, Delete.",
        "+ next to patterns" to "New empty pattern, or one from a template.",
    ),
    "Piano roll" to listOf(
        "Tap empty space" to "Place a note (length = the Grid setting).",
        "Tap a note" to "Remove it.",
        "Drag a note" to "Move it in time and pitch.",
        "Drag a note's end" to "Make it longer or shorter.",
        "One-finger drag" to "Scroll (on empty space).",
        "Two-finger drag" to "Scroll left/right and up/down.",
        "Pinch / spread" to "Zoom time in or out.",
        "Grid buttons" to "Set the grid lines, snapping and new note length.",
        "Track chips" to "Switch which channel you edit; others show as faint notes.",
    ),
    "Playlist" to listOf(
        "Tap an empty cell" to "Place the selected pattern.",
        "Tap a clip" to "Remove it.",
        "Swipe sideways" to "Move along the song (time runs left to right).",
    ),
    "Mixer" to listOf(
        "Sliders" to "Volume and pan (pan snaps to centre).",
        "M / S" to "Mute / solo a channel.",
    ),
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        color = DawColors.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Text(subtitle, fontSize = 12.sp, color = DawColors.Muted)
        }
        Text("›", fontSize = 22.sp, color = DawColors.Muted)
    }
}

/** A tiny preview of a theme: background, a note bar with its pill, and an accent playhead. */
@Composable
private fun PresetTile(name: String, c: ThemeChoice, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Canvas(
            Modifier
                .size(width = 84.dp, height = 60.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) DawColors.Ink else DawColors.Line, RoundedCornerShape(14.dp)),
        ) {
            drawRect(c.background)
            val h = 6.dp.toPx()
            drawRoundRect(c.note, Offset(10.dp.toPx(), 16.dp.toPx()), Size(44.dp.toPx(), h), CornerRadius(h / 2))
            drawRoundRect(c.note, Offset(26.dp.toPx(), 30.dp.toPx()), Size(48.dp.toPx(), h), CornerRadius(h / 2))
            drawRoundRect(c.accent.copy(alpha = 0.45f), Offset(16.dp.toPx(), 44.dp.toPx()), Size(30.dp.toPx(), h), CornerRadius(h / 2))
            drawLine(c.accent, Offset(36.dp.toPx(), 8.dp.toPx()), Offset(36.dp.toPx(), size.height - 8.dp.toPx()), 1.5.dp.toPx())
        }
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 12.sp, color = if (selected) DawColors.Ink else DawColors.Muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SwatchRow(label: String, colors: List<Color>, selected: Color, onPick: (Color) -> Unit) {
    Text(label, fontSize = 13.sp, color = DawColors.Muted)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { color ->
            val on = color == selected
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(if (on) 2.dp else 1.dp, if (on) DawColors.Ink else DawColors.Line, CircleShape)
                    .padding(if (on) 4.dp else 2.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onPick(color) },
            )
        }
    }
}
