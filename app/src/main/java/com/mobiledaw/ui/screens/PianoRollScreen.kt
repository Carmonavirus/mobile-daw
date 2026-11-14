package com.mobiledaw.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.stepPitch
import com.mobiledaw.model.Note
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.model.TICKS_PER_BEAT
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.model.isBlackKey
import com.mobiledaw.model.inScale
import com.mobiledaw.model.noteName
import com.mobiledaw.ui.DawChip
import com.mobiledaw.ui.rightMouseTarget
import com.mobiledaw.ui.Wheel
import com.mobiledaw.ui.Segmented
import com.mobiledaw.ui.WaveformStrip
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.floor
import kotlin.math.roundToInt

private const val LOWEST_PITCH = 12   // C0
private const val HIGHEST_PITCH = 108 // C8

@Composable
fun PianoRollScreen(vm: DawViewModel, showPatternBar: Boolean = true) {
    val channel = vm.selectedChannel

    Column(Modifier.fillMaxSize()) {
        // Beside the rack on a wide screen the rack already shows the pattern bar.
        if (showPatternBar) PatternBar(vm)
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

        if (channel == null) {
            Text(
                if (vm.project.channels.isEmpty()) "No sounds yet. Add drums or sounds in the Channel rack, then draw notes here."
                else "Pick a sound above to draw its notes.",
                fontSize = 13.sp,
                color = DawColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            )
            return@Column
        }

        // clipToBounds stops any note/label drawing from spilling up over the track chips.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PianoRollCanvas(vm, Modifier.fillMaxSize().clipToBounds())
            // Short confirmation for Loop / Copy / Paste.
            vm.pianoNotice?.let { text ->
                Text(
                    text,
                    fontSize = 13.sp,
                    color = DawColors.Ink,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DawColors.Card)
                        .border(1.dp, DawColors.Line, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        // Chords panel: replaces the controls below while open.
        if (vm.chordGen != null) {
            com.mobiledaw.ui.ChordPanel(vm, Modifier.heightIn(max = 430.dp))
            return@Column
        }

        // One compact row: grid size (dropdown), note labels and the Chords button.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            GridDropdown(vm)
            Segmented(
                options = listOf(false to "Key ${vm.project.keyLabel}", true to "All"),
                selected = vm.labelAllNotes,
                onSelect = vm::changeLabelAllNotes,
            )
            // Resume: Play/Pause carries on from where it paused. Off = Play starts from the beginning.
            DawChip("Resume", selected = vm.pianoResume, dotColor = DawColors.Accent) {
                vm.changePianoResume(!vm.pianoResume)
            }
            // Wave: show or hide the moving soundwave above the play bar (remembered).
            DawChip("Wave", selected = vm.showWaveform, dotColor = DawColors.Accent) {
                vm.changeShowWaveform(!vm.showWaveform)
            }
            DawChip("Chords", selected = false, dotColor = DawColors.Note) { vm.openChordGen() }
            // Loop: repeat the 4 bars holding the notes into the next 4 bars.
            DawChip("Loop bars", selected = false, dotColor = null) { vm.loopBars() }
            // Copy this sound's notes, then pick another sound above and Paste.
            DawChip("Copy", selected = false, dotColor = null) { vm.copyNotes() }
            vm.noteClipboard?.let {
                DawChip("Paste", selected = false, dotColor = DawColors.Note) { vm.pasteNotes() }
            }
        }
        if (vm.showWaveform) {
            WaveformStrip(
                vm.scope,
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** The note length for drawing, as a compact dropdown (tap to choose 1/32 … 1/2). */
@Composable
private fun GridDropdown(vm: DawViewModel) {
    val options = listOf(
        TICKS_PER_STEP / 2 to "1/32",
        TICKS_PER_STEP to "1/16",
        TICKS_PER_STEP * 2 to "1/8",
        TICKS_PER_BEAT to "1/4",
        TICKS_PER_BEAT * 2 to "1/2",
    )
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == vm.drawLength }?.second ?: "1/16"
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, DawColors.Line, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Grid", fontSize = 11.sp, letterSpacing = 1.sp, color = DawColors.Muted)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Text(" ▾", fontSize = 12.sp, color = DawColors.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (ticks, name) ->
                DropdownMenuItem(
                    text = { Text(name, color = if (ticks == vm.drawLength) DawColors.Accent else DawColors.Ink) },
                    onClick = { vm.drawLength = ticks; open = false },
                )
            }
        }
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
    // A whole drum kit plays a different drum on every row, so name the drums on their rows.
    val drumKit = vm.isWholeDrumKit(channel)
    val drumStyle = TextStyle(fontSize = 9.sp, color = DawColors.Muted)
    val drumLabels = remember { HashMap<Int, TextLayoutResult>() }
    val barStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = DawColors.InkSoft)
    val barLabels = remember { HashMap<Int, TextLayoutResult>() }

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
        // Also when the Chords panel opens: fit the progression in view.
        val chordsOpen = vm.chordGen != null
        LaunchedEffect(channel.id, chordsOpen) {
            val notes = pattern.notesFor(channel.id)
            val center = if (notes.isEmpty()) channel.stepPitch else notes.map { it.pitch }.average().roundToInt()
            scrollY = ((HIGHEST_PITCH - center) * rowH - heightPx / 2f).coerceIn(0f, (contentHeight - heightPx).coerceAtLeast(0f))
            scrollX = 0f
            vm.chordGen?.let { s ->
                val span = (s.slots.size * vm.chordSettings.lengthTicks).coerceIn(TICKS_PER_BAR, TICKS_PER_BAR * 4)
                pxPerTick = (widthPx - gutter) / span
            }
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

        // Mouse only (PC / laptop): where the pointer hovers, and the Ctrl/Shift-drag selection box.
        var hover by remember { mutableStateOf<Offset?>(null) }
        var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
        var mouseDragging by remember { mutableStateOf(false) }
        val hoverOnNote = hover?.let { hitNote(it) >= 0 } == true
        // Right mouse button: delete the note under the pointer, and every note a right-drag crosses
        // (one undo step). Checks points in between so a fast drag doesn't skip notes.
        val eraseLast = remember { arrayOf(Offset.Zero) }
        fun eraseAt(pos: Offset) {
            val i = hitNote(pos)
            if (i < 0) return
            val gone = notesState.value[i]
            vm.editNotes(inGesture = true) { it.filterIndexed { j, _ -> j != i } }
            vm.pianoSelection = vm.pianoSelection - gone
        }
        fun eraseStart(pos: Offset) {
            vm.beginNoteGesture()
            eraseAt(pos)
            eraseLast[0] = pos
        }
        fun eraseTo(pos: Offset) {
            val last = eraseLast[0]
            val steps = ((pos - last).getDistance() / (rowH / 3f)).toInt().coerceIn(1, 32)
            for (k in 1..steps) eraseAt(last + (pos - last) * (k / steps.toFloat()))
            eraseLast[0] = pos
        }
        fun zoomAround(focusX: Float, factor: Float) {
            val minZoom = (widthPx - gutter) / (TICKS_PER_BAR * 8f)
            val maxZoom = (widthPx - gutter) / (TICKS_PER_STEP * 4f)
            val focusTick = tickAt(focusX)
            pxPerTick = (pxPerTick * factor).coerceIn(minZoom, maxZoom)
            scrollX = focusTick * pxPerTick - (focusX - gutter)
            clampScroll()
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerHoverIcon(if (hoverOnNote && !mouseDragging) PointerIcon.Hand else PointerIcon.Default)
                .rightMouseTarget(onPress = ::eraseStart, onDrag = ::eraseTo)
                // Mouse wheel / trackpad: scroll; Shift+wheel scrolls sideways; Ctrl+wheel or a
                // trackpad pinch zooms around the pointer. Also tracks the hover position.
                .pointerInput(widthPx, heightPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.type != PointerType.Mouse) {
                                hover = null
                                continue
                            }
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    val d = change.scrollDelta
                                    val mods = event.keyboardModifiers
                                    if (mods.isCtrlPressed || mods.isMetaPressed) {
                                        zoomAround(change.position.x, Wheel.zoomFactor(d.y))
                                    } else if (mods.isShiftPressed) {
                                        scrollX += Wheel.scrollPx(if (d.x != 0f) d.x else d.y, density.density)
                                    } else {
                                        scrollX += Wheel.scrollPx(d.x, density.density)
                                        scrollY += Wheel.scrollPx(d.y, density.density)
                                    }
                                    clampScroll()
                                    change.consume()
                                }
                                PointerEventType.Exit -> hover = null
                                else -> hover = change.position
                            }
                        }
                    }
                }
                .pointerInput(widthPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (down.type == PointerType.Mouse) {
                            // PC / laptop: FL-style mouse editing (see mouseGesture). Touch is unchanged below.
                            val buttons = currentEvent.buttons
                            val mods = currentEvent.keyboardModifiers
                            val list0 = notesState.value
                            val g = drawLengthState.value
                            val len = lengthState.value
                            val chId = channelState.value.id
                            down.consume()
                            if (buttons.isTertiaryPressed) {
                                // Middle-drag pans.
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    val delta = c.positionChange()
                                    scrollX -= delta.x
                                    scrollY -= delta.y
                                    clampScroll()
                                    c.consume()
                                }
                                return@awaitEachGesture
                            }
                            if (!buttons.isPrimaryPressed) return@awaitEachGesture
                            val hitIndex = hitNote(down.position)
                            if (mods.isCtrlPressed || mods.isMetaPressed || mods.isShiftPressed) {
                                // Ctrl/Shift-drag: box select (Shift adds to the selection). Ctrl+click toggles one note.
                                val before = vm.pianoSelection
                                var moved = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    if (!moved && (c.position - down.position).getDistance() > touchSlop / 2f) moved = true
                                    if (moved) box = down.position to c.position
                                    c.consume()
                                }
                                val area = box
                                box = null
                                if (area == null) {
                                    val n = list0.getOrNull(hitIndex)
                                    vm.pianoSelection = when {
                                        n == null -> if (mods.isShiftPressed) before else emptySet()
                                        n in before -> before - n
                                        else -> before + n
                                    }
                                } else {
                                    val (a, b) = area
                                    val t0 = tickAt(minOf(a.x, b.x)); val t1 = tickAt(maxOf(a.x, b.x))
                                    val p0 = pitchAt(maxOf(a.y, b.y)); val p1 = pitchAt(minOf(a.y, b.y))
                                    val inside = notesState.value.filter { it.pitch in p0..p1 && it.start < t1 && it.start + it.length > t0 }.toSet()
                                    vm.pianoSelection = if (mods.isShiftPressed) before + inside else inside
                                }
                                return@awaitEachGesture
                            }

                            // Left click: on empty grid places a note (drag right away to move it);
                            // on a note, drag moves it (or resizes it near its right end), with the
                            // whole selection when that note is selected.
                            val base: List<Note>
                            val anchorIndex: Int
                            var resize = false
                            val group: Set<Int>
                            val selection = vm.pianoSelection
                            if (hitIndex < 0) {
                                val tick = tickAt(down.position.x)
                                val pitch = pitchAt(down.position.y)
                                if (tick < 0 || tick >= len || pitch !in LOWEST_PITCH..HIGHEST_PITCH) return@awaitEachGesture
                                val start = (floor(tick / g).toInt()) * g
                                val note = Note(start, g.coerceAtMost(len - start), pitch)
                                vm.pianoSelection = emptySet()
                                vm.addNote(chId, note)
                                base = list0 + note
                                anchorIndex = base.lastIndex
                                group = setOf(anchorIndex)
                            } else {
                                val n = list0[hitIndex]
                                base = list0
                                anchorIndex = hitIndex
                                resize = noteX(n.start + n.length) - down.position.x < edgeGrab * 0.6f
                                group = if (n in selection) list0.indices.filter { list0[it] in selection }.toSet() else setOf(hitIndex)
                                if (n !in selection) vm.pianoSelection = emptySet()
                                vm.beginNoteGesture()
                                vm.editPreview(channelState.value, n.pitch)
                            }
                            val anchor = base[anchorIndex]
                            var lastShift = 0 to 0
                            var result = base
                            mouseDragging = true
                            try {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    val total = c.position - down.position
                                    val raw = (total.x / pxPerTick).roundToInt()
                                    if (resize) {
                                        val newLen = (((anchor.length + raw).toFloat() / g).roundToInt() * g).coerceAtLeast(g)
                                        var dl = newLen - anchor.length
                                        // Every note keeps at least one grid step and stays inside the pattern.
                                        group.forEach { i -> val n = base[i]; dl = dl.coerceAtLeast(minOf(g, n.length) - n.length).coerceAtMost(len - n.start - n.length) }
                                        if (dl to 0 == lastShift) continue
                                        lastShift = dl to 0
                                        result = base.mapIndexed { i, n -> if (i in group) n.copy(length = n.length + dl) else n }
                                    } else {
                                        val newStart = ((anchor.start + raw).toFloat() / g).roundToInt() * g
                                        var dt = newStart - anchor.start
                                        var ds = -(total.y / rowH).roundToInt()
                                        group.forEach { i ->
                                            val n = base[i]
                                            dt = dt.coerceIn(-n.start, maxOf(-n.start, len - g - n.start))
                                            ds = ds.coerceIn(LOWEST_PITCH - n.pitch, HIGHEST_PITCH - n.pitch)
                                        }
                                        if (dt to ds == lastShift) continue
                                        if (ds != lastShift.second) vm.editPreview(channelState.value, anchor.pitch + ds)
                                        lastShift = dt to ds
                                        result = base.mapIndexed { i, n -> if (i in group) n.copy(start = n.start + dt, pitch = n.pitch + ds) else n }
                                    }
                                    val next = result
                                    vm.editNotes(inGesture = true) { next }
                                }
                            } finally {
                                mouseDragging = false
                            }
                            if (group.size > 1) vm.pianoSelection = group.map { result[it] }.toSet()
                            return@awaitEachGesture
                        }
                        val hit = hitNote(down.position)
                        val original: Note? = notesState.value.getOrNull(hit)
                        // A touch that starts on a note resizes (near its right edge) or moves it;
                        // a touch on empty grid scrolls with one finger. A tap still places/deletes.
                        var mode = when {
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
                        // A whole move/resize drag is one undo step.
                        if (original != null) vm.beginNoteGesture()
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
                            val delta = change.positionChange()  // read before consume(): consumed changes report zero
                            total += delta
                            if (!dragging && total.getDistance() > touchSlop) dragging = true
                            if (!dragging) continue
                            change.consume()
                            when (mode) {
                                DragMode.PAN -> {
                                    scrollX -= delta.x
                                    scrollY -= delta.y
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
                if (drumKit) {
                    com.mobiledaw.sound.SoundFontLibrary.GM_DRUMS[p]?.let { name ->
                        val layout = drumLabels.getOrPut(p) { measurer.measure(name, drumStyle) }
                        drawText(layout, topLeft = Offset(gutter + 4.dp.toPx(), y + (rowH - layout.size.height) / 2f))
                    }
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

            // Mouse hover (PC / laptop): which note is under the pointer, or where a click would place one.
            val selection = vm.pianoSelection
            val hoverPos = if (mouseDragging || box != null) null else hover
            val hoveredIndex = hoverPos?.let { hitNote(it) } ?: -1
            val hovered = notesState.value.getOrNull(hoveredIndex)
            val hoverEdge = hovered != null && hoverPos != null &&
                noteX(hovered.start + hovered.length) - hoverPos.x < edgeGrab * 0.6f
            if (hoverPos != null && hovered == null && hoverPos.x > gutter) {
                val tick = tickAt(hoverPos.x)
                val pitch = pitchAt(hoverPos.y)
                val g = vm.drawLength
                if (tick >= 0 && tick < len && pitch in LOWEST_PITCH..HIGHEST_PITCH) {
                    val start = floor(tick / g).toInt() * g
                    val w = (minOf(g, len - start) * pxPerTick - 2f).coerceAtLeast(4f)
                    drawRect(DawColors.Note.copy(alpha = 0.05f), Offset(gutter, noteY(pitch)), Size(size.width - gutter, rowH))
                    drawRoundRect(DawColors.Note.copy(alpha = 0.22f), Offset(noteX(start), noteY(pitch) + (rowH - barH) / 2f), Size(w, barH), CornerRadius(barH / 2f))
                }
            }

            // This channel's notes: thin ink bars with a note-name pill.
            notesState.value.forEach { n ->
                if (n.pitch !in firstPitch..lastPitch) return@forEach
                val x = noteX(n.start)
                val w = (n.length * pxPerTick - 2f).coerceAtLeast(4f)
                if (x + w < gutter || x > size.width) return@forEach
                val y = noteY(n.pitch) + (rowH - barH) / 2f
                val picked = n in selection
                drawRoundRect(if (picked) DawColors.Accent else DawColors.Note, Offset(x, y), Size(w, barH), CornerRadius(barH / 2f))
                if (n == hovered) {
                    // Mouse hover: a soft ring, and a brighter right end where a drag resizes.
                    val pad = 2.dp.toPx()
                    drawRoundRect(DawColors.Accent.copy(alpha = 0.35f), Offset(x - pad, y - pad), Size(w + pad * 2, barH + pad * 2), CornerRadius(barH / 2f + pad), style = Stroke(1.5.dp.toPx()))
                    if (hoverEdge) drawCircle(DawColors.Accent, barH * 0.38f, Offset(x + w - barH / 2f, y + barH / 2f))
                }

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

            // Bar numbers at the top of each bar line.
            var bar = 0
            while (bar * TICKS_PER_BAR < len) {
                val x = noteX(bar * TICKS_PER_BAR)
                if (x >= gutter - 1f && x < size.width) {
                    val layout = barLabels.getOrPut(bar + 1) { measurer.measure("${bar + 1}", barStyle) }
                    val padX = 4.dp.toPx()
                    val left = x + 2.dp.toPx()
                    val top = 2.dp.toPx()
                    drawRoundRect(
                        DawColors.Paper.copy(alpha = 0.9f),
                        Offset(left, top),
                        Size(layout.size.width + padX * 2, layout.size.height.toFloat()),
                        CornerRadius(4.dp.toPx()),
                    )
                    drawText(layout, topLeft = Offset(left + padX, top))
                }
                bar++
            }

            // Ctrl/Shift-drag selection box.
            box?.let { (a, b) ->
                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val sz = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                drawRect(DawColors.Accent.copy(alpha = 0.12f), tl, sz)
                drawRect(DawColors.Accent, tl, sz, style = Stroke(1.dp.toPx()))
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
