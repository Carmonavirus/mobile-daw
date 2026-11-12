package com.mobiledaw.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.PlayMode
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.ui.theme.DawColors

private const val LANES = 8
private val LANE_H = 52.dp
private val BAR_W = 56.dp
private val HEADER_H = 24.dp
private val GUTTER_W = 40.dp

/**
 * The arrangement, FL-style: time runs left to right (one column per bar), tracks are rows.
 * Swipe sideways to move through the song.
 */
@Composable
fun PlaylistScreen(vm: DawViewModel) {
    val project = vm.project
    val bars = maxOf(32, project.songBars + 8)
    val measurer = rememberTextMeasurer()
    val projectState = rememberUpdatedState(project)

    Column(Modifier.fillMaxSize()) {
        PatternBar(vm)
        Text(
            "Tap a cell to place the selected pattern, tap a clip to remove it.",
            fontSize = 11.sp,
            color = DawColors.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        ) {
            // Track names stay put while the grid scrolls.
            Column(Modifier.width(GUTTER_W)) {
                Box(Modifier.height(HEADER_H))
                for (lane in 0 until LANES) {
                    Box(Modifier.height(LANE_H).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("${lane + 1}", fontSize = 12.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Canvas(
                    Modifier
                        .width(BAR_W * bars)
                        .height(HEADER_H + LANE_H * LANES)
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val header = HEADER_H.toPx()
                                if (pos.y < header) return@detectTapGestures
                                val lane = ((pos.y - header) / LANE_H.toPx()).toInt()
                                val bar = (pos.x / BAR_W.toPx()).toInt()
                                if (lane in 0 until LANES) vm.toggleClip(lane, bar)
                            }
                        },
                ) {
                    val p = projectState.value
                    val bw = BAR_W.toPx()
                    val lh = LANE_H.toPx()
                    val header = HEADER_H.toPx()
                    val gap = 2.dp.toPx()
                    val numberStyle = TextStyle(fontSize = 10.sp, color = DawColors.Muted)

                    // Bar numbers along the top.
                    for (bar in 0 until bars) {
                        if (bar % 4 == 0) {
                            val label = measurer.measure("${bar + 1}", numberStyle)
                            drawText(label, topLeft = Offset(bar * bw + 4.dp.toPx(), (header - label.size.height) / 2f))
                        }
                    }
                    // Cells, shaded in groups of four bars.
                    for (lane in 0 until LANES) {
                        for (bar in 0 until bars) {
                            drawRoundRect(
                                if (bar % 8 < 4) DawColors.Card else DawColors.Chip.copy(alpha = 0.6f),
                                Offset(bar * bw + gap, header + lane * lh + gap),
                                Size(bw - gap * 2, lh - gap * 2),
                                CornerRadius(8.dp.toPx()),
                            )
                        }
                    }

                    // Clips
                    p.clips.forEach { clip ->
                        val index = p.patterns.indexOfFirst { it.id == clip.patternId }
                        if (index < 0 || clip.lane >= LANES) return@forEach
                        val pattern = p.patterns[index]
                        val color = DawColors.PatternColors[index % DawColors.PatternColors.size]
                        val x = clip.bar * bw + gap
                        val y = header + clip.lane * lh + gap
                        val w = pattern.bars * bw - gap * 2
                        drawRoundRect(color, Offset(x, y), Size(w, lh - gap * 2), CornerRadius(10.dp.toPx()))
                        val textColor = if (color.luminance() > 0.5f) Color(0xFF1D1D1F) else Color.White
                        val label = measurer.measure(
                            pattern.name,
                            TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (w - 12.dp.toPx()).toInt().coerceAtLeast(1)),
                        )
                        drawText(label, topLeft = Offset(x + 6.dp.toPx(), y + 6.dp.toPx()))
                    }

                    // Song playhead
                    if (vm.playing && vm.mode == PlayMode.SONG) {
                        val x = vm.positionTicks.toFloat() / TICKS_PER_BAR * bw
                        drawLine(DawColors.Accent, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    }
                }
            }
        }
    }
}
