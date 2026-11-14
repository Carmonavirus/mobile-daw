package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.sound.SampleDigger
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sound library → Dig for samples: a random old record by genre, decade and country. */
@Composable
fun ColumnScope.SampleDiggerPage() {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var genre by remember { mutableStateOf<SampleDigger.Genre?>(null) }
    var era by remember { mutableStateOf(SampleDigger.ERAS.first()) }
    var region by remember { mutableStateOf(SampleDigger.REGIONS.first()) }
    var current by remember { mutableStateOf(SampleDigger.history.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val history = remember { mutableStateListOf<SampleDigger.Dig>().apply { addAll(SampleDigger.history.drop(1)) } }
    val digging = job?.isActive == true

    fun dig() {
        if (digging) return
        error = null
        job = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { SampleDigger.dig(genre, era, region) }
                current?.let { history.add(0, it) }
                if (history.size > 30) history.removeAt(history.lastIndex)
                current = found
                SampleDigger.history.add(0, found)
                if (SampleDigger.history.size > 31) SampleDigger.history.removeAt(SampleDigger.history.lastIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SampleDigger.NothingFound) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't reach MusicBrainz. Check your connection and try again."
            } finally {
                job = null
            }
        }
    }

    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            Text(
                "Tap Dig for a random record from the crates hip-hop and trap producers sample: old soul, funk, jazz, library and more. " +
                    "Play it on YouTube, then bring the part you like in with Import from link.",
                fontSize = 13.sp,
                color = DawColors.InkSoft,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            DigLabel("Genre")
            DigChips {
                DawChip("Any", selected = genre == null, dotColor = null) { genre = null }
                SampleDigger.GENRES.forEach { g -> DawChip(g.label, selected = genre == g, dotColor = null) { genre = g } }
            }
            DigLabel("Decade")
            DigChips {
                SampleDigger.ERAS.forEach { e -> DawChip(e.label, selected = era == e, dotColor = null) { era = e } }
            }
            DigLabel("Country")
            DigChips {
                SampleDigger.REGIONS.forEach { r -> DawChip(r.label, selected = region == r, dotColor = null) { region = r } }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(DawColors.Ink)
                        .clickable(enabled = !digging) { dig() }
                        .padding(horizontal = 26.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (digging) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = DawColors.Card, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (digging) "Digging…" else if (current == null) "Dig" else "Dig again", color = DawColors.Card, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                TextAction("Open Samplette") { uri.openUri(SampleDigger.SAMPLETTE_URL) }
            }
            error?.let { Text(it, fontSize = 13.sp, color = DawColors.InkSoft, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
        }
        current?.let { d ->
            item {
                DigCard(d, big = true, onClick = { uri.openUri(d.youTubeUrl) })
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextAction("Play on YouTube", strong = true) { uri.openUri(d.youTubeUrl) }
                    TextAction("Copy name") { clipboard.setText(AnnotatedString("${d.artist} - ${d.title}")) }
                    TextAction("Record info") { uri.openUri(d.musicBrainzUrl) }
                    TextAction("Discogs") { uri.openUri(d.discogsUrl) }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { DigLabel("Earlier digs") }
            items(history) { d -> DigCard(d, big = false, onClick = { uri.openUri(d.youTubeUrl) }) }
        }
        item {
            Text(
                "Record info from MusicBrainz (CC0). Sampled records are copyrighted: clear samples before releasing music commercially.",
                fontSize = 11.sp,
                color = DawColors.Muted,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun DigCard(d: SampleDigger.Dig, big: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (big) 18.dp else 14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(if (big) 16.dp else 12.dp),
    ) {
        Text(d.title, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(d.artist, fontSize = if (big) 15.sp else 13.sp, color = DawColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val details = listOfNotNull(
            d.release.takeIf { it != d.title },
            d.year?.toString(),
            d.country,
            d.label,
            d.genre,
            d.lengthMs?.let { ms -> val s = ms / 1000; "${s / 60}:${(s % 60).toString().padStart(2, '0')}" },
        ).joinToString(" · ")
        if (details.isNotEmpty()) Text(details, fontSize = 12.sp, color = DawColors.Muted, maxLines = if (big) 3 else 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DigLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun DigChips(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}
