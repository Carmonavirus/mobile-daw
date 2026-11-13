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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import com.mobiledaw.model.MAX_PLAYLIST_ROWS
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import com.mobiledaw.PlaylistTool
import com.mobiledaw.model.AudioClip
import com.mobiledaw.model.ClipStretch
import com.mobiledaw.model.Clip
import com.mobiledaw.model.ClipEdit
import com.mobiledaw.model.Project
import com.mobiledaw.model.TICKS_PER_BEAT
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.ui.LabeledSlider
import com.mobiledaw.ui.formatVolume
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.ui.rightMouseTarget
import com.mobiledaw.ui.Wheel
import com.mobiledaw.PlayMode
import com.mobiledaw.TakeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.ui.theme.DawColors

private val LANE_H = 52.dp
private val HEADER_H = 24.dp
private val GUTTER_W = 40.dp
private val HANDLE_R = 6.dp
private val HANDLE_HIT = 18.dp
/** How far the playhead may glide past the last engine reading (they arrive every ~33 ms). */
private const val PLAYHEAD_LEAD_NANOS = 60_000_000L
/** After you touch the playlist (swipe, tap, drag, pinch), it stops following the playhead for this long. */
private const val FOLLOW_PAUSE_NANOS = 4_000_000_000L

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

private fun hitTest(p: Project, g: Grid, pos: Offset, handleHit: Float, rows: Int): Hit? {
    // Round handles first (they hang under the clip's bottom corners); nearest one wins.
    var best: Hit? = null
    var bestD = handleHit
    fun consider(d: Float, hit: Hit) { if (d < bestD) { bestD = d; best = hit } }
    p.clips.forEachIndexed { i, c ->
        if (c.lane >= rows) return@forEachIndexed
        val (l, r) = g.edges(c.startTick, p.clipLength(c))
        val y = g.bottom(c.lane)
        consider((pos - Offset(l, y)).getDistance(), Hit.PatternEdge(i, c, true))
        consider((pos - Offset(r, y)).getDistance(), Hit.PatternEdge(i, c, false))
    }
    p.audioClips.forEach { c ->
        if (c.lane >= rows) return@forEach
        val (l, r) = g.edges(c.startTick, c.lengthTicks(p.bpm))
        val y = g.bottom(c.lane)
        consider((pos - Offset(l, y)).getDistance(), Hit.AudioEdge(c, true))
        consider((pos - Offset(r, y)).getDistance(), Hit.AudioEdge(c, false))
    }
    if (best != null) return best
    if (pos.y < g.header) return null
    val lane = ((pos.y - g.header) / g.lh).toInt()
    if (lane !in 0 until rows) return null
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
    val rows = project.rowCount
    val rowsState = rememberUpdatedState(rows)
    // Zoom: the width of one bar (pinch the grid, or the − / + buttons).
    val barW = vm.playlistBarDp.dp
    val barWState = rememberUpdatedState(barW)
    val hScroll = rememberScrollState()
    // When the finger last touched the grid (System.nanoTime), and whether one is down now:
    // the playlist doesn't page along with the song while you're working on it.
    val touch = remember { LongArray(2) }
    // Scroll position to land on after a zoom, once the new width is laid out (-1 = none).
    val pendingScroll = remember { IntArray(2).also { it[0] = -1 } }
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
    val windowOpen = rememberUpdatedState(openAudioId != null || openPatternIndex != null)
    // Mouse only: the clip under the pointer (drawn with a ring, hand cursor).
    var hoverHit by remember { mutableStateOf<Hit?>(null) }
    // Right mouse button: delete the clip under the pointer, and every clip a right-drag crosses.
    // One Undo brings them all back.
    val eraseBefore = remember { arrayOfNulls<Project>(1) }
    val eraseCount = remember { IntArray(1) }
    fun eraseAt(pos: Offset) {
        if (openAudioId != null || openPatternIndex != null) return
        val g = with(density) { Grid(barW.toPx(), LANE_H.toPx(), HEADER_H.toPx(), 2.dp.toPx(), 4.dp.toPx()) }
        when (val h = hitTest(vm.project, g, pos, with(density) { HANDLE_HIT.toPx() }, vm.project.rowCount)) {
            is Hit.AudioBody -> vm.deleteAudioClip(h.clip.id)
            is Hit.AudioEdge -> vm.deleteAudioClip(h.clip.id)
            is Hit.PatternBody -> vm.deletePatternClip(h.index)
            is Hit.PatternEdge -> vm.deletePatternClip(h.index)
            null -> return
        }
        eraseCount[0]++
        hoverHit = null
    }

    // Song playhead: glides every frame between engine readings (which already include the
    // Bluetooth delay), and the playlist pages along to keep it in view, FL-style.
    var headTick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastPoll = Long.MIN_VALUE
        var lastPollNanos = 0L
        var moving = false
        var userScrollUntil = 0L
        while (true) {
            val now = withFrameNanos { it }
            val polled = vm.positionTicks.coerceAtLeast(0L)
            if (polled != lastPoll) {
                moving = lastPoll != Long.MIN_VALUE && polled > lastPoll && polled - lastPoll < TICKS_PER_BAR
                lastPoll = polled
                lastPollNanos = now
            }
            val ticksPerNano = projectState.value.bpm * TICKS_PER_BEAT / 60e9
            val ahead = if (vm.playing && moving && vm.countIn == null) {
                (now - lastPollNanos).coerceAtMost(PLAYHEAD_LEAD_NANOS) * ticksPerNano
            } else 0.0
            val target = (polled + ahead).toFloat().coerceAtMost(maxOf(1, vm.loopTicks).toFloat())
            // Never step back a hair when a fresh reading lands just behind the glide.
            headTick = if (vm.playing && target < headTick && headTick - target < TICKS_PER_STEP) headTick else target

            // A zoom asked for a new scroll position: apply it once the wider/narrower grid is laid out.
            if (pendingScroll[0] >= 0) {
                hScroll.scrollTo(pendingScroll[0])
                if (hScroll.value == pendingScroll[0] || ++pendingScroll[1] > 3) {
                    pendingScroll[0] = -1
                    pendingScroll[1] = 0
                }
            }

            // Follow mode pauses while you touch, scroll, drag or have a clip window open, and
            // for a few seconds after, so the view never jumps away from what you're editing.
            if (hScroll.isScrollInProgress || touch[1] != 0L || windowOpen.value || dragAudio != null || dragPattern != null) {
                userScrollUntil = now + FOLLOW_PAUSE_NANOS
            }
            if (touch[0] != 0L) userScrollUntil = maxOf(userScrollUntil, touch[0] + FOLLOW_PAUSE_NANOS)
            if (vm.playlistFollow && vm.mode == PlayMode.SONG && vm.playing && viewportPx > 0 && now > userScrollUntil) {
                val x = with(density) { headTick / TICKS_PER_BAR * barWState.value.toPx() }
                if (x > hScroll.value + viewportPx * 0.85f || x < hScroll.value) {
                    hScroll.scrollTo((x - viewportPx * 0.1f).toInt().coerceAtLeast(0))
                }
            }
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 52.dp),
        ) {
            // Track names stay put while the grid scrolls.
            Column(Modifier.width(GUTTER_W)) {
                Box(Modifier.height(HEADER_H))
                for (lane in 0 until rows) {
                    LaneNumber(
                        lane,
                        canDelete = rows > 1,
                        canAdd = rows < MAX_PLAYLIST_ROWS,
                        onAddAudio = { pickLane = lane; pickAudio.launch(AUDIO_TYPES) },
                        onInsertRow = { vm.insertPlaylistRow(lane) },
                        onDeleteRow = { vm.deletePlaylistRow(lane) },
                    )
                }
                if (rows < MAX_PLAYLIST_ROWS) AddRowButton(vm::addPlaylistRow)
            }
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { viewportPx = it.width }
                    // Mouse wheel / trackpad: Ctrl+wheel or a pinch zooms around the pointer;
                    // Shift+wheel scrolls sideways. Plain scrolling is left to the scroll areas.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Scroll) continue
                                val change = event.changes.firstOrNull() ?: continue
                                val d = change.scrollDelta
                                val mods = event.keyboardModifiers
                                touch[0] = System.nanoTime()
                                if (mods.isCtrlPressed || mods.isMetaPressed) {
                                    val old = vm.playlistBarDp
                                    vm.changePlaylistZoom(old * Wheel.zoomFactor(d.y))
                                    val now = vm.playlistBarDp
                                    val focusX = change.position.x
                                    pendingScroll[0] = ((hScroll.value + focusX) * now / old - focusX).roundToInt().coerceAtLeast(0)
                                    pendingScroll[1] = 0
                                    change.consume()
                                } else if (mods.isShiftPressed && d.x == 0f) {
                                    hScroll.dispatchRawDelta(Wheel.scrollPx(d.y, density.density))
                                    change.consume()
                                }
                            }
                        }
                    }
                    // Sees every touch first: notes it (to pause follow mode) and turns two fingers
                    // into a pinch zoom, keeping the spot between the fingers in place.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var zooming = false
                            var startDist = 0f
                            var startBar = 0f
                            var anchorX = 0f
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val now = System.nanoTime()
                                touch[0] = now
                                val pressed = event.changes.filter { it.pressed }
                                touch[1] = if (pressed.isEmpty()) 0L else now
                                if (pressed.size >= 2) {
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val dist = (a - b).getDistance()
                                    val focusX = (a.x + b.x) / 2f
                                    if (!zooming) {
                                        zooming = true
                                        startDist = dist
                                        startBar = barWState.value.value
                                        anchorX = hScroll.value + focusX
                                    } else if (startDist > 0f) {
                                        val target = (startBar * dist / startDist)
                                            .coerceIn(DawViewModel.PLAYLIST_BAR_MIN_DP, DawViewModel.PLAYLIST_BAR_MAX_DP)
                                        if (kotlin.math.abs(target - vm.playlistBarDp) > 0.25f) {
                                            vm.changePlaylistZoom(target)
                                            pendingScroll[0] = (anchorX * target / startBar - focusX).roundToInt().coerceAtLeast(0)
                                            pendingScroll[1] = 0
                                        }
                                    }
                                }
                                if (zooming) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            touch[1] = 0L
                        }
                    }
                    .horizontalScroll(hScroll),
            ) {
              Box {
                Canvas(
                    Modifier
                        .width(barW * bars)
                        .height(HEADER_H + LANE_H * rows + HANDLE_R * 2)
                        .pointerHoverIcon(if (hoverHit != null) PointerIcon.Hand else PointerIcon.Default)
                        .rightMouseTarget(
                            onPress = { pos -> eraseBefore[0] = vm.project; eraseCount[0] = 0; eraseAt(pos) },
                            onDrag = { pos -> eraseAt(pos) },
                            onRelease = {
                                val before = eraseBefore[0]
                                if (before != null && eraseCount[0] > 1) vm.markUndo(before, "Deleted ${eraseCount[0]} clips")
                                eraseBefore[0] = null
                            },
                        )
                        .pointerInput(Unit) {
                            // Mouse hover: which clip is under the pointer.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    hoverHit = if (change.type != PointerType.Mouse || event.type == PointerEventType.Exit) {
                                        null
                                    } else {
                                        val g = Grid(barWState.value.toPx(), LANE_H.toPx(), HEADER_H.toPx(), 2.dp.toPx(), 4.dp.toPx())
                                        hitTest(vm.project, g, change.position, HANDLE_HIT.toPx(), rowsState.value)
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val g = Grid(barWState.value.toPx(), LANE_H.toPx(), HEADER_H.toPx(), 2.dp.toPx(), 4.dp.toPx())
                                val rowCount = rowsState.value
                                val p = projectState.value
                                val tool = vm.playlistTool
                                val hit = hitTest(p, g, down.position, HANDLE_HIT.toPx(), rowCount)
                                val lane = ((down.position.y - g.header) / g.lh).toInt()
                                val tick = g.tickAt(down.position.x)

                                fun tap() {
                                    // Tap the bar numbers to move the song playhead there.
                                    if (down.position.y < g.header) {
                                        vm.switchMode(PlayMode.SONG)
                                        vm.seekToTick(tick)
                                        return
                                    }
                                    if (lane !in 0 until rowCount) return
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
                                var cancelled = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // A second finger means a pinch zoom: drop the drag, leave the clip where it was.
                                    if (event.changes.count { it.pressed } > 1) {
                                        cancelled = true
                                        break
                                    }
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
                                            lane = (hit.clip.lane + dLanes).coerceIn(0, rowCount - 1),
                                            startTick = vm.snapTick(hit.clip.startTick + dTicks),
                                        )
                                        is Hit.AudioEdge -> dragAudio = if (hit.left) {
                                            ClipEdit.resizeLeft(hit.clip, vm.snapTick(hit.clip.startTick + dTicks), p.bpm)
                                        } else {
                                            ClipEdit.resizeRight(hit.clip, vm.snapTick(hit.clip.endTick(p.bpm) + dTicks), p.bpm)
                                        }
                                        is Hit.PatternBody -> dragPattern = hit.index to hit.clip.movedTo(
                                            (hit.clip.lane + dLanes).coerceIn(0, rowCount - 1),
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
                                if (cancelled) {
                                    // Nothing to do; the pinch handles the rest of the gesture.
                                } else if (!dragging) {
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
                    val g = Grid(barW.toPx(), LANE_H.toPx(), HEADER_H.toPx(), 2.dp.toPx(), 4.dp.toPx())
                    val bw = g.bw
                    val lh = g.lh
                    val header = g.header
                    val gap = g.gap
                    val numberStyle = TextStyle(fontSize = 10.sp, color = DawColors.Muted)
                    val handles = ArrayList<Pair<Offset, Color>>()

                    // Bar numbers along the top: every bar when zoomed in, every 4 or 8 when zoomed out.
                    val every = when {
                        barW >= 80.dp -> 1
                        barW >= 22.dp -> 4
                        else -> 8
                    }
                    for (bar in 0 until bars) {
                        if (bar % every == 0) {
                            val label = measurer.measure("${bar + 1}", numberStyle)
                            drawText(label, topLeft = Offset(bar * bw + 4.dp.toPx(), (header - label.size.height) / 2f))
                        }
                    }
                    // Cells, shaded in groups of four bars.
                    val cellGap = if (barW < 24.dp) gap / 2f else gap
                    for (lane in 0 until rows) {
                        for (bar in 0 until bars) {
                            drawRoundRect(
                                if (bar % 8 < 4) DawColors.Card else DawColors.Chip.copy(alpha = 0.6f),
                                Offset(bar * bw + cellGap, header + lane * lh + gap),
                                Size(bw - cellGap * 2, lh - gap * 2),
                                CornerRadius(minOf(8.dp.toPx(), bw / 4f)),
                            )
                            // Zoomed in: faint beat lines inside each bar.
                            if (barW >= 110.dp) {
                                for (beat in 1 until TICKS_PER_BAR / TICKS_PER_BEAT) {
                                    val bx = bar * bw + beat * bw * TICKS_PER_BEAT / TICKS_PER_BAR
                                    drawLine(
                                        DawColors.Muted.copy(alpha = 0.18f),
                                        Offset(bx, header + lane * lh + gap * 3),
                                        Offset(bx, header + (lane + 1) * lh - gap * 3),
                                        1.dp.toPx(),
                                    )
                                }
                            }
                        }
                    }

                    // Pattern clips
                    val draggedPattern = dragPattern
                    val hovered = hoverHit
                    p.clips.forEachIndexed { i, original ->
                        val clip = if (draggedPattern?.first == i) draggedPattern.second else original
                        val index = p.patterns.indexOfFirst { it.id == clip.patternId }
                        if (index < 0 || clip.lane >= rows) return@forEachIndexed
                        val pattern = p.patterns[index]
                        val base = DawColors.PatternColors[index % DawColors.PatternColors.size]
                        val color = if (clip.muted) base.copy(alpha = 0.3f) else base
                        val (x, right) = g.edges(clip.startTick, p.clipLength(clip))
                        val y = g.top(clip.lane)
                        val w = right - x
                        drawRoundRect(color, Offset(x, y), Size(w, lh - gap * 2), CornerRadius(10.dp.toPx()))
                        if (draggedPattern?.first == i) {
                            drawRoundRect(DawColors.Ink, Offset(x, y), Size(w, lh - gap * 2), CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        } else if (hovered.let { (it is Hit.PatternBody && it.index == i) || (it is Hit.PatternEdge && it.index == i) }) {
                            drawRoundRect(DawColors.Ink.copy(alpha = 0.35f), Offset(x, y), Size(w, lh - gap * 2), CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
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
                        if (clip.lane >= rows) return@forEach
                        val (x, right) = g.edges(clip.startTick, clip.lengthTicks(p.bpm))
                        val y = g.top(clip.lane)
                        val w = right - x
                        val h = lh - gap * 2
                        val fade = if (clip.muted) 0.35f else 1f
                        drawRoundRect(DawColors.AccentSoft.copy(alpha = DawColors.AccentSoft.alpha * fade), Offset(x, y), Size(w, h), corner)
                        if (draggedAudio?.id == original.id) {
                            drawRoundRect(DawColors.Ink, Offset(x, y), Size(w, h), corner, style = Stroke(1.5.dp.toPx()))
                        } else if (hovered.let { (it is Hit.AudioBody && it.clip.id == original.id) || (it is Hit.AudioEdge && it.clip.id == original.id) }) {
                            drawRoundRect(DawColors.Ink.copy(alpha = 0.35f), Offset(x, y), Size(w, h), corner, style = Stroke(1.5.dp.toPx()))
                        }
                        val (a, b) = ClipEdit.trimFractions(clip)
                        drawWave(
                            audioPeaks[clip.path], x, y + 16.dp.toPx(), w, h - 18.dp.toPx(),
                            DawColors.Accent.copy(alpha = fade), from = a, to = b, reverse = clip.reverse,
                        )
                        val tags = buildList {
                            if (clip.reverse) add("rev")
                            if (clip.pitchSemis != 0) add((if (clip.pitchSemis > 0) "+${clip.pitchSemis}" else "${clip.pitchSemis}") + if (clip.keepsTempo) " str" else "")
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
                }
                // Song playhead on its own layer, so only the line redraws each frame.
                Canvas(Modifier.matchParentSize()) {
                    if (vm.mode != PlayMode.SONG) return@Canvas
                    val x = headTick / TICKS_PER_BAR * barW.toPx()
                    val color = if (recording) RecordRed else DawColors.Accent
                    val top = HEADER_H.toPx()
                    val bottom = top + LANE_H.toPx() * rows
                    drawLine(color, Offset(x, top - 2.dp.toPx()), Offset(x, bottom), 2.dp.toPx())
                    // Marker in the bar-number ruler.
                    val half = 6.dp.toPx()
                    val marker = Path().apply {
                        moveTo(x - half, top - 11.dp.toPx())
                        lineTo(x + half, top - 11.dp.toPx())
                        lineTo(x, top - 1.dp.toPx())
                        close()
                    }
                    drawPath(marker, color)
                }
              }
            }
        }
        // Follow and zoom, floating over the grid's bottom-right corner.
        ViewControls(vm, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp)) { factor ->
            // Button zoom keeps the middle of the screen in place.
            val old = vm.playlistBarDp
            vm.changePlaylistZoom(old * factor)
            val now = vm.playlistBarDp
            val mid = viewportPx / 2f
            pendingScroll[0] = ((hScroll.value + mid) * now / old - mid).roundToInt().coerceAtLeast(0)
            pendingScroll[1] = 0
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
                Text(
                    if (vm.isStretching(clip)) "Making the new pitch… playing the original meanwhile." else clip.stretch.blurb,
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                )
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
        // FL-style stretch mode: resample (turntable) or keep the tempo while changing the pitch.
        Segmented(
            ClipStretch.entries.map { it to it.label },
            clip.stretch,
            { mode -> set { it.copy(stretch = mode) } },
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
            display = formatVolume(clip.gain),
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

/**
 * Tap on a pattern clip: mute it, open its pattern, copy it (same pattern), clone it (its own
 * pattern), split it by channel, or delete it.
 */
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
        val sharedBy = vm.project.clips.count { it.patternId == pattern.id }
        if (sharedBy > 1) {
            Text(
                "This pattern is used by $sharedBy clips. Editing it changes all of them; Clone gives this one its own copy.",
                fontSize = 12.sp,
                color = DawColors.Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Edit pattern") {
                vm.selectPattern(pattern.id)
                vm.showRack()
                onDismiss()
            }
            TextAction(if (clip.muted) "Unmute" else "Mute") { vm.setPatternClip(index, clip.copy(muted = !clip.muted)) }
            Spacer(Modifier.weight(1f))
            TextAction("Delete") { vm.deletePatternClip(index); onDismiss() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Copy: the same pattern again, right after this clip.
            TextAction("Copy") { vm.duplicatePatternClip(index); onDismiss() }
            // Clone: this clip gets a new, independent pattern.
            TextAction("Clone") { vm.makeClipUnique(index); onDismiss() }
            if (vm.channelsIn(pattern).size > 1) {
                TextAction("Split by channel") { vm.splitClipByChannel(index); onDismiss() }
            }
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

/** A playlist row number. Tapping it opens a small menu: add an audio file, insert or delete a row. */
@Composable
private fun LaneNumber(
    lane: Int,
    canDelete: Boolean,
    canAdd: Boolean,
    onAddAudio: () -> Unit,
    onInsertRow: () -> Unit,
    onDeleteRow: () -> Unit,
) {
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
            if (canAdd) {
                DropdownMenuItem(
                    text = { Text("Insert empty row above") },
                    onClick = { menu = false; onInsertRow() },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text("Delete row ${lane + 1}", color = DawColors.Mute) },
                    onClick = { menu = false; onDeleteRow() },
                )
            }
        }
    }
}

/** "+" under the row numbers: adds a row at the bottom of the playlist. */
@Composable
private fun AddRowButton(onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 4.dp)
            .height(36.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DawColors.Chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
    }
}

/**
 * Small floating pill: Follow (the view pages along with the song while it plays) and zoom − / +.
 * [onZoom] gets the factor to multiply the bar width by.
 */
@Composable
private fun ViewControls(vm: DawViewModel, modifier: Modifier, onZoom: (Float) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Paper.copy(alpha = 0.94f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val follow = vm.playlistFollow
        Box(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (follow) DawColors.Note else DawColors.Chip)
                .clickable { vm.changePlaylistFollow(!follow) }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Follow",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (follow) DawColors.OnNote else DawColors.Muted,
            )
        }
        val zoomOut = vm.playlistBarDp > DawViewModel.PLAYLIST_BAR_MIN_DP + 0.5f
        val zoomIn = vm.playlistBarDp < DawViewModel.PLAYLIST_BAR_MAX_DP - 0.5f
        StepButton("−", enabled = zoomOut) { onZoom(1f / 1.4f) }
        StepButton("+", enabled = zoomIn) { onZoom(1.4f) }
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
