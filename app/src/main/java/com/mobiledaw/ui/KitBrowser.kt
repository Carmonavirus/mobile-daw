package com.mobiledaw.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.KitLibrary
import com.mobiledaw.ui.theme.DawColors

/**
 * Browse imported drum kits. Tap a sound to hear it; the button on the right either adds it
 * as a new channel or, when [replaceChannelId] is set, swaps that channel's sound.
 */
@Composable
fun KitBrowser(vm: DawViewModel, replaceChannelId: Long?, onDismiss: () -> Unit) {
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importKit(uri, isFolder = false)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importKit(uri, isFolder = true)
    }
    val kits = remember(vm.kitsVersion) { vm.kits.kits() }
    var selectedKit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(kits) {
        if (kits.none { it == selectedKit }) selectedKit = kits.lastOrNull()
    }
    val sounds = remember(selectedKit, vm.kitsVersion) { selectedKit?.let { vm.kits.sounds(it) }.orEmpty() }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    val replacing = replaceChannelId?.let { id -> vm.project.channels.firstOrNull { it.id == id } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sounds", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                    Text(
                        if (replacing != null) "Pick a new sound for ${replacing.name}" else "Tap to listen, + to add as a channel",
                        fontSize = 12.sp,
                        color = DawColors.Muted,
                    )
                }
                TextAction("Done", strong = true, onClick = onDismiss)
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction("Import .zip") { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                TextAction("Import folder") { folderPicker.launch(null) }
                if (vm.importing) CircularProgressIndicator(Modifier.size(20.dp), color = DawColors.Ink, strokeWidth = 2.dp)
            }
            vm.importMessage?.let {
                Text(it, fontSize = 12.sp, color = DawColors.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }

            if (kits.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("No kits yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Download a drum kit on your phone, then tap Import .zip and pick the file from Downloads. " +
                            "If you already unzipped it, use Import folder instead. WAV sounds are copied into the app.",
                        fontSize = 14.sp,
                        color = DawColors.InkSoft,
                    )
                }
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                kits.forEach { kit -> DawChip(kit, selected = kit == selectedKit) { selectedKit = kit } }
            }

            val groups = sounds.groupBy { it.group }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                groups.forEach { (group, list) ->
                    item(key = "h:$group") {
                        Text(
                            group.uppercase(),
                            fontSize = 11.sp,
                            letterSpacing = 2.sp,
                            color = DawColors.Muted,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(list, key = { it.path }) { sound ->
                        SoundRow(
                            sound = sound,
                            playing = sound.path == lastPlayed,
                            actionLabel = if (replacing != null) "Use" else "+",
                            onPlay = {
                                lastPlayed = sound.path
                                vm.audition(sound.path)
                            },
                            onAction = {
                                if (replacing != null) {
                                    vm.setChannelSound(replacing.id, sound)
                                    onDismiss()
                                } else {
                                    vm.addSampleChannel(sound)
                                    vm.importMessage = "Added ${sound.name} to the rack."
                                }
                            },
                        )
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Remove this kit",
                            fontSize = 12.sp,
                            color = DawColors.Mute,
                            modifier = Modifier.clip(CircleShape).clickable { selectedKit?.let(vm::deleteKit) }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundRow(sound: KitLibrary.Sound, playing: Boolean, actionLabel: String, onPlay: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (playing) DawColors.ChipActive else DawColors.Card)
            .clickable(onClick = onPlay)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (playing) DawColors.Accent else DawColors.LineStrong))
        Spacer(Modifier.width(10.dp))
        Text(sound.name, fontSize = 14.sp, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 48.dp, height = 34.dp)
                .clip(RoundedCornerShape(50))
                .background(DawColors.Ink)
                .clickable(onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Paper)
        }
    }
}
