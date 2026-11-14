package com.mobiledaw.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.NOTE_NAMES
import com.mobiledaw.model.PitchScale
import com.mobiledaw.model.PitchSettings
import com.mobiledaw.model.PitchStyle
import com.mobiledaw.model.PitchVoices
import com.mobiledaw.model.Project
import com.mobiledaw.model.isBlackKey
import com.mobiledaw.ui.synth.Choices
import com.mobiledaw.ui.synth.SynthCard
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.delay
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Pitch Correct: pulls a voice to the notes of a key. Pick a sound, check the key, sing.
 * Speed sets how fast notes are pulled in: instant is the hard-tuned effect, slower keeps it natural.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PitchEditor(vm: DawViewModel, trackId: Long, effectId: Long, project: Project, settings: PitchSettings, onChange: (PitchSettings) -> Unit) {
    var reading by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(trackId, effectId) {
        while (true) {
            val out = FloatArray(2)
            vm.readPitch(trackId, effectId, out)
            reading = out
            delay(50)
        }
    }
    val key = settings.keyFor(project)
    val scale = settings.scaleFor(project)
    val allowed = settings.allowedNotes(project)
    val followsSong = settings.key == null && settings.scale == null

    Column {
        Meter(reading[0], reading[1])

        SynthCard(title = "Sound") {
            val styles = PitchStyle.entries
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                styles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { style ->
                            StyleTile(style, style.matches(settings), Modifier.weight(1f)) { onChange(style.applyTo(settings)) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        SynthCard(
            title = "Key",
            trailing = {
                Pill(if (followsSong) "Follows song key" else "Use song key", followsSong) {
                    onChange(settings.copy(key = null, scale = null, disabledNotes = emptySet()))
                }
            },
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                NOTE_NAMES.forEachIndexed { i, n ->
                    Pill(n, i == key) { onChange(settings.copy(key = i, scale = scale, disabledNotes = emptySet())) }
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                PitchScale.entries.forEach { s ->
                    Pill(s.label, s == scale) { onChange(settings.copy(key = key, scale = s, disabledNotes = emptySet())) }
                }
            }
        }

        SynthCard(title = "Tuning") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                FxKnob("Speed", settings.retuneMs, KnobRange(0f, 400f, 8f), { if (it < 1f) "Instant" else "${it.roundToInt()} ms" }, { onChange(settings.copy(retuneMs = if (it < 2f) 0f else it)) })
                FxKnob("Strength", settings.amount, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(amount = it)) })
                FxKnob(
                    "Voices",
                    settings.voicesLevel,
                    KnobRange(0f, 1f, 0.6f),
                    ::formatPercent,
                    { onChange(settings.copy(voicesLevel = it)) },
                    enabled = settings.voices != PitchVoices.OFF,
                )
                FxKnob("Mix", settings.mix, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(mix = it)) })
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { more = !more }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (more) "Fewer options" else "More options", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Accent, modifier = Modifier.weight(1f))
            Text(if (more) "▲" else "▼", fontSize = 11.sp, color = DawColors.Accent)
        }

        if (more) {
            SynthCard(title = "Notes") {
                Text("Notes the voice can land on (tap to switch one off)", fontSize = 11.sp, color = DawColors.Muted)
                Spacer(Modifier.height(6.dp))
                NoteStrip(key, scale, allowed) { pc ->
                    val inScale = scale.steps.contains((pc - key).mod(12))
                    if (!inScale) return@NoteStrip
                    val off = settings.disabledNotes
                    onChange(settings.copy(key = key, scale = scale, disabledNotes = if (pc in off) off - pc else off + pc))
                }
            }
            SynthCard(title = "Voice") {
                Text("Extra voices", fontSize = 13.sp, color = DawColors.Ink)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    PitchVoices.entries.forEach { v ->
                        Pill(v.label, v == settings.voices) { onChange(settings.copy(voices = v)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transpose", fontSize = 13.sp, color = DawColors.Ink, modifier = Modifier.weight(1f))
                    Pill("−", false) { onChange(settings.copy(transpose = (settings.transpose - 1).coerceAtLeast(-12))) }
                    Text(
                        (if (settings.transpose > 0) "+" else "") + "${settings.transpose} st",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DawColors.Ink,
                        modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Pill("+", false) { onChange(settings.copy(transpose = (settings.transpose + 1).coerceAtMost(12))) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Humanize", fontSize = 13.sp, color = DawColors.Ink)
                        Text("Long notes keep a bit of their own movement", fontSize = 11.sp, color = DawColors.Muted)
                    }
                    FxKnob("", settings.humanize, KnobRange(0f, 1f, 0f), ::formatPercent, { onChange(settings.copy(humanize = it)) })
                }
                Spacer(Modifier.height(12.dp))
                Text("Singer's range", fontSize = 13.sp, color = DawColors.Ink)
                Spacer(Modifier.height(6.dp))
                Choices(listOf("Low", "Mid", "High"), settings.range, { onChange(settings.copy(range = it)) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A sound preset: name and one line on what it does. */
@Composable
private fun StyleTile(style: PitchStyle, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) DawColors.Note else DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(style.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) DawColors.OnNote else DawColors.Ink)
        Text(style.hint, fontSize = 11.sp, color = if (on) DawColors.OnNote else DawColors.Muted, maxLines = 2)
    }
}

/** Live readout: the note being sung and how far off it is before correction. */
@Composable
private fun Meter(detectedHz: Float, targetHz: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val voiced = detectedHz > 0f
        val midi = if (voiced) 69f + 12f * log2(detectedHz / 440f) else 0f
        val targetMidi = if (targetHz > 0f) 69f + 12f * log2(targetHz / 440f) else midi
        val note = targetMidi.roundToInt()
        val cents = ((midi - note) * 100f).roundToInt()
        Text(
            if (voiced) "${NOTE_NAMES[note.mod(12)]}${note / 12 - 1}" else "—",
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
            color = DawColors.Ink,
        )
        Text(
            if (voiced) "sung ${if (cents >= 0) "+" else ""}$cents cents · ${detectedHz.roundToInt()} Hz" else "Waiting for a voice on this track",
            fontSize = 12.sp,
            color = DawColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val w = size.width
            val mid = w / 2f
            drawRoundRect(DawColors.Chip, size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
            drawLine(DawColors.LineStrong, Offset(mid, 0f), Offset(mid, size.height), 2.dp.toPx())
            for (c in listOf(-25, 25)) {
                val x = mid + c / 50f * (w / 2f)
                drawLine(DawColors.Line, Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), 1.dp.toPx())
            }
            if (voiced) {
                val x = mid + (cents.coerceIn(-50, 50) / 50f) * (w / 2f - 8.dp.toPx())
                val color = if (kotlin.math.abs(cents) <= 10) Color(0xFF3E9C6E) else DawColors.Accent
                drawCircle(color, 8.dp.toPx(), Offset(x, size.height / 2f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("flat", fontSize = 10.sp, color = DawColors.Muted)
            Spacer(Modifier.weight(1f))
            Text("in tune", fontSize = 10.sp, color = DawColors.Muted)
            Spacer(Modifier.weight(1f))
            Text("sharp", fontSize = 10.sp, color = DawColors.Muted)
        }
    }
}

/** One octave of keys; allowed notes are filled. */
@Composable
private fun NoteStrip(key: Int, scale: PitchScale, allowed: Set<Int>, onToggle: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 12) {
            val pc = (key + i) % 12
            val on = pc in allowed
            val inScale = scale.steps.contains(i)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            on -> DawColors.Note
                            inScale -> DawColors.Chip
                            isBlackKey(pc + 60) -> DawColors.Line
                            else -> DawColors.Paper
                        },
                    )
                    .border(1.dp, DawColors.Line, RoundedCornerShape(8.dp))
                    .clickable(enabled = inScale) { onToggle(pc) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    NOTE_NAMES[pc],
                    fontSize = 10.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) DawColors.OnNote else DawColors.Muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}
