package com.mobiledaw.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.model.Effect
import com.mobiledaw.model.EffectType
import com.mobiledaw.model.MAX_EFFECTS
import com.mobiledaw.model.MAX_MIXER_TRACKS
import com.mobiledaw.model.MixerTrack
import com.mobiledaw.model.NOTE_NAMES
import com.mobiledaw.model.Project
import com.mobiledaw.ui.RoundToggle
import com.mobiledaw.ui.WindowWidthClass
import com.mobiledaw.ui.windowWidthClass
import com.mobiledaw.ui.inkSliderColors
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The mixer, top to bottom: the master, the mixer tracks (each with an effects chain, e.g. the
 * Vocals track recordings play through) and the channels, which can each be sent to a track.
 */
@Composable
fun MixerScreen(vm: DawViewModel) {
    val levels = vm.levels
    val p = vm.project
    var renaming by remember { mutableStateOf<MixerTrack?>(null) }

    val master: @Composable () -> Unit = {
        Strip(
            name = "Master",
            subtitle = "the whole song",
            level = levels.getOrElse(NativeEngine.MAX_CHANNELS) { 0f },
            volume = p.masterVolume,
            onVolume = vm::setMasterVolume,
            emphasized = true,
        ) {
            EffectChain(vm, p, p.masterTrack)
        }
    }
    val tracksHeader: @Composable () -> Unit = {
        SectionHeader(
            "Mixer tracks",
            action = if (p.mixerTracks.size < MAX_MIXER_TRACKS) "+ Track" else null,
            onAction = { vm.addMixerTrack() },
        )
    }
    val noTracks: @Composable () -> Unit = { Hint("No mixer tracks. Add one to give a group of sounds its own effects.") }
    val channelStrip: @Composable (Int, com.mobiledaw.model.Channel) -> Unit = { index, ch ->
        Strip(
            name = ch.name,
            subtitle = ch.instrument.label.lowercase(),
            level = levels.getOrElse(index) { 0f },
            volume = ch.volume,
            onVolume = { v -> vm.updateChannel(ch.id) { it.copy(volume = if (abs(v - 1f) < 0.04f) 1f else v) } },
            maxVolume = 2f,
            pan = ch.pan,
            onPan = { v -> vm.updateChannel(ch.id) { it.copy(pan = if (abs(v) < 0.06f) 0f else v) } },
            mute = ch.mute,
            onMute = { vm.updateChannel(ch.id) { it.copy(mute = !it.mute) } },
            solo = ch.solo,
            onSolo = { vm.updateChannel(ch.id) { it.copy(solo = !it.solo) } },
            header = {
                RouteChip(p, ch.mixerTrack) { vm.routeChannel(ch.id, it) }
            },
        )
    }

    if (windowWidthClass() != WindowWidthClass.COMPACT) {
        // Tablets: master and mixer tracks on the left, the channel strips in a grid on the right.
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { master() }
                item { tracksHeader() }
                if (p.mixerTracks.isEmpty()) item { noTracks() }
                itemsIndexed(p.mixerTracks, key = { _, t -> "track-${t.id}" }) { index, track ->
                    TrackCard(vm, p, track, vm.trackLevels.getOrElse(index) { 0f }, onRename = { renaming = track })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Channels") }
                gridItemsIndexed(p.channels, key = { _, c -> c.id }) { index, ch -> channelStrip(index, ch) }
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { master() }
            item { tracksHeader() }
            if (p.mixerTracks.isEmpty()) item { noTracks() }
            itemsIndexed(p.mixerTracks, key = { _, t -> "track-${t.id}" }) { index, track ->
                TrackCard(vm, p, track, vm.trackLevels.getOrElse(index) { 0f }, onRename = { renaming = track })
            }
            item { SectionHeader("Channels") }
            itemsIndexed(p.channels, key = { _, c -> c.id }) { index, ch -> channelStrip(index, ch) }
        }
    }

    renaming?.let { track ->
        var text by remember(track.id) { mutableStateOf(track.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename track") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameMixerTrack(track.id, text); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
            containerColor = DawColors.Card,
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.weight(1f))
        if (action != null) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(action, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = DawColors.Muted, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A mixer track: level, volume, pan and its chain of effects. */
@Composable
private fun TrackCard(vm: DawViewModel, p: Project, track: MixerTrack, level: Float, onRename: () -> Unit) {
    val inputs = buildList {
        if (p.recordingTrackId == track.id) add("Recordings")
        addAll(p.channels.filter { it.mixerTrack == track.id }.map { it.name })
        addAll(p.audioClips.filter { it.trackId == track.id }.map { it.name })
    }
    var menu by remember { mutableStateOf(false) }
    Strip(
        name = track.name,
        subtitle = if (inputs.isEmpty()) "nothing sent here" else "← " + inputs.joinToString(", "),
        level = level,
        volume = track.volume,
        onVolume = { v -> vm.updateMixerTrack(track.id) { it.copy(volume = if (abs(v - 1f) < 0.04f) 1f else v) } },
        maxVolume = 2f,
        pan = track.pan,
        onPan = { v -> vm.updateMixerTrack(track.id) { it.copy(pan = if (abs(v) < 0.06f) 0f else v) } },
        mute = track.mute,
        onMute = { vm.updateMixerTrack(track.id) { it.copy(mute = !it.mute) } },
        onName = onRename,
        accent = true,
        header = {
            if (p.recordingTrackId == track.id) Badge("Recordings")
            Box {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) { Text("⋯", fontSize = 16.sp, color = DawColors.Muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    if (p.recordingTrackId == track.id) {
                        DropdownMenuItem(text = { Text("Send recordings to master instead") }, onClick = { menu = false; vm.setRecordingTrack(null) })
                    } else {
                        DropdownMenuItem(text = { Text("Send recordings here") }, onClick = { menu = false; vm.setRecordingTrack(track.id) })
                    }
                    DropdownMenuItem(text = { Text("Delete track", color = DawColors.Mute) }, onClick = { menu = false; vm.deleteMixerTrack(track.id) })
                }
            }
        },
    ) {
        EffectChain(vm, p, track)
    }
}

/** A track's (or the master's) effects, in order, with "+ Add effect". */
@Composable
private fun EffectChain(vm: DawViewModel, p: Project, track: MixerTrack) {
    Spacer(Modifier.height(4.dp))
    Text("EFFECTS", fontSize = 10.sp, letterSpacing = 2.sp, color = DawColors.Muted)
    Spacer(Modifier.height(6.dp))
    track.effects.forEachIndexed { i, fx ->
        EffectRow(
            index = i,
            fx = fx,
            summary = summary(fx, p),
            canUp = i > 0,
            canDown = i < track.effects.size - 1,
            onOpen = { vm.openEffect(track.id, fx.id) },
            onToggle = { vm.updateEffect(track.id, fx.id) { it.copy(bypass = !it.bypass) } },
            onMove = { by -> vm.moveEffect(track.id, fx.id, by) },
            onRemove = { vm.removeEffect(track.id, fx.id) },
        )
        Spacer(Modifier.height(6.dp))
    }
    if (track.effects.size < MAX_EFFECTS) AddEffect { vm.addEffect(track.id, it) }
}

private fun summary(fx: Effect, p: Project): String = when (fx.type) {
    EffectType.EQ -> {
        val bands = fx.eq?.bands.orEmpty()
        val active = bands.count { it.on && (!it.type.hasGain || abs(it.gain) > 0.05f) }
        "${bands.size} bands" + if (active == 0) " · flat" else " · $active shaping"
    }
    EffectType.PITCH -> {
        val s = fx.pitch ?: com.mobiledaw.model.PitchSettings()
        val style = com.mobiledaw.model.PitchStyle.entries.firstOrNull { it.matches(s) }?.label
            ?: if (s.retuneMs < 1f) "instant" else "${s.retuneMs.roundToInt()} ms"
        "${NOTE_NAMES[s.keyFor(p)]} ${s.scaleFor(p).label.lowercase()} · $style"
    }
    EffectType.CLIP -> {
        val c = fx.clip ?: com.mobiledaw.model.ClipSettings()
        fun db(v: Float) = (if (v > 0.05f) "+" else "") + "%.1f".format(v)
        "gain ${db(c.gain)} · threshold ${db(c.threshold)} · post ${db(c.post)} dB"
    }
}

@Composable
private fun EffectRow(
    index: Int,
    fx: Effect,
    summary: String,
    canUp: Boolean,
    canDown: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DawColors.Paper)
            .border(1.dp, DawColors.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Power dot: tap to bypass.
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (fx.bypass) DawColors.LineStrong else DawColors.Accent))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${index + 1}. ${fx.type.label}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (fx.bypass) DawColors.Muted else DawColors.Ink,
            )
            Text(if (fx.bypass) "off" else summary, fontSize = 11.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Text("⋯", fontSize = 16.sp, color = DawColors.Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(if (fx.bypass) "Switch on" else "Switch off") }, onClick = { menu = false; onToggle() })
                if (canUp) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                if (canDown) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                DropdownMenuItem(text = { Text("Remove", color = DawColors.Mute) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun AddEffect(onPick: (EffectType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, DawColors.LineStrong, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Text("+ Add effect", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EffectType.entries.forEach { t ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(t.label, fontSize = 14.sp, color = DawColors.Ink)
                            Text(t.blurb, fontSize = 11.sp, color = DawColors.Muted)
                        }
                    },
                    onClick = { open = false; onPick(t) },
                )
            }
        }
    }
}

/** "→ Master" / "→ Vocals": where a channel's sound goes. */
@Composable
private fun RouteChip(p: Project, current: Long?, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = p.mixerTrack(current)?.name ?: "Master"
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (current != null && p.mixerTrack(current) != null) DawColors.AccentSoft else DawColors.Chip)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) { Text("→ $name", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Master") }, onClick = { open = false; onPick(null) })
            p.mixerTracks.forEach { t ->
                DropdownMenuItem(text = { Text(t.name) }, onClick = { open = false; onPick(t.id) })
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(50)).background(DawColors.AccentSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink) }
}

@Composable
private fun Strip(
    name: String,
    level: Float,
    volume: Float,
    onVolume: (Float) -> Unit,
    subtitle: String? = null,
    pan: Float? = null,
    onPan: (Float) -> Unit = {},
    mute: Boolean = false,
    onMute: (() -> Unit)? = null,
    solo: Boolean = false,
    onSolo: (() -> Unit)? = null,
    emphasized: Boolean = false,
    accent: Boolean = false,
    maxVolume: Float = 1f,
    onName: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (emphasized) DawColors.Chip else DawColors.Card)
            .border(if (accent) 1.5.dp else 1.dp, if (accent) DawColors.AccentSoft else DawColors.Line, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).let { if (onName != null) it.clip(RoundedCornerShape(8.dp)).clickable(onClick = onName) else it },
            ) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 11.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            header()
            Spacer(Modifier.width(6.dp))
            Text("${(volume * 100).roundToInt()}%", fontSize = 12.sp, color = DawColors.Muted)
            if (onMute != null) {
                Spacer(Modifier.width(8.dp))
                RoundToggle("M", mute, DawColors.Mute, onMute)
            }
            if (onSolo != null) {
                Spacer(Modifier.width(6.dp))
                RoundToggle("S", solo, DawColors.Solo, onSolo)
            }
        }
        Spacer(Modifier.height(6.dp))
        Meter(level)
        Slider(value = volume, onValueChange = onVolume, valueRange = 0f..maxVolume, colors = inkSliderColors())
        if (pan != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("L", fontSize = 11.sp, color = DawColors.Muted)
                Slider(
                    value = pan,
                    onValueChange = onPan,
                    valueRange = -1f..1f,
                    colors = inkSliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("R", fontSize = 11.sp, color = DawColors.Muted)
            }
        }
        content()
    }
}

@Composable
private fun Meter(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(DawColors.Line),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(50))
                .background(if (level > 0.95f) DawColors.Mute else DawColors.Accent),
        )
    }
}
