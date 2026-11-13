package com.mobiledaw.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import kotlin.math.roundToInt
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.Instrument
import com.mobiledaw.sound.ArchiveClient
import com.mobiledaw.sound.CommonsClient
import com.mobiledaw.sound.EnginePreset
import com.mobiledaw.sound.FreesoundClient
import com.mobiledaw.sound.FreesoundFilters
import com.mobiledaw.sound.SoundLength
import com.mobiledaw.sound.LicenseFilter
import com.mobiledaw.sound.LicenseKind
import com.mobiledaw.sound.OnlineSound
import com.mobiledaw.sound.OnlineSource
import com.mobiledaw.sound.SoundChoice
import com.mobiledaw.sound.SoundFontLibrary
import com.mobiledaw.sound.SoundSources
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sources the library and the sound picker can show. */
enum class SoundSource(val title: String, val tab: String, val blurb: String) {
    KITS("Drum kits", "Kits", "Your imported .zip / folder kits, plus sounds saved from online libraries"),
    SOUNDFONTS("SoundFont instruments", "Instruments", "GeneralUser GS: 261 General MIDI instruments and 13 drum kits, plus your .sf2 files"),
    SYNTHS("Synth engines", "Synths", "Macro (based on Plaits) and Modeled (DaisySP and STK): leads, keys, pads, plucks, winds, drums"),
    SFZ("SFZ instruments", "SFZ", "Multisampled instruments you import as a .zip"),
    FREESOUND("Freesound", "Freesound", "Search 600k+ community sounds; CC0 by default"),
    COMMONS("Wikimedia Commons", "Commons", "Public-domain and Creative Commons recordings"),
    ARCHIVE("Internet Archive", "Archive", "Creative Commons and public-domain audio collections"),
    FREE_SITES("Free sample sites", "Sites", "Where to download free packs to import"),
    CREDITS("Credits & licences", "Credits", "Who made the sounds you saved, and the licences of built-in sounds"),
}

/** Settings → Sounds → Sound library: every source, separated, with a page each. */
@Composable
fun SoundLibraryScreen(vm: DawViewModel, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<SoundSource?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    PreviewScreen(vm, onDismiss = { if (open != null) open = null else onDismiss() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open != null) {
                Text("‹", fontSize = 26.sp, color = DawColors.Ink, modifier = Modifier.clip(CircleShape).clickable { open = null }.padding(horizontal = 8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(open?.title ?: "Sound library", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(
                    added ?: if (open == null) "Every free source in one place. Tap a sound to hear it, + adds it to the rack." else open!!.blurb,
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                    maxLines = 2,
                )
            }
            TextAction("Done", strong = true, onClick = onDismiss)
        }
        val pick: (SoundChoice) -> Unit = { choice ->
            vm.applyChoice(null, choice)
            added = "Added ${choice.name} to the rack."
        }
        when (val source = open) {
            null -> LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                items(SoundSource.entries) { s ->
                    SourceCard(s) { open = s }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            SoundSource.KITS -> Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { DrumKitsSection(vm) }
            }
            else -> SourcePage(vm, source, actionLabel = "+", drumMode = false, onPick = pick)
        }
    }
}

@Composable
private fun SourceCard(source: SoundSource, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Text(source.blurb, fontSize = 12.sp, color = DawColors.Muted)
        }
        Text("›", fontSize = 22.sp, color = DawColors.Muted)
    }
}

/** Tab row for the sound picker; Kits stays first and is the default. */
@Composable
fun SourceTabs(selected: SoundSource, onSelect: (SoundSource) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PICKER_TABS.forEach { s -> DawChip(s.tab, selected = s == selected, dotColor = null) { onSelect(s) } }
    }
}

val PICKER_TABS = listOf(
    SoundSource.KITS, SoundSource.SOUNDFONTS, SoundSource.SYNTHS, SoundSource.SFZ,
    SoundSource.FREESOUND, SoundSource.COMMONS, SoundSource.ARCHIVE,
)

/** One source's browser. [drumMode] opens drum sections first (the picker for a drum channel). */
@Composable
fun ColumnScope.SourcePage(vm: DawViewModel, source: SoundSource, actionLabel: String, drumMode: Boolean, onPick: (SoundChoice) -> Unit) {
    when (source) {
        SoundSource.SOUNDFONTS -> SoundFontPage(vm, actionLabel, drumMode, onPick)
        SoundSource.SYNTHS -> EnginePage(vm, actionLabel, drumMode, onPick)
        SoundSource.SFZ -> SfzPage(vm, actionLabel, onPick)
        SoundSource.FREESOUND, SoundSource.COMMONS, SoundSource.ARCHIVE -> OnlinePage(vm, source, actionLabel, onPick)
        SoundSource.FREE_SITES -> FreeSitesPage()
        SoundSource.CREDITS -> CreditsPage(vm)
        SoundSource.KITS -> {}
    }
}

// ------------------------------------------------------------------------------ rows

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String?,
    playing: Boolean,
    actionLabel: String?,
    badge: LicenseKind? = null,
    indent: Int = 1,
    busy: Boolean = false,
    /** Small info chips under the name (length, BPM, key, loop or one-shot). */
    facts: List<String> = emptyList(),
    /** An outlined button before the main one (online results: Save to the library only). */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onPlay: () -> Unit,
    onAction: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (indent * 14).dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (playing) DawColors.ChipActive else DawColors.Card)
            .clickable(onClick = onPlay)
            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (playing) DawColors.Accent else DawColors.LineStrong))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null || badge != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (badge != null) {
                        LicenseBadge(badge)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (facts.isNotEmpty()) {
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    facts.forEach { fact ->
                        Text(
                            fact,
                            fontSize = 10.sp,
                            color = DawColors.InkSoft,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = DawColors.Ink, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        if (secondaryLabel != null && onSecondary != null) {
            Box(
                Modifier
                    .size(width = 58.dp, height = 34.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, DawColors.LineStrong, RoundedCornerShape(50))
                    .clickable(onClick = onSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(secondaryLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            }
            Spacer(Modifier.width(6.dp))
        }
        if (actionLabel != null && onAction != null) {
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
}

@Composable
private fun LicenseBadge(kind: LicenseKind) {
    val color = when {
        kind == LicenseKind.CC0 || kind == LicenseKind.PUBLIC_DOMAIN -> Color(0xFF3E9B5F)
        kind == LicenseKind.BY -> Color(0xFF4F74C9)
        kind == LicenseKind.NC -> Color(0xFFE0675A)
        else -> Color(0xFFC08A2E)
    }
    Text(
        kind.label,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun LibraryNote(text: String) {
    Text(text, fontSize = 12.sp, color = DawColors.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = DawColors.Muted) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DawColors.Ink,
            unfocusedTextColor = DawColors.Ink,
            focusedBorderColor = DawColors.InkSoft,
            unfocusedBorderColor = DawColors.Line,
            cursorColor = DawColors.Ink,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// ------------------------------------------------------------------------------ SoundFonts

@Composable
private fun ColumnScope.SoundFontPage(vm: DawViewModel, actionLabel: String, drumMode: Boolean, onPick: (SoundChoice) -> Unit) {
    val version = vm.sounds.version
    val fonts = remember(version) { vm.sounds.soundFonts.fonts() }
    var fontSource by remember { mutableStateOf(SoundFontLibrary.BUILTIN) }
    val font = fonts.firstOrNull { it.source == fontSource } ?: fonts.first()
    val loaded = vm.sounds.soundFonts.loadedBank(font.source) != null
    LaunchedEffect(font.source) { vm.loadSoundFont(font.source) }
    val presets = remember(version, font.source, loaded) { vm.sounds.soundFonts.presets(font.source) }
    var query by remember { mutableStateOf("") }
    val openGroups = remember { mutableStateListOf<String>().apply { if (drumMode) add("Drum kits") } }
    val openKits = remember { mutableStateListOf<Int>() }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importSoundFont(uri) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fonts.forEach { f -> DawChip(f.name, selected = f.source == font.source) { fontSource = f.source } }
        TextAction("Import .sf2") { importer.launch(arrayOf("*/*")) }
        if (vm.importing) CircularProgressIndicator(Modifier.size(18.dp), color = DawColors.Ink, strokeWidth = 2.dp)
    }
    LibraryNote(font.credit + (vm.importMessage?.let { " · $it" } ?: ""))
    SearchField(query, "Filter instruments", { query = it }, {})
    if (!loaded) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = DawColors.Ink, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Loading ${font.name}…", fontSize = 14.sp, color = DawColors.InkSoft)
        }
        return
    }
    val filtered = presets.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true) }
    val groups = filtered.groupBy { it.category }.toList().sortedBy { (cat, _) ->
        if (cat == "Drum kits") (if (drumMode) -1 else 99) else SoundFontLibrary.GM_CATEGORIES.indexOf(cat)
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        groups.forEach { (category, list) ->
            val open = category in openGroups || query.isNotBlank()
            item(key = "g:$category") {
                TreeHeader(category, "${list.size}", open, level = 0) {
                    if (category in openGroups) openGroups.remove(category) else openGroups.add(category)
                }
            }
            if (!open) return@forEach
            list.forEach { p ->
                if (!p.isDrumKit) {
                    item(key = "p:${p.index}") {
                        val choice = SoundChoice.Engine(Instrument.SOUNDFONT, p.index, font.source, null, p.name)
                        LibraryRow(
                            p.name, "Program ${p.program + 1}" + if (p.bank != 0) " · variation ${p.bank}" else "",
                            playing = lastPlayed == "p${p.index}", actionLabel = actionLabel,
                            onPlay = { lastPlayed = "p${p.index}"; vm.auditionChoice(choice) },
                            onAction = { onPick(choice) },
                        )
                    }
                } else {
                    val kitOpen = p.index in openKits
                    item(key = "k:${p.index}") {
                        TreeHeader(p.name, "${SoundFontLibrary.GM_DRUMS.size} sounds", kitOpen, level = 1) {
                            if (kitOpen) openKits.remove(p.index) else openKits.add(p.index)
                        }
                    }
                    if (kitOpen) {
                        item(key = "kw:${p.index}") {
                            val whole = SoundChoice.Engine(Instrument.SOUNDFONT, p.index, font.source, null, p.name)
                            LibraryRow(
                                "Whole kit", "Every drum on its own key, for the piano roll",
                                playing = lastPlayed == "kw${p.index}", actionLabel = actionLabel, indent = 2,
                                onPlay = { lastPlayed = "kw${p.index}"; vm.auditionChoice(SoundChoice.Engine(Instrument.SOUNDFONT, p.index, font.source, 36, p.name)) },
                                onAction = { onPick(whole) },
                            )
                        }
                        items(SoundFontLibrary.GM_DRUMS.toList(), key = { (key, _) -> "kd:${p.index}:$key" }) { (key, drum) ->
                            val choice = SoundChoice.Engine(Instrument.SOUNDFONT, p.index, font.source, key, drum)
                            LibraryRow(
                                drum, "${p.name} · key $key",
                                playing = lastPlayed == "d${p.index}:$key", actionLabel = actionLabel, indent = 2,
                                onPlay = { lastPlayed = "d${p.index}:$key"; vm.auditionChoice(choice) },
                                onAction = { onPick(choice) },
                            )
                        }
                    }
                }
            }
        }
        if (!font.builtIn) {
            item {
                Spacer(Modifier.height(12.dp))
                TextAction("Remove ${font.name}") {
                    vm.sounds.soundFonts.delete(font.source)
                    fontSource = SoundFontLibrary.BUILTIN
                    vm.sounds.changed()
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ------------------------------------------------------------------------------ synth engines

@Composable
private fun ColumnScope.EnginePage(vm: DawViewModel, actionLabel: String, drumMode: Boolean, onPick: (SoundChoice) -> Unit) {
    val engines = listOf(
        Triple("Macro", "Synthesis models from Plaits by Emilie Gillet (MIT)", vm.sounds.macroPresets),
        Triple("Modeled", "DaisySP by Electrosmith (MIT) and the Synthesis ToolKit by Perry Cook and Gary Scavone", vm.sounds.modeledPresets),
    )
    val openGroups = remember {
        mutableStateListOf<String>().apply {
            engines.forEach { (engine, _, presets) ->
                presets.map { it.group }.distinct().forEach { g -> if (!drumMode || g == "Drums" || g == "Percussion") add("$engine/$g") }
            }
        }
    }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        engines.forEach { (engine, credit, presets) ->
            item(key = "e:$engine") {
                Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                    Text(engine, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                    Text(credit, fontSize = 11.sp, color = DawColors.Muted)
                }
            }
            val groups = presets.groupBy { it.group }.toList().sortedBy { (g, _) ->
                val drums = g == "Drums" || g == "Percussion"
                if (drums == drumMode) 0 else 1
            }
            groups.forEach { (group, list) ->
                val id = "$engine/$group"
                val open = id in openGroups
                item(key = "g:$id") {
                    TreeHeader(group, "${list.size}", open, level = 0) { if (open) openGroups.remove(id) else openGroups.add(id) }
                }
                if (open) {
                    items(list, key = { "p:$engine:${it.index}" }) { p: EnginePreset ->
                        val choice = SoundChoice.Engine(p.instrument, p.index, null, p.pitch, p.name)
                        LibraryRow(
                            p.name, p.library.ifEmpty { null },
                            playing = lastPlayed == "$engine${p.index}", actionLabel = actionLabel,
                            onPlay = { lastPlayed = "$engine${p.index}"; vm.auditionChoice(choice) },
                            onAction = { onPick(choice) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ------------------------------------------------------------------------------ SFZ

@Composable
private fun ColumnScope.SfzPage(vm: DawViewModel, actionLabel: String, onPick: (SoundChoice) -> Unit) {
    val version = vm.sounds.version
    val instruments = remember(version) { vm.sounds.sfz.instruments() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importSfz(uri) }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAction("Import .zip", strong = true) {
            importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        if (vm.importing) CircularProgressIndicator(Modifier.size(18.dp), color = DawColors.Ink, strokeWidth = 2.dp)
    }
    LibraryNote(vm.importMessage ?: "A .zip holding .sfz files and their samples (WAV, FLAC or OGG). Free ones: Musical Artifacts, Pianobook.")
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        if (instruments.isEmpty()) item { LibraryNote("No SFZ instruments yet.") }
        instruments.groupBy { it.pack }.forEach { (pack, list) ->
            item(key = "pk:$pack") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pack, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                    Text("Remove", fontSize = 12.sp, color = DawColors.Mute, modifier = Modifier.clip(CircleShape).clickable {
                        vm.sounds.sfz.deletePack(pack)
                        vm.sounds.changed()
                    }.padding(8.dp))
                }
            }
            items(list, key = { it.source }) { inst ->
                val choice = SoundChoice.Engine(Instrument.MULTISAMPLE, 0, inst.source, null, inst.name)
                LibraryRow(
                    inst.name, null, playing = lastPlayed == inst.source, actionLabel = actionLabel,
                    onPlay = { lastPlayed = inst.source; vm.auditionChoice(choice) },
                    onAction = { onPick(choice) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ------------------------------------------------------------------------------ online libraries

@Composable
private fun ColumnScope.OnlinePage(vm: DawViewModel, source: SoundSource, actionLabel: String, onPick: (SoundChoice) -> Unit) {
    val scope = rememberCoroutineScope()
    val fs = vm.sounds.freesound
    var keyVersion by remember { mutableStateOf(0) }
    if (source == SoundSource.FREESOUND && (keyVersion >= 0) && !fs.hasKey) {
        FreesoundSetup(fs) { keyVersion++ }
        return
    }
    var query by remember(source) { mutableStateOf("") }
    var filter by remember(source) { mutableStateOf(LicenseFilter.NO_CREDIT) }
    var length by remember(source) { mutableStateOf(SoundLength.DEFAULT) }
    var lengthOpen by remember(source) { mutableStateOf(false) }
    var extra by remember(source) { mutableStateOf(FreesoundFilters()) }
    var filtersOpen by remember(source) { mutableStateOf(false) }
    var results by remember(source) { mutableStateOf<List<OnlineSound>>(emptyList()) }
    var items by remember(source) { mutableStateOf<List<ArchiveClient.Item>>(emptyList()) }
    val itemFiles = remember(source) { mutableStateMapOf<String, List<OnlineSound>>() }
    var searching by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var page by remember(source) { mutableStateOf(1) }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    val busy = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf<String?>(null) }

    fun search(nextPage: Int) {
        // Freesound can search by filters alone (e.g. every 90 BPM trap loop in A minor).
        if (query.isBlank() && (source != SoundSource.FREESOUND || extra.isEmpty)) return
        searching = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (source) {
                        SoundSource.FREESOUND -> fs.search(query, filter, nextPage, length, extra) to emptyList<ArchiveClient.Item>()
                        SoundSource.COMMONS -> CommonsClient.search(query, filter, nextPage) to emptyList<ArchiveClient.Item>()
                        else -> emptyList<OnlineSound>() to ArchiveClient.search(query, filter, nextPage)
                    }
                }
            }
            searching = false
            r.onSuccess { (sounds, found) ->
                page = nextPage
                results = if (nextPage == 1) sounds else results + sounds
                items = if (nextPage == 1) found else items + found
                if (sounds.isEmpty() && found.isEmpty()) error = "Nothing found. Try other words, another licence filter" + if (extra.isEmpty) "." else " or fewer filters."
            }.onFailure { e ->
                error = when {
                    e is com.mobiledaw.sound.HttpError && e.code == 401 -> "Freesound didn't accept the API key. Check it below."
                    e is com.mobiledaw.sound.HttpError && e.code == 400 && !extra.isEmpty -> "Freesound couldn't use those filters. Try fewer, or tap Clear."
                    e is com.mobiledaw.sound.HttpError && e.code == 429 -> "Too many searches for now (Freesound allows 60 a minute). Try again shortly."
                    else -> "Couldn't reach ${source.title}. Check your connection."
                }
            }
        }
    }

    fun preview(sound: OnlineSound) {
        lastPlayed = sound.id
        busy.add(sound.id)
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { runCatching { vm.sounds.fetchPreview(sound) }.getOrNull() }
            busy.remove(sound.id)
            if (decoded != null) vm.auditionDecoded(decoded, sound.title) else status = "Couldn't play that one."
        }
    }

    /** Save keeps the sound in the library; [andPick] also adds it to the rack (or the channel). */
    fun save(sound: OnlineSound, andPick: Boolean) {
        busy.add(sound.id)
        status = "Saving ${sound.title}…"
        scope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { vm.sounds.save(sound) }.getOrNull() }
            busy.remove(sound.id)
            vm.onKitsChanged()
            if (path == null) {
                status = "Couldn't save ${sound.title}."
            } else {
                status = "Saved to Drum kits → ${sound.source.label}." +
                    if (sound.license == LicenseKind.BY || sound.license == LicenseKind.BY_SA || sound.license == LicenseKind.NC) " Credit: ${sound.author} (see Credits)." else ""
                if (andPick) onPick(SoundChoice.Sample(path, sound.title, sound.source.label))
            }
        }
    }

    SearchField(query, "Search ${source.title} (e.g. kick, rain, choir)", { query = it }, { search(1) })
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LicenseFilter.entries.forEach { f -> DawChip(f.label, selected = f == filter, dotColor = null) { filter = f; search(1) } }
        if (source == SoundSource.FREESOUND) {
            DawChip("⏱ ${length.label}", selected = lengthOpen || !length.isAny, dotColor = null) {
                lengthOpen = !lengthOpen
                if (lengthOpen) filtersOpen = false
            }
            DawChip(if (extra.isEmpty) "Filters" else "Filters (${extra.count})", selected = filtersOpen || !extra.isEmpty, dotColor = null) {
                filtersOpen = !filtersOpen
                if (filtersOpen) lengthOpen = false
            }
        }
    }
    if (source == SoundSource.FREESOUND && lengthOpen) {
        SoundLengthPanel(
            initial = length,
            onApply = { length = it; lengthOpen = false; search(1) },
        )
    }
    if (source == SoundSource.FREESOUND && filtersOpen) {
        FreesoundFilterPanel(
            initial = extra,
            onApply = { extra = it; filtersOpen = false; search(1) },
            onClear = { extra = FreesoundFilters(); filtersOpen = false; search(1) },
        )
    }
    LibraryNote(
        status ?: when (source) {
            SoundSource.FREESOUND -> "Freesound sounds are free through its API for non-commercial apps. " +
                if (fs.signedIn) "Signed in: originals are saved." else "Saves the high-quality preview; sign in below for original files."
            SoundSource.COMMONS -> "Files from Wikimedia Commons. Each shows its licence; credit CC BY authors."
            else -> "Tap a collection to list its audio files. Each shows its licence."
        },
    )
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        if (searching && results.isEmpty() && items.isEmpty()) item {
            Row(Modifier.padding(16.dp)) { CircularProgressIndicator(Modifier.size(20.dp), color = DawColors.Ink, strokeWidth = 2.dp) }
        }
        error?.let { e -> item { LibraryNote(e) } }
        soundRows(results, lastPlayed, busy, actionLabel, onPlay = ::preview, onSave = { save(it, false) }, onAction = { save(it, true) })
        items.forEach { item ->
            val open = itemFiles.containsKey(item.id)
            item(key = "ia:${item.id}") {
                TreeHeader(item.title, item.license.label, open, level = 0) {
                    if (open) {
                        itemFiles.remove(item.id)
                    } else {
                        itemFiles[item.id] = emptyList()
                        scope.launch {
                            val files = withContext(Dispatchers.IO) { runCatching { ArchiveClient.files(item) }.getOrDefault(emptyList()) }
                            itemFiles[item.id] = files
                        }
                    }
                }
            }
            if (open) soundRows(itemFiles[item.id].orEmpty(), lastPlayed, busy, actionLabel, onPlay = ::preview, onSave = { save(it, false) }, onAction = { save(it, true) })
        }
        if (results.isNotEmpty() || items.isNotEmpty()) item {
            Row(Modifier.padding(vertical = 10.dp)) { TextAction(if (searching) "Loading…" else "More results") { if (!searching) search(page + 1) } }
        }
        if (source == SoundSource.FREESOUND) item { FreesoundAccount(fs) { keyVersion++ } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun LazyListScope.soundRows(
    sounds: List<OnlineSound>,
    lastPlayed: String?,
    busy: List<String>,
    actionLabel: String,
    onPlay: (OnlineSound) -> Unit,
    onSave: (OnlineSound) -> Unit,
    onAction: (OnlineSound) -> Unit,
) {
    items(sounds, key = { "s:${it.source}:${it.id}" }) { s ->
        val seconds = s.durationSeconds?.let { if (it < 10) "%.1f s".format(it) else "${it.toInt()} s" }
        LibraryRow(
            s.title,
            s.author,
            playing = lastPlayed == s.id,
            actionLabel = actionLabel,
            secondaryLabel = "Save",
            onSecondary = { onSave(s) },
            badge = s.license,
            busy = s.id in busy,
            indent = 0,
            facts = listOfNotNull(seconds) + s.facts,
            onPlay = { onPlay(s) },
            onAction = { onAction(s) },
        )
    }
}

@Composable
private fun TextInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DawColors.Ink,
            unfocusedTextColor = DawColors.Ink,
            focusedBorderColor = DawColors.InkSoft,
            unfocusedBorderColor = DawColors.Line,
            focusedLabelColor = DawColors.InkSoft,
            unfocusedLabelColor = DawColors.Muted,
            cursorColor = DawColors.Ink,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** First-time Freesound setup: each user gets a free API key from Freesound and pastes it here. */
@Composable
private fun ColumnScope.FreesoundSetup(fs: FreesoundClient, onSaved: () -> Unit) {
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf(fs.apiKey) }
    Column(Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Text("Connect Freesound (free)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "1. Make a free account on freesound.org.\n" +
                "2. Open the API page below and fill in the short form (name: Mobile DAW, description: personal music app, " +
                "Callback URL: ${FreesoundClient.CALLBACK_URL}).\n" +
                "3. Copy the \"Client secret / Api key\" and paste it here.",
            fontSize = 13.sp,
            color = DawColors.InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        TextAction("Open the Freesound API page", strong = true) { uri.openUri(FreesoundClient.APPLY_URL) }
        Spacer(Modifier.height(10.dp))
        TextInput("API key", key) { key = it }
        Spacer(Modifier.height(8.dp))
        TextAction("Save key", strong = true) {
            fs.apiKey = key
            onSaved()
        }
        Spacer(Modifier.height(10.dp))
        Text("The key stays on this phone. Freesound's API is free for non-commercial use.", fontSize = 12.sp, color = DawColors.Muted)
    }
}

/** Key and optional sign-in (for original-quality files), at the bottom of the Freesound page. */
@Composable
private fun FreesoundAccount(fs: FreesoundClient, onChanged: () -> Unit) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(fs.clientId) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        TreeHeader("Freesound account", if (fs.signedIn) "Signed in" else "Key saved", expanded, level = 0) { expanded = !expanded }
        if (!expanded) return@Column
        Spacer(Modifier.height(6.dp))
        val clipboard = LocalClipboardManager.current
        Text(
            "Optional: sign in to save original files instead of previews. Use the Client id from the same API page. " +
                "Sign-in only works if your API credential's Callback URL is set to:",
            fontSize = 12.sp, color = DawColors.Muted,
        )
        Text(FreesoundClient.CALLBACK_URL, fontSize = 12.sp, color = DawColors.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Copy callback URL") { clipboard.setText(AnnotatedString(FreesoundClient.CALLBACK_URL)) }
            TextAction("Edit credential") { uri.openUri(FreesoundClient.APPLY_URL) }
        }
        TextInput("Client id", clientId) { clientId = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("1. Open sign-in") {
                fs.clientId = clientId
                uri.openUri(fs.authorizeUrl())
            }
        }
        TextInput("2. Paste the code Freesound shows", code) { code = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("3. Finish sign-in", strong = true) {
                fs.clientId = clientId
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { fs.signIn(code) }.isSuccess }
                    message = if (ok) "Signed in. Saved sounds are now the original files." else "That code didn't work. Codes last 10 minutes; try again."
                    onChanged()
                }
            }
            if (fs.signedIn) TextAction("Sign out") { fs.signOut(); onChanged() }
            TextAction("Change key") { fs.apiKey = ""; fs.signOut(); onChanged() }
        }
        message?.let { Text(it, fontSize = 12.sp, color = DawColors.Muted) }
    }
}

// ------------------------------------------------------------------------------ free sites & credits

@Composable
private fun ColumnScope.FreeSitesPage() {
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        item { LibraryNote("These sites don't allow apps to fetch their sounds, so download in your browser and import here.") }
        items(SoundSources.FREE_SITES) { site ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DawColors.Card)
                    .border(1.dp, DawColors.Line, RoundedCornerShape(16.dp))
                    .clickable { uri.openUri(site.url) }
                    .padding(14.dp),
            ) {
                Text(site.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(site.what, fontSize = 13.sp, color = DawColors.InkSoft)
                Spacer(Modifier.height(4.dp))
                Text(site.terms, fontSize = 12.sp, color = DawColors.Muted)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Licences of what ships inside the app. Mirrors THIRD_PARTY_NOTICES.md. */
private val BUILT_IN_CREDITS = listOf(
    Triple("GeneralUser GS", "S. Christian Collins", "GeneralUser GS License v2.0: free to use in your music (also commercially) and to include in software."),
    Triple("TinySoundFont", "Bernhard Schelling", "MIT licence. Plays the SoundFont instruments."),
    Triple("Plaits", "Emilie Gillet (Mutable Instruments)", "MIT licence. Synthesis models used by the Macro engine. (Not affiliated with or endorsed by Mutable Instruments.)"),
    Triple("DaisySP", "Electrosmith", "MIT licence. Drum, string, modal and FM models in the Modeled engine."),
    Triple("The Synthesis ToolKit (STK)", "Perry R. Cook and Gary P. Scavone", "MIT-style licence. Wind, brass, bowed, sitar and shaker models in the Modeled engine."),
    Triple("Freesound", "Music Technology Group, UPF", "Sounds come from Freesound users under their own licences. The API is used non-commercially."),
)

@Composable
private fun ColumnScope.CreditsPage(vm: DawViewModel) {
    val version = vm.sounds.version
    val credits = remember(version) { vm.sounds.credits() }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            Text("Sounds you saved", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.padding(top = 8.dp))
            Text(
                "CC BY sounds need a credit when you release music, e.g. in the track description. CC0 and public-domain sounds don't.",
                fontSize = 12.sp, color = DawColors.Muted,
            )
            if (credits.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextAction(if (copied) "Copied" else "Copy credits", strong = true) {
                    val needed = credits.filter { it.kind != LicenseKind.CC0 && it.kind != LicenseKind.PUBLIC_DOMAIN }
                    val text = (if (needed.isEmpty()) credits else needed).joinToString("\n") { it.line() }
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (credits.isEmpty()) item { LibraryNote("Nothing saved from online libraries yet.") }
        items(credits, key = { it.path }) { c ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(DawColors.Card)
                    .clickable { uri.openUri(c.pageUrl) }.padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LicenseBadge(c.kind)
                    Spacer(Modifier.width(8.dp))
                    Text(c.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("by ${c.author} · ${c.source} · ${c.license}", fontSize = 12.sp, color = DawColors.InkSoft)
                Text(c.kind.note, fontSize = 11.sp, color = DawColors.Muted)
            }
        }
        item {
            Text("Built into the app", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
        }
        items(BUILT_IN_CREDITS) { (name, by, terms) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(DawColors.Card).padding(12.dp)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                Text(by, fontSize = 12.sp, color = DawColors.InkSoft)
                Text(terms, fontSize = 11.sp, color = DawColors.Muted)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** How long Freesound results may be: quick picks plus a two-handle slider from 0 s to no limit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundLengthPanel(initial: SoundLength, onApply: (SoundLength) -> Unit) {
    var range by remember { mutableStateOf(initial.lo.toFloat()..initial.hi.toFloat()) }
    val current = SoundLength(range.start.roundToInt(), range.endInclusive.roundToInt())
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        FilterLabel("Sound length")
        ChipRow {
            SoundLength.PRESETS.forEach { p ->
                DawChip(p.label, selected = p == current, dotColor = null) { range = p.lo.toFloat()..p.hi.toFloat() }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(current.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
        RangeSlider(
            value = range,
            onValueChange = { r ->
                // Keep the handles at least one stop apart so the range never becomes empty.
                val lo = r.start.roundToInt()
                val hi = r.endInclusive.roundToInt()
                if (hi > lo) range = lo.toFloat()..hi.toFloat()
            },
            valueRange = 0f..SoundLength.LAST.toFloat(),
            steps = SoundLength.LAST - 1,
            colors = SliderDefaults.colors(
                thumbColor = DawColors.Note,
                activeTrackColor = DawColors.Note,
                inactiveTrackColor = DawColors.Line,
                activeTickColor = DawColors.Note,
                inactiveTickColor = DawColors.Line,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("0 s", fontSize = 11.sp, color = DawColors.Muted, modifier = Modifier.weight(1f))
            Text("No limit", fontSize = 11.sp, color = DawColors.Muted)
        }
        Spacer(Modifier.height(10.dp))
        TextAction("Apply", strong = true) { onApply(current) }
    }
}

/** Freesound's extra filters: BPM, key, loop or one-shot, vibe and genre. Applied together. */
@Composable
private fun FreesoundFilterPanel(initial: FreesoundFilters, onApply: (FreesoundFilters) -> Unit, onClear: () -> Unit) {
    var f by remember { mutableStateOf(initial) }
    var bpmMin by remember { mutableStateOf(initial.bpmMin?.toString().orEmpty()) }
    var bpmMax by remember { mutableStateOf(initial.bpmMax?.toString().orEmpty()) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(16.dp))
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        FilterLabel("BPM")
        Row(verticalAlignment = Alignment.CenterVertically) {
            BpmInput("From", bpmMin, Modifier.weight(1f)) { bpmMin = it }
            Text("  to  ", fontSize = 13.sp, color = DawColors.Muted)
            BpmInput("To", bpmMax, Modifier.weight(1f)) { bpmMax = it }
        }
        FilterLabel("Key")
        ChipRow {
            DawChip("Any", selected = f.key == null, dotColor = null) { f = f.copy(key = null) }
            FreesoundFilters.KEYS.forEach { k -> DawChip(k, selected = f.key == k, dotColor = null) { f = f.copy(key = if (f.key == k) null else k) } }
        }
        ChipRow {
            DawChip("Major or minor", selected = f.scale == null, dotColor = null) { f = f.copy(scale = null) }
            DawChip("Major", selected = f.scale == "major", dotColor = null) { f = f.copy(scale = if (f.scale == "major") null else "major") }
            DawChip("Minor", selected = f.scale == "minor", dotColor = null) { f = f.copy(scale = if (f.scale == "minor") null else "minor") }
        }
        FilterLabel("Type")
        ChipRow {
            FreesoundFilters.Kind.entries.forEach { k -> DawChip(k.label, selected = f.kind == k, dotColor = null) { f = f.copy(kind = k) } }
        }
        FilterLabel("Vibe")
        ChipRow {
            FreesoundFilters.Vibe.entries.forEach { v ->
                DawChip(v.label, selected = v in f.vibes, dotColor = null) { f = f.copy(vibes = if (v in f.vibes) f.vibes - v else f.vibes + v) }
            }
        }
        FilterLabel("Genre (sound tags)")
        ChipRow {
            FreesoundFilters.Genre.entries.forEach { g ->
                DawChip(g.label, selected = g in f.genres, dotColor = null) { f = f.copy(genres = if (g in f.genres) f.genres - g else f.genres + g) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Apply", strong = true) {
                val lo = bpmMin.toIntOrNull()?.coerceIn(1, 400)
                val hi = bpmMax.toIntOrNull()?.coerceIn(1, 400)
                onApply(f.copy(bpmMin = if (lo != null && hi != null) minOf(lo, hi) else lo, bpmMax = if (lo != null && hi != null) maxOf(lo, hi) else hi))
            }
            TextAction("Clear", onClick = onClear)
        }
        Text(
            "BPM, key, type and vibe come from Freesound's own analysis of each sound, so a few may be off. Genre matches the tags uploaders gave.",
            fontSize = 11.sp,
            color = DawColors.Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun BpmInput(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        singleLine = true,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DawColors.Ink,
            unfocusedTextColor = DawColors.Ink,
            focusedBorderColor = DawColors.InkSoft,
            unfocusedBorderColor = DawColors.Line,
            focusedLabelColor = DawColors.InkSoft,
            unfocusedLabelColor = DawColors.Muted,
            cursorColor = DawColors.Ink,
        ),
    )
}
