package com.mobiledaw.ui

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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.cos
import kotlin.math.sin

/** Where a double-click puts the master volume back to (the new-project level). */
const val MASTER_VOLUME_DEFAULT = 0.7f

/**
 * Small master volume knob for the top bar on a PC (FL Studio keeps one in its toolbar).
 * Drag up/right to raise, scroll the mouse wheel over it, double-click to reset.
 * The same level as the Master strip in the mixer.
 */
@Composable
fun MasterVolumeKnob(volume: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(volume)
    var dragging by remember { mutableStateOf(false) }
    val travel = with(LocalDensity.current) { 180.dp.toPx() }
    val readout = formatGainDb(volume)

    Column(
        modifier.width(48.dp).semantics { contentDescription = "Master volume $readout" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(30.dp)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(MASTER_VOLUME_DEFAULT) }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { pointer, delta ->
                        pointer.consume()
                        change((current + (delta.x - delta.y) / travel).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val c = event.changes.firstOrNull() ?: continue
                            val d = c.scrollDelta.y
                            if (d == 0f) continue
                            // One wheel notch = 2 %.
                            val step = if (Wheel.reportsPixels) d / 100f * 0.02f else d * 0.02f
                            change((current - step).coerceIn(0f, 1f))
                            c.consume()
                        }
                    }
                },
        ) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val norm = current.coerceIn(0f, 1f)
            drawArc(DawColors.Line, 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (norm * 270f > 0.5f) drawArc(DawColors.Accent, 135f, norm * 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val c = Offset(size.width / 2, size.height / 2)
            val r = arc.width / 2 - stroke
            drawCircle(DawColors.Card, radius = r, center = c)
            val angle = Math.toRadians(135.0 + norm * 270.0)
            drawLine(
                DawColors.Ink,
                c + Offset((cos(angle) * r * 0.3).toFloat(), (sin(angle) * r * 0.3).toFloat()),
                c + Offset((cos(angle) * r * 0.9).toFloat(), (sin(angle) * r * 0.9).toFloat()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(
            if (dragging) readout else "VOL " + readout.removeSuffix(" dB"),
            fontSize = 9.sp,
            color = if (dragging) DawColors.Accent else DawColors.Muted,
            maxLines = 1,
        )
    }
}
