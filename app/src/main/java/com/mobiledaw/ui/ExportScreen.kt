package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.export.ExportOptions
import com.mobiledaw.export.ExportState
import com.mobiledaw.ui.theme.DawColors
import java.io.File

/** Export the song: WAV, MP3, stems (one WAV per channel) and MIDI, saved to Downloads and shareable. */
@Composable
fun ExportScreen(vm: DawViewModel) {
    val state = vm.exportState
    val running = state is ExportState.Running
    Dialog(
        onDismissRequest = { vm.closeExport() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !running, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Export", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                if (!running) TextAction(if (state is ExportState.Done) "Done" else "Close", strong = state is ExportState.Done) { vm.closeExport() }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                when (state) {
                    is ExportState.Running -> RunningCard(state) { vm.cancelExport() }
                    is ExportState.Done -> DoneCard(vm, state)
                    else -> OptionsForm(vm, (state as? ExportState.Failed)?.message)
                }
            }
        }
    }
}

@Composable
private fun OptionsForm(vm: DawViewModel, error: String?) {
    val o = vm.exportOptions
    fun set(transform: (ExportOptions) -> ExportOptions) { vm.exportOptions = transform(vm.exportOptions) }
    var name by rememberSaveable { mutableStateOf(vm.project.name) }

    Card {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("File name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("What", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
            Segmented(listOf(true to "Song", false to "Pattern"), o.song, { v -> set { it.copy(song = v) } })
        }
        Text(
            if (o.song) {
                if (vm.hasArrangement) "The whole playlist, from bar 1 to the end of the last clip."
                else "The playlist is empty, so the song would be silent. Pick Pattern or add clips first."
            } else "\"${vm.selectedPattern.name}\", played once.",
            fontSize = 12.sp,
            color = if (o.song && !vm.hasArrangement) DawColors.Mute else DawColors.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Title("Formats")
    Card {
        FormatRow("WAV", "Full quality, for mastering or uploading.", o.wav, { v -> set { it.copy(wav = v) } }) {
            Segmented(listOf(16 to "16-bit", 24 to "24-bit"), o.wavBits, { v -> set { it.copy(wavBits = v) } })
        }
        Divider()
        FormatRow("MP3", "Small file to send or post.", o.mp3, { v -> set { it.copy(mp3 = v) } }) {
            Segmented(listOf(128 to "128", 192 to "192", 320 to "320 kbps"), o.mp3Kbps, { v -> set { it.copy(mp3Kbps = v) } })
        }
        Divider()
        val stems = remember(vm.project, o.song, vm.selectedPattern) { vm.exportStemNames() }
        FormatRow(
            "Stems",
            if (stems.isEmpty()) "One WAV per channel. Nothing plays in this ${if (o.song) "song" else "pattern"} yet."
            else "One WAV per channel, all the same length (${stems.size}: ${stems.joinToString(", ")}).",
            o.stems, { v -> set { it.copy(stems = v) } },
        )
        Divider()
        FormatRow("MIDI", "The notes of every channel, one track each, at the song's tempo.", o.midi, { v -> set { it.copy(midi = v) } })
    }

    Title("FL Studio")
    Card {
        Text(
            "FL Studio's own project file (.flp) is a closed format that only FL can write, so it can't be made here. " +
                "Export Stems + MIDI instead: drag the stem WAVs into FL's playlist (they all start at bar 1, so they line up), " +
                "and drop the .mid on a channel to get the notes back, ready for FL's own instruments.",
            fontSize = 13.sp,
            color = DawColors.InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        TextAction("Pick Stems + MIDI") { vm.exportOptions = vm.exportOptions.copy(stems = true, midi = true) }
    }

    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text("Export failed: $error", fontSize = 13.sp, color = DawColors.Mute)
    }
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (o.any) DawColors.Ink else DawColors.Chip)
            .clickable(enabled = o.any) { vm.startExport(name) }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (o.any) "Export" else "Pick a format",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (o.any) DawColors.Paper else DawColors.Muted,
        )
    }
    Text(
        "Saved to Download › Mobile DAW › ${name.ifBlank { "Untitled" }}. The metronome is never in an export.",
        fontSize = 12.sp,
        color = DawColors.Muted,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp, start = 4.dp),
    )
}

@Composable
private fun RunningCard(state: ExportState.Running, onCancel: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Card {
        Text("Exporting…", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(state.label, fontSize = 13.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(DawColors.Chip)) {
            Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(DawColors.Accent))
        }
        Spacer(Modifier.height(6.dp))
        Text("${(state.progress * 100).toInt()}%", fontSize = 12.sp, color = DawColors.Muted)
        Spacer(Modifier.height(14.dp))
        TextAction("Cancel", onClick = onCancel)
    }
    Text(
        "You can leave the app; the export keeps going.",
        fontSize = 12.sp,
        color = DawColors.Muted,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun DoneCard(vm: DawViewModel, state: ExportState.Done) {
    Spacer(Modifier.height(8.dp))
    Card {
        Text("Saved", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
        Text(state.folder, fontSize = 13.sp, color = DawColors.Muted)
        Spacer(Modifier.height(12.dp))
        TextAction(if (state.all.size == 1) "Share" else "Share all (${state.all.size})", strong = true) { vm.shareExport(state.all) }
    }
    if (state.mix.isNotEmpty() || state.midi != null) {
        Title("Files")
        Card {
            (state.mix + listOfNotNull(state.midi)).forEachIndexed { i, f ->
                if (i > 0) Divider()
                FileRow(f) { vm.shareExport(listOf(f)) }
            }
        }
    }
    if (state.stems.isNotEmpty()) {
        Title("Stems (${state.stems.size})")
        Card {
            TextAction("Share all stems") { vm.shareExport(state.stems) }
            Spacer(Modifier.height(6.dp))
            state.stems.forEach { f ->
                Divider()
                FileRow(f) { vm.shareExport(listOf(f)) }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun FileRow(file: File, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 14.sp, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sizeLabel(file.length()), fontSize = 12.sp, color = DawColors.Muted)
        }
        Spacer(Modifier.width(8.dp))
        TextAction("Share", onClick = onShare)
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Composable
private fun FormatRow(
    title: String,
    subtitle: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(subtitle, fontSize = 12.sp, color = DawColors.Muted)
            }
            Switch(
                checked = on,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Note, checkedThumbColor = DawColors.OnNote),
            )
        }
        if (on && extra != null) {
            Spacer(Modifier.height(6.dp))
            extra()
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        color = DawColors.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(DawColors.Line))
}
