package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mobiledaw.DawViewModel
import com.mobiledaw.TakeState
import com.mobiledaw.audio.Recorder
import com.mobiledaw.PlayMode
import com.mobiledaw.Tab
import com.mobiledaw.ui.screens.ChannelRackScreen
import com.mobiledaw.ui.screens.MixerScreen
import com.mobiledaw.ui.screens.PianoRollScreen
import com.mobiledaw.ui.screens.PlaylistScreen
import com.mobiledaw.ui.theme.DawColors

@Composable
fun DawApp(vm: DawViewModel) {
    var showProjects by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun micAllowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.recordMessage = if (granted) null else "Allow the microphone to record."
    }

    // Tablet in landscape: screens side by side. Any landscape window: one bottom row.
    val wide = useSplitPanes()
    val landscape = isLandscapeWindow()
    SideEffect {
        vm.wideLayout = wide
        Desktop.dialogOpen = showProjects || showTempo || vm.settingsOpen || vm.exportOpen ||
            vm.synthEditorChannelId != null || vm.openEffect != null
    }
    // Ctrl+O on a computer keyboard.
    if (Desktop.projectsRequested) {
        SideEffect {
            Desktop.projectsRequested = false
            showProjects = true
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DawColors.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Header(
            vm = vm,
            onTitle = { showProjects = true },
            onTempo = { showTempo = true },
            onMenu = { vm.settingsOpen = true },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (wide) {
                WideContent(vm)
            } else {
                when (vm.tab) {
                    Tab.PLAYLIST -> PlaylistScreen(vm)
                    Tab.RACK -> ChannelRackScreen(vm)
                    Tab.PIANO -> PianoRollScreen(vm)
                    Tab.MIXER -> MixerScreen(vm)
                }
            }
            CountInOverlay(vm.countIn, Modifier.align(Alignment.Center))
            // "Piano added to the channel rack": top of the screen, clear of the piano roll's own notices.
            AddedNotice(vm, Modifier.align(Alignment.TopCenter))
            // Keyboard shortcut feedback ("Saved", "Typing keyboard on") on screens without the piano roll,
            // which shows these itself.
            val pianoShown = vm.tab == Tab.PIANO || (wide && vm.tab == Tab.RACK)
            if (!pianoShown) vm.pianoNotice?.let { text ->
                Text(
                    text,
                    fontSize = 13.sp,
                    color = DawColors.Ink,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DawColors.Card)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        // Transport sits right above the tabs, within thumb reach.
        // A slim progress bar sits just above it: tap or drag to move the playhead.
        TransportProgress(vm, Modifier.padding(top = 2.dp))
        val transport: @Composable () -> Unit = {
            if (vm.recordMode && vm.tab == Tab.PLAYLIST) {
                RecordTransport(vm, onRecord = { if (micAllowed()) vm.toggleTake() else askMic.launch(Manifest.permission.RECORD_AUDIO) })
            } else {
                Transport(vm, onRecordMode = {
                    vm.enterRecordMode()
                    if (!micAllowed()) askMic.launch(Manifest.permission.RECORD_AUDIO)
                })
            }
        }
        val selectTab: (Tab) -> Unit = {
            if (vm.recordMode) vm.exitRecordMode()
            vm.tab = it
        }
        if (landscape) {
            // Landscape: tabs and transport share one row, leaving more height for the screens.
            // Play sits in the middle of the screen, with the other controls to its right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BottomTabs(vm.tab, Modifier.weight(1f), selectTab)
                if (vm.recordMode && vm.tab == Tab.PLAYLIST) {
                    Box(Modifier.weight(1f)) { transport() }
                } else {
                    PlayButton(vm.playing, vm::togglePlay, diameter = 38.dp)
                    LandscapeTransport(vm, Modifier.weight(1f), onRecordMode = {
                        vm.enterRecordMode()
                        if (!micAllowed()) askMic.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
            }
        } else {
            transport()
            BottomTabs(vm.tab, onSelect = selectTab)
        }
    }
    vm.turnCountdown?.let { TurnOverlay(it) }
    }

    vm.synthEditorChannelId?.let { com.mobiledaw.ui.synth.SynthEditor(vm, it) }
    vm.openEffect?.let { (track, effect) -> com.mobiledaw.ui.fx.EffectEditor(vm, track, effect) }
    if (showProjects) ProjectsDialog(vm) { showProjects = false }
    SharedAudioDialog(vm)
    if (showTempo) TempoKeyDialog(vm) { showTempo = false }
    if (Desktop.shortcutsOpen) ShortcutsSheet(vm) { Desktop.shortcutsOpen = false }
    if (vm.exportOpen) ExportScreen(vm)
    if (vm.settingsOpen) {
        SettingsScreen(
            vm = vm,
            onOpenProjects = { vm.settingsOpen = false; showProjects = true },
            onDismiss = { vm.settingsOpen = false },
        )
    }
}

@Composable
private fun Header(vm: DawViewModel, onTitle: () -> Unit, onTempo: () -> Unit, onMenu: () -> Unit) {
    val p = vm.project
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                p.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onTitle).padding(horizontal = 8.dp),
            )
            Text(
                "${p.bpm} BPM  ·  ${p.keyLabel.uppercase()}",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = DawColors.Muted,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onTempo).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        MetronomeToggle(vm.metronomeOn, vm::toggleMetronome, Modifier.align(Alignment.CenterStart))
        MenuGlyph(onMenu, Modifier.align(Alignment.CenterEnd))
        CloudStatusButton(vm, onMenu, Modifier.align(Alignment.CenterEnd).padding(end = 42.dp))
    }
}

/** Metronome on/off, top left: a small pendulum-and-base glyph that fills in when it's on. */
@Composable
private fun MetronomeToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (on) Color.White else DawColors.Ink
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (on) DawColors.Accent else DawColors.Chip)
            .clickable(onClickLabel = if (on) "Turn metronome off" else "Turn metronome on", onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.8.dp.toPx()
            // Body: a tapered trapezoid.
            val body = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.36f, h * 0.06f)
                lineTo(w * 0.64f, h * 0.06f)
                lineTo(w * 0.88f, h * 0.94f)
                lineTo(w * 0.12f, h * 0.94f)
                close()
            }
            drawPath(body, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            // Pendulum arm, leaning right, with its weight.
            drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.8f), androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.18f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(ink, radius = stroke * 1.1f, center = androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.49f))
        }
    }
}

/** Normal transport: PAT/SONG on the left of play, and on the playlist a record button on its right. */
@Composable
private fun Transport(vm: DawViewModel, onRecordMode: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            StopAllButton(vm::stopAllSound)
            Spacer(Modifier.weight(1f))
            Segmented(
                options = listOf(PlayMode.PATTERN to "PAT", PlayMode.SONG to "SONG"),
                selected = vm.mode,
                onSelect = vm::switchMode,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            )
        }
        PlayButton(vm.playing, vm::togglePlay, diameter = 38.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (vm.pianoVisible) {
                UndoButton(
                    enabled = vm.canUndo,
                    canRedo = vm.canRedo,
                    onUndo = vm::undoNoteEdit,
                    onRedo = vm::redoNoteEdit,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            if (vm.tab == Tab.PLAYLIST) RecordChip(onRecordMode, Modifier.padding(start = 14.dp))
        }
    }
}

/**
 * Landscape transport, right of the centred play button: PAT/SONG, then Stop, then undo
 * (piano roll showing) or Record (playlist).
 */
@Composable
private fun LandscapeTransport(vm: DawViewModel, modifier: Modifier, onRecordMode: () -> Unit) {
    Row(modifier.padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Segmented(
            options = listOf(PlayMode.PATTERN to "PAT", PlayMode.SONG to "SONG"),
            selected = vm.mode,
            onSelect = vm::switchMode,
            modifier = Modifier.padding(end = 10.dp),
        )
        StopAllButton(vm::stopAllSound, diameter = 38.dp)
        if (vm.pianoVisible) {
            UndoButton(
                enabled = vm.canUndo,
                canRedo = vm.canRedo,
                onUndo = vm::undoNoteEdit,
                onRedo = vm::redoNoteEdit,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (vm.tab == Tab.PLAYLIST) RecordChip(onRecordMode, Modifier.padding(start = 10.dp))
    }
}

/** Opens record mode on the playlist. */
@Composable
private fun RecordChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
        Spacer(Modifier.width(6.dp))
        Text("Record", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
    }
}

/**
 * Piano roll undo, right of play: tap to undo the last note edit, long-press to redo.
 * Greyed out when there is nothing to undo (it still takes a long-press while a redo is waiting).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UndoButton(enabled: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (enabled) DawColors.Ink else DawColors.Muted.copy(alpha = 0.45f)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (enabled) DawColors.Chip else DawColors.Chip.copy(alpha = 0.5f))
            .combinedClickable(
                enabled = enabled || canRedo,
                onClick = { if (enabled) onUndo() },
                onLongClick = {
                    if (canRedo) {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onRedo()
                    }
                },
                onClickLabel = "Undo",
                onLongClickLabel = "Redo",
            ),
        contentAlignment = Alignment.Center,
    ) {
        // A curved arrow pointing back (left).
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 2.dp.toPx()
            val arc = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.2f, h * 0.38f)
                lineTo(w * 0.62f, h * 0.38f)
                cubicTo(w * 0.98f, h * 0.38f, w * 0.98f, h * 0.88f, w * 0.62f, h * 0.88f)
                lineTo(w * 0.38f, h * 0.88f)
            }
            drawPath(
                arc,
                ink,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            val head = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.04f, h * 0.38f)
                lineTo(w * 0.32f, h * 0.14f)
                lineTo(w * 0.32f, h * 0.62f)
                close()
            }
            drawPath(head, ink)
        }
    }
}

/**
 * Record mode, shown on the playlist after tapping Record: a bigger play button on the left,
 * the take status in the middle and a big record button on the right
 * (tap to count in and record, tap again to stop). "Close" goes back to the normal bar.
 */
@Composable
private fun RecordTransport(vm: DawViewModel, onRecord: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(vm.playing, vm::togglePlay, diameter = 56.dp)
        StopAllButton(vm::stopAllSound, diameter = 38.dp, modifier = Modifier.padding(start = 10.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = when (vm.takeState) {
                TakeState.COUNTING -> "Get ready…"
                TakeState.RECORDING -> "Recording  " + clock(vm.takePeaks.size * Recorder.PEAK_MS)
                TakeState.IDLE -> vm.recordMessage ?: "Tap ● to record"
            }
            Text(
                status,
                fontSize = 12.sp,
                fontWeight = if (vm.takeState == TakeState.RECORDING) FontWeight.SemiBold else FontWeight.Normal,
                color = if (vm.takeState == TakeState.RECORDING) RecordRed else DawColors.Muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            // Mic level
            Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(CircleShape).background(DawColors.Line)) {
                Box(
                    Modifier
                        .fillMaxWidth(vm.micLevel.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (vm.micLevel > 0.95f) RecordRed else DawColors.Accent),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Close",
                fontSize = 12.sp,
                color = DawColors.Ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DawColors.Chip)
                    .clickable(onClick = vm::exitRecordMode)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
        RecordButton(vm.takeState != TakeState.IDLE, onRecord)
    }
}

/**
 * Stop: a square in a ring. Stops playback, cuts every sound at once and goes back to where play
 * started (the play button pauses instead).
 */
@Composable
private fun StopAllButton(onClick: () -> Unit, diameter: androidx.compose.ui.unit.Dp = 30.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(DawColors.Chip)
            .clickable(onClickLabel = "Stop", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(diameter * 0.36f).clip(RoundedCornerShape(2.dp)).background(DawColors.Ink))
    }
}

@Composable
private fun RecordButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(DawColors.Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(RecordRed), contentAlignment = Alignment.Center) {
            // A white square while recording means "tap to stop".
            if (active) Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
        }
    }
}

/**
 * Wide screens: two screens side by side.
 * Playlist tab = playlist + channel rack (build patterns and arrange them together).
 * Rack tab = channel rack + piano roll (pick a sound on the left, write its notes on the right).
 * Piano and Mixer use the whole width on their own.
 * Drag the divider to resize, or use its arrows to close a pane; a closed pane leaves a strip
 * on its edge that opens it again. Sizes and closed panes are kept for the session, per tab.
 */
@Composable
private fun WideContent(vm: DawViewModel) {
    when (vm.tab) {
        Tab.PLAYLIST -> SplitPanes(vm, Tab.PLAYLIST, 0.6f, "Playlist", "Rack", { PlaylistScreen(vm) }, { ChannelRackScreen(vm) })
        Tab.RACK -> SplitPanes(vm, Tab.RACK, 0.46f, "Rack", "Piano", { ChannelRackScreen(vm) }, { PianoRollScreen(vm, showPatternBar = false) })
        Tab.PIANO -> PianoRollScreen(vm)
        Tab.MIXER -> MixerScreen(vm)
    }
}

private const val MIN_SHARE = 0.25f
private const val MAX_SHARE = 0.75f
/** Dragging the divider this close to an edge closes the pane on that side. */
private const val CLOSE_SHARE = 0.16f

@Composable
private fun SplitPanes(
    vm: DawViewModel,
    tab: Tab,
    defaultShare: Float,
    leftName: String,
    rightName: String,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    when (vm.splitHidden[tab]) {
        com.mobiledaw.PaneSide.LEFT -> Row(Modifier.fillMaxSize()) {
            ReopenStrip(leftName, onLeftEdge = true) { vm.splitHidden.remove(tab) }
            Box(Modifier.weight(1f).fillMaxHeight()) { right() }
        }
        com.mobiledaw.PaneSide.RIGHT -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { left() }
            ReopenStrip(rightName, onLeftEdge = false) { vm.splitHidden.remove(tab) }
        }
        null -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val totalPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val share = vm.splitShare[tab] ?: defaultShare
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(share).fillMaxHeight()) { left() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Line))
                Box(Modifier.weight(1f - share).fillMaxHeight()) { right() }
            }
            val handleWidth = 24.dp
            var dragging by remember { mutableStateOf(false) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val halfHandlePx = with(density) { (handleWidth / 2).roundToPx() }
            DividerHandle(
                leftName = leftName,
                rightName = rightName,
                width = handleWidth,
                dragging = dragging,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { androidx.compose.ui.unit.IntOffset((share * totalPx).toInt() - halfHandlePx, 0) }
                    .pointerInput(tab, totalPx) {
                        var startShare = defaultShare
                        detectHorizontalDragGestures(
                            onDragStart = { startShare = vm.splitShare[tab] ?: defaultShare; dragging = true },
                            onDragEnd = {
                                dragging = false
                                val now = vm.splitShare[tab] ?: defaultShare
                                when {
                                    now < CLOSE_SHARE -> { vm.splitShare[tab] = startShare.coerceIn(MIN_SHARE, MAX_SHARE); vm.splitHidden[tab] = com.mobiledaw.PaneSide.LEFT }
                                    now > 1f - CLOSE_SHARE -> { vm.splitShare[tab] = startShare.coerceIn(MIN_SHARE, MAX_SHARE); vm.splitHidden[tab] = com.mobiledaw.PaneSide.RIGHT }
                                    else -> vm.splitShare[tab] = now.coerceIn(MIN_SHARE, MAX_SHARE)
                                }
                            },
                            onDragCancel = { dragging = false; vm.splitShare[tab] = (vm.splitShare[tab] ?: defaultShare).coerceIn(MIN_SHARE, MAX_SHARE) },
                        ) { change, dx ->
                            change.consume()
                            val now = vm.splitShare[tab] ?: defaultShare
                            vm.splitShare[tab] = (now + dx / totalPx).coerceIn(0.05f, 0.95f)
                        }
                    },
                onCloseLeft = { vm.splitHidden[tab] = com.mobiledaw.PaneSide.LEFT },
                onCloseRight = { vm.splitHidden[tab] = com.mobiledaw.PaneSide.RIGHT },
            )
        }
    }
}

/**
 * The split line, Windows style: hover anywhere along it and the cursor turns into the
 * two-way resize arrows; press anywhere on it and drag sideways to resize. The whole height is a
 * comfortably wide touch target. The line lights up while hovered or dragged.
 * The two small arrows at the top close the pane they point into; they show while the mouse is
 * over the line, or for a few seconds after a touch on it.
 */
@Composable
private fun DividerHandle(
    leftName: String,
    rightName: String,
    width: androidx.compose.ui.unit.Dp,
    dragging: Boolean,
    modifier: Modifier,
    onCloseLeft: () -> Unit,
    onCloseRight: () -> Unit,
) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Touch has no hover: a touch on the line shows the arrows for a few seconds instead.
    var touchedAt by remember { mutableStateOf(0L) }
    var touchShow by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(touchedAt) {
        if (touchedAt != 0L) {
            touchShow = true
            kotlinx.coroutines.delay(3000)
            touchShow = false
        }
    }
    val active = hovered || dragging
    val showArrows = hovered || dragging || touchShow
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .hoverable(hover)
            .ewResizeCursor()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
                            e.changes.any { it.type == androidx.compose.ui.input.pointer.PointerType.Touch }
                        ) touchedAt = e.changes.first().uptimeMillis
                    }
                }
            }
            .semantics { contentDescription = "Drag to resize" },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (active) Box(Modifier.width(3.dp).fillMaxHeight().background(DawColors.Accent))
        // The close arrows sit at the top of the line, and only show while you're on it.
        if (showArrows) {
            Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArrowButton(pointsLeft = true, label = "Close $leftName", onClick = onCloseLeft)
                Spacer(Modifier.height(6.dp))
                ArrowButton(pointsLeft = false, label = "Close $rightName", onClick = onCloseRight)
            }
        }
    }
}

/** A small round button with a chevron, drawn (no icon font needed). */
@Composable
private fun ArrowButton(pointsLeft: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(DawColors.Card)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsLeft, Modifier.size(10.dp))
    }
}

@Composable
private fun Chevron(pointsLeft: Boolean, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val tipX = if (pointsLeft) size.width * 0.25f else size.width * 0.75f
        val backX = if (pointsLeft) size.width * 0.7f else size.width * 0.3f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(backX, 0f)
            lineTo(tipX, size.height / 2f)
            lineTo(backX, size.height)
        }
        drawPath(
            path,
            DawColors.Ink,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** A closed pane: a slim strip on its edge with its name. Tap it to open the pane again. */
@Composable
private fun ReopenStrip(name: String, onLeftEdge: Boolean, onOpen: () -> Unit) {
    Box(
        Modifier
            .width(30.dp)
            .fillMaxHeight()
            .background(DawColors.Chip)
            .clickable(onClickLabel = "Open $name", onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Points into the screen: the way the pane slides back.
            Chevron(pointsLeft = !onLeftEdge, modifier = Modifier.size(10.dp))
            Spacer(Modifier.height(44.dp))
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .wrapContentSize(unbounded = true)
                    .graphicsLayer { rotationZ = if (onLeftEdge) -90f else 90f },
            )
            Spacer(Modifier.height(44.dp))
            Chevron(pointsLeft = !onLeftEdge, modifier = Modifier.size(10.dp))
        }
    }
}

/**
 * Phone about to turn: covers the whole screen and swallows every touch while it counts 3, 2, 1.
 * Turning the phone back cancels it. Playback carries on underneath.
 */
@Composable
private fun TurnOverlay(c: com.mobiledaw.TurnCountdown) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DawColors.Paper.copy(alpha = 0.94f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (c.toLandscape) "Turning sideways" else "Turning upright",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(DawColors.Card),
                contentAlignment = Alignment.Center,
            ) {
                Text("${c.seconds}", fontSize = 60.sp, fontWeight = FontWeight.Bold, color = DawColors.Ink)
            }
            Spacer(Modifier.height(12.dp))
            Text("Turn the phone back to cancel", fontSize = 13.sp, color = DawColors.Muted)
        }
    }
}

/** Big 1, 2, 3, 4, GO over the screen while counting in (the engine clicks each beat). */
@Composable
private fun CountInOverlay(count: Int?, modifier: Modifier = Modifier) {
    if (count == null) return
    val go = count == DawViewModel.COUNT_IN_GO
    Box(
        modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(DawColors.Card.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (go) "GO" else "$count",
            fontSize = if (go) 52.sp else 64.sp,
            fontWeight = FontWeight.Bold,
            color = if (go) RecordRed else DawColors.Ink,
        )
    }
}

private val RecordRed = Color(0xFFE5484D)

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun BottomTabs(current: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(DawColors.Paper)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) DawColors.Ink else DawColors.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(width = if (on) 16.dp else 4.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(if (on) DawColors.Ink else DawColors.Paper),
                )
            }
        }
    }
}
