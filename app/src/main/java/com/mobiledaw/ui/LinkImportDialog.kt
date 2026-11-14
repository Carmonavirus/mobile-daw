package com.mobiledaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.sound.LinkImport
import com.mobiledaw.ui.theme.DawColors

/**
 * Playlist row menu → "Add audio from a link": paste an Instagram reel link (Share → Copy link)
 * and its sound lands on that row with its own mixer track. A copied link is filled in already.
 */
@Composable
fun LinkImportDialog(vm: DawViewModel) {
    val lane = vm.linkDialogLane ?: return
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val copied = runCatching { clipboard.getText()?.text }.getOrNull()
        LinkImport.findLink(copied)?.let { if (link.isEmpty()) link = it }
    }
    fun close() { vm.linkDialogLane = null }
    DawDialog(::close, "Add audio from a link") {
        Text(
            "Paste an Instagram reel link (Share → Copy link). The app downloads the reel and puts its sound on row ${lane + 1}.",
            fontSize = 13.sp,
            color = DawColors.Muted,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            label = { Text("Link") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (vm.linkService.isSet) "Uses your downloader first (Settings → Link downloads)."
            else "Public reels only. For personal use: respect the creator's rights.",
            fontSize = 11.sp,
            color = DawColors.Muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Cancel", onClick = ::close)
            TextAction("Paste") {
                LinkImport.findLink(runCatching { clipboard.getText()?.text }.getOrNull())?.let { link = it }
            }
            TextAction("Add", strong = true) {
                val found = LinkImport.findLink(link) ?: link.trim()
                if (found.isNotEmpty()) {
                    close()
                    vm.importFromLink(found, lane)
                }
            }
        }
    }
}

/** Settings → Link downloads: your own downloader for links, tried before the built-in one. */
@Composable
fun LinkDownloadsSection(vm: DawViewModel) {
    var url by remember { mutableStateOf(vm.linkService.url) }
    var key by remember { mutableStateOf(vm.linkService.key) }
    Text("Downloader for links", fontSize = 15.sp, color = DawColors.Ink)
    Text(
        "Instagram reels work without one in the phone app, until Instagram changes something. " +
            "Add a downloader as a backup, or to use links in the web app: a cobalt server address " +
            "(https://…/, you can run your own) or any address with {url} where the link goes.",
        fontSize = 12.sp,
        color = DawColors.Muted,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        singleLine = true,
        label = { Text("Downloader address (optional)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        singleLine = true,
        label = { Text("API key (optional)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    val changed = url.trim() != vm.linkService.url || key.trim() != vm.linkService.key
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAction(if (changed) "Save" else "Saved", strong = changed) { vm.changeLinkService(url, key) }
        if (vm.linkService.isSet) {
            TextAction("Remove") {
                url = ""
                key = ""
                vm.changeLinkService("", "")
            }
        }
    }
}
