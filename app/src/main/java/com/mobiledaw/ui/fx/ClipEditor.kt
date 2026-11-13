package com.mobiledaw.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.model.ClipSettings
import com.mobiledaw.ui.synth.SynthCard
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Soft Clipper: Gain pushes the sound in, Threshold is where the curve starts rounding off the
 * peaks (they never go past 0 dB), Post sets how loud it comes out.
 */
@Composable
fun ClipEditor(settings: ClipSettings, onChange: (ClipSettings) -> Unit) {
    Column {
        SynthCard(title = "Curve") {
            ClipCurve(settings)
            Spacer(Modifier.height(6.dp))
            Text(
                "Straight line = untouched. Where it bends, peaks are rounded off. More Gain pushes more of the sound into the bend.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
        SynthCard(title = "Controls") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                FxKnob(
                    "Gain",
                    settings.gain,
                    KnobRange(ClipSettings.GAIN_MIN, ClipSettings.GAIN_MAX, 0f),
                    ::formatDb,
                    { onChange(settings.copy(gain = snap(it))) },
                )
                FxKnob(
                    "Threshold",
                    settings.threshold,
                    KnobRange(ClipSettings.THRESHOLD_MIN, 0f, -6f),
                    ::formatDb,
                    { onChange(settings.copy(threshold = snap(it))) },
                )
                FxKnob(
                    "Post",
                    settings.post,
                    KnobRange(ClipSettings.POST_MIN, ClipSettings.POST_MAX, 0f),
                    ::formatDb,
                    { onChange(settings.copy(post = snap(it))) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Gain: how hard the sound hits the clipper.  Threshold: where clipping starts.  Post: the level that comes out.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
    }
}

/** Knob values land on 0.1 dB, and near 0 they stick to 0. */
private fun snap(db: Float): Float = if (abs(db) < 0.25f) 0f else (db * 10f).toInt() / 10f

private fun dbToGain(db: Float) = 10f.pow(db / 20f)

/** Same curve as fx::SoftClipper::shape. */
private fun shape(x: Float, threshold: Float): Float {
    val a = abs(x)
    if (a <= threshold) return x
    if (threshold >= 0.999f) return if (x > 0f) 1f else -1f
    val room = 1f - threshold
    val y = threshold + room * tanh((a - threshold) / room)
    return if (x > 0f) y else -y
}

/** Input level (across) against output level (up), from -1 to 1 on both. */
@Composable
private fun ClipCurve(s: ClipSettings) {
    val gain = dbToGain(s.gain)
    val threshold = dbToGain(s.threshold)
    val post = dbToGain(s.post)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
        val w = size.width
        val h = size.height
        fun px(x: Float) = (x + 1f) / 2f * w
        fun py(y: Float) = (1f - (y.coerceIn(-1.1f, 1.1f) + 1f) / 2f) * h
        val thin = 1.dp.toPx()
        drawLine(DawColors.Line, Offset(0f, h / 2), Offset(w, h / 2), thin)
        drawLine(DawColors.Line, Offset(w / 2, 0f), Offset(w / 2, h), thin)
        // Unprocessed reference.
        drawLine(DawColors.LineStrong, Offset(px(-1f), py(-1f)), Offset(px(1f), py(1f)), thin)
        // Threshold (where the bend starts, at the output).
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        val ty = threshold * post
        drawLine(DawColors.AccentSoft, Offset(0f, py(ty)), Offset(w, py(ty)), thin * 1.5f, pathEffect = dash)
        drawLine(DawColors.AccentSoft, Offset(0f, py(-ty)), Offset(w, py(-ty)), thin * 1.5f, pathEffect = dash)
        val path = Path()
        val steps = 160
        for (i in 0..steps) {
            val x = -1f + 2f * i / steps
            val y = shape(x * gain, threshold) * post
            if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
        }
        drawPath(path, DawColors.Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
