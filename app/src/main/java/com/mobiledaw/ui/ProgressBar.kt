package com.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.STEPS_PER_BAR
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.ui.theme.DawColors

/**
 * Media-player style progress bar for the pattern (PAT) or the whole song (SONG).
 * Shows the current bar.beat on the left and the total length on the right.
 * Tap to jump, drag to scrub (the jump happens when the finger lifts, so playback doesn't stutter).
 */
@Composable
fun TransportProgress(vm: DawViewModel, modifier: Modifier = Modifier) {
    val total = vm.loopTicks.coerceAtLeast(TICKS_PER_STEP)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val position = (vm.positionTicks % total).toFloat()
    val fraction = dragFraction ?: (position / total)
    val shownTicks = (fraction * total).toInt()

    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            barBeat(shownTicks),
            fontSize = 11.sp,
            color = DawColors.Muted,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(48.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(22.dp)
                .pointerInput(total) {
                    detectTapGestures { vm.seekTo(it.x / size.width) }
                }
                .pointerInput(total) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFraction?.let(vm::seekTo); dragFraction = null },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val track = 3.dp.toPx()
            val y = size.height / 2f
            val radius = CornerRadius(track / 2f)
            drawRoundRect(DawColors.Line, Offset(0f, y - track / 2f), Size(size.width, track), radius)
            // Thin ticks at each bar line so longer songs are easy to read.
            val bars = total / TICKS_PER_BAR
            if (bars in 2..32) {
                for (b in 1 until bars) {
                    val x = size.width * b / bars
                    drawLine(DawColors.Paper, Offset(x, y - track), Offset(x, y + track), 1.dp.toPx())
                }
            }
            val fx = size.width * fraction.coerceIn(0f, 1f)
            drawRoundRect(DawColors.Accent, Offset(0f, y - track / 2f), Size(fx, track), radius)
            val knob = if (dragFraction != null) 7.dp.toPx() else 5.dp.toPx()
            drawCircle(DawColors.Accent, knob, Offset(fx, y))
        }
        Text(
            lengthLabel(total),
            fontSize = 11.sp,
            color = DawColors.Muted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

/** "3.2" = bar 3, beat 2 (1-based, like FL's song position). */
private fun barBeat(ticks: Int): String {
    val bar = ticks / TICKS_PER_BAR + 1
    val beat = (ticks % TICKS_PER_BAR) / (TICKS_PER_STEP * 4) + 1
    return "$bar.$beat"
}

private fun lengthLabel(ticks: Int): String {
    val bars = ticks / TICKS_PER_BAR
    return if (bars >= 1 && ticks % TICKS_PER_BAR == 0) "$bars bar${if (bars == 1) "" else "s"}"
    else "${ticks / TICKS_PER_STEP}/${STEPS_PER_BAR}"
}
