package com.mobiledaw.ui.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.model.SynthParams
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** How a knob's travel maps onto a parameter's range. */
enum class Curve { LINEAR, LOG, TIME }

internal fun toNorm(info: SynthParams.Info, v: Float, curve: Curve): Float {
    val span = info.max - info.min
    return when (curve) {
        Curve.LINEAR -> (v - info.min) / span
        Curve.LOG -> (ln(v / info.min) / ln(info.max / info.min))
        Curve.TIME -> cbrt(((v - info.min) / span).coerceAtLeast(0f))
    }.coerceIn(0f, 1f)
}

internal fun fromNorm(info: SynthParams.Info, n: Float, curve: Curve): Float {
    val x = n.coerceIn(0f, 1f)
    val v = when (curve) {
        Curve.LINEAR -> info.min + x * (info.max - info.min)
        Curve.LOG -> info.min * exp(x * ln(info.max / info.min))
        Curve.TIME -> info.min + x * x * x * (info.max - info.min)
    }
    return if (info.stepped) v.roundToInt().toFloat() else v
}

/** Human-readable value for a parameter. */
internal fun formatValue(name: String, v: Float): String = when {
    name == "sub_oct" || name.endsWith("_unison") -> v.roundToInt().toString()
    name == "flt_cutoff" -> if (v >= 1000f) "%.1f kHz".format(v / 1000f) else "${v.roundToInt()} Hz"
    name == "lfo_rate" -> if (v < 10f) "%.2f Hz".format(v) else "%.1f Hz".format(v)
    name.endsWith("_a") || name.endsWith("_d") || name.endsWith("_r") || name == "glide" ->
        if (v < 1f) "${(v * 1000).roundToInt()} ms" else "%.2f s".format(v)
    name == "mod_pitch" || name == "lfo_pitch" -> "%+.1f st".format(v)
    name == "volume" -> com.mobiledaw.ui.formatGainDb(v)
    name.endsWith("_fine") -> "%+d ct".format(v.roundToInt())
    name.endsWith("_semi") || name.endsWith("_oct") -> "%+d".format(v.roundToInt())
    (SynthParams.byName(name)?.min ?: 0f) < 0f -> "%+d%%".format((v * 100).roundToInt())
    else -> "${(v * 100).roundToInt()}%"
}

/**
 * A round knob. Drag up/right to raise, down/left to lower; double-tap resets it.
 * Bipolar parameters (with a negative minimum) fill from the centre.
 */
@Composable
fun Knob(
    name: String,
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    curve: Curve = Curve.LINEAR,
    enabled: Boolean = true,
) {
    val info = SynthParams.byName(name) ?: return
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    var dragNorm by remember { mutableStateOf<Float?>(null) }
    val norm = dragNorm ?: toNorm(info, value, curve)
    val travel = with(LocalDensity.current) { 220.dp.toPx() }
    val ink = if (enabled) DawColors.Ink else DawColors.LineStrong
    val accent = if (enabled) DawColors.Accent else DawColors.LineStrong

    Column(modifier.width(70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(48.dp)
                .pointerInput(name) {
                    detectTapGestures(onDoubleTap = { change(info.default) })
                }
                .pointerInput(name, curve) {
                    detectDragGestures(
                        onDragStart = { dragNorm = toNorm(info, current, curve) },
                        onDragEnd = { dragNorm = null },
                        onDragCancel = { dragNorm = null },
                    ) { pointer, delta ->
                        pointer.consume()
                        val next = ((dragNorm ?: 0f) + (delta.x - delta.y) / travel).coerceIn(0f, 1f)
                        dragNorm = next
                        change(fromNorm(info, next, curve))
                    }
                },
        ) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(DawColors.Line, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val bipolar = info.min < 0f
            val start = if (bipolar) 270f else 135f
            val sweep = if (bipolar) (norm - 0.5f) * 270f else norm * 270f
            if (abs(sweep) > 0.5f) {
                drawArc(accent, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val angle = Math.toRadians((135.0 + norm * 270.0))
            val r = arcSize.width / 2 - stroke * 1.6f
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(DawColors.Card, radius = arcSize.width / 2 - stroke, center = c)
            drawLine(
                ink,
                c + Offset((kotlin.math.cos(angle) * r * 0.35).toFloat(), (kotlin.math.sin(angle) * r * 0.35).toFloat()),
                c + Offset((kotlin.math.cos(angle) * r).toFloat(), (kotlin.math.sin(angle) * r).toFloat()),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(label, fontSize = 11.sp, color = DawColors.InkSoft, maxLines = 1, textAlign = TextAlign.Center)
        Text(
            formatValue(name, if (dragNorm != null) fromNorm(info, norm, curve) else value),
            fontSize = 10.sp,
            color = if (dragNorm != null) DawColors.Accent else DawColors.Muted,
            maxLines = 1,
        )
    }
}

/** "− value +" for whole-number parameters such as octave or unison voices. */
@Composable
fun Stepper(name: String, label: String, value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val info = SynthParams.byName(name) ?: return
    val v = value.roundToInt()
    Column(modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", v > info.min) { onChange((v - 1).toFloat()) }
            Text(
                formatValue(name, v.toFloat()),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(30.dp),
            )
            StepButton("+", v < info.max) { onChange((v + 1).toFloat()) }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = DawColors.InkSoft)
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

/** Small on/off pill. */
@Composable
fun Switch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) DawColors.Note else DawColors.Chip)
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) DawColors.Accent else DawColors.LineStrong))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) DawColors.OnNote else DawColors.Muted)
    }
}

/** A row of choices; the selected one is filled. */
@Composable
fun Choices(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) DawColors.Card else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = if (on) DawColors.Ink else DawColors.Muted,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Rounded card used for each block of the synth. */
@Composable
fun SynthCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Evenly spaced knobs that wrap onto as many rows as needed. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KnobRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
