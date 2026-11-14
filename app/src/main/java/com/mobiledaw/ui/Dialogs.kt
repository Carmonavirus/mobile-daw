package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.window.Dialog
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.Instrument
import com.mobiledaw.model.NOTE_NAMES
import com.mobiledaw.ui.theme.DawColors

@Composable
internal fun DawDialog(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(DawColors.Card)
                .padding(20.dp),
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun RenameChannelDialog(vm: DawViewModel, channelId: Long, onDismiss: () -> Unit) {
    val channel = vm.project.channels.firstOrNull { it.id == channelId } ?: return
    var name by remember { mutableStateOf(channel.name) }
    DawDialog(onDismiss, "Rename channel") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Cancel", onClick = onDismiss)
            TextAction("Rename", strong = true) {
                val clean = name.trim()
                if (clean.isNotEmpty()) vm.updateChannel(channelId) { it.copy(name = clean) }
                onDismiss()
            }
        }
    }
}

@Composable
fun TextAction(label: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (strong) DawColors.Ink else DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = if (strong) DawColors.Paper else DawColors.Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ProjectsDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(vm.project.name) }
    // Re-read when a Google Drive sync brings projects from another device.
    var saved by remember(vm.cloud.revision) { mutableStateOf(vm.savedProjects()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.cloud.syncSoon(0) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importProject(uri)
            onDismiss()
        }
    }
    DawDialog(onDismiss, "Projects") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Rename") { vm.renameProject(name); onDismiss() }
            TextAction("New project", strong = true) { vm.newProject(); onDismiss() }
            TextAction("Export") { onDismiss(); vm.openExport() }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OPEN", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.weight(1f))
            TextAction("Import project") {
                importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            items(saved, key = { it }) { item ->
                val current = vm.isCurrentProject(item)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.openProject(item); onDismiss() }
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (current) DawColors.Ink else DawColors.Line))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        item,
                        fontSize = 15.sp,
                        color = DawColors.Ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable { vm.shareProject(item); onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Share", fontSize = 13.sp, color = DawColors.Ink, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable { confirmDelete = item }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Delete", fontSize = 13.sp, color = DawColors.Mute, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
    confirmDelete?.let { target ->
        val current = vm.isCurrentProject(target)
        DawDialog({ confirmDelete = null }, "Delete \"$target\"?") {
            Text(
                "This project will be gone for good. Your recordings and drum kits stay in the library." +
                    if (current) " It's the one that's open now, so ${if (saved.size > 1) "your most recent other project opens" else "a new empty project opens"} instead." else "",
                fontSize = 14.sp,
                color = DawColors.InkSoft,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("Cancel") { confirmDelete = null }
                DangerAction("Delete") {
                    vm.deleteProject(target)
                    confirmDelete = null
                    saved = vm.savedProjects()
                    name = vm.project.name
                }
            }
        }
    }
}

/**
 * "Share project" and "Import project" progress and results: packing a project with its sounds
 * into one .zip to send to a friend, and opening one a friend sent.
 */
@Composable
fun ProjectShareDialog(vm: DawViewModel) {
    val state = vm.projectShare ?: return
    when (state) {
        is DawViewModel.ProjectShareState.Busy -> DawDialog({}, "One moment") {
            Text(state.text, fontSize = 14.sp, color = DawColors.InkSoft)
        }
        is DawViewModel.ProjectShareState.Done -> DawDialog(vm::closeProjectShare, state.title) {
            Text(state.text, fontSize = 14.sp, color = DawColors.InkSoft)
            Spacer(Modifier.height(16.dp))
            TextAction("OK", strong = true) { vm.closeProjectShare() }
        }
        is DawViewModel.ProjectShareState.Ready -> DawDialog(vm::closeProjectShare, "Share \"${state.name}\"") {
            val tenthsMb = (state.file.length() * 10 / (1024 * 1024)).coerceAtLeast(1)
            val sounds = when (state.sounds) {
                0 -> "It uses no sound files"
                1 -> "It's packed with its 1 sound"
                else -> "It's packed with its ${state.sounds} sounds"
            }
            Text(
                "$sounds, one .zip of ${tenthsMb / 10}.${tenthsMb % 10} MB. Send it to a friend: in Mobile DAW " +
                    "(phone or web) they tap Projects → Import project, or share the file to the app. " +
                    "It opens as a new project, so nothing of theirs is replaced.",
                fontSize = 14.sp,
                color = DawColors.InkSoft,
            )
            if (state.missing > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.missing} sound${if (state.missing == 1) " isn't" else "s aren't"} on this device any more, so ${if (state.missing == 1) "it was" else "they were"} left out.",
                    fontSize = 13.sp,
                    color = DawColors.Mute,
                )
            }
            state.savedTo?.let {
                Spacer(Modifier.height(8.dp))
                Text(if (it.isEmpty()) "Couldn't save it to Downloads." else "Saved to $it.", fontSize = 13.sp, color = DawColors.Ink)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("Share", strong = true) { vm.sendSharedProject() }
                TextAction("Save to Downloads") { vm.saveSharedProject() }
            }
            Spacer(Modifier.height(8.dp))
            TextAction("Done") { vm.closeProjectShare() }
        }
    }
}

/** A red pill button for an action that can't be undone. */
@Composable
private fun DangerAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(DawColors.Mute)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TempoKeyDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    val p = vm.project
    DawDialog(onDismiss, "Tempo & key") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("−") { vm.setBpm(p.bpm - 1) }
            Text(
                "${p.bpm} BPM",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextAction("+") { vm.setBpm(p.bpm + 1) }
        }
        Slider(
            value = p.bpm.toFloat(),
            onValueChange = { vm.setBpm(it.toInt()) },
            valueRange = 60f..200f,
            colors = inkSliderColors(),
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NOTE_NAMES.forEachIndexed { i, n ->
                DawChip(n, selected = p.key == i, dotColor = null) { vm.setKey(i, p.minor) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Segmented(
            options = listOf(false to "Major", true to "Minor"),
            selected = p.minor,
            onSelect = { vm.setKey(p.key, it) },
        )
        Spacer(Modifier.height(16.dp))
        TextAction("Done", strong = true, onClick = onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddChannelDialog(onPick: (Instrument) -> Unit, onDismiss: () -> Unit) {
    DawDialog(onDismiss, "Add channel") {
        Text("DRUMS", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Instrument.entries.filter { it.addable && it.isDrum && it != Instrument.SAMPLE }.forEach { DawChip(it.label, false) { onPick(it); onDismiss() } }
        }
        Spacer(Modifier.height(12.dp))
        Text("SYNTHS", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Instrument.entries.filter { it.addable && !it.isDrum }.forEach { DawChip(it.label, false) { onPick(it); onDismiss() } }
        }
    }
}

@Composable
fun inkSliderColors() = SliderDefaults.colors(
    thumbColor = DawColors.Note,
    activeTrackColor = DawColors.Note,
    inactiveTrackColor = DawColors.Line,
    activeTickColor = DawColors.Ink,
    inactiveTickColor = DawColors.Line,
)
