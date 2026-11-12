package com.mobiledaw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.audio.WavDecoder
import com.mobiledaw.model.KitLibrary
import com.mobiledaw.model.Channel
import com.mobiledaw.model.Clip
import com.mobiledaw.model.DemoProject
import com.mobiledaw.model.Instrument
import com.mobiledaw.model.Note
import com.mobiledaw.model.Pattern
import com.mobiledaw.model.Project
import com.mobiledaw.model.ProjectStore
import com.mobiledaw.model.SequenceCompiler
import com.mobiledaw.model.TICKS_PER_STEP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { PLAYLIST("Playlist"), RACK("Rack"), PIANO("Piano"), MIXER("Mixer") }

/** PATTERN loops the selected pattern; SONG plays the playlist arrangement. */
enum class PlayMode { PATTERN, SONG }

class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    val kits = KitLibrary(app)
    private val templateStore = com.mobiledaw.model.TemplateStore(app)
    var templates by mutableStateOf(templateStore.list())
        private set

    /** Which sample path each engine channel slot currently holds. */
    private val loadedSamples = arrayOfNulls<String>(NativeEngine.MAX_CHANNELS)
    private val sampleCache = object : LinkedHashMap<String, WavDecoder.Decoded>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WavDecoder.Decoded>?) = size > 48
    }

    /** Bumped after an import so the kit browser refreshes. */
    var kitsVersion by mutableStateOf(0)
        private set
    var importing by mutableStateOf(false)
        private set
    var importMessage by mutableStateOf<String?>(null)

    var project by mutableStateOf(loadInitial())
        private set
    var tab by mutableStateOf(Tab.RACK)
    var settingsOpen by mutableStateOf(false)

    private val prefs = app.getSharedPreferences("daw", android.content.Context.MODE_PRIVATE)

    /** Hear a sound when placing a step or note. Never while playing, so beats don't double up. */
    var previewOnEdit by mutableStateOf(prefs.getBoolean("preview_on_edit", true))
        private set

    fun changePreviewOnEdit(on: Boolean) {
        previewOnEdit = on
        prefs.edit().putBoolean("preview_on_edit", on).apply()
    }
    var mode by mutableStateOf(PlayMode.PATTERN)
        private set
    var playing by mutableStateOf(false)
        private set
    var selectedPatternId by mutableLongStateOf(project.patterns.first().id)
        private set
    var selectedChannelId by mutableLongStateOf(project.channels.firstOrNull()?.id ?: 0L)
        private set

    /** Piano roll grid in ticks: the lines drawn, where notes snap, and the length of new notes. */
    var drawLength by mutableStateOf(TICKS_PER_STEP * 2)

    // Live engine readings, refreshed ~30 times a second.
    var positionTicks by mutableLongStateOf(0L)
        private set
    var levels by mutableStateOf(FloatArray(NativeEngine.MAX_CHANNELS + 1))
        private set
    var scope by mutableStateOf(FloatArray(SCOPE_BINS))
        private set

    private var saveJob: Job? = null

    val selectedPattern: Pattern
        get() = project.pattern(selectedPatternId) ?: project.patterns.first()

    val selectedChannel: Channel?
        get() = project.channels.firstOrNull { it.id == selectedChannelId }

    init {
        syncEngine()
        viewModelScope.launch {
            while (isActive) {
                positionTicks = NativeEngine.positionTicks()
                levels = FloatArray(NativeEngine.MAX_CHANNELS + 1).also { NativeEngine.readLevels(it) }
                scope = FloatArray(SCOPE_BINS).also { NativeEngine.readScope(it) }
                delay(33)
            }
        }
    }

    private fun loadInitial(): Project {
        val last = store.lastOpened()?.let { store.load(it) }
        return last ?: DemoProject.create().also { store.save(it) }
    }

    // ---------------------------------------------------------------- engine sync

    fun onResume() {
        NativeEngine.start()
        syncEngine()
    }

    fun onPause() {
        applyPlaying(false)
        NativeEngine.stop()
        saveNow()
    }

    private fun syncEngine() {
        val p = project
        NativeEngine.setBpm(p.bpm.toFloat())
        NativeEngine.setMasterVolume(p.masterVolume)
        NativeEngine.setChannelCount(p.channels.size.coerceAtMost(NativeEngine.MAX_CHANNELS))
        p.channels.take(NativeEngine.MAX_CHANNELS).forEachIndexed { i, c ->
            NativeEngine.setChannel(i, c.instrument.id, c.volume, c.pan, c.mute, c.solo)
            val path = if (c.instrument == Instrument.SAMPLE) c.sample else null
            if (loadedSamples[i] != path) {
                val decoded = path?.let { decodeSample(it) }
                NativeEngine.setSample(i, decoded?.frames, decoded?.sampleRate ?: 0f)
                loadedSamples[i] = path
            }
        }
        val compiled = when (mode) {
            PlayMode.PATTERN -> SequenceCompiler.pattern(p, selectedPattern)
            PlayMode.SONG -> SequenceCompiler.song(p)
        }
        NativeEngine.setSequence(compiled.events, compiled.loopTicks)
    }

    private fun update(transform: (Project) -> Project) {
        project = transform(project)
        syncEngine()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            val snapshot = project
            withContext(Dispatchers.IO) { store.save(snapshot) }
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        store.save(project)
    }

    // ---------------------------------------------------------------- transport

    fun togglePlay() = applyPlaying(!playing)

    private fun applyPlaying(value: Boolean) {
        playing = value
        NativeEngine.setPlaying(value)
    }

    fun switchMode(value: PlayMode) {
        if (mode == value) return
        mode = value
        syncEngine()
    }

    fun setBpm(bpm: Int) = update { it.copy(bpm = bpm.coerceIn(40, 240)) }

    fun setKey(key: Int, minor: Boolean) = update { it.copy(key = key, minor = minor) }

    fun setMasterVolume(v: Float) = update { it.copy(masterVolume = v) }

    // ---------------------------------------------------------------- patterns

    fun selectPattern(id: Long) {
        selectedPatternId = id
        syncEngine()
    }

    fun addPattern() {
        val id = project.nextId()
        update { it.copy(patterns = it.patterns + Pattern(id, "Pattern ${it.patterns.size + 1}")) }
        selectPattern(id)
    }

    fun setPatternBars(count: Int) = updatePattern(selectedPatternId) { pat ->
        val bars = count.coerceIn(1, com.mobiledaw.model.MAX_BARS)
        // Notes past the end are kept (just not played), so shortening by accident loses nothing.
        pat.copy(bars = bars)
    }

    /** Saves the selected pattern, with the sounds it uses, as a reusable template. */
    fun saveTemplate(name: String) {
        val clean = name.trim().ifEmpty { selectedPattern.name }
        templateStore.save(com.mobiledaw.model.PatternTemplate.from(clean, project, selectedPattern))
        templates = templateStore.list()
    }

    fun applyTemplate(template: com.mobiledaw.model.PatternTemplate) {
        val (updated, patternId) = template.applyTo(project, NativeEngine.MAX_CHANNELS)
        update { updated }
        selectPattern(patternId)
    }

    fun deleteTemplate(name: String) {
        templateStore.delete(name)
        templates = templateStore.list()
    }

    fun deletePattern(id: Long) {
        if (project.patterns.size <= 1) return
        update { p -> p.copy(patterns = p.patterns.filter { it.id != id }, clips = p.clips.filter { it.patternId != id }) }
        if (selectedPatternId == id) selectPattern(project.patterns.first().id)
    }

    private fun updatePattern(id: Long, transform: (Pattern) -> Pattern) = update { p ->
        p.copy(patterns = p.patterns.map { if (it.id == id) transform(it) else it })
    }

    private fun updateNotes(channelId: Long, transform: (List<Note>) -> List<Note>) =
        updatePattern(selectedPatternId) { pat ->
            pat.copy(notes = pat.notes + (channelId to transform(pat.notesFor(channelId))))
        }

    // ---------------------------------------------------------------- channel rack

    fun selectChannel(id: Long) {
        selectedChannelId = id
    }

    fun toggleStep(channel: Channel, step: Int) {
        val tick = step * TICKS_PER_STEP
        val existing = selectedPattern.notesFor(channel.id).filter { it.start == tick }
        if (existing.isNotEmpty()) {
            updateNotes(channel.id) { list -> list.filter { it.start != tick } }
        } else {
            val pitch = channel.instrument.defaultPitch
            updateNotes(channel.id) { it + Note(tick, TICKS_PER_STEP, pitch) }
            editPreview(channel, pitch)
        }
    }

    /** Preview used while editing: skipped during playback (and when switched off in Settings). */
    fun editPreview(channel: Channel, pitch: Int = channel.instrument.defaultPitch) {
        if (!playing && previewOnEdit) preview(channel, pitch)
    }

    fun preview(channel: Channel, pitch: Int = channel.instrument.defaultPitch) {
        val index = project.channels.indexOf(channel)
        if (index >= 0) NativeEngine.previewNote(index, pitch, 100)
    }

    fun addChannel(instrument: Instrument) {
        if (project.channels.size >= NativeEngine.MAX_CHANNELS) return
        val id = project.nextId()
        val sameKind = project.channels.count { it.instrument == instrument }
        val name = if (sameKind == 0) instrument.label else "${instrument.label} ${sameKind + 1}"
        update { it.copy(channels = it.channels + Channel(id, name, instrument)) }
        selectedChannelId = id
    }

    fun deleteChannel(id: Long) {
        update { p ->
            p.copy(
                channels = p.channels.filter { it.id != id },
                patterns = p.patterns.map { it.copy(notes = it.notes - id) },
            )
        }
        if (selectedChannelId == id) selectedChannelId = project.channels.firstOrNull()?.id ?: 0L
    }

    fun updateChannel(id: Long, transform: (Channel) -> Channel) = update { p ->
        p.copy(channels = p.channels.map { if (it.id == id) transform(it) else it })
    }

    // ---------------------------------------------------------------- kits & samples

    private fun decodeSample(path: String): WavDecoder.Decoded? =
        sampleCache[path] ?: WavDecoder.decode(kits.file(path))?.also { sampleCache[path] = it }

    /** Plays a kit sound without adding it to the project. */
    fun audition(path: String) {
        val decoded = decodeSample(path) ?: return
        NativeEngine.setSample(NativeEngine.AUDITION_SLOT, decoded.frames, decoded.sampleRate)
        NativeEngine.audition()
    }

    fun addSampleChannel(sound: KitLibrary.Sound) {
        if (project.channels.size >= NativeEngine.MAX_CHANNELS) return
        val id = project.nextId()
        update { it.copy(channels = it.channels + Channel(id, sound.name, Instrument.SAMPLE, sample = sound.path)) }
        selectedChannelId = id
    }

    fun setChannelSound(channelId: Long, sound: KitLibrary.Sound) {
        updateChannel(channelId) { it.copy(name = sound.name, instrument = Instrument.SAMPLE, sample = sound.path) }
        project.channels.firstOrNull { it.id == channelId }?.let { preview(it) }
    }

    fun importKit(uri: android.net.Uri, isFolder: Boolean) {
        importing = true
        importMessage = null
        viewModelScope.launch {
            val kit = withContext(Dispatchers.IO) {
                runCatching { if (isFolder) kits.importFolder(uri) else kits.importZip(uri) }.getOrNull()
            }
            importing = false
            importMessage = if (kit == null) "No .wav sounds found there." else "Imported \"$kit\"."
            kitsVersion++
        }
    }

    fun deleteKit(kit: String) {
        kits.deleteKit(kit)
        sampleCache.clear()
        kitsVersion++
    }

    // ---------------------------------------------------------------- piano roll

    fun addNote(channelId: Long, note: Note) {
        updateNotes(channelId) { it + note }
        project.channels.firstOrNull { it.id == channelId }?.let { editPreview(it, note.pitch) }
    }

    fun replaceNote(channelId: Long, index: Int, note: Note) {
        updateNotes(channelId) { list -> list.mapIndexed { i, n -> if (i == index) note else n } }
    }

    fun deleteNote(channelId: Long, index: Int) {
        updateNotes(channelId) { list -> list.filterIndexed { i, _ -> i != index } }
    }

    // ---------------------------------------------------------------- playlist

    fun toggleClip(lane: Int, bar: Int) {
        val p = project
        val hit = p.clips.firstOrNull { it.lane == lane && bar >= it.bar && bar < it.bar + p.clipBars(it) }
        if (hit != null) {
            update { it.copy(clips = it.clips - hit) }
            return
        }
        val bars = selectedPattern.bars
        val overlaps = p.clips.any { it.lane == lane && bar < it.bar + p.clipBars(it) && it.bar < bar + bars }
        if (!overlaps) update { it.copy(clips = it.clips + Clip(lane, bar, selectedPatternId)) }
    }

    // ---------------------------------------------------------------- projects

    fun savedProjects(): List<String> = store.list()

    fun openProject(name: String) {
        saveNow()
        val loaded = store.load(name) ?: return
        applyPlaying(false)
        project = loaded
        selectedPatternId = loaded.patterns.first().id
        selectedChannelId = loaded.channels.firstOrNull()?.id ?: 0L
        syncEngine()
        store.save(loaded)
    }

    fun newProject() {
        saveNow()
        applyPlaying(false)
        val p = DemoProject.empty(store.uniqueName("New project"))
        project = p
        selectedPatternId = p.patterns.first().id
        selectedChannelId = p.channels.first().id
        mode = PlayMode.PATTERN
        syncEngine()
        store.save(p)
    }

    fun renameProject(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == project.name || store.exists(clean)) return
        saveJob?.cancel()
        store.rename(project.name, clean)
        project = project.copy(name = clean)
        store.save(project)
    }

    override fun onCleared() {
        saveNow()
    }

    companion object {
        const val SCOPE_BINS = 96
    }
}
