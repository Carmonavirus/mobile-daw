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
import com.mobiledaw.model.Effect
import com.mobiledaw.model.EffectType
import com.mobiledaw.model.EqSettings
import com.mobiledaw.model.MAX_EFFECTS
import com.mobiledaw.model.MAX_MIXER_TRACKS
import com.mobiledaw.model.MixerTrack
import com.mobiledaw.model.PitchSettings
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

    // What each engine effect slot last received, so unchanged effects aren't resent.
    // Also declared before init{} (syncEngine() -> syncMixer() reads it).
    private val sentEffects = HashMap<Int, Pair<Int, FloatArray>>()

    /** Mixer track meters, by position in [Project.mixerTracks]. Before init{}: its loop writes it. */
    var trackLevels by mutableStateOf(FloatArray(NativeEngine.MAX_INSERTS))
        private set
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
                trackLevels = FloatArray(NativeEngine.MAX_INSERTS).also { NativeEngine.readInsertLevels(it) }
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
            NativeEngine.setChannelSampler(i, c.cutItself, c.trimStart, c.trimEnd, c.envDelay, c.attack, c.hold, c.decay, c.sustain, c.release, c.tension, c.pitchSemis, c.reverse)
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
            NativeEngine.setChannelSampler(slot, false, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0.005f, 0f, 0, false)
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
        syncMixer()
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

    /** The "stop all sound" button: stops playback (and a take in progress) and cuts every sound. */
    fun stopAllSound() {
        if (takeState != TakeState.IDLE) stopTake()
        playing = false
        NativeEngine.panic()
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
        // Switching channels keeps the chords written so far.
        if (chordGen != null && chordGen?.channelId != id) closeChordGen(keep = true)
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
            val long = path.startsWith(RECORDINGS_DIR) || path.startsWith(com.mobiledaw.model.AudioImport.DIR + "/")
            val max = if (long) WavDecoder.MAX_RECORDING_SECONDS else 60
            WavDecoder.decode(kits.file(path), max)?.also {
                sampleCache[path] = it
                trimSampleCache()
            }
        }

    /**
     * Keeps decoded sounds under ~24M frames (about 100 MB of floats): a few long imported loops
     * would otherwise fill memory. The engine keeps its own copy, so dropping one here is safe.
     */
    private fun trimSampleCache() {
        var total = sampleCache.values.sumOf { it.frames.size.toLong() }
        val iter = sampleCache.entries.iterator()
        while (total > MAX_CACHED_FRAMES && sampleCache.size > 1 && iter.hasNext()) {
            total -= iter.next().value.frames.size
            iter.remove()
        }
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

    // ---------------------------------------------------------------- chord generator

    /** The open Chords panel: which channel/pattern it writes into and what was there before. */
    data class ChordGenSession(
        val channelId: Long,
        val patternId: Long,
        val originalNotes: List<Note>,
        val originalBars: Int,
        val slots: List<com.mobiledaw.model.ChordSlot>,
    )

    var chordGen by mutableStateOf<ChordGenSession?>(null)
        private set

    /** Remembered between openings. */
    var chordSettings by mutableStateOf(com.mobiledaw.model.ChordSettings())
        private set

    private val chordRandom = kotlin.random.Random(System.nanoTime())
    private var chordAudition: Job? = null

    /** Opens the Chords panel on the selected channel and writes a first progression. */
    fun openChordGen() {
        val channel = selectedChannel ?: return
        val pattern = selectedPattern
        if (chordGen == null) {
            chordGen = ChordGenSession(channel.id, pattern.id, pattern.notesFor(channel.id), pattern.bars, emptyList())
        }
        if (mode != PlayMode.PATTERN) switchMode(PlayMode.PATTERN)
        generateChords()
    }

    /** Keep = leave the chords in the pattern; otherwise put back what was there. */
    fun closeChordGen(keep: Boolean) {
        val s = chordGen ?: return
        chordAudition?.cancel()
        chordGen = null
        if (!keep) {
            updatePattern(s.patternId) { pat ->
                pat.copy(bars = s.originalBars, notes = pat.notes + (s.channelId to s.originalNotes))
            }
        }
    }

    fun generateChords() {
        val s = chordGen ?: return
        setChordSlots(com.mobiledaw.model.ChordGen.generate(s.slots, chordSettings, project.minor, chordRandom))
    }

    fun rerollChord(index: Int) {
        val s = chordGen ?: return
        if (index !in s.slots.indices) return
        setChordSlots(com.mobiledaw.model.ChordGen.reroll(s.slots, index, chordSettings, project.minor, chordRandom))
        auditionChord(index)
    }

    fun toggleChordLock(index: Int) {
        val s = chordGen ?: return
        setChordSlots(s.slots.mapIndexed { i, c -> if (i == index) c.copy(locked = !c.locked) else c })
    }

    fun invertChord(index: Int, delta: Int) {
        val s = chordGen ?: return
        setChordSlots(s.slots.mapIndexed { i, c -> if (i == index) c.copy(inversion = (c.inversion + delta).coerceIn(-4, 4)) else c })
        auditionChord(index)
    }

    /** Count, octave, length, bass and rhythm apply right away; the slider applies on the next Generate. */
    fun changeChordSettings(transform: (com.mobiledaw.model.ChordSettings) -> com.mobiledaw.model.ChordSettings) {
        val old = chordSettings
        val new = transform(old).let {
            it.copy(
                count = it.count.coerceIn(com.mobiledaw.model.ChordGen.MIN_COUNT, com.mobiledaw.model.ChordGen.MAX_COUNT),
                octave = it.octave.coerceIn(com.mobiledaw.model.ChordGen.MIN_OCTAVE, com.mobiledaw.model.ChordGen.MAX_OCTAVE),
                adventure = it.adventure.coerceIn(0f, 1f),
            )
        }
        chordSettings = new
        val s = chordGen ?: return
        when {
            new.count > s.slots.size -> {
                // Keep the chords we have and add new ones after them.
                val kept = s.slots.map { it.copy(locked = true) }
                val grown = com.mobiledaw.model.ChordGen.generate(kept, new, project.minor, chordRandom)
                setChordSlots(grown.mapIndexed { i, c -> if (i < s.slots.size) s.slots[i] else c })
            }
            new.count < s.slots.size -> setChordSlots(s.slots.take(new.count))
            new != old -> setChordSlots(s.slots)
        }
    }

    private fun setChordSlots(slots: List<com.mobiledaw.model.ChordSlot>) {
        val s = chordGen ?: return
        chordGen = s.copy(slots = slots)
        val settings = chordSettings
        val span = slots.size * settings.lengthTicks
        val chordNotes = com.mobiledaw.model.ChordGen.notes(slots, settings, project.key)
        val neededBars = ((span + TICKS_PER_BAR - 1) / TICKS_PER_BAR).coerceAtMost(com.mobiledaw.model.MAX_BARS)
        updatePattern(s.patternId) { pat ->
            // The chords replace this channel's notes where they play; anything after them stays.
            val rest = s.originalNotes.filter { it.start >= span }
            val bars = maxOf(s.originalBars, neededBars)
            val limit = bars * TICKS_PER_BAR
            val notes = (chordNotes.filter { it.start < limit }.map { it.copy(length = minOf(it.length, limit - it.start)) } + rest)
            pat.copy(bars = bars, notes = pat.notes + (s.channelId to notes))
        }
    }

    /** Plays one chord of the progression on its channel (only while stopped). */
    fun auditionChord(index: Int) {
        val s = chordGen ?: return
        if (playing) return
        val pitches = com.mobiledaw.model.ChordGen.voice(s.slots, chordSettings, project.key).getOrNull(index) ?: return
        chordAudition?.cancel()
        chordAudition = viewModelScope.launch {
            pitches.forEach { synthNote(s.channelId, it, true) }
            try {
                delay(700)
            } finally {
                pitches.forEach { synthNote(s.channelId, it, false) }
            }
        }
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

    // ---------------------------------------------------------------- imported audio files

    private val audioImport = com.mobiledaw.model.AudioImport(app)

    /** True while an audio file is being copied and converted. */
    var importingAudio by mutableStateOf(false)
        private set

    /** A short note after an import ("Added …", or why it failed). */
    var audioImportMessage by mutableStateOf<String?>(null)

    /** Files that came in through Share / Open with, waiting for the user to pick a playlist row. */
    var sharedAudio by mutableStateOf<List<com.mobiledaw.model.AudioImport.Imported>>(emptyList())
        private set

    /** Imports audio files and puts them on playlist row [lane], each on its own new mixer track. */
    fun importAudioToLane(uris: List<android.net.Uri>, lane: Int) {
        if (uris.isEmpty()) return
        importAudio(uris) { placeImported(it, lane) }
    }

    /** Called with every intent the activity receives; ignores ones that carry no audio. */
    fun receiveShared(intent: android.content.Intent?) {
        val uris = audioImport.urisFrom(intent)
        if (uris.isEmpty()) return
        // Copy straight away: the permission to read a shared file doesn't last.
        importAudio(uris) { sharedAudio = it }
    }

    private fun importAudio(uris: List<android.net.Uri>, then: (List<com.mobiledaw.model.AudioImport.Imported>) -> Unit) {
        importingAudio = true
        audioImportMessage = null
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.take(MAX_MIXER_TRACKS).mapNotNull { runCatching { audioImport.import(it) }.getOrNull() }
            }
            importingAudio = false
            if (imported.isEmpty()) {
                audioImportMessage = "Couldn't read that file as audio."
                return@launch
            }
            if (imported.size < uris.size) audioImportMessage = "Skipped ${uris.size - imported.size} file(s) that weren't readable audio."
            then(imported)
        }
    }

    /** Puts the files waiting from a share on playlist row [lane]. */
    fun placeShared(lane: Int) {
        val files = sharedAudio
        sharedAudio = emptyList()
        if (files.isNotEmpty()) placeImported(files, lane)
    }

    fun dismissShared() {
        sharedAudio = emptyList()
    }

    /**
     * Each file goes on [lane] after whatever is already on that row (or at bar 1 if it is empty),
     * with a new mixer track named after it. Nothing is added to the channel rack.
     */
    private fun placeImported(files: List<com.mobiledaw.model.AudioImport.Imported>, lane: Int) {
        var full = false
        update { start ->
            var p = start
            files.forEach { f ->
                val trackId = if (p.mixerTracks.size < MAX_MIXER_TRACKS) p.nextMixerTrackId() else null
                if (trackId == null) full = true
                val laneEnd = maxOf(
                    p.clips.filter { it.lane == lane }.maxOfOrNull { (it.bar + p.clipBars(it)) * TICKS_PER_BAR } ?: 0,
                    p.audioClips.filter { it.lane == lane }.maxOfOrNull { it.endTick(p.bpm) } ?: 0,
                )
                // Start on a bar line.
                val startTick = (laneEnd + TICKS_PER_BAR - 1) / TICKS_PER_BAR * TICKS_PER_BAR
                p = p.copy(
                    mixerTracks = if (trackId != null) p.mixerTracks + MixerTrack(trackId, f.name.take(24)) else p.mixerTracks,
                    audioClips = p.audioClips + AudioClip(p.nextId(), lane, startTick, f.path, f.seconds, f.name, trackId),
                )
            }
            p
        }
        sentEffects.clear()
        tab = Tab.PLAYLIST
        // Audio clips play in song mode.
        if (mode != PlayMode.SONG) switchMode(PlayMode.SONG)
        val what = if (files.size == 1) "\"${files[0].name}\"" else "${files.size} files"
        audioImportMessage = when {
            full -> "Added $what to row ${lane + 1}. The mixer is full ($MAX_MIXER_TRACKS tracks), so it plays through the recordings track."
            else -> "Added $what to row ${lane + 1} with its own mixer track."
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

    // ---------------------------------------------------------------- mixer tracks & effects

    /** The effect whose editor is open: (mixer track id, effect id). */
    var openEffect by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    /** Engine insert index of a mixer track, or -1 (master) when it doesn't exist. */
    fun insertIndex(trackId: Long?): Int {
        if (trackId == null) return -1
        val i = project.mixerTracks.indexOfFirst { it.id == trackId }
        return if (i in 0 until NativeEngine.MAX_INSERTS) i else -1
    }

    private fun syncMixer() {
        val p = project
        val tracks = p.mixerTracks.take(NativeEngine.MAX_INSERTS)
        NativeEngine.setInsertCount(tracks.size)
        tracks.forEachIndexed { t, track ->
            NativeEngine.setInsert(t, track.volume, track.pan, track.mute)
            for (slot in 0 until NativeEngine.MAX_SLOTS) {
                val fx = track.effects.getOrNull(slot)
                val key = t * NativeEngine.MAX_SLOTS + slot
                val type = fx?.type?.id ?: 0
                NativeEngine.setInsertEffect(t, slot, type, fx?.bypass ?: false)
                if (fx == null) {
                    sentEffects.remove(key)
                    continue
                }
                val params = fx.params(p)
                val last = sentEffects[key]
                if (last == null || last.first != type || !last.second.contentEquals(params)) {
                    NativeEngine.setEffectParams(t, slot, params)
                    sentEffects[key] = type to params
                }
            }
        }
        p.channels.take(NativeEngine.MAX_CHANNELS).forEachIndexed { i, c ->
            NativeEngine.setChannelInsert(i, insertIndex(c.mixerTrack))
        }
        // Recordings play through the recording track (the Vocals track by default);
        // imported audio files through the mixer track made for them.
        SequenceCompiler.audioSlots(p, NativeEngine.MAX_CHANNELS).forEach { (slot, clip) ->
            NativeEngine.setChannelInsert(slot, insertIndex(clip.trackId ?: p.recordingTrackId))
        }
    }

    /** Mixer-only edits (knobs, EQ drags) skip recompiling the song. */
    private fun updateMixer(transform: (Project) -> Project) {
        undoSnapshot = null
        project = transform(project)
        syncMixer()
        scheduleSave()
    }

    fun updateMixerTrack(id: Long, transform: (MixerTrack) -> MixerTrack) = updateMixer { p ->
        p.copy(mixerTracks = p.mixerTracks.map { if (it.id == id) transform(it) else it })
    }

    fun addMixerTrack(): Long? {
        if (project.mixerTracks.size >= MAX_MIXER_TRACKS) return null
        val id = project.nextMixerTrackId()
        val name = "Track ${project.mixerTracks.size + 1}"
        updateMixer { it.copy(mixerTracks = it.mixerTracks + MixerTrack(id, name)) }
        return id
    }

    fun deleteMixerTrack(id: Long) {
        val before = project
        val name = before.mixerTrack(id)?.name ?: "track"
        update { p ->
            p.copy(
                mixerTracks = p.mixerTracks.filter { it.id != id },
                channels = p.channels.map { if (it.mixerTrack == id) it.copy(mixerTrack = null) else it },
                audioClips = p.audioClips.map { if (it.trackId == id) it.copy(trackId = null) else it },
                recordingTrackId = p.recordingTrackId.takeIf { it != id },
            )
        }
        sentEffects.clear()
        undoSnapshot = before
        undoLabel = "Deleted $name"
    }

    fun renameMixerTrack(id: Long, name: String) {
        val clean = name.trim().ifEmpty { return }
        updateMixerTrack(id) { it.copy(name = clean) }
    }

    fun routeChannel(channelId: Long, trackId: Long?) = updateMixer { p ->
        p.copy(channels = p.channels.map { if (it.id == channelId) it.copy(mixerTrack = trackId) else it })
    }

    /** Where recorded audio plays through: a mixer track, or null for straight to the master. */
    fun setRecordingTrack(trackId: Long?) = update { it.copy(recordingTrackId = trackId) }

    fun addEffect(trackId: Long, type: EffectType) {
        val track = project.mixerTrack(trackId) ?: return
        if (track.effects.size >= MAX_EFFECTS) return
        val id = project.nextEffectId()
        val fx = when (type) {
            EffectType.EQ -> Effect(id, type, eq = EqSettings.flat())
            EffectType.PITCH -> Effect(id, type, pitch = PitchSettings())
        }
        updateMixerTrack(trackId) { it.copy(effects = it.effects + fx) }
        openEffect = trackId to id
    }

    fun removeEffect(trackId: Long, effectId: Long) {
        if (openEffect == trackId to effectId) openEffect = null
        updateMixerTrack(trackId) { t -> t.copy(effects = t.effects.filter { it.id != effectId }) }
    }

    /** Moves an effect one place earlier (-1) or later (+1) in the chain. */
    fun moveEffect(trackId: Long, effectId: Long, by: Int) = updateMixerTrack(trackId) { t ->
        val i = t.effects.indexOfFirst { it.id == effectId }
        val j = i + by
        if (i < 0 || j !in t.effects.indices) return@updateMixerTrack t
        val list = t.effects.toMutableList()
        list.add(j, list.removeAt(i))
        t.copy(effects = list)
    }

    fun updateEffect(trackId: Long, effectId: Long, transform: (Effect) -> Effect) = updateMixerTrack(trackId) { t ->
        t.copy(effects = t.effects.map { if (it.id == effectId) transform(it) else it })
    }

    fun openEffect(trackId: Long, effectId: Long) {
        openEffect = trackId to effectId
    }

    fun closeEffect() {
        openEffect = null
    }

    /** Fills [out] with the newest output of a mixer track, for the EQ's spectrum. */
    fun readTrackSignal(trackId: Long, out: FloatArray) {
        val i = insertIndex(trackId)
        if (i >= 0) NativeEngine.readInsertSignal(i, out) else out.fill(0f)
    }

    /** Detected and corrected pitch (Hz) of a pitch corrector, 0 when nothing is sung. */
    fun readPitch(trackId: Long, effectId: Long, out: FloatArray) {
        val i = insertIndex(trackId)
        val slot = project.mixerTrack(trackId)?.effects?.indexOfFirst { it.id == effectId } ?: -1
        if (i >= 0 && slot in 0 until NativeEngine.MAX_SLOTS) NativeEngine.readPitch(i, slot, out) else out.fill(0f)
    }

    override fun onCleared() {
        if (recorder.isRunning) recorder.stop()
        saveNow()
    }

    companion object {
        const val SCOPE_BINS = 96
        const val PLAYLIST_LANES = 8
        const val RECORDINGS_DIR = "recordings"
        private const val MAX_CACHED_FRAMES = 24_000_000L
        /** Ticks per minute per BPM, i.e. TICKS_PER_BEAT. Used to size the loop while recording. */
        private const val TICKS_PER_BEAT_PER_MINUTE = com.mobiledaw.model.TICKS_PER_BEAT
    }
}
