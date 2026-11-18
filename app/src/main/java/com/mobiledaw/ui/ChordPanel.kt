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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.ChordGen
import com.mobiledaw.model.ChordRhythm
import com.mobiledaw.model.ChordSlot
import com.mobiledaw.model.NOTE_NAMES
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.ui.theme.DawColors

/**
 * Chords panel: sits under the piano roll while open. Every change is written straight into the
 * channel's pattern, so the roll above shows the notes and the transport plays them.
 * Keep leaves them there; Cancel puts back what the channel had before.
 */
@Composable
fun ChordPanel(vm: DawViewModel, modifier: Modifier = Modifier) {
    val session = vm.chordGen ?: return
    val settings = vm.chordSettings
    val project = vm.project
    val voiced = ChordGen.voice(session.slots, settings, project.key)
    val keyName = NOTE_NAMES[project.key] + if (project.minor) " minor" else " major"

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        // Title row: what we're writing into, plus Cancel / Keep.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chords", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(
                    "$keyName · ${vm.project.channels.firstOrNull { it.id == session.channelId }?.name ?: ""}",
                    fontSize = 11.sp,
                    color = DawColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PillButton("Cancel", filled = false) { vm.closeChordGen(keep = false) }
            Spacer(Modifier.width(8.dp))
            PillButton("Keep", filled = true) { vm.closeChordGen(keep = true) }
        }

        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // Generate + preview.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayButton(vm.playing, vm::togglePlay, diameter = 40.dp)
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DawColors.Note)
                        .clickable { vm.generateChords() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↻  Generate", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.OnNote)
                }
            }

            // Conventional ↔ Adventurous.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Slider(
                    value = settings.adventure,
                    onValueChange = { v -> vm.changeChordSettings { it.copy(adventure = v) } },
                    colors = inkSliderColors(),
                    modifier = Modifier.height(30.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("Conventional", fontSize = 11.sp, color = DawColors.Muted, modifier = Modifier.weight(1f))
                    Text("Adventurous", fontSize = 11.sp, color = DawColors.Muted)
                }
            }

            // One card per chord.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                session.slots.forEachIndexed { i, slot ->
                    ChordCard(
                        name = ChordGen.chordName(project.key, project.minor, slot.chord),
                        slot = slot,
                        pitches = voiced.getOrNull(i).orEmpty(),
                        bass = settings.bass,
                        onTap = { vm.auditionChord(i) },
                        onLock = { vm.toggleChordLock(i) },
                        onReroll = { vm.rerollChord(i) },
                        onInvert = { d -> vm.invertChord(i, d) },
                    )
                }
            }

            // Settings.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingLabel("Chords")
                    NumberStepper(settings.count, ChordGen.MIN_COUNT, ChordGen.MAX_COUNT) { n -> vm.changeChordSettings { it.copy(count = n) } }
                    Spacer(Modifier.weight(1f))
                    SettingLabel("Octave")
                    NumberStepper(settings.octave, ChordGen.MIN_OCTAVE, ChordGen.MAX_OCTAVE) { n -> vm.changeChordSettings { it.copy(octave = n) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingLabel("Length")
                    Segmented(
                        options = listOf(TICKS_PER_BAR / 2 to "½ bar", TICKS_PER_BAR to "1 bar", TICKS_PER_BAR * 2 to "2 bars"),
                        selected = settings.lengthTicks,
                        onSelect = { l -> vm.changeChordSettings { it.copy(lengthTicks = l) } },
                    )
                    Spacer(Modifier.weight(1f))
                    DawChip("Bass", selected = settings.bass, dotColor = if (settings.bass) DawColors.Note else null) {
                        vm.changeChordSettings { it.copy(bass = !it.bass) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingLabel("Rhythm")
                    Segmented(
                        options = ChordRhythm.entries.map { it to it.label },
                        selected = settings.rhythm,
                        onSelect = { r -> vm.changeChordSettings { it.copy(rhythm = r) } },
                    )
                }
                Text(
                    "Tap a chord to hear it. Lock the ones you like, then Generate again for new ones around them.",
                    fontSize = 11.sp,
                    color = DawColors.Muted,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ChordCard(
    name: String,
    slot: ChordSlot,
    pitches: List<Int>,
    bass: Boolean,
    onTap: () -> Unit,
    onLock: () -> Unit,
    onReroll: () -> Unit,
    onInvert: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val locked = slot.locked
    Column(
        Modifier
            .width(86.dp)
            .clip(shape)
            .background(if (locked) DawColors.ChipActive else DawColors.Paper)
            .border(if (locked) 1.5.dp else 1.dp, if (locked) DawColors.Ink else DawColors.Line, shape)
            .clickable(onClick = onTap)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Lock + inversion.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onLock) { LockGlyph(locked) }
            Spacer(Modifier.weight(1f))
            Text(
                if (slot.inversion == 0) "" else (if (slot.inversion > 0) "+" else "") + slot.inversion,
                fontSize = 10.sp,
                color = DawColors.Muted,
            )
        }
        Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1)
        Text(slot.chord.roman, fontSize = 11.sp, color = DawColors.Muted, maxLines = 1)
        VoicingGlyph(pitches, bass, Modifier.padding(vertical = 6.dp).size(width = 60.dp, height = 40.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onInvert(-1) }) { Text("▾", fontSize = 15.sp, color = DawColors.InkSoft) }
            Spacer(Modifier.weight(1f))
            IconButton(onReroll) { Text("↻", fontSize = 15.sp, color = DawColors.InkSoft) }
            Spacer(Modifier.weight(1f))
            IconButton({ onInvert(1) }) { Text("▴", fontSize = 15.sp, color = DawColors.InkSoft) }
        }
    }
}

/** The chord's notes as little bars stacked by pitch; the bass note is drawn hollow. */
@Composable
private fun VoicingGlyph(pitches: List<Int>, bass: Boolean, modifier: Modifier) {
    val ink = DawColors.Note
    val soft = DawColors.InkSoft
    Canvas(modifier) {
        if (pitches.isEmpty()) return@Canvas
        val lo = pitches.min()
        val hi = maxOf(pitches.max(), lo + 12)
        val barH = 4.dp.toPx()
        pitches.forEachIndexed { i, p ->
            val y = size.height - barH - (p - lo).toFloat() / (hi - lo) * (size.height - barH)
            val isBass = bass && i == 0
            val w = if (isBass) size.width * 0.55f else size.width
            val x = (size.width - w) / 2f
            if (isBass) {
                drawRoundRect(soft, Offset(x, y), Size(w, barH), CornerRadius(barH / 2f), style = Stroke(1.dp.toPx()))
            } else {
                drawRoundRect(ink, Offset(x, y), Size(w, barH), CornerRadius(barH / 2f))
            }
        }
    }
}

@Composable
private fun LockGlyph(locked: Boolean) {
    val color = if (locked) DawColors.Ink else DawColors.Muted
    Canvas(Modifier.size(width = 11.dp, height = 13.dp)) {
        val stroke = 1.5.dp.toPx()
        val bodyTop = size.height * 0.45f
        // Shackle: closed when locked, swung open when not.
        val sx = size.width * 0.2f
        val sw = size.width * 0.6f
        val lift = if (locked) 0f else size.height * 0.12f
        drawArc(
            color, 180f, 180f, false,
            topLeft = Offset(sx, stroke / 2f - lift),
            size = Size(sw, bodyTop * 1.3f),
            style = Stroke(stroke),
        )
        drawLine(color, Offset(sx + sw, bodyTop * 0.65f - lift), Offset(sx + sw, bodyTop), stroke)
        if (locked) drawLine(color, Offset(sx, bodyTop * 0.65f), Offset(sx, bodyTop), stroke)
        drawRoundRect(color, Offset(0f, bodyTop), Size(size.width, size.height - bodyTop), CornerRadius(2.dp.toPx()))
    }
}

@Composable
private fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 1.5.sp, color = DawColors.Muted, modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun NumberStepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", value > min) { onChange(value - 1) }
        Text("$value", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.padding(horizontal = 8.dp))
        StepButton("+", value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (enabled) DawColors.Card else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, color = if (enabled) DawColors.Ink else DawColors.LineStrong)
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) DawColors.Ink else DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (filled) DawColors.Paper else DawColors.Ink)
    }
}
