package com.mobiledaw.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.Note
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.model.TICKS_PER_BEAT
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.model.isBlackKey
import com.mobiledaw.model.inScale
import com.mobiledaw.model.noteName
import com.mobiledaw.ui.DawChip
import com.mobiledaw.ui.Segmented
import com.mobiledaw.ui.WaveformStrip
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.floor
import kotlin.math.roundToInt

private const val LOWEST_PITCH = 12   // C0
private const val HIGHEST_PITCH = 108 // C8

@Composable
fun PianoRollScreen(vm: DawViewModel) {
    val channel = vm.selectedChannel

    Column(Modifier.fillMaxSize()) {
        PatternBar(vm)
        // Track chips: which channel the piano roll is editing. Solid background so the
        // grid below can never show through behind them.
        Row(
            Modifier
                .fillMaxWidth()
                .background(DawColors.Paper)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            vm.project.channels.forEach { ch ->
                val hasNotes = vm.selectedPattern.notesFor(ch.id).isNotEmpty()
                DawChip(
                    ch.name,
                    selected = ch.id == vm.selectedChannelId,
                    dotColor = if (hasNotes) DawColors.Note else DawColors.Accent,
                ) { vm.selectChannel(ch.id) }
            }
        }

        if (channel == null) return@Column

        // clipToBounds stops any note/label drawing from spilling up over the track chips.
        PianoRollCanvas(vm, Modifier.weight(1f).fillMaxWidth().clipToBounds())

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("GRID", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 10.dp))
            Segmented(
                options = listOf(
                    TICKS_PER_STEP / 2 to "1/32",
                    TICKS_PER_STEP to "1/16",
                    TICKS_PER_STEP * 2 to "1/8",
                    TICKS_PER_BEAT to "1/4",
                    TICKS_PER_BEAT * 2 to "1/2",
                ),
                selected = vm.drawLength,
                onSelect = { vm.drawLength = it },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("NOTES", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 10.dp))
            Segmented(
                options = listOf(false to "Key ${vm.project.keyLabel}", true to "All"),
                selected = vm.labelAllNotes,
                onSelect = vm::changeLabelAllNotes,
            )
        }
        WaveformStrip(
            vm.scope,
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private enum class DragMode { NONE, PAN, MOVE, RESIZE }

@Composable
private fun PianoRollCanvas(vm: DawViewModel, modifier: Modifier) {
    val channel = vm.selectedChannel ?: return
    val pattern = vm.selectedPattern
    val density = LocalDensity.current
    val rowH = with(density) { 22.dp.toPx() }
    val barH = with(density) { 10.dp.toPx() }
    val edgeGrab = with(density) { 18.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
    val gutterStyle = TextStyle(fontSize = 9.sp, color = DawColors.Muted)
    val labels = remember { HashMap<Int, TextLayoutResult>() }
    val gutterLabels = remember { HashMap<Int, TextLayoutResult>() }
    val rootStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
    val rootLabels = remember { HashMap<Int, TextLayoutResult>() }
    val project = vm.project
    val labelAll = vm.labelAllNotes

    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val gutter = with(density) { 28.dp.toPx() }
        // Default zoom: one bar fills the width.
        var pxPerTick by remember { mutableFloatStateOf((widthPx - gutter) / TICKS_PER_BAR) }
        var scrollX by remember { mutableFloatStateOf(0f) }
        var scrollY by remember { mutableFloatStateOf(0f) }
        val contentHeight = (HIGHEST_PITCH - LOWEST_PITCH + 1) * rowH

        // Center the view on the selected channel's notes when it changes.
        LaunchedEffect(channel.id) {
            val notes = pattern.notesFor(channel.id)
            val center = if (notes.isEmpty()) channel.instrument.defaultPitch else notes.map { it.pitch }.average().roundToInt()
            scrollY = ((HIGHEST_PITCH - center) * rowH - heightPx / 2f).coerceIn(0f, (contentHeight - heightPx).coerceAtLeast(0f))
            scrollX = 0f
        }

        val notesState = rememberUpdatedState(pattern.notesFor(channel.id))
        val lengthState = rememberUpdatedState(pattern.lengthTicks)
        val channelState = rememberUpdatedState(channel)
        val drawLengthState = rememberUpdatedState(vm.drawLength)

        fun tickAt(x: Float) = (x - gutter + scrollX) / pxPerTick
        fun pitchAt(y: Float) = HIGHEST_PITCH - floor((y + scrollY) / rowH).toInt()
        fun noteX(tick: Int) = gutter + tick * pxPerTick - scrollX
        fun noteY(pitch: Int) = (HIGHEST_PITCH - pitch) * rowH - scrollY
        fun clampScroll() {
            val maxX = (lengthState.value * pxPerTick - (widthPx - gutter) * 0.5f).coerceAtLeast(0f)
            scrollX = scrollX.coerceIn(0f, maxX)
            scrollY = scrollY.coerceIn(0f, (contentHeight - heightPx).coerceAtLeast(0f))
        }
        fun hitNote(pos: Offset): Int {
            val pitch = pitchAt(pos.y)
            val tick = tickAt(pos.x)
            return notesState.value.indexOfLast { it.pitch == pitch && tick >= it.start && tick <= it.start + it.length }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val hit = hitNote(down.position)
                        val original: Note? = notesState.value.getOrNull(hit)
                        // One-finger-scroll mode: a drag always pans; a tap still places/deletes.
                        var mode = when {
                            vm.oneFingerScroll -> DragMode.PAN
                            original == null -> DragMode.PAN
                            noteX(original.start + original.length) - down.position.x < edgeGrab -> DragMode.RESIZE
                            else -> DragMode.MOVE
                        }
                        var total = Offset.Zero
                        var dragging = false
                        var pinched = false
                        var zoomAccum = 1f
                        var zooming = false
                        var lastApplied = original
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // Two fingers: drag to scroll in any direction. Spreading or pinching
                                // the fingers clearly apart zooms time around the fingers.
                                pinched = true
                                mode = DragMode.NONE
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                zoomAccum *= zoom
                                if (!zooming && kotlin.math.abs(zoomAccum - 1f) > 0.12f) zooming = true
                                if (zooming && zoom != 1f) {
                                    val minZoom = (widthPx - gutter) / (TICKS_PER_BAR * 8f)
                                    val maxZoom = (widthPx - gutter) / (TICKS_PER_STEP * 4f)
                                    val focusX = event.calculateCentroid().x
                                    val focusTick = tickAt(focusX)
                                    pxPerTick = (pxPerTick * zoom).coerceIn(minZoom, maxZoom)
                                    scrollX = focusTick * pxPerTick - (focusX - gutter)
                                }
                                scrollX -= pan.x
                                scrollY -= pan.y
                                clampScroll()
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinched) continue
                            val change = pressed.first()
                            total += change.positionChange()
                            if (!dragging && total.getDistance() > touchSlop) dragging = true
                            if (!dragging) continue
                            change.consume()
                            when (mode) {
                                DragMode.PAN -> {
                                    scrollX -= change.positionChange().x
                                    scrollY -= change.positionChange().y
                                    clampScroll()
                                }
                                DragMode.MOVE -> if (original != null) {
                                    val dTicks = total.x / pxPerTick
                                    val g = drawLengthState.value
                                    val start = (((original.start + dTicks) / g).roundToInt() * g)
                                        .coerceIn(0, maxOf(0, lengthState.value - g))
                                    val pitch = (original.pitch - (total.y / rowH).roundToInt()).coerceIn(LOWEST_PITCH, HIGHEST_PITCH)
                                    val moved = original.copy(start = start, pitch = pitch)
                                    if (moved != lastApplied) {
                                        if (moved.pitch != lastApplied?.pitch) vm.editPreview(channelState.value, moved.pitch)
                                        vm.replaceNote(channelState.value.id, hit, moved)
                                        lastApplied = moved
                                    }
                                }
                                DragMode.RESIZE -> if (original != null) {
                                    val dTicks = total.x / pxPerTick
                                    val g = drawLengthState.value
                                    val length = (((original.length + dTicks) / g).roundToInt() * g)
                                        .coerceIn(minOf(g, lengthState.value - original.start), lengthState.value - original.start)
                                    val resized = original.copy(length = length)
                                    if (resized != lastApplied) {
                                        vm.replaceNote(channelState.value.id, hit, resized)
                                        lastApplied = resized
                                    }
                                }
                                DragMode.NONE -> Unit
                            }
                        }
                        if (!dragging && !pinched) {
                            // Tap: delete a note, or draw a new one.
                            if (original != null) {
                                vm.deleteNote(channelState.value.id, hit)
                            } else {
                                val tick = tickAt(down.position.x)
                                val pitch = pitchAt(down.position.y)
                                if (tick >= 0 && tick < lengthState.value && pitch in LOWEST_PITCH..HIGHEST_PITCH) {
                                    val g = drawLengthState.value
                                    val start = (floor(tick / g).toInt()) * g
                                    val length = drawLengthState.value.coerceAtMost(lengthState.value - start)
                                    vm.addNote(channelState.value.id, Note(start, length, pitch))
                                }
                            }
                        }
                    }
                },
        ) {
            val firstPitch = pitchAt(size.height).coerceAtLeast(LOWEST_PITCH)
            val lastPitch = pitchAt(0f).coerceAtMost(HIGHEST_PITCH)

            // Row shading. In "Key" mode (FL-style): notes in the project scale stay light,
            // out-of-scale notes are darkened. In "All" mode: the usual piano black-key shading.
            for (p in firstPitch..lastPitch) {
                val y = noteY(p)
                val darken = if (labelAll) isBlackKey(p) else !inScale(p, project.key, project.minor)
                if (darken) {
                    drawRect(DawColors.Chip.copy(alpha = 0.55f), Offset(0f, y), Size(size.width, rowH))
                }
                if (p % 12 == 0) {
                    drawLine(DawColors.Line, Offset(0f, y + rowH), Offset(size.width, y + rowH), 1f)
                }
                // Labels: every note, or only the project's key (Notes switch below). The root is darker.
                val isRoot = (p - project.key).mod(12) == 0
                if (labelAll || inScale(p, project.key, project.minor)) {
                    val cache = if (isRoot) rootLabels else gutterLabels
                    val layout = cache.getOrPut(p) { measurer.measure(noteName(p), if (isRoot) rootStyle else gutterStyle) }
                    drawText(layout, topLeft = Offset(4.dp.toPx(), y + (rowH - layout.size.height) / 2f))
                }
            }

            // Grid: one line per grid step (the Grid buttons below), stronger on beats, strongest on bars.
            val len = pattern.lengthTicks
            val grid = vm.drawLength
            val lineStep = minOf(grid, TICKS_PER_BEAT)
            // Shade every other beat so the grid is easy to read at a glance.
            var b = TICKS_PER_BEAT
            while (b < len) {
                val x0 = noteX(b)
                val x1 = noteX(b + TICKS_PER_BEAT)
                if (x1 > gutter && x0 < size.width) {
                    drawRect(DawColors.Chip.copy(alpha = 0.35f), Offset(maxOf(x0, gutter), 0f), Size(minOf(x1, size.width) - maxOf(x0, gutter), size.height))
                }
                b += TICKS_PER_BEAT * 2
            }
            var t = 0
            while (t <= len) {
                val x = noteX(t)
                if (x in gutter..size.width) {
                    val isBar = t % TICKS_PER_BAR == 0
                    val isBeat = t % TICKS_PER_BEAT == 0
                    val color = when {
                        isBar -> DawColors.InkSoft.copy(alpha = 0.45f)
                        isBeat -> DawColors.LineStrong
                        else -> DawColors.Line
                    }
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), if (isBar) 2f else 1f)
                }
                t += lineStep
            }
            // Dim everything past the end of the pattern.
            val endX = noteX(len)
            if (endX < size.width) {
                drawRect(DawColors.Chip.copy(alpha = 0.7f), Offset(endX, 0f), Size(size.width - endX, size.height))
            }

            // Other channels as soft ghost notes.
            val ghostH = barH * 0.8f
            vm.project.channels.filter { it.id != channel.id }.forEach { other ->
                pattern.notesFor(other.id).forEach { n ->
                    if (n.pitch !in firstPitch..lastPitch) return@forEach
                    val x = noteX(n.start)
                    val w = n.length * pxPerTick - 2f
                    if (x + w < gutter || x > size.width) return@forEach
                    drawRoundRect(
                        DawColors.Ghost,
                        Offset(x, noteY(n.pitch) + (rowH - ghostH) / 2f),
                        Size(w.coerceAtLeast(2f), ghostH),
                        CornerRadius(ghostH / 2f),
                    )
                }
            }

            // This channel's notes: thin ink bars with a note-name pill.
            notesState.value.forEach { n ->
                if (n.pitch !in firstPitch..lastPitch) return@forEach
                val x = noteX(n.start)
                val w = (n.length * pxPerTick - 2f).coerceAtLeast(4f)
                if (x + w < gutter || x > size.width) return@forEach
                val y = noteY(n.pitch) + (rowH - barH) / 2f
                drawRoundRect(DawColors.Note, Offset(x, y), Size(w, barH), CornerRadius(barH / 2f))

                val label = labels.getOrPut(n.pitch) { measurer.measure(noteName(n.pitch), labelStyle) }
                val pillW = label.size.width + 8.dp.toPx()
                val pillH = label.size.height + 1.dp.toPx()
                if (w > pillW + 4.dp.toPx()) {
                    val px = x + 3.dp.toPx()
                    val py = noteY(n.pitch) + (rowH - pillH) / 2f
                    drawRoundRect(DawColors.Card, Offset(px, py), Size(pillW, pillH), CornerRadius(pillH / 2f))
                    drawRoundRect(DawColors.Ink, Offset(px, py), Size(pillW, pillH), CornerRadius(pillH / 2f), style = Stroke(1.dp.toPx()))
                    drawText(label, topLeft = Offset(px + 4.dp.toPx(), py + 0.5.dp.toPx()))
                }
            }

            // Playhead.
            if (vm.playing) {
                val x = noteX((vm.positionTicks % len).toInt())
                if (x in gutter..size.width) {
                    drawLine(DawColors.InkSoft.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), 1.5f)
                }
            }
        }
    }
}
