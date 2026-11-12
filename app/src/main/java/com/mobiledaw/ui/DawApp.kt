package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
            when (vm.tab) {
                Tab.PLAYLIST -> PlaylistScreen(vm)
                Tab.RACK -> ChannelRackScreen(vm)
                Tab.PIANO -> PianoRollScreen(vm)
                Tab.MIXER -> MixerScreen(vm)
            }
            CountInOverlay(vm.countIn, Modifier.align(Alignment.Center))
        }
        // Transport sits right above the tabs, within thumb reach.
        // A slim progress bar sits just above it: tap or drag to move the playhead.
        TransportProgress(vm, Modifier.padding(top = 2.dp))
        if (vm.recordMode && vm.tab == Tab.PLAYLIST) {
            RecordTransport(vm, onRecord = { if (micAllowed()) vm.toggleTake() else askMic.launch(Manifest.permission.RECORD_AUDIO) })
        } else {
            Transport(vm, onRecordMode = {
                vm.enterRecordMode()
                if (!micAllowed()) askMic.launch(Manifest.permission.RECORD_AUDIO)
            })
        }
        BottomTabs(vm.tab) {
            if (vm.recordMode) vm.exitRecordMode()
            vm.tab = it
        }
    }

    vm.synthEditorChannelId?.let { com.mobiledaw.ui.synth.SynthEditor(vm, it) }
    vm.openEffect?.let { (track, effect) -> com.mobiledaw.ui.fx.EffectEditor(vm, track, effect) }
    if (showProjects) ProjectsDialog(vm) { showProjects = false }
    SharedAudioDialog(vm)
    if (showTempo) TempoKeyDialog(vm) { showTempo = false }
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
        MenuGlyph(onMenu, Modifier.align(Alignment.CenterEnd))
    }
}

/** Normal transport: PAT/SONG on the left of play, and on the playlist a record button on its right. */
@Composable
private fun Transport(vm: DawViewModel, onRecordMode: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Segmented(
                options = listOf(PlayMode.PATTERN to "PAT", PlayMode.SONG to "SONG"),
                selected = vm.mode,
                onSelect = vm::switchMode,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
        PlayButton(vm.playing, vm::togglePlay, diameter = 38.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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

/** Big 1, 2, 3, GO over the screen while counting in. */
@Composable
private fun CountInOverlay(count: Int?, modifier: Modifier = Modifier) {
    if (count == null) return
    Box(
        modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(DawColors.Card.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count >= 4) "GO" else "$count",
            fontSize = if (count >= 4) 52.sp else 64.sp,
            fontWeight = FontWeight.Bold,
            color = if (count >= 4) RecordRed else DawColors.Ink,
        )
    }
}

private val RecordRed = Color(0xFFE5484D)

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun BottomTabs(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
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
