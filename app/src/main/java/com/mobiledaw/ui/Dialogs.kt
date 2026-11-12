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
private fun DawDialog(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
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
    val saved = remember { vm.savedProjects() }
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
        }
        Spacer(Modifier.height(16.dp))
        Text("OPEN", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            items(saved) { item ->
                val current = item == vm.project.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.openProject(item); onDismiss() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (current) DawColors.Ink else DawColors.Line))
                    Spacer(Modifier.size(10.dp))
                    Text(item, fontSize = 15.sp, color = DawColors.Ink)
                }
            }
        }
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
            Instrument.entries.filter { it.isDrum && it != Instrument.SAMPLE }.forEach { DawChip(it.label, false) { onPick(it); onDismiss() } }
        }
        Spacer(Modifier.height(12.dp))
        Text("SYNTHS", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Instrument.entries.filter { !it.isDrum }.forEach { DawChip(it.label, false) { onPick(it); onDismiss() } }
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
