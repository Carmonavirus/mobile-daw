package com.mobiledaw.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** A knob's range. [log] spreads it evenly in ratios (frequency, time), [bipolar] fills from the centre. */
data class KnobRange(
    val min: Float,
    val max: Float,
    val default: Float,
    val log: Boolean = false,
    val bipolar: Boolean = false,
) {
    fun toNorm(v: Float): Float = if (log) {
        (ln(v.coerceIn(min, max) / min) / ln(max / min)).toFloat()
    } else {
        ((v - min) / (max - min)).coerceIn(0f, 1f)
    }

    fun fromNorm(n: Float): Float = if (log) {
        (min * exp(n.coerceIn(0f, 1f) * ln(max / min).toDouble()).toFloat())
    } else {
        min + n.coerceIn(0f, 1f) * (max - min)
    }
}

/** Round knob in the synth's style, for effect parameters. Drag up/right to raise; double-tap resets. */
@Composable
fun FxKnob(
    label: String,
    value: Float,
    range: KnobRange,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    var dragNorm by remember { mutableStateOf<Float?>(null) }
    val norm = dragNorm ?: range.toNorm(value)
    val travel = with(LocalDensity.current) { 220.dp.toPx() }
    val ink = if (enabled) DawColors.Ink else DawColors.LineStrong
    val accent = if (enabled) DawColors.Accent else DawColors.LineStrong

    Column(modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(48.dp)
                .pointerInput(range) { detectTapGestures(onDoubleTap = { if (enabled) change(range.default) }) }
                .pointerInput(range, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { dragNorm = range.toNorm(current) },
                        onDragEnd = { dragNorm = null },
                        onDragCancel = { dragNorm = null },
                    ) { pointer, delta ->
                        pointer.consume()
                        val next = ((dragNorm ?: 0f) + (delta.x - delta.y) / travel).coerceIn(0f, 1f)
                        dragNorm = next
                        change(range.fromNorm(next))
                    }
                },
        ) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(DawColors.Line, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val start = if (range.bipolar) 270f else 135f
            val sweep = if (range.bipolar) (norm - 0.5f) * 270f else norm * 270f
            if (abs(sweep) > 0.5f) drawArc(accent, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians(135.0 + norm * 270.0)
            val r = arcSize.width / 2 - stroke * 1.6f
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(DawColors.Card, radius = arcSize.width / 2 - stroke, center = c)
            drawLine(
                ink,
                c + Offset((cos(angle) * r * 0.35).toFloat(), (sin(angle) * r * 0.35).toFloat()),
                c + Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat()),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(label, fontSize = 11.sp, color = DawColors.InkSoft, maxLines = 1, textAlign = TextAlign.Center)
        Text(
            format(if (dragNorm != null) range.fromNorm(norm) else value),
            fontSize = 10.sp,
            color = if (dragNorm != null) DawColors.Accent else DawColors.Muted,
            maxLines = 1,
        )
    }
}

fun formatHz(hz: Float): String = when {
    hz >= 10000f -> "${(hz / 1000f).roundToInt()} kHz"
    hz >= 1000f -> "${"%.1f".format(hz / 1000f)} kHz"
    else -> "${hz.roundToInt()} Hz"
}

fun formatDb(db: Float): String = (if (db > 0.05f) "+" else "") + "%.1f dB".format(db)

fun formatPercent(v: Float): String = "${(v * 100).roundToInt()}%"
