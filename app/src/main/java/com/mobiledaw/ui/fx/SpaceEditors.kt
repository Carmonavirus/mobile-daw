package com.mobiledaw.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.model.DelayMode
import com.mobiledaw.model.DelaySettings
import com.mobiledaw.model.DelayTime
import com.mobiledaw.model.ReverbSettings
import com.mobiledaw.model.ReverbSpace
import com.mobiledaw.ui.synth.Choices
import com.mobiledaw.ui.synth.KnobRow
import com.mobiledaw.ui.synth.SynthCard
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Delay: pick the echo length in song time, then how many repeats (Feedback), how dark they get
 * (Tone) and how loud they are (Wet). Ping-pong bounces the echoes between left and right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DelayEditor(settings: DelaySettings, bpm: Int, onChange: (DelaySettings) -> Unit) {
    Column {
        SynthCard(title = "Echoes") {
            EchoPicture(settings)
            Spacer(Modifier.height(6.dp))
            val ms = (settings.time.seconds(bpm) * 1000f).roundToInt()
            Text(
                "Each bar is one echo: ${settings.time.label} = $ms ms at $bpm BPM." +
                    if (settings.mode == DelayMode.PING_PONG) " Top = left, bottom = right." else "",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
        SynthCard(title = "Time") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                DelayTime.entries.forEach { t ->
                    Pill(t.label, t == settings.time) { onChange(settings.copy(time = t)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Choices(
                DelayMode.entries.map { it.label },
                settings.mode.ordinal,
                { onChange(settings.copy(mode = DelayMode.entries[it])) },
                Modifier.fillMaxWidth(),
            )
        }
        SynthCard(title = "Controls") {
            KnobRow {
                FxKnob(
                    "Feedback",
                    settings.feedback,
                    KnobRange(0f, DelaySettings.FEEDBACK_MAX, 0.4f),
                    ::formatPercent,
                    { onChange(settings.copy(feedback = snapPercent(it))) },
                )
                FxKnob(
                    "Tone",
                    settings.tone,
                    KnobRange(DelaySettings.TONE_MIN, DelaySettings.TONE_MAX, 6000f, log = true),
                    ::formatHz,
                    { onChange(settings.copy(tone = it.roundToInt().toFloat())) },
                )
                FxKnob("Dry", settings.dry, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(dry = snapPercent(it))) })
                FxKnob("Wet", settings.wet, KnobRange(0f, 1f, 0.35f), ::formatPercent, { onChange(settings.copy(wet = snapPercent(it))) })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Feedback: how many times it repeats.  Tone: lower makes the repeats darker.  Dry: your sound.  Wet: the echoes.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
    }
}

/**
 * Reverb: pick a space to start from, then shape it. Size and Decay set the room and how long
 * the tail lasts, High cut and Low cut its colour, Pre-delay the gap before it starts.
 */
@Composable
fun ReverbEditor(settings: ReverbSettings, onChange: (ReverbSettings) -> Unit) {
    Column {
        SynthCard(title = "Tail") {
            TailPicture(settings)
            Spacer(Modifier.height(6.dp))
            Text(
                "The gap is the pre-delay, the slope is how the room fades. Longer Decay = slower fade.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
        SynthCard(title = "Space") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReverbSpace.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { space ->
                            SpaceTile(space, space.matches(settings), Modifier.weight(1f)) {
                                onChange(space.settings.copy(dry = settings.dry, wet = settings.wet))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        SynthCard(title = "Room") {
            KnobRow {
                FxKnob(
                    "Pre-delay",
                    settings.preDelay,
                    KnobRange(0f, ReverbSettings.PRE_DELAY_MAX, 10f),
                    { "${it.roundToInt()} ms" },
                    { onChange(settings.copy(preDelay = it.roundToInt().toFloat())) },
                )
                FxKnob("Size", settings.size, KnobRange(0f, 1f, 0.5f), ::formatPercent, { onChange(settings.copy(size = snapPercent(it))) })
                FxKnob(
                    "Decay",
                    settings.decay,
                    KnobRange(ReverbSettings.DECAY_MIN, ReverbSettings.DECAY_MAX, 1.6f, log = true),
                    { "${"%.1f".format(it)} s" },
                    { onChange(settings.copy(decay = (it * 10f).roundToInt() / 10f)) },
                )
                FxKnob("Width", settings.width, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(width = snapPercent(it))) })
            }
        }
        SynthCard(title = "Colour and mix") {
            KnobRow {
                FxKnob(
                    "High cut",
                    settings.highCut,
                    KnobRange(ReverbSettings.HIGH_CUT_MIN, ReverbSettings.HIGH_CUT_MAX, 7000f, log = true),
                    ::formatHz,
                    { onChange(settings.copy(highCut = it.roundToInt().toFloat())) },
                )
                FxKnob(
                    "Low cut",
                    settings.lowCut,
                    KnobRange(ReverbSettings.LOW_CUT_MIN, ReverbSettings.LOW_CUT_MAX, 150f, log = true),
                    ::formatHz,
                    { onChange(settings.copy(lowCut = it.roundToInt().toFloat())) },
                )
                FxKnob("Dry", settings.dry, KnobRange(0f, 1f, 1f), ::formatPercent, { onChange(settings.copy(dry = snapPercent(it))) })
                FxKnob("Wet", settings.wet, KnobRange(0f, 1f, 0.25f), ::formatPercent, { onChange(settings.copy(wet = snapPercent(it))) })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "High cut: lower makes the room darker.  Low cut: keeps the reverb out of the bass.  Dry: your sound.  Wet: the room.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
    }
}

/** Percent knobs land on whole percents. */
private fun snapPercent(v: Float): Float = (v * 100f).roundToInt() / 100f

@Composable
private fun SpaceTile(space: ReverbSpace, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) DawColors.Note else DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(space.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) DawColors.OnNote else DawColors.Ink)
        Text(space.hint, fontSize = 11.sp, color = if (on) DawColors.OnNote else DawColors.Muted, maxLines = 2)
    }
}

/** The dry hit, then each echo as a bar that shrinks by Feedback; ping-pong alternates sides. */
@Composable
private fun EchoPicture(s: DelaySettings) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(2.6f)) {
        val w = size.width
        val h = size.height
        val pingPong = s.mode == DelayMode.PING_PONG
        val mid = h / 2
        drawLine(DawColors.Line, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
        val repeats = 12
        val step = w / (repeats + 1.5f)
        val barW = (step * 0.42f).coerceAtMost(14.dp.toPx())
        val radius = CornerRadius(barW / 2, barW / 2)
        // Higher tone settings keep more of the brightness: shown as a stronger colour.
        val bright = ((s.tone - DelaySettings.TONE_MIN) / (DelaySettings.TONE_MAX - DelaySettings.TONE_MIN)).coerceIn(0f, 1f)
        fun bar(i: Int, level: Float, up: Boolean, dry: Boolean) {
            val x = step * (i + 0.75f)
            val len = (mid - 4.dp.toPx()) * level.coerceIn(0f, 1f)
            if (len < 1f) return
            val y = if (pingPong && !up) mid else mid - len
            val height = if (pingPong) len else len * 2f
            val color = if (dry) DawColors.Note else DawColors.Accent.copy(alpha = 0.45f + 0.55f * bright)
            drawRoundRect(color, Offset(x - barW / 2, y), Size(barW, height), radius)
        }
        bar(0, s.dry, up = true, dry = true)
        if (pingPong) bar(0, s.dry, up = false, dry = true)
        for (k in 1..repeats) {
            val level = s.wet * s.feedback.pow(k - 1)
            bar(k, level, up = k % 2 == 1, dry = false)
        }
    }
}

/** Pre-delay gap, then an exponential fade over Decay seconds (drawn over a fixed 4 s window). */
@Composable
private fun TailPicture(s: ReverbSettings) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(2.6f)) {
        val w = size.width
        val h = size.height
        val window = 4f  // seconds shown
        fun px(t: Float) = t / window * w
        val base = h - 4.dp.toPx()
        drawLine(DawColors.Line, Offset(0f, base), Offset(w, base), 1.dp.toPx())
        // The dry hit.
        val dryH = (base - 6.dp.toPx()) * s.dry
        drawLine(DawColors.Note, Offset(px(0.02f), base), Offset(px(0.02f), base - dryH), 4.dp.toPx(), cap = StrokeCap.Round)
        val start = px(0.02f + s.preDelay / 1000f)
        val peak = (base - 6.dp.toPx()) * (0.25f + 0.75f * s.wet)
        val fill = Path()
        val line = Path()
        val steps = 120
        var lastX = start
        for (i in 0..steps) {
            val t = window * i / steps
            val x = start + px(t)
            if (x > w) break
            lastX = x
            // 60 dB over Decay seconds, drawn on a 60 dB scale so it reads as a straight-ish slope.
            val db = -60f * t / s.decay
            val y = base - peak * ((db + 60f) / 60f).coerceAtLeast(0f)
            if (i == 0) {
                fill.moveTo(x, base)
                fill.lineTo(x, y)
                line.moveTo(x, y)
            } else {
                fill.lineTo(x, y)
                line.lineTo(x, y)
            }
        }
        fill.lineTo(lastX, base)
        fill.close()
        drawPath(fill, DawColors.AccentSoft)
        drawPath(line, DawColors.Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
