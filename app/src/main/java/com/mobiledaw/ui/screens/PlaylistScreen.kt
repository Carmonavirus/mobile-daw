package com.mobiledaw.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import com.mobiledaw.PlaylistTool
import com.mobiledaw.Tab
import com.mobiledaw.model.AudioClip
import com.mobiledaw.model.Clip
import com.mobiledaw.model.ClipEdit
import com.mobiledaw.model.Project
import com.mobiledaw.model.TICKS_PER_BEAT
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.ui.LabeledSlider
import com.mobiledaw.ui.Segmented
import com.mobiledaw.ui.TextAction
import com.mobiledaw.ui.inkSliderColors
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
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
import com.mobiledaw.TakeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.ui.theme.DawColors

private const val LANES = 8
private val LANE_H = 52.dp
private val BAR_W = 56.dp
private val HEADER_H = 24.dp
private val GUTTER_W = 40.dp
private val HANDLE_R = 6.dp
private val HANDLE_HIT = 18.dp

/** What a touch on the playlist grid landed on. */
private sealed interface Hit {
    data class AudioBody(val clip: AudioClip) : Hit
    data class AudioEdge(val clip: AudioClip, val left: Boolean) : Hit
    data class PatternBody(val index: Int, val clip: Clip) : Hit
    data class PatternEdge(val index: Int, val clip: Clip, val left: Boolean) : Hit
}

/** Pixel geometry shared by drawing and touch handling. */
private class Grid(val bw: Float, val lh: Float, val header: Float, val gap: Float, val minW: Float) {
    fun tickAt(x: Float): Int = (x / bw * TICKS_PER_BAR).toInt()
    fun x(tick: Int): Float = tick.toFloat() / TICKS_PER_BAR * bw
    fun top(lane: Int): Float = header + lane * lh + gap
    fun bottom(lane: Int): Float = header + (lane + 1) * lh - gap

    /** Left and right pixel edges of a clip from its start tick and length. */
    fun edges(start: Int, length: Int): Pair<Float, Float> {
        val x = x(start) + gap
        val w = (x(length) - gap * 2).coerceAtLeast(minW)
        return x to x + w
    }
}

private fun hitTest(p: Project, g: Grid, pos: Offset, handleHit: Float): Hit? {
    // Round handles first (they hang under the clip's bottom corners); nearest one wins.
    var best: Hit? = null
    var bestD = handleHit
    fun consider(d: Float, hit: Hit) { if (d < bestD) { bestD = d; best = hit } }
    p.clips.forEachIndexed { i, c ->
        if (c.lane >= LANES) return@forEachIndexed
        val (l, r) = g.edges(c.startTick, p.clipLength(c))
        val y = g.bottom(c.lane)
        consider((pos - Offset(l, y)).getDistance(), Hit.PatternEdge(i, c, true))
        consider((pos - Offset(r, y)).getDistance(), Hit.PatternEdge(i, c, false))
    }
    p.audioClips.forEach { c ->
        if (c.lane >= LANES) return@forEach
        val (l, r) = g.edges(c.startTick, c.lengthTicks(p.bpm))
        val y = g.bottom(c.lane)
        consider((pos - Offset(l, y)).getDistance(), Hit.AudioEdge(c, true))
        consider((pos - Offset(r, y)).getDistance(), Hit.AudioEdge(c, false))
    }
    if (best != null) return best
    if (pos.y < g.header) return null
    val lane = ((pos.y - g.header) / g.lh).toInt()
    if (lane !in 0 until LANES) return null
    // Audio clips are drawn on top, so they win.
    p.audioClips.lastOrNull { c ->
        val (l, r) = g.edges(c.startTick, c.lengthTicks(p.bpm))
        c.lane == lane && pos.x >= l && pos.x <= r
    }?.let { return Hit.AudioBody(it) }
    val index = p.clips.indexOfLast { c ->
        val (l, r) = g.edges(c.startTick, p.clipLength(c))
        c.lane == lane && pos.x >= l && pos.x <= r
    }
    if (index >= 0) return Hit.PatternBody(index, p.clips[index])
    return null
}

/**
 * The arrangement, FL-style: time runs left to right (one column per bar), tracks are rows.
 * Swipe sideways to move through the song. A tool bar under the rows picks what a tap does:
 * Pencil adds patterns, opens clips, drags them around and resizes them by the round handles;
 * Cut slices a clip in two; Mute silences a clip.
 */
@Composable
fun PlaylistScreen(vm: DawViewModel) {
    val project = vm.project
    val recording = vm.takeState == TakeState.RECORDING
    val liveEndBar = if (recording) (vm.positionTicks / TICKS_PER_BAR).toInt() + 1 else 0
    val bars = maxOf(32, project.songBars + 8, liveEndBar + 8)
    val measurer = rememberTextMeasurer()
    val projectState = rememberUpdatedState(project)
    val hScroll = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Waveforms of the audio files, worked out once per clip list.
    val audioPeaks = remember(project.audioClips.map { it.path }.distinct()) {
        project.audioClips.map { it.path }.distinct().associateWith { vm.samplePeaks(it, 1200) }
    }

    // A clip being dragged or resized: drawn in its new place, saved when the finger lifts.
    var dragAudio by remember { mutableStateOf<AudioClip?>(null) }
    var dragPattern by remember { mutableStateOf<Pair<Int, Clip>?>(null) }
    // The clip whose window is open.
    var openAudioId by remember { mutableStateOf<Long?>(null) }
    var openPatternIndex by remember { mutableStateOf<Int?>(null) }

    // Keep the playhead in view while recording.
    LaunchedEffect(vm.positionTicks, recording) {
        if (!recording || viewportPx == 0) return@LaunchedEffect
        val x = with(density) { (vm.positionTicks.toFloat() / TICKS_PER_BAR * BAR_W.toPx()).toInt() }
        if (x > hScroll.value + viewportPx * 0.75f || x < hScroll.value) {
            hScroll.animateScrollTo((x - viewportPx / 4).coerceAtLeast(0))
        }
    }

    // Tap a row number -> "Add audio file" -> the system file picker -> the clip lands on that row.
    var pickLane by remember { mutableIntStateOf(0) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importAudioToLane(uris, pickLane)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        PatternBar(vm)
        val importNote = when {
            vm.importingAudio -> "Importing audio…"
            else -> vm.audioImportMessage
        }
        // Only status notes (e.g. an audio import) show here; tool hints were dropped to save space.
        if (importNote != null) {
            Text(
                importNote,
                fontSize = 11.sp,
                color = DawColors.Ink,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clickable(enabled = vm.audioImportMessage != null) { vm.audioImportMessage = null },
            )
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            // Track names stay put while the grid scrolls.
            Column(Modifier.width(GUTTER_W)) {
                Box(Modifier.height(HEADER_H))
                for (lane in 0 until LANES) {
                    LaneNumber(lane, onAddAudio = { pickLane = lane; pickAudio.launch(AUDIO_TYPES) })
                }
            }
            Box(Modifier.weight(1f).onSizeChanged { viewportPx = it.width }.horizontalScroll(hScroll)) {
                Canvas(
                    Modifier
                        .width(BAR_W * bars)
                        .height(HEADER_H + LANE_H * LANES + HANDLE_R * 2)
                        .pointerInput(Unit) {
                            val g = Grid(BAR_W.toPx(), LANE_H.toPx(), HEADER_H.toPx(), 2.dp.toPx(), 4.dp.toPx())
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val p = projectState.value
                                val tool = vm.playlistTool
                                val hit = hitTest(p, g, down.position, HANDLE_HIT.toPx())
                                val lane = ((down.position.y - g.header) / g.lh).toInt()
                                val tick = g.tickAt(down.position.x)

                                fun tap() {
                                    if (down.position.y < g.header || lane !in 0 until LANES) return
                                    when (tool) {
                                        PlaylistTool.PENCIL -> when (hit) {
                                            is Hit.AudioBody -> openAudioId = hit.clip.id
                                            is Hit.AudioEdge -> openAudioId = hit.clip.id
                                            is Hit.PatternBody -> openPatternIndex = hit.index
                                            is Hit.PatternEdge -> openPatternIndex = hit.index
                                            null -> vm.addPatternClip(lane, tick)
                                        }
                                        PlaylistTool.CUT -> vm.cutAt(lane, tick)
                                        PlaylistTool.MUTE -> vm.toggleMuteAt(lane, tick)
                                    }
                                }

                                if (tool != PlaylistTool.PENCIL || hit == null) {
                                    // Taps only; a swipe scrolls the playlist instead.
                                    if (waitForUpOrCancellation() != null) tap()
                                    return@awaitEachGesture
                                }

                                // Pencil on a clip: a drag moves it (or resizes it from a handle), a tap opens it.
                                var total = Offset.Zero
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    total += change.positionChange()
                                    change.consume()
                                    if (!dragging && total.getDistance() > viewConfiguration.touchSlop) dragging = true
                                    if (!dragging) continue
                                    val dTicks = (total.x / g.bw * TICKS_PER_BAR).toInt()
                                    val dLanes = (total.y / g.lh).roundToInt()
                                    when (hit) {
                                        is Hit.AudioBody -> dragAudio = hit.clip.copy(
                                            lane = (hit.clip.lane + dLanes).coerceIn(0, LANES - 1),
                                            startTick = vm.snapTick(hit.clip.startTick + dTicks),
                                        )
                                        is Hit.AudioEdge -> dragAudio = if (hit.left) {
                                            ClipEdit.resizeLeft(hit.clip, vm.snapTick(hit.clip.startTick + dTicks), p.bpm)
                                        } else {
                                            ClipEdit.resizeRight(hit.clip, vm.snapTick(hit.clip.endTick(p.bpm) + dTicks), p.bpm)
                                        }
                                        is Hit.PatternBody -> dragPattern = hit.index to hit.clip.movedTo(
                                            (hit.clip.lane + dLanes).coerceIn(0, LANES - 1),
                                            vm.snapTick(hit.clip.startTick + dTicks),
                                        )
                                        is Hit.PatternEdge -> dragPattern = hit.index to if (hit.left) {
                                            ClipEdit.resizeLeft(p, hit.clip, vm.snapTick(hit.clip.startTick + dTicks))
                                        } else {
                                            ClipEdit.resizeRight(p, hit.clip, vm.snapTick(p.clipEnd(hit.clip) + dTicks))
                                        }
                                        null -> Unit
                                    }
                                }
                                if (!dragging) {
                                    tap()
                                } else {
                                    dragAudio?.let { moved -> vm.updateAudioClip(moved.id) { moved } }
                                    dragPattern?.let { (i, moved) -> vm.setPatternClip(i, moved) }
                                }
                                dragAudio = null
                                dragPattern = null
                            }
                        },
                ) {
                    val p = projectState.value
                    val g = Grid(BAR_W.toPx(), LANE_H.toPx(), HEADER_H.toPx(), 2.dp.toPx(), 4.dp.toPx())
                    val bw = g.bw
                    val lh = g.lh
                    val header = g.header
                    val gap = g.gap
                    val numberStyle = TextStyle(fontSize = 10.sp, color = DawColors.Muted)
                    val handles = ArrayList<Pair<Offset, Color>>()

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

                    // Pattern clips
                    val draggedPattern = dragPattern
                    p.clips.forEachIndexed { i, original ->
                        val clip = if (draggedPattern?.first == i) draggedPattern.second else original
                        val index = p.patterns.indexOfFirst { it.id == clip.patternId }
                        if (index < 0 || clip.lane >= LANES) return@forEachIndexed
                        val pattern = p.patterns[index]
                        val base = DawColors.PatternColors[index % DawColors.PatternColors.size]
                        val color = if (clip.muted) base.copy(alpha = 0.3f) else base
                        val (x, right) = g.edges(clip.startTick, p.clipLength(clip))
                        val y = g.top(clip.lane)
                        val w = right - x
                        drawRoundRect(color, Offset(x, y), Size(w, lh - gap * 2), CornerRadius(10.dp.toPx()))
                        if (draggedPattern?.first == i) {
                            drawRoundRect(DawColors.Ink, Offset(x, y), Size(w, lh - gap * 2), CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                        val textColor = when {
                            clip.muted -> DawColors.Muted
                            color.luminance() > 0.5f -> Color(0xFF1D1D1F)
                            else -> Color.White
                        }
                        val label = measurer.measure(
                            if (clip.muted) "${pattern.name} (muted)" else pattern.name,
                            TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (w - 12.dp.toPx()).toInt().coerceAtLeast(1)),
                        )
                        drawText(label, topLeft = Offset(x + 6.dp.toPx(), y + 6.dp.toPx()))
                        handles += Offset(x, g.bottom(clip.lane)) to base
                        handles += Offset(right, g.bottom(clip.lane)) to base
                    }

                    // Audio clips, with the part of the waveform they play.
                    val corner = CornerRadius(10.dp.toPx())
                    val draggedAudio = dragAudio
                    p.audioClips.forEach { original ->
                        val clip = if (draggedAudio?.id == original.id) draggedAudio else original
                        if (clip.lane >= LANES) return@forEach
                        val (x, right) = g.edges(clip.startTick, clip.lengthTicks(p.bpm))
                        val y = g.top(clip.lane)
                        val w = right - x
                        val h = lh - gap * 2
                        val fade = if (clip.muted) 0.35f else 1f
                        drawRoundRect(DawColors.AccentSoft.copy(alpha = DawColors.AccentSoft.alpha * fade), Offset(x, y), Size(w, h), corner)
                        if (draggedAudio?.id == original.id) {
                            drawRoundRect(DawColors.Ink, Offset(x, y), Size(w, h), corner, style = Stroke(1.5.dp.toPx()))
                        }
                        val (a, b) = ClipEdit.trimFractions(clip)
                        drawWave(
                            audioPeaks[clip.path], x, y + 16.dp.toPx(), w, h - 18.dp.toPx(),
                            DawColors.Accent.copy(alpha = fade), from = a, to = b, reverse = clip.reverse,
                        )
                        val tags = buildList {
                            if (clip.reverse) add("rev")
                            if (clip.pitchSemis != 0) add(if (clip.pitchSemis > 0) "+${clip.pitchSemis}" else "${clip.pitchSemis}")
                            if (clip.muted) add("muted")
                        }
                        val label = measurer.measure(
                            if (tags.isEmpty()) clip.name else clip.name + " · " + tags.joinToString(" "),
                            TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (clip.muted) DawColors.Muted else DawColors.Ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (w - 12.dp.toPx()).toInt().coerceAtLeast(1)),
                        )
                        drawText(label, topLeft = Offset(x + 6.dp.toPx(), y + 3.dp.toPx()))
                        handles += Offset(x, g.bottom(clip.lane)) to DawColors.Accent
                        handles += Offset(right, g.bottom(clip.lane)) to DawColors.Accent
                    }

                    // Round resize handles under each clip's edges (pencil tool only).
                    if (vm.playlistTool == PlaylistTool.PENCIL) {
                        val r = HANDLE_R.toPx()
                        handles.forEach { (c, color) ->
                            drawCircle(DawColors.Paper, r, c)
                            drawCircle(color, r, c, style = Stroke(2.dp.toPx()))
                        }
                    }

                    // The take being recorded grows from its start to the playhead.
                    if (recording) {
                        val x = vm.takeStartTick.toFloat() / TICKS_PER_BAR * bw + gap
                        val y = header + vm.takeLane * lh + gap
                        val endX = vm.positionTicks.toFloat() / TICKS_PER_BAR * bw
                        val w = (endX - x).coerceAtLeast(4.dp.toPx())
                        val h = lh - gap * 2
                        drawRoundRect(RecordRed.copy(alpha = 0.22f), Offset(x, y), Size(w, h), corner)
                        drawWave(vm.takePeaks, x, y + 6.dp.toPx(), w, h - 12.dp.toPx(), RecordRed, normalize = false)
                    }

                    // Song playhead
                    if (vm.mode == PlayMode.SONG && (vm.playing || vm.positionTicks > 0)) {
                        val x = vm.positionTicks.toFloat() / TICKS_PER_BAR * bw
                        drawLine(if (recording) RecordRed else DawColors.Accent, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    }
                }
            }
        }
        PlaylistToolBar(vm)
    }
    vm.undoSnapshot?.let {
        UndoBar(vm.undoLabel, onUndo = vm::undoDelete, onDismiss = vm::dismissUndo)
    }
    }

    openAudioId?.let { id ->
        AudioClipWindow(vm, id, onDismiss = { openAudioId = null })
    }
    openPatternIndex?.let { index ->
        PatternClipWindow(vm, index, onDismiss = { openPatternIndex = null })
    }
}

/** FL-style tools between the rows and the play bar, plus the snap grid. */
@Composable
private fun PlaylistToolBar(vm: DawViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolButton("Pencil", vm.playlistTool == PlaylistTool.PENCIL, { vm.playlistTool = PlaylistTool.PENCIL }) { c -> drawPencil(c) }
        ToolButton("Cut", vm.playlistTool == PlaylistTool.CUT, { vm.playlistTool = PlaylistTool.CUT }) { c -> drawScissors(c) }
        ToolButton("Mute", vm.playlistTool == PlaylistTool.MUTE, { vm.playlistTool = PlaylistTool.MUTE }) { c -> drawMuted(c) }
        Spacer(Modifier.weight(1f))
        Segmented(
            options = listOf(TICKS_PER_BAR to "Bar", TICKS_PER_BEAT to "Beat", TICKS_PER_STEP to "Step", 1 to "Free"),
            selected = vm.playlistSnap,
            onSelect = { vm.playlistSnap = it },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val tint = if (selected) DawColors.OnNote else DawColors.Ink
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DawColors.Note else DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(18.dp)) { this.icon(tint) }
        Text(label, fontSize = 10.sp, color = tint, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPencil(c: Color) {
    val s = size.width
    val stroke = 2.dp.toPx()
    drawLine(c, Offset(s * 0.25f, s * 0.75f), Offset(s * 0.85f, s * 0.15f), stroke * 1.6f, StrokeCap.Round)
    drawLine(c, Offset(s * 0.12f, s * 0.88f), Offset(s * 0.25f, s * 0.75f), stroke, StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScissors(c: Color) {
    val s = size.width
    val stroke = 1.6.dp.toPx()
    drawCircle(c, s * 0.14f, Offset(s * 0.22f, s * 0.78f), style = Stroke(stroke))
    drawCircle(c, s * 0.14f, Offset(s * 0.78f, s * 0.78f), style = Stroke(stroke))
    drawLine(c, Offset(s * 0.32f, s * 0.68f), Offset(s * 0.75f, s * 0.08f), stroke, StrokeCap.Round)
    drawLine(c, Offset(s * 0.68f, s * 0.68f), Offset(s * 0.25f, s * 0.08f), stroke, StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMuted(c: Color) {
    val s = size.width
    val stroke = 1.6.dp.toPx()
    val body = Path().apply {
        moveTo(s * 0.08f, s * 0.38f)
        lineTo(s * 0.26f, s * 0.38f)
        lineTo(s * 0.48f, s * 0.18f)
        lineTo(s * 0.48f, s * 0.82f)
        lineTo(s * 0.26f, s * 0.62f)
        lineTo(s * 0.08f, s * 0.62f)
        close()
    }
    drawPath(body, c)
    drawLine(c, Offset(s * 0.62f, s * 0.36f), Offset(s * 0.9f, s * 0.64f), stroke, StrokeCap.Round)
    drawLine(c, Offset(s * 0.9f, s * 0.36f), Offset(s * 0.62f, s * 0.64f), stroke, StrokeCap.Round)
}

/**
 * A clip's window: rounded card with a title row and content. With [onRename], tapping the
 * title or "Rename" turns the title into a text field.
 */
@Composable
private fun ClipWindowFrame(
    title: String,
    onDismiss: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(title) }
    fun finishRename() {
        val name = draft.trim()
        if (name.isNotEmpty() && name != title) onRename?.invoke(name.take(40))
        renaming = false
    }
    Dialog(onDismissRequest = { if (renaming) finishRename(); onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(DawColors.Paper)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (renaming) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text("Clip name") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishRename() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextAction("Save", strong = true) { finishRename() }
                } else {
                    Text(
                        title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DawColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = onRename != null) { draft = title; renaming = true },
                    )
                    if (onRename != null) {
                        TextAction("Rename") { draft = title; renaming = true }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextAction("Done", strong = true, onClick = onDismiss)
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Tap on an audio clip: reverse it, pitch it up or down, turn it up or down. */
@Composable
private fun AudioClipWindow(vm: DawViewModel, id: Long, onDismiss: () -> Unit) {
    val clip = vm.project.audioClips.firstOrNull { it.id == id }
    if (clip == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    fun set(transform: (AudioClip) -> AudioClip) = vm.updateAudioClip(id, transform)
    val peaks = remember(clip.path) { vm.samplePeaks(clip.path, 400) }
    ClipWindowFrame(clip.name, onDismiss, onRename = { name -> set { it.copy(name = name) } }) {
        val (a, b) = ClipEdit.trimFractions(clip)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DawColors.AccentSoft),
        ) {
            drawWave(peaks, 0f, 4.dp.toPx(), size.width, size.height - 8.dp.toPx(), DawColors.Accent, from = a, to = b, reverse = clip.reverse)
        }
        Text(
            "%.2f s on the playlist".format(clip.playSeconds),
            fontSize = 12.sp,
            color = DawColors.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reverse", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text("Play the clip backwards.", fontSize = 12.sp, color = DawColors.Muted)
            }
            Switch(
                checked = clip.reverse,
                onCheckedChange = { on -> set { it.copy(reverse = on) } },
                colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Note, checkedThumbColor = DawColors.OnNote),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pitch", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text("Higher also plays faster, like a turntable.", fontSize = 12.sp, color = DawColors.Muted)
            }
            StepButton("−", enabled = clip.pitchSemis > -12) { set { it.copy(pitchSemis = (it.pitchSemis - 1).coerceAtLeast(-12)) } }
            Text(
                when {
                    clip.pitchSemis == 0 -> "0 st"
                    clip.pitchSemis > 0 -> "+${clip.pitchSemis} st"
                    else -> "${clip.pitchSemis} st"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp),
            )
            StepButton("+", enabled = clip.pitchSemis < 12) { set { it.copy(pitchSemis = (it.pitchSemis + 1).coerceAtMost(12)) } }
        }
        Slider(
            value = clip.pitchSemis.toFloat(),
            onValueChange = { v -> set { it.copy(pitchSemis = v.roundToInt().coerceIn(-12, 12)) } },
            valueRange = -12f..12f,
            steps = 23,
            colors = inkSliderColors(),
        )

        // Timing: slide the sound earlier / later without moving the clip (line a take up by ear).
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Timing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(
                    when {
                        clip.timingMs == 0 -> "As recorded. Slide the sound if it's off the beat."
                        clip.timingMs > 0 -> "${clip.timingMs} ms later"
                        else -> "${-clip.timingMs} ms earlier"
                    },
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Earlier", fontSize = 12.sp, color = DawColors.Muted)
            StepButton("«", enabled = true) { vm.nudgeAudioTiming(id, -25) }
            StepButton("−", enabled = true) { vm.nudgeAudioTiming(id, -5) }
            Text(
                if (clip.timingMs > 0) "+${clip.timingMs}" else "${clip.timingMs}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clickable(enabled = clip.timingMs != 0) { vm.nudgeAudioTiming(id, -clip.timingMs) },
            )
            StepButton("+", enabled = true) { vm.nudgeAudioTiming(id, 5) }
            StepButton("»", enabled = true) { vm.nudgeAudioTiming(id, 25) }
            Text("Later", fontSize = 12.sp, color = DawColors.Muted)
        }
        if (clip.recorded && clip.takeTimingMs + clip.timingMs != vm.recordTimingMs) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sounds right now? Use this timing for new takes on ${vm.outputDevice.label}.",
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextAction("Use for new takes") { vm.useTimingForNewTakes(clip) }
            }
        }

        LabeledSlider(
            label = "Volume",
            value = clip.gain,
            range = 0f..2f,
            display = "${(clip.gain * 100).roundToInt()}%" + if (clip.gain > 1.001f) " boost" else "",
            snapTo = 1f,
        ) { v -> set { it.copy(gain = v) } }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction(if (clip.muted) "Unmute" else "Mute") { set { it.copy(muted = !it.muted) } }
            if (clip.reverse || clip.pitchSemis != 0 || clip.gain != 1f) {
                TextAction("Reset") { set { it.copy(reverse = false, pitchSemis = 0, gain = 1f) } }
            }
            Spacer(Modifier.weight(1f))
            TextAction("Delete") { vm.deleteAudioClip(id); onDismiss() }
        }
    }
}

/** Tap on a pattern clip: mute it, open its pattern, or delete it. */
@Composable
private fun PatternClipWindow(vm: DawViewModel, index: Int, onDismiss: () -> Unit) {
    val clip = vm.project.clips.getOrNull(index)
    val pattern = clip?.let { vm.project.pattern(it.patternId) }
    if (clip == null || pattern == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ClipWindowFrame(pattern.name, onDismiss) {
        val bars = vm.project.clipLength(clip).toFloat() / TICKS_PER_BAR
        Text(
            "Plays %s of %d bar%s. Drag the round handles to change that.".format(
                if (bars == bars.roundToInt().toFloat()) "${bars.roundToInt()} bar" + (if (bars.roundToInt() == 1) "" else "s") else "%.2f bars".format(bars),
                pattern.bars,
                if (pattern.bars == 1) "" else "s",
            ),
            fontSize = 12.sp,
            color = DawColors.Muted,
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Edit pattern") {
                vm.selectPattern(pattern.id)
                vm.tab = Tab.RACK
                onDismiss()
            }
            TextAction(if (clip.muted) "Unmute" else "Mute") { vm.setPatternClip(index, clip.copy(muted = !clip.muted)) }
            Spacer(Modifier.weight(1f))
            TextAction("Delete") { vm.deletePatternClip(index); onDismiss() }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) DawColors.Chip else DawColors.Chip.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) DawColors.Ink else DawColors.Muted)
    }
}

private val RecordRed = Color(0xFFE5484D)

/** File types the "Add audio file" picker offers (Android decodes WAV, MP3, OGG, FLAC, M4A/AAC...). */
private val AUDIO_TYPES = arrayOf("audio/*", "application/ogg")

/** A playlist row number. Tapping it opens a small menu with "Add audio file". */
@Composable
private fun LaneNumber(lane: Int, onAddAudio: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(LANE_H)
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { menu = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("${lane + 1}", fontSize = 12.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Clip)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Add audio file to row ${lane + 1}") },
                onClick = { menu = false; onAddAudio() },
            )
        }
    }
}

/** Mirrored peak bars across a box, one bar every 2 dp; [from]..[to] picks part of the file. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(
    peaks: FloatArray?,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    color: Color,
    normalize: Boolean = true,
    from: Float = 0f,
    to: Float = 1f,
    reverse: Boolean = false,
) {
    if (peaks == null || peaks.isEmpty() || w <= 0f || h <= 0f) return
    val step = 2.dp.toPx()
    val mid = y + h / 2f
    val scale = if (normalize) 1f else 1f / (peaks.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f)
    var px = 0f
    while (px < w) {
        // Position inside the used part of the file (from..to), read backwards for reversed clips.
        val t = if (reverse) 1f - px / w else px / w
        val i = ((from + (to - from) * t) * peaks.size).toInt().coerceIn(0, peaks.size - 1)
        val a = (peaks[i] * scale).coerceIn(0.03f, 1f) * h / 2f
        drawLine(color, Offset(x + px, mid - a), Offset(x + px, mid + a), 1.2.dp.toPx())
        px += step
    }
}
