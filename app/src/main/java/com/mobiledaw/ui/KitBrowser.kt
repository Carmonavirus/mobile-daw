package com.mobiledaw.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.mobiledaw.model.Instrument
import com.mobiledaw.model.KitLibrary
import com.mobiledaw.ui.theme.DawColors

/** Words that tie a channel name to kit folders, so tapping "Kick" opens the kick folders. */
private val KEYWORDS = listOf("kick", "snare", "hat", "clap", "808", "perc", "rim", "crash", "ride", "tom", "open", "fx", "vox", "snap", "shaker")

private fun keywordFor(channelName: String): String? {
    val n = channelName.lowercase()
    return KEYWORDS.firstOrNull { n.contains(it) }
}

/**
 * Pick a sound from every imported kit. Kits and their folders expand like a file browser.
 * Tap a sound to hear it; the button on the right either swaps [replaceChannelId]'s sound or,
 * when it is null, adds the sound as a new channel.
 */
@Composable
fun KitPicker(
    vm: DawViewModel,
    replaceChannelId: Long?,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    /** When adding (not replacing): true for "+ Add drums", false for "+ Add sounds". */
    forDrums: Boolean = true,
) {
    val kits = remember(vm.kitsVersion) { vm.kits.kits() }
    val soundsByKit = remember(vm.kitsVersion) { kits.associateWith { vm.kits.sounds(it).groupBy { s -> s.group } } }
    val replacing = replaceChannelId?.let { id -> vm.project.channels.firstOrNull { it.id == id } }
    val keyword = replacing?.let { keywordFor(it.name) }

    // Open every kit, and inside them the folders that match the drum you tapped.
    val openKits = remember { mutableStateListOf<String>().apply { addAll(kits) } }
    val openFolders = remember {
        mutableStateListOf<String>().apply {
            soundsByKit.forEach { (kit, folders) ->
                folders.keys.filter { keyword != null && it.lowercase().contains(keyword) }.forEach { add("$kit/$it") }
                if (folders.size == 1) add("$kit/${folders.keys.first()}")
            }
        }
    }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    // Opens on the tab the channel's current sound comes from; Kits otherwise.
    var tab by remember {
        mutableStateOf(
            when (replacing?.instrument) {
                Instrument.SOUNDFONT -> SoundSource.SOUNDFONTS
                Instrument.MACRO, Instrument.MODELED -> SoundSource.SYNTHS
                Instrument.MULTISAMPLE -> SoundSource.SFZ
                null -> if (forDrums) SoundSource.KITS else SoundSource.SOUNDFONTS
                else -> SoundSource.KITS
            },
        )
    }

    PreviewScreen(vm, onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (replacing != null) "Sound for ${replacing.name}" else if (forDrums) "Add drums" else "Add sounds", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(
                    added ?: "Tap a sound to listen, then ${if (replacing != null) "Use" else "+"}.",
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                )
            }
            TextAction("Done", strong = true, onClick = onDismiss)
        }

        // Adding: the app's own built-in sounds first, one tap each.
        if (replacing == null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("BUILT-IN", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
                Instrument.entries
                    .filter { it.addable && it != Instrument.SAMPLE && it.isDrum == forDrums }
                    .forEach { inst ->
                        DawChip(inst.label, selected = false, dotColor = null) {
                            vm.addChannel(inst)
                            // The wavetable editor opens behind this picker, so close it.
                            if (inst == Instrument.WAVETABLE) onDismiss() else added = "Added ${inst.label} to the rack."
                        }
                    }
            }
        }
        // Other sources (SoundFonts, synth engines, SFZ, online libraries) sit in tabs after Kits.
        SourceTabs(tab) { tab = it }
        if (tab != SoundSource.KITS) {
            SourcePage(vm, tab, actionLabel = if (replacing != null) "Use" else "+", drumMode = replacing?.instrument?.isDrum ?: forDrums) { choice ->
                vm.applyChoice(replacing?.id, choice)
                if (replacing != null) onDismiss() else added = "Added ${choice.name} to the rack."
            }
            return@PreviewScreen
        }

        if (kits.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("No drum kits yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Spacer(Modifier.height(6.dp))
                Text("Add your kits in Settings → Sounds → Drum kits, or pick from the other tabs above.", fontSize = 14.sp, color = DawColors.InkSoft)
                Spacer(Modifier.height(14.dp))
                TextAction("Open Drum kits", strong = true, onClick = onOpenSettings)
            }
            return@PreviewScreen
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            kits.forEach { kit ->
                val kitOpen = kit in openKits
                val folders = soundsByKit[kit].orEmpty()
                item(key = "k:$kit") {
                    TreeHeader(
                        title = kit,
                        subtitle = "${folders.size} folder${if (folders.size == 1) "" else "s"}",
                        open = kitOpen,
                        level = 0,
                    ) { if (kitOpen) openKits.remove(kit) else openKits.add(kit) }
                }
                if (kitOpen) {
                    folders.forEach { (folder, sounds) ->
                        val id = "$kit/$folder"
                        val open = id in openFolders
                        item(key = "f:$id") {
                            TreeHeader(
                                title = if (folder == kit) "Sounds" else folder,
                                subtitle = "${sounds.size}",
                                open = open,
                                level = 1,
                                highlight = keyword != null && folder.lowercase().contains(keyword),
                            ) { if (open) openFolders.remove(id) else openFolders.add(id) }
                        }
                        if (open) {
                            items(sounds, key = { it.path }) { sound ->
                                SoundRow(
                                    sound = sound,
                                    playing = sound.path == lastPlayed,
                                    actionLabel = if (replacing != null) "Use" else "+",
                                    onPlay = {
                                        lastPlayed = sound.path
                                        vm.audition(sound.path, sound.name)
                                    },
                                    onAction = {
                                        if (replacing != null) {
                                            vm.setChannelSound(replacing.id, sound)
                                            onDismiss()
                                        } else {
                                            vm.addSampleChannel(sound)
                                            added = "Added ${sound.name} to the rack."
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Settings → Drum kits: import new kits and remove old ones. Imports always add a new kit. */
@Composable
fun DrumKitsSection(vm: DawViewModel) {
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importKit(uri, isFolder = false)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importKit(uri, isFolder = true)
    }
    val kits = remember(vm.kitsVersion) { vm.kits.kits().map { it to vm.kits.sounds(it) } }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Text(
        "Download a kit on your phone, then import the .zip (or the folder, if you unzipped it). " +
            "Each import is added as a new kit; your other kits stay as they are.",
        fontSize = 13.sp,
        color = DawColors.InkSoft,
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction("Import .zip", strong = true) {
            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        TextAction("Import folder") { folderPicker.launch(null) }
        if (vm.importing) CircularProgressIndicator(Modifier.size(20.dp), color = DawColors.Ink, strokeWidth = 2.dp)
    }
    vm.importMessage?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 12.sp, color = DawColors.Muted)
    }
    if (kits.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        kits.forEach { (kit, sounds) ->
            val folders = sounds.map { it.group }.distinct().size
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(DawColors.Note))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(kit, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${sounds.size} sounds · $folders folder${if (folders == 1) "" else "s"}", fontSize = 12.sp, color = DawColors.Muted)
                }
                Text(
                    "Remove",
                    fontSize = 12.sp,
                    color = DawColors.Mute,
                    modifier = Modifier.clip(CircleShape).clickable { confirmDelete = kit }.padding(8.dp),
                )
            }
        }
    }

    confirmDelete?.let { kit ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove \"$kit\"?") },
            text = { Text("Its sounds are deleted from the app. Channels using them go silent until you pick another sound.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteKit(kit); confirmDelete = null }) { Text("Remove", color = DawColors.Mute) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = DawColors.Ink) } },
            containerColor = DawColors.Card,
        )
    }
}

@Composable
internal fun FullScreen(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
            content = content,
        )
    }
}

@Composable
internal fun TreeHeader(title: String, subtitle: String, open: Boolean, level: Int, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (level * 14).dp, top = if (level == 0) 10.dp else 3.dp, bottom = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (level == 0) DawColors.Chip else if (highlight) DawColors.AccentSoft else DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾" else "▸", fontSize = 13.sp, color = DawColors.Muted)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = if (level == 0) 15.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = DawColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(subtitle, fontSize = 12.sp, color = DawColors.Muted)
    }
}

@Composable
private fun SoundRow(sound: KitLibrary.Sound, playing: Boolean, actionLabel: String, onPlay: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (playing) DawColors.ChipActive else DawColors.Card)
            .clickable(onClick = onPlay)
            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (playing) DawColors.Accent else DawColors.LineStrong))
        Spacer(Modifier.width(10.dp))
        Text(sound.name, fontSize = 14.sp, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 52.dp, height = 34.dp)
                .clip(RoundedCornerShape(50))
                .background(DawColors.Note)
                .clickable(onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DawColors.OnNote)
        }
    }
}
