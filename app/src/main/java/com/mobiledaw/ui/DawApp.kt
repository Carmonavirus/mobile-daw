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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
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

    // Tablet in landscape (or any window 840dp+ wide): screens side by side.
    val wide = windowWidthClass() == WindowWidthClass.EXPANDED
    SideEffect { vm.wideLayout = wide }

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
        if (wide) {
            // Landscape tablet: tabs and transport share one row, leaving more height for the screens.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BottomTabs(vm.tab, Modifier.weight(1f), selectTab)
                Box(Modifier.weight(1f)) { transport() }
            }
        } else {
            transport()
            BottomTabs(vm.tab, onSelect = selectTab)
        }
    }

    vm.synthEditorChannelId?.let { com.mobiledaw.ui.synth.SynthEditor(vm, it) }
    vm.openEffect?.let { (track, effect) -> com.mobiledaw.ui.fx.EffectEditor(vm, track, effect) }
    if (showProjects) ProjectsDialog(vm) { showProjects = false }
    SharedAudioDialog(vm)
    if (showTempo) TempoKeyDialog(vm) { showTempo = false }
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
            if (vm.tab == Tab.PLAYLIST) {
                Row(
                    Modifier
                        .padding(start = 14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DawColors.Chip)
                        .clickable(onClick = onRecordMode)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
                    Spacer(Modifier.width(6.dp))
                    Text("Record", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                }
            }
        }
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

/** "Stop all sound": a square in a ring, far left of the transport. Cuts every sound at once. */
@Composable
private fun StopAllButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(DawColors.Chip)
            .clickable(onClickLabel = "Stop all sound", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(DawColors.Ink))
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
 * Wide screens: two screens side by side, split by a thin line.
 * Playlist tab = playlist + channel rack (build patterns and arrange them together).
 * Rack tab = channel rack + piano roll (pick a sound on the left, write its notes on the right).
 * Piano and Mixer use the whole width on their own.
 */
@Composable
private fun WideContent(vm: DawViewModel) {
    when (vm.tab) {
        Tab.PLAYLIST -> SplitPanes(0.6f, { PlaylistScreen(vm) }, { ChannelRackScreen(vm) })
        Tab.RACK -> SplitPanes(0.46f, { ChannelRackScreen(vm) }, { PianoRollScreen(vm, showPatternBar = false) })
        Tab.PIANO -> PianoRollScreen(vm)
        Tab.MIXER -> MixerScreen(vm)
    }
}

@Composable
private fun SplitPanes(leftShare: Float, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(leftShare).fillMaxHeight()) { left() }
        Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Line))
        Box(Modifier.weight(1f - leftShare).fillMaxHeight()) { right() }
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
