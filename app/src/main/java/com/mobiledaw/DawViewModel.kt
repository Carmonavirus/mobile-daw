package com.mobiledaw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.audio.Recorder
import com.mobiledaw.audio.WavDecoder
import com.mobiledaw.model.KitLibrary
import com.mobiledaw.model.AudioClip
import com.mobiledaw.model.Channel
import com.mobiledaw.model.Clip
import com.mobiledaw.model.DemoProject
import com.mobiledaw.model.Instrument
import com.mobiledaw.model.Note
import com.mobiledaw.model.Pattern
import com.mobiledaw.model.Project
import com.mobiledaw.model.ProjectStore
import com.mobiledaw.model.SequenceCompiler
import com.mobiledaw.model.TICKS_PER_BAR
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

/** Where a mic recording is: idle, counting in (1, 2, 3, GO) or recording. */
enum class TakeState { IDLE, COUNTING, RECORDING }

class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    val kits = KitLibrary(app)
    private val templateStore = com.mobiledaw.model.TemplateStore(app)
    var templates by mutableStateOf(templateStore.list())
        private set

    /** Which sample path each engine channel slot currently holds. */
    private val loadedSamples = arrayOfNulls<String>(NativeEngine.MAX_CHANNELS)

    // The patch each engine slot last received, so unchanged sounds aren't resent.
    // Declared before init{} because syncEngine()/syncSynth() (run from init) reads it.
    private val sentPatches = arrayOfNulls<com.mobiledaw.model.SynthPatch>(NativeEngine.MAX_CHANNELS)
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

    /** Piano roll: one-finger drag scrolls (tap still edits). Off = one finger edits notes, two scroll. */
    var oneFingerScroll by mutableStateOf(prefs.getBoolean("one_finger_scroll", false))
        private set

    fun changeOneFingerScroll(on: Boolean) {
        oneFingerScroll = on
        prefs.edit().putBoolean("one_finger_scroll", on).apply()
    }

    fun changePreviewOnEdit(on: Boolean) {
        previewOnEdit = on
        prefs.edit().putBoolean("preview_on_edit", on).apply()
    }

    /** How many bars the channel rack shows side by side (pinch to change), 1..4. */
    var rackBars by mutableIntStateOf(prefs.getInt("rack_bars", 1).coerceIn(1, 4))
        private set

    fun changeRackBars(n: Int) {
        rackBars = n.coerceIn(1, 4)
        prefs.edit().putInt("rack_bars", rackBars).apply()
    }

    /** Piano roll row labels: every note, or only notes in the project's key. */
    var labelAllNotes by mutableStateOf(prefs.getBoolean("label_all_notes", false))
        private set

    fun changeLabelAllNotes(all: Boolean) {
        labelAllNotes = all
        prefs.edit().putBoolean("label_all_notes", all).apply()
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
    /** Length of what is playing (the pattern or the whole song), in ticks. */
    var loopTicks by mutableIntStateOf(TICKS_PER_BAR)
        private set
    var levels by mutableStateOf(FloatArray(NativeEngine.MAX_CHANNELS + 1))
        private set
    var scope by mutableStateOf(FloatArray(SCOPE_BINS))
        private set

    // Recording state. Declared before init{} because syncEngine() reads takeState.
    private val recorder = Recorder()
    private var takeJob: Job? = null

    /** Playlist record mode: the bottom bar turns into big play + record buttons. */
    var recordMode by mutableStateOf(false)
        private set
    var takeState by mutableStateOf(TakeState.IDLE)
        private set
    /** Count-in shown over the playlist: 1, 2, 3, then 4 means "GO". Null when hidden. */
    var countIn by mutableStateOf<Int?>(null)
        private set
    /** Where the current take starts and which lane it lands on. */
    var takeStartTick by mutableIntStateOf(0)
        private set
    var takeLane by mutableIntStateOf(0)
        private set
    /** Live peaks of the take being recorded, each covering Recorder.PEAK_MS. */
    var takePeaks by mutableStateOf(FloatArray(0))
        private set
    var micLevel by mutableStateOf(0f)
        private set
    var recordMessage by mutableStateOf<String?>(null)

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
        if (takeState != TakeState.IDLE) stopTake()
        applyPlaying(false)
        NativeEngine.stop()
        saveNow()
    }

    private fun syncEngine() {
        val p = project
        NativeEngine.setBpm(p.bpm.toFloat())
        NativeEngine.setMasterVolume(p.masterVolume)
        val audioSlots = SequenceCompiler.audioSlots(p, NativeEngine.MAX_CHANNELS)
        NativeEngine.setChannelCount((p.channels.size + audioSlots.size).coerceAtMost(NativeEngine.MAX_CHANNELS))
        p.channels.take(NativeEngine.MAX_CHANNELS).forEachIndexed { i, c ->
            NativeEngine.setChannel(i, c.instrument.id, c.volume, c.pan, c.mute, c.solo)
            NativeEngine.setChannelClip(i, false)
            NativeEngine.setChannelSampler(i, c.cutItself, c.trimStart, c.trimEnd, c.attack, c.hold, c.decay, c.sustain, c.release, c.pitchSemis, c.reverse)
            syncSynth(i, c)
            val path = if (c.instrument == Instrument.SAMPLE) c.sample else null
            if (loadedSamples[i] != path) {
                val decoded = path?.let { decodeSample(it) }
                NativeEngine.setSample(i, decoded?.frames, decoded?.sampleRate ?: 0f)
                loadedSamples[i] = path
            }
        }
        // Audio clips each get a hidden sample slot after the channels.
        audioSlots.forEach { (slot, clip) ->
            NativeEngine.setChannel(slot, Instrument.SAMPLE.id, 1f, 0f, false, false)
            NativeEngine.setChannelSampler(slot, false, 0f, 1f, 0f, 0f, 0f, 1f, 0.005f, 0, false)
            NativeEngine.setChannelClip(slot, true)
            sentPatches[slot] = null
            if (loadedSamples[slot] != clip.path) {
                val decoded = decodeSample(clip.path)
                NativeEngine.setSample(slot, decoded?.frames, decoded?.sampleRate ?: 0f)
                loadedSamples[slot] = clip.path
            }
        }
        val compiled = when (mode) {
            PlayMode.PATTERN -> SequenceCompiler.pattern(p, selectedPattern)
            PlayMode.SONG -> SequenceCompiler.song(p)
        }
        // While recording, keep the song from looping back to the start under the take.
        val loop = if (takeState == TakeState.IDLE) compiled.loopTicks
        else maxOf(compiled.loopTicks, takeStartTick + p.bpm * TICKS_PER_BEAT_PER_MINUTE * 5)
        NativeEngine.setSequence(compiled.events, loop)
        loopTicks = loop
    }

    /** Project as it was before the last delete, while that delete can still be undone. */
    var undoSnapshot by mutableStateOf<Project?>(null)
        private set
    var undoLabel by mutableStateOf("")
        private set

    private fun update(transform: (Project) -> Project) {
        undoSnapshot = null
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

    fun togglePlay() {
        if (takeState != TakeState.IDLE) stopTake() else applyPlaying(!playing)
    }

    /** Moves the playhead to a fraction (0..1) of the pattern or song, snapped to a step. */
    fun seekTo(fraction: Float) {
        val raw = (fraction.coerceIn(0f, 1f) * loopTicks).toInt()
        val tick = ((raw / TICKS_PER_STEP) * TICKS_PER_STEP).coerceIn(0, maxOf(0, loopTicks - TICKS_PER_STEP))
        NativeEngine.seek(tick.toLong())
        positionTicks = tick.toLong()
    }

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

    /** Replaces a channel's steps with one hit every [every] steps across the whole pattern. */
    fun fillSteps(channel: Channel, every: Int) {
        val pattern = selectedPattern
        val pitch = channel.instrument.defaultPitch
        val steps = pattern.lengthTicks / TICKS_PER_STEP
        updateNotes(channel.id) { (0 until steps step every).map { Note(it * TICKS_PER_STEP, TICKS_PER_STEP, pitch) } }
    }

    fun clearSteps(channel: Channel) = updateNotes(channel.id) { emptyList() }

    /** Doubles the selected pattern by copying its bars after themselves (4 bars -> 8). */
    fun loopPattern() = updatePattern(selectedPatternId) { pat ->
        if (pat.bars * 2 > com.mobiledaw.model.MAX_BARS) return@updatePattern pat
        val len = pat.lengthTicks
        pat.copy(
            bars = pat.bars * 2,
            notes = pat.notes.mapValues { (_, list) ->
                val inside = list.filter { it.start < len }
                inside + inside.map { it.copy(start = it.start + len) }
            },
        )
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
        if (instrument == Instrument.WAVETABLE) openSynth(id)
    }

    fun deleteChannel(id: Long) {
        val before = project
        val name = before.channels.firstOrNull { it.id == id }?.name ?: "channel"
        update { p ->
            p.copy(
                channels = p.channels.filter { it.id != id },
                patterns = p.patterns.map { it.copy(notes = it.notes - id) },
            )
        }
        if (selectedChannelId == id) selectedChannelId = project.channels.firstOrNull()?.id ?: 0L
        undoSnapshot = before
        undoLabel = "Deleted $name"
    }

    fun undoDelete() {
        val snapshot = undoSnapshot ?: return
        update { snapshot }
    }

    fun dismissUndo() {
        undoSnapshot = null
    }

    fun updateChannel(id: Long, transform: (Channel) -> Channel) = update { p ->
        p.copy(channels = p.channels.map { if (it.id == id) transform(it) else it })
    }

    // ---------------------------------------------------------------- wavetable synth

    val synthPresets = com.mobiledaw.model.SynthPresetLibrary(app)
    var userSynthPresets by mutableStateOf<List<com.mobiledaw.model.SynthPreset>>(emptyList())
        private set

    /** The channel whose synth editor is open, or null. */
    var synthEditorChannelId by mutableStateOf<Long?>(null)
        private set

    private fun syncSynth(slot: Int, c: Channel) {
        if (c.instrument != Instrument.WAVETABLE) {
            sentPatches[slot] = null
            return
        }
        val patch = c.synth ?: com.mobiledaw.model.SynthPatch()
        if (sentPatches[slot] != patch) {
            com.mobiledaw.audio.SynthEngine.setParams(slot, patch.toArray())
            sentPatches[slot] = patch
        }
    }

    fun openSynth(channelId: Long) {
        userSynthPresets = synthPresets.user()
        selectedChannelId = channelId
        synthEditorChannelId = channelId
    }

    fun closeSynth() {
        synthEditorChannelId = null
    }

    /** Knob changes go straight to the engine, without recompiling the whole project. */
    fun setSynthParam(channelId: Long, name: String, value: Float) {
        val info = com.mobiledaw.model.SynthParams.byName(name) ?: return
        val index = project.channels.indexOfFirst { it.id == channelId }
        if (index < 0) return
        val patch = (project.channels[index].synth ?: com.mobiledaw.model.SynthPatch()).with(name, value)
        undoSnapshot = null
        project = project.copy(channels = project.channels.map { if (it.id == channelId) it.copy(synth = patch) else it })
        if (index < NativeEngine.MAX_CHANNELS) {
            com.mobiledaw.audio.SynthEngine.setParam(index, info.index, value)
            sentPatches[index] = patch
        }
        scheduleSave()
    }

    fun loadSynthPreset(channelId: Long, preset: com.mobiledaw.model.SynthPreset) {
        updateChannel(channelId) { c ->
            // Channels still named after their instrument or old preset take the preset's name.
            val oldPreset = c.synth?.preset
            val autoNamed = c.name.startsWith(Instrument.WAVETABLE.label) || c.name == oldPreset
            c.copy(synth = com.mobiledaw.model.SynthPatch.from(preset), name = if (autoNamed) preset.name else c.name)
        }
        val low = preset.category == "808" || preset.category == "Bass"
        auditionSynth(channelId, if (low) 48 else 60)
    }

    /** Plays a short note so a freshly picked preset can be heard. */
    private fun auditionSynth(channelId: Long, pitch: Int) {
        if (playing) return
        synthNote(channelId, pitch, true)
        viewModelScope.launch {
            delay(450)
            synthNote(channelId, pitch, false)
        }
    }

    fun synthNote(channelId: Long, pitch: Int, on: Boolean) {
        val index = project.channels.indexOfFirst { it.id == channelId }
        if (index in 0 until NativeEngine.MAX_CHANNELS) com.mobiledaw.audio.SynthEngine.noteHold(index, pitch, 100, on)
    }

    fun saveSynthPreset(channelId: Long, name: String, category: String) {
        val channel = project.channels.firstOrNull { it.id == channelId } ?: return
        val clean = name.trim().ifEmpty { return }
        val patch = channel.synth ?: com.mobiledaw.model.SynthPatch()
        synthPresets.save(com.mobiledaw.model.SynthPreset(clean, category, patch.params))
        userSynthPresets = synthPresets.user()
        updateChannel(channelId) { it.copy(synth = patch.copy(preset = clean, category = category, edited = false)) }
    }

    fun deleteSynthPreset(name: String) {
        synthPresets.delete(name)
        userSynthPresets = synthPresets.user()
    }

    // ---------------------------------------------------------------- kits & samples

    private fun decodeSample(path: String): WavDecoder.Decoded? =
        sampleCache[path] ?: run {
            val max = if (path.startsWith(RECORDINGS_DIR)) WavDecoder.MAX_RECORDING_SECONDS else 60
            WavDecoder.decode(kits.file(path), max)?.also { sampleCache[path] = it }
        }

    /** Peak values (0..1) across a sample, for drawing its waveform. */
    fun samplePeaks(path: String, bins: Int): FloatArray {
        val frames = decodeSample(path)?.frames ?: return FloatArray(0)
        if (frames.isEmpty()) return FloatArray(0)
        val out = FloatArray(bins)
        val per = maxOf(1, frames.size / bins)
        for (b in 0 until bins) {
            var peak = 0f
            val from = b * frames.size / bins
            for (i in from until minOf(frames.size, from + per)) peak = maxOf(peak, kotlin.math.abs(frames[i]))
            out[b] = peak
        }
        val max = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] /= max
        return out
    }

    fun sampleSeconds(path: String): Float =
        decodeSample(path)?.let { it.frames.size / it.sampleRate } ?: 0f

    /** Plays a kit sound without adding it to the project. */
    fun audition(path: String) {
        val decoded = decodeSample(path) ?: return
        NativeEngine.setSample(NativeEngine.AUDITION_SLOT, decoded.frames, decoded.sampleRate)
        NativeEngine.audition()
    }

    fun addSampleChannel(sound: KitLibrary.Sound) {
        if (project.channels.size >= NativeEngine.MAX_CHANNELS) return
        val id = project.nextId()
        update { it.copy(channels = it.channels + Channel(id, shortName(sound.name), Instrument.SAMPLE, sample = sound.path)) }
        selectedChannelId = id
    }

    /** A short channel name from a sound's filename, e.g. "808 - Christopher Spinza" -> "808". */
    private fun shortName(full: String): String {
        val head = full.substringBefore(" - ").substringBefore(" (").trim()
        val name = head.ifEmpty { full.trim() }
        return if (name.length > 16) name.take(16).trim() else name
    }

    fun setChannelSound(channelId: Long, sound: KitLibrary.Sound) {
        // Keep the name the user gave the channel; the rack shows the sound's file name beside it.
        updateChannel(channelId) { it.copy(instrument = Instrument.SAMPLE, sample = sound.path) }
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

    /** The audio clip under a point on the playlist, if any. */
    fun audioClipAt(lane: Int, tick: Int): AudioClip? =
        project.audioClips.lastOrNull { it.lane == lane && tick >= it.startTick && tick < it.endTick(project.bpm) }

    fun deleteAudioClip(id: Long) {
        val before = project
        update { p -> p.copy(audioClips = p.audioClips.filter { it.id != id }) }
        undoSnapshot = before
        undoLabel = "Deleted recording"
    }

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

    // ---------------------------------------------------------------- recording


    fun enterRecordMode() {
        recordMode = true
        tab = Tab.PLAYLIST
        switchMode(PlayMode.SONG)
    }

    fun exitRecordMode() {
        if (takeState != TakeState.IDLE) stopTake()
        recordMode = false
    }

    /** The big record button: starts a count-in and take, or stops the take. */
    fun toggleTake() {
        if (takeState == TakeState.IDLE) startTake() else stopTake()
    }

    private fun startTake() {
        if (playing) applyPlaying(false)
        switchMode(PlayMode.SONG)
        val start = (positionTicks.toInt()).coerceAtLeast(0)
        takeStartTick = start
        takeLane = (0 until PLAYLIST_LANES).firstOrNull { project.laneFree(it, start, Int.MAX_VALUE) }
            ?: (0 until PLAYLIST_LANES).firstOrNull { project.laneFree(it, start, start + TICKS_PER_BAR) }
            ?: 0
        takePeaks = FloatArray(0)
        takeState = TakeState.COUNTING
        recordMessage = null
        takeJob = viewModelScope.launch {
            val beatMs = 60_000L / project.bpm.coerceAtLeast(1)
            for (n in 1..3) {
                countIn = n
                click(high = false)
                delay(beatMs)
            }
            countIn = 4
            click(high = true)
            val file = recordingFile("take-${System.currentTimeMillis()}.wav")
            val ok = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { recorder.start(file) }
            if (!isActive) {
                // Stopped during the count-in while the mic was opening.
                recorder.stop()?.file?.delete()
                return@launch
            }
            if (!ok) {
                countIn = null
                takeState = TakeState.IDLE
                recordMessage = "Couldn't open the microphone."
                return@launch
            }
            takeState = TakeState.RECORDING
            syncEngine() // longer loop so the song doesn't wrap under the take
            NativeEngine.seek(start.toLong())
            applyPlaying(true)
            launch {
                delay(beatMs.coerceAtMost(700))
                if (countIn == 4) countIn = null
            }
            while (isActive && recorder.isRunning) {
                takePeaks = recorder.peaksSnapshot()
                micLevel = recorder.level
                delay(50)
            }
            // The recorder stopped by itself (time limit): finish the take.
            if (takeState == TakeState.RECORDING) stopTake()
        }
    }

    fun stopTake() {
        val state = takeState
        takeJob?.cancel()
        takeJob = null
        countIn = null
        micLevel = 0f
        takeState = TakeState.IDLE
        applyPlaying(false)
        if (state != TakeState.RECORDING) {
            syncEngine()
            return
        }
        val take = recorder.stop()
        takePeaks = FloatArray(0)
        val start = takeStartTick
        if (take == null) {
            syncEngine()
            return
        }
        placeRecording(take.file, take.seconds, takeLane, start)
        // Park the playhead at the start of the take so play replays it.
        NativeEngine.seek(start.toLong())
        positionTicks = start.toLong()
    }

    /** Puts a finished recording (a WAV in the app's files folder) on the playlist. */
    fun placeRecording(file: java.io.File, seconds: Float, lane: Int, startTick: Int) {
        val path = file.relativeTo(getApplication<Application>().filesDir).path
        val number = project.audioClips.size + 1
        update { p ->
            p.copy(audioClips = p.audioClips + AudioClip(p.nextId(), lane, startTick, path, seconds, "Take $number"))
        }
    }

    /** Where recordings are saved. */
    fun recordingFile(name: String): java.io.File = kits.file("$RECORDINGS_DIR/$name")

    /** A short count-in blip through the audition voice. */
    private fun click(high: Boolean) {
        val sr = 48000
        val freq = if (high) 1760.0 else 1175.0
        val n = sr / 20
        val frames = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            (kotlin.math.sin(2 * Math.PI * freq * t) * kotlin.math.exp(-t * 60) * 0.8).toFloat()
        }
        NativeEngine.setSample(NativeEngine.AUDITION_SLOT, frames, sr.toFloat())
        NativeEngine.audition()
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
        if (recorder.isRunning) recorder.stop()
        saveNow()
    }

    companion object {
        const val SCOPE_BINS = 96
        const val PLAYLIST_LANES = 8
        const val RECORDINGS_DIR = "recordings"
        /** Ticks per minute per BPM, i.e. TICKS_PER_BEAT. Used to size the loop while recording. */
        private const val TICKS_PER_BEAT_PER_MINUTE = com.mobiledaw.model.TICKS_PER_BEAT
    }
}
