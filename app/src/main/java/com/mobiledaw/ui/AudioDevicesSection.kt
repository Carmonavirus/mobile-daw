package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mobiledaw.DawViewModel
import com.mobiledaw.audio.AudioDevices
import com.mobiledaw.audio.AudioPort
import com.mobiledaw.ui.theme.DawColors

/**
 * Settings → Audio devices: which speaker / headphones the app plays through and which mic it
 * records from. Automatic leaves it to the phone (or browser). A chosen device is remembered; while
 * it is unplugged the app falls back to Automatic and switches back when it returns.
 */
@Composable
fun AudioDevicesSection(vm: DawViewModel) {
    LaunchedEffect(Unit) { vm.refreshAudioDevices() }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = output, false = input

    DeviceRow("Output", "Plays the song, previews and metronome", vm.audioOutputs, vm.chosenOutputKey, AudioDevices.outputNote) { picking = true }
    Spacer(Modifier.height(12.dp))
    DeviceRow("Input", "Records takes on the playlist", vm.audioInputs, vm.chosenInputKey, AudioDevices.inputNote) { picking = false }

    if (AudioDevices.needsPermissionForNames) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Allow the microphone to see every device by name.",
            fontSize = 12.sp,
            color = DawColors.Muted,
        )
        TextAction("Allow microphone", strong = true) { vm.allowAudioDeviceNames() }
    }

    picking?.let { output ->
        DevicePicker(
            title = if (output) "Play through" else "Record from",
            ports = if (output) vm.audioOutputs else vm.audioInputs,
            chosen = if (output) vm.chosenOutputKey else vm.chosenInputKey,
            onPick = { key -> if (output) vm.chooseOutput(key) else vm.chooseInput(key); picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DeviceRow(title: String, hint: String, ports: List<AudioPort>, chosen: String?, note: String?, onClick: () -> Unit) {
    val current = chosen?.let { key -> ports.firstOrNull { it.key == key } }
    val value = when {
        chosen == null -> "Automatic"
        current != null -> current.label
        else -> "Not connected, using Automatic"
    }
    val enabled = note == null
    Row(
        Modifier.fillMaxWidth().let { if (enabled) it.clickable(onClick = onClick) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Text(if (enabled) value else "Automatic", fontSize = 14.sp, color = DawColors.Ink)
            Text(note ?: hint, fontSize = 12.sp, color = DawColors.Muted)
        }
        if (enabled) Text("›", fontSize = 22.sp, color = DawColors.Muted)
    }
}

@Composable
private fun DevicePicker(title: String, ports: List<AudioPort>, chosen: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    DawDialog(onDismiss = onDismiss, title = title) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Choice("Automatic", "Let the system choose, like other apps", chosen == null) { onPick(null) }
            for (p in ports) Choice(p.label, p.detail, p.key == chosen) { onPick(p.key) }
            // A remembered device that isn't plugged in now: keep it visible so it can be seen as chosen.
            if (chosen != null && ports.none { it.key == chosen }) {
                Choice("Last chosen device", "Not connected; the app switches back when it is", true) { onPick(chosen) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextAction("Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun Choice(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) DawColors.Note else DawColors.Line, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (selected) DawColors.Note else DawColors.Card),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = DawColors.Ink)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = DawColors.Muted)
        }
    }
}
