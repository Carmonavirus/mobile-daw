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
import com.mobiledaw.model.Project
import com.mobiledaw.model.isBlackKey
import com.mobiledaw.ui.synth.Choices
import com.mobiledaw.ui.synth.SynthCard
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.delay
import kotlin.math.log2
import kotlin.math.roundToInt

/** Quick starting points for the pitch corrector. */
private val STYLES = listOf(
    Triple("Natural", 90f, 0.4f),
    Triple("Tight", 25f, 0.15f),
    Triple("Robot", 0f, 0f),
)

/**
 * Pitch Correct: pulls a voice to the nearest note of a key. Retune speed sets how fast:
 * 0 ms is the hard, robotic effect; slower speeds just tidy the pitch up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PitchEditor(vm: DawViewModel, trackId: Long, effectId: Long, project: Project, settings: PitchSettings, onChange: (PitchSettings) -> Unit) {
    var reading by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
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

    Column {
        Meter(reading[0], reading[1])

        SynthCard(title = "Style") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STYLES.forEach { (name, speed, human) ->
                    val on = settings.retuneMs == speed && settings.humanize == human
                    Box(Modifier.weight(1f)) {
                        Pill(name, on) { onChange(settings.copy(retuneMs = speed, humanize = human, amount = 1f)) }
                    }
                }
            }
        }

        SynthCard(
            title = "Key",
            trailing = {
                Pill(if (settings.key == null && settings.scale == null) "Follows song key" else "Use song key", settings.key == null && settings.scale == null) {
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
            Spacer(Modifier.height(12.dp))
            Text("Notes the voice can land on (tap to switch one off)", fontSize = 11.sp, color = DawColors.Muted)
            Spacer(Modifier.height(6.dp))
            NoteStrip(key, scale, allowed) { pc ->
                val inScale = scale.steps.contains((pc - key).mod(12))
                if (!inScale) return@NoteStrip
                val off = settings.disabledNotes
                onChange(settings.copy(key = key, scale = scale, disabledNotes = if (pc in off) off - pc else off + pc))
            }
        }

        SynthCard(title = "Correction") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                FxKnob("Retune speed", settings.retuneMs, KnobRange(0f, 400f, 50f), { if (it < 1f) "Instant" else "${it.roundToInt()} ms" }, { onChange(settings.copy(retuneMs = if (it < 2f) 0f else it)) })
                FxKnob("Amount", settings.amount, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(amount = it)) })
                FxKnob("Humanize", settings.humanize, KnobRange(0f, 1f, 0.2f), ::formatPercent, { onChange(settings.copy(humanize = it)) })
                FxKnob("Mix", settings.mix, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(mix = it)) })
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
            Text("Voice", fontSize = 13.sp, color = DawColors.Ink)
            Spacer(Modifier.height(6.dp))
            Choices(listOf("Low", "Mid", "High"), settings.range, { onChange(settings.copy(range = it)) }, Modifier.fillMaxWidth())
        }
        Text(
            "Instant retune gives the hard, robotic effect. 50–100 ms keeps it natural. Humanize lets long notes keep a little of their own movement.",
            fontSize = 12.sp,
            color = DawColors.Muted,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
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
