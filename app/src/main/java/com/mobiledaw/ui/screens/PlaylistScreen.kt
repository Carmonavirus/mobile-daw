package com.mobiledaw.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.PlayMode
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.ui.theme.DawColors

private const val LANES = 4

/**
 * The arrangement. Time runs downwards (one row per bar) so a phone held upright shows a long
 * stretch of the song; lanes run across.
 */
@Composable
fun PlaylistScreen(vm: DawViewModel) {
    val project = vm.project
    val rows = maxOf(32, project.songBars + 8)
    val rowH = 44.dp
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
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(rowH * rows)
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            val gutter = 32.dp.toPx()
                            val laneW = (size.width - gutter) / LANES
                            val lane = ((pos.x - gutter) / laneW).toInt()
                            val bar = (pos.y / rowH.toPx()).toInt()
                            if (pos.x >= gutter && lane in 0 until LANES) vm.toggleClip(lane, bar)
                        }
                    },
            ) {
                val p = projectState.value
                val gutter = 32.dp.toPx()
                val laneW = (size.width - gutter) / LANES
                val rh = rowH.toPx()
                val numberStyle = TextStyle(fontSize = 10.sp, color = DawColors.Muted)

                // Grid
                for (bar in 0 until rows) {
                    val y = bar * rh
                    if (bar % 4 == 0) {
                        val label = measurer.measure("${bar + 1}", numberStyle)
                        drawText(label, topLeft = Offset(4.dp.toPx(), y + 4.dp.toPx()))
                    }
                    for (lane in 0 until LANES) {
                        drawRoundRect(
                            if (bar % 8 < 4) DawColors.Card else DawColors.Chip.copy(alpha = 0.6f),
                            Offset(gutter + lane * laneW + 2.dp.toPx(), y + 2.dp.toPx()),
                            Size(laneW - 4.dp.toPx(), rh - 4.dp.toPx()),
                            CornerRadius(8.dp.toPx()),
                        )
                    }
                }

                // Clips
                p.clips.forEach { clip ->
                    val index = p.patterns.indexOfFirst { it.id == clip.patternId }
                    if (index < 0) return@forEach
                    val pattern = p.patterns[index]
                    val color = DawColors.PatternColors[index % DawColors.PatternColors.size]
                    val x = gutter + clip.lane * laneW + 2.dp.toPx()
                    val y = clip.bar * rh + 2.dp.toPx()
                    val h = pattern.bars * rh - 4.dp.toPx()
                    drawRoundRect(color, Offset(x, y), Size(laneW - 4.dp.toPx(), h), CornerRadius(10.dp.toPx()))
                    val textColor = if (color.luminance() > 0.5f) DawColors.Ink else Color.White
                    val label = measurer.measure(
                        pattern.name,
                        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor),
                        maxLines = 1,
                    )
                    drawText(label, topLeft = Offset(x + 8.dp.toPx(), y + 6.dp.toPx()))
                }

                // Song playhead
                if (vm.playing && vm.mode == PlayMode.SONG) {
                    val y = vm.positionTicks.toFloat() / TICKS_PER_BAR * rh
                    drawLine(DawColors.Accent, Offset(gutter, y), Offset(size.width, y), 2.dp.toPx())
                }
            }
        }
    }
}
