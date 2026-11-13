package com.mobiledaw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.audio.Recorder
import com.mobiledaw.audio.StretchCache
import com.mobiledaw.audio.WavDecoder
import com.mobiledaw.model.stepPitch
import com.mobiledaw.model.KitLibrary
import com.mobiledaw.model.AudioClip
import com.mobiledaw.model.Channel
import com.mobiledaw.model.Clip
import com.mobiledaw.model.DemoProject
import com.mobiledaw.model.Effect
import com.mobiledaw.model.EffectType
import com.mobiledaw.model.ClipSettings
import com.mobiledaw.model.EqSettings
import com.mobiledaw.model.MASTER_TRACK_ID
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
import com.mobiledaw.model.TICKS_PER_BEAT
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

/** A side of the wide-screen split view. */
enum class PaneSide { LEFT, RIGHT }

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
    /** SoundFont hits we know how to record, by pseudo path: (bank, preset, key). */
    private val soundFontHits = HashMap<String, Triple<Int, Int, Int>>()
    private val samplePeakCache = HashMap<String, Float>()

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

    // Audio clips pitched with Stretch / Vocal play a rendered copy (see stretchedSample). Before
    // init{}: syncEngine() reads these.
    private val stretchPending = HashSet<String>()
    private val stretchFailed = HashSet<String>()

    /** Clip sounds being rendered at a new pitch right now, by [stretchKey]. */
    var stretchingKeys by mutableStateOf(emptySet<String>())
        private set

    // Declared before init {} below, which syncs the engine and needs them.
    /** SoundFonts, SFZ, synth engine presets, online libraries and credits. */
    val sounds = com.mobiledaw.sound.SoundSources(app)

    /** Which SFZ each engine slot holds. */
    private val loadedSources = arrayOfNulls<String>(NativeEngine.MAX_CHANNELS)
    private val pendingFontLoads = mutableSetOf<String>()

    /** True while a SoundFont is being read into memory (takes a second or two). */
    var loadingSoundFont by mutableStateOf(false)
        private set

    /** Bumped after an import so the kit browser refreshes. */
    var kitsVersion by mutableStateOf(0)
        private set
    var importing by mutableStateOf(false)
        private set
    var importMessage by mutableStateOf<String?>(null)

    var project by mutableStateOf(loadInitial())
        private set
    var tab by mutableStateOf(Tab.RACK)

    /**
     * Wide screens (tablet in landscape) show two screens side by side: Playlist + Rack on the
     * Playlist tab, Rack + Piano roll on the Rack tab. Set by the UI from the window width.
     */
    var wideLayout by mutableStateOf(false)

    /** Phone turning to/from landscape: the 3, 2, 1 countdown that blocks the screen first. */
    var turnCountdown by mutableStateOf<TurnCountdown?>(null)

    /**
     * Wide-screen split view, per tab: how much of the width the left pane takes (dragged with the
     * divider), and which pane, if any, the user closed. Kept for the session only.
     */
    val splitShare = androidx.compose.runtime.mutableStateMapOf<Tab, Float>()
    val splitHidden = androidx.compose.runtime.mutableStateMapOf<Tab, PaneSide>()

    /** The second screen of a tab's split view is showing (wide layout and not closed). */
    private fun rightPaneShown(t: Tab) = wideLayout && tab == t && splitHidden[t] != PaneSide.RIGHT

    /** The piano roll is on screen: its own tab, or beside the rack on a wide screen. */
    val pianoVisible: Boolean get() = tab == Tab.PIANO || rightPaneShown(Tab.RACK)

    /** Go to the piano roll, unless it is (or can be) showing beside the rack. */
    fun showPianoRoll() {
        if (wideLayout && tab == Tab.RACK) splitHidden.remove(Tab.RACK) else tab = Tab.PIANO
    }

    /** Go to the channel rack, unless it is (or can be) showing beside the playlist. */
    fun showRack() {
        when {
            wideLayout && tab == Tab.PLAYLIST -> splitHidden.remove(Tab.PLAYLIST)
            wideLayout && tab == Tab.RACK -> splitHidden.remove(Tab.RACK)
            else -> tab = Tab.RACK
        }
    }
    var settingsOpen by mutableStateOf(false)

    private val prefs = app.getSharedPreferences("daw", android.content.Context.MODE_PRIVATE)

    /** Hear a sound when placing a step or note. Never while playing, so beats don't double up. */
    var previewOnEdit by mutableStateOf(prefs.getBoolean("preview_on_edit", true))
        private set

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

    // ---------------------------------------------------------------- output latency (Bluetooth sync)
    // Declared before init{}: the engine polling loop reads them.

    private val visualDelay = com.mobiledaw.audio.VisualDelay()
    private val latencyMeter = com.mobiledaw.audio.LatencyMeter()

    /** What the app is playing through right now (speaker, wired, or the earbuds' name). */
    var outputDevice by mutableStateOf(com.mobiledaw.audio.OutputDevice("speaker", "Phone speaker", false))
        private set
    /** Delay the phone reports for that output, in ms; null until it has been measured. */
    var measuredLatencyMs by mutableStateOf<Int?>(null)
        private set
    /** Auto: follow the measured delay. Off: only [latencyOffsetMs]. Remembered per output. */
    var latencyAuto by mutableStateOf(true)
        private set
    /** Manual ms added to the measured delay (Auto), or the whole delay (Auto off). Per output. */
    var latencyOffsetMs by mutableIntStateOf(0)
        private set

    /**
     * Extra nudge for new recordings on this output, in ms: positive puts takes later, negative
     * earlier. For when the automatic line-up is off for a phone / earbuds pair. Per output.
     */
    var recordTimingMs by mutableIntStateOf(0)
        private set

    fun changeRecordTiming(ms: Int) {
        recordTimingMs = ms.coerceIn(-RECORD_TIMING_RANGE, RECORD_TIMING_RANGE)
        saveLatency()
    }

    /** Nudges an audio clip's sound earlier (negative) or later (positive) by [ms], clip stays put. */
    fun nudgeAudioTiming(id: Long, ms: Int) = updateAudioClip(id) { com.mobiledaw.model.ClipEdit.shiftTiming(it, ms, project.bpm) }

    /** Makes a recording's total timing fix the default for new takes on the current output. */
    fun useTimingForNewTakes(clip: AudioClip) = changeRecordTiming(clip.takeTimingMs + clip.timingMs)

    /** True when Auto is on but the phone gave no reading, so [estimatedLatencyMs] stands in. */
    val latencyEstimated: Boolean get() = latencyAuto && measuredLatencyMs == null

    private val estimatedLatencyMs: Int get() = if (outputDevice.wireless) 200 else 30

    /** How far the screen runs behind the engine, in ms: what you hear vs. what is drawn. */
    val uiDelayMs: Int
        get() = ((if (latencyAuto) measuredLatencyMs ?: estimatedLatencyMs else 0) + latencyOffsetMs)
            .coerceIn(0, com.mobiledaw.audio.VisualDelay.MAX_DELAY_MS)

    fun changeLatencyAuto(on: Boolean) {
        latencyAuto = on
        // Keep the total where it was when switching, so the slider doesn't jump the playhead.
        val measured = measuredLatencyMs ?: estimatedLatencyMs
        latencyOffsetMs = if (on) (latencyOffsetMs - measured).coerceIn(LATENCY_OFFSET_MIN, LATENCY_OFFSET_MAX)
        else (latencyOffsetMs + measured).coerceIn(0, com.mobiledaw.audio.VisualDelay.MAX_DELAY_MS)
        saveLatency()
    }

    fun changeLatencyOffset(ms: Int) {
        latencyOffsetMs = if (latencyAuto) ms.coerceIn(LATENCY_OFFSET_MIN, LATENCY_OFFSET_MAX)
        else ms.coerceIn(0, com.mobiledaw.audio.VisualDelay.MAX_DELAY_MS)
        saveLatency()
    }

    private fun saveLatency() {
        val key = outputDevice.key
        prefs.edit()
            .putBoolean("latency_auto:$key", latencyAuto)
            .putInt("latency_offset:$key", latencyOffsetMs)
            .putInt("record_timing:$key", recordTimingMs)
            .apply()
    }

    private fun loadLatency() {
        val key = outputDevice.key
        latencyAuto = prefs.getBoolean("latency_auto:$key", true)
        latencyOffsetMs = prefs.getInt("latency_offset:$key", 0)
        recordTimingMs = prefs.getInt("record_timing:$key", 0)
    }

    /** Re-reads which output is in use and how late it is. Called about once a second. */
    private fun refreshOutputLatency() {
        val device = com.mobiledaw.audio.OutputDevices.describe(getApplication<Application>(), NativeEngine.outputDeviceId())
        if (device.key != outputDevice.key) {
            outputDevice = device
            latencyMeter.reset()
            measuredLatencyMs = null
            loadLatency()
        } else if (device != outputDevice) {
            outputDevice = device
        }
        // Never report less than the buffer alone adds.
        val floor = NativeEngine.bufferLatencyMs()
        val reading = NativeEngine.outputLatencyMs().let { if (it > 0 && floor > 0) maxOf(it, floor) else it }
        latencyMeter.add(reading)?.let { if (it != measuredLatencyMs) measuredLatencyMs = it }
    }

    /** Shows the engine's state right away (no delay), e.g. after a jump while stopped. */
    private fun showLive(tick: Long) {
        visualDelay.clear()
        positionTicks = tick
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
    /** Count-in shown over the playlist: 1..4 while counting, then [COUNT_IN_GO]. Null when hidden. */
    var countIn by mutableStateOf<Int?>(null)
        private set

    /** Metronome click on every beat (accented on beat 1), made in the engine. Never bounced. Remembered. */
    var metronomeOn by mutableStateOf(prefs.getBoolean("metronome", false))
        private set

    fun toggleMetronome() {
        metronomeOn = !metronomeOn
        prefs.edit().putBoolean("metronome", metronomeOn).apply()
        NativeEngine.setMetronome(metronomeOn, BEATS_PER_BAR)
    }
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
        loadLatency()
        viewModelScope.launch {
            var polls = 0
            while (isActive) {
                if (polls++ % 30 == 0) refreshOutputLatency()
                val now = System.nanoTime()
                val frame = com.mobiledaw.audio.VisualDelay.Frame(
                    now,
                    NativeEngine.positionTicks(),
                    FloatArray(NativeEngine.MAX_CHANNELS + 1).also { NativeEngine.readLevels(it) },
                    FloatArray(SCOPE_BINS).also { NativeEngine.readScope(it) },
                    FloatArray(NativeEngine.MAX_INSERTS).also { NativeEngine.readInsertLevels(it) },
                )
                // While playing, draw what is being heard now: the engine reading from uiDelayMs ago.
                // Stopped, show it live so taps and jumps answer at once.
                val shown = if (playing && uiDelayMs > 0) {
                    visualDelay.push(frame)
                    visualDelay.at(now, uiDelayMs) ?: frame
                } else {
                    visualDelay.clear()
                    frame
                }
                positionTicks = shown.tick
                levels = shown.levels
                scope = shown.scope
                trackLevels = shown.trackLevels
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
        visible = true
        // An export in progress owns the engine; the stream comes back when it is done.
        if (exporting) return
        NativeEngine.start()
        syncEngine()
    }

    fun onPause() {
        visible = false
        if (takeState != TakeState.IDLE) stopTake()
        // Leaving the app doesn't stop an export: it keeps rendering in the background.
        if (!exporting) applyPlaying(false)
        NativeEngine.stop()
        saveNow()
    }

    private var visible = true

    private fun syncEngine() {
        val p = project
        NativeEngine.setBpm(p.bpm.toFloat())
        NativeEngine.setMetronome(metronomeOn, BEATS_PER_BAR)
        NativeEngine.setMasterVolume(p.masterVolume)
        val audioSlots = SequenceCompiler.audioSlots(p, NativeEngine.MAX_CHANNELS)
        NativeEngine.setChannelCount((p.channels.size + audioSlots.size).coerceAtMost(NativeEngine.MAX_CHANNELS))
        p.channels.take(NativeEngine.MAX_CHANNELS).forEachIndexed { i, c ->
            // Built-in drums and SoundFont drum hits play a recording of their hit through the
            // sampler, like kit sounds, so every drum has the same channel settings.
            val path = soundPath(c)
            val engineInstrument = if (path != null) Instrument.SAMPLE.id else c.instrument.id
            NativeEngine.setChannel(i, engineInstrument, c.volume, c.pan, c.mute, c.solo)
            NativeEngine.setChannelClip(i, false)
            // The sampler plays a recording at its own pitch on C4. A single SoundFont drum hit is
            // recorded on its own key (e.g. 42 for a closed hat), which is also where its steps and
            // piano-roll notes sit, so shift it to sound as recorded there instead of 18 semitones low.
            val rootShift = if (path != null) samplerRootShift(c) else 0
            val transpose = (if (canTranspose(c)) c.pitchSemis else 0) + rootShift
            NativeEngine.setChannelSampler(i, c.cutItself, c.trimStart, c.trimEnd, c.envDelay, c.attack, c.hold, c.decay, c.sustain, c.release, c.tension, transpose, c.reverse)
            NativeEngine.setChannelBoost(i, sampleBoostGain(c))
            syncSynth(i, c)
            if (loadedSamples[i] != path) {
                val decoded = path?.let { decodeSample(it) }
                NativeEngine.setSample(i, decoded?.frames, decoded?.right, decoded?.sampleRate ?: 0f)
                loadedSamples[i] = path
            }
            syncExtraEngine(i, c)
        }
        // Audio clips each get a hidden sample slot after the channels.
        audioSlots.forEach { (slot, clip) ->
            NativeEngine.setChannel(slot, Instrument.SAMPLE.id, clip.gain, 0f, clip.muted, false)
            val (trimA, trimB) = com.mobiledaw.model.ClipEdit.trimFractions(clip)
            // A clip that keeps its tempo plays a copy rendered at the new pitch, at normal speed.
            val enginePitch = if (clip.keepsTempo) 0 else clip.pitchSemis
            NativeEngine.setChannelSampler(slot, false, trimA, trimB, 0f, 0f, 0f, 0f, 1f, 0.005f, 0f, enginePitch, clip.reverse)
            NativeEngine.setChannelBoost(slot, 1f)
            NativeEngine.setChannelClip(slot, true)
            sentPatches[slot] = null
            val key = stretchKey(clip)
            if (loadedSamples[slot] != key) {
                val stretched = if (key != clip.path) stretchedSample(clip, key) else null
                // Until the shifted copy is ready, the clip plays its original sound (in time).
                val (decoded, loaded) = if (stretched != null) stretched to key else decodeSample(clip.path) to clip.path
                if (loadedSamples[slot] != loaded) {
                    NativeEngine.setSample(slot, decoded?.frames, decoded?.right, decoded?.sampleRate ?: 0f)
                    loadedSamples[slot] = loaded
                }
            }
        }
        // While exporting, the engine plays the export's sequence; it gets this one back afterwards.
        if (exporting) {
            syncMixer()
            return
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
        when {
            takeState != TakeState.IDLE -> stopTake()
            playing -> pause()
            else -> applyPlaying(true)
        }
    }

    /** Pause: stops where you are, so the next play carries on from there. Stop goes back instead. */
    private fun pause() {
        val tick = positionTicks.coerceIn(0L, maxOf(0, loopTicks - TICKS_PER_STEP).toLong())
        applyPlaying(false)
        NativeEngine.seek(tick)
        showLive(tick)
    }

    /** The "stop all sound" button: stops playback (and a take in progress) and cuts every sound. */
    fun stopAllSound() {
        if (takeState != TakeState.IDLE) stopTake()
        val wasPlaying = playing
        playing = false
        NativeEngine.panic()
        if (wasPlaying) returnToPlayFrom()
    }

    /**
     * Where play starts: the spot you last scrubbed or tapped to (0 if you haven't). Stopping goes
     * back there, FL-style, so play / stop / play keeps starting from your spot, not the beginning.
     */
    private var playFromTick = 0

    private fun returnToPlayFrom() {
        val tick = playFromTick.coerceIn(0, maxOf(0, loopTicks - TICKS_PER_STEP))
        NativeEngine.seek(tick.toLong())
        showLive(tick.toLong())
    }

    /** Moves the playhead to a fraction (0..1) of the pattern or song, snapped to a step. */
    fun seekTo(fraction: Float) {
        val raw = (fraction.coerceIn(0f, 1f) * loopTicks).toInt()
        val tick = ((raw / TICKS_PER_STEP) * TICKS_PER_STEP).coerceIn(0, maxOf(0, loopTicks - TICKS_PER_STEP))
        NativeEngine.seek(tick.toLong())
        playFromTick = tick
        // Playing: hold the playhead here while the earbuds finish what was already sent, then go on in sync.
        showLive(tick.toLong())
    }

    /** Moves the song playhead to [tick] (snapped to a step), e.g. from a tap on the playlist ruler. */
    fun seekToTick(tick: Int) {
        if (loopTicks <= 0) return
        seekTo((tick + 0.5f) / loopTicks)
    }

    private fun applyPlaying(value: Boolean) {
        playing = value
        NativeEngine.setPlaying(value)
        visualDelay.clear()
        if (value) playStartNanos = System.nanoTime()
    }

    /** When playback last started (System.nanoTime), for lining recordings up with what was heard. */
    private var playStartNanos = 0L

    fun switchMode(value: PlayMode) {
        if (mode == value) return
        mode = value
        playFromTick = 0
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

    /**
     * Replaces a channel's steps with one hit every [every] steps (1 = every step) across all
     * the bars of the selected pattern. Can be undone from the Undo bar.
     */
    fun fillSteps(channel: Channel, every: Int) {
        val before = project
        val pattern = selectedPattern
        val pitch = channel.stepPitch
        val steps = pattern.lengthTicks / TICKS_PER_STEP
        updateNotes(channel.id) { (0 until steps step every.coerceAtLeast(1)).map { Note(it * TICKS_PER_STEP, TICKS_PER_STEP, pitch) } }
        if (project != before) {
            undoSnapshot = before
            undoLabel = if (every <= 1) "Filled every step" else "Filled every $every steps"
        }
    }

    /** Removes all of a channel's steps in the selected pattern. Can be undone from the Undo bar. */
    fun clearSteps(channel: Channel) {
        val before = project
        updateNotes(channel.id) { emptyList() }
        if (project != before) {
            undoSnapshot = before
            undoLabel = "Cleared ${channel.name}"
        }
    }

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
            val pitch = channel.stepPitch
            updateNotes(channel.id) { it + Note(tick, TICKS_PER_STEP, pitch) }
            editPreview(channel, pitch)
        }
    }

    /** Preview used while editing: skipped during playback (and when switched off in Settings). */
    fun editPreview(channel: Channel, pitch: Int = channel.stepPitch) {
        if (!playing && previewOnEdit) preview(channel, pitch)
    }

    fun preview(channel: Channel, pitch: Int = channel.stepPitch) {
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

    /**
     * The sound a channel plays through the sampler: an imported file, or a recording of a
     * built-in drum (see [builtInDrumPath]). Null for synths and the other engines.
     */
    fun soundPath(c: Channel): String? = when {
        c.instrument == Instrument.SAMPLE -> c.sample
        c.instrument.isBuiltInDrum -> builtInDrumPath(c.instrument)
        c.instrument == Instrument.SOUNDFONT && c.pitch != null -> soundFontHitPath(c)
        else -> null
    }

    /** Semitones that put a recorded hit back on its own key: SoundFont single hits play as recorded on their key. */
    private fun samplerRootShift(c: Channel): Int =
        if (c.instrument == Instrument.SOUNDFONT && c.pitch != null) 60 - c.pitch else 0

    /** A whole SoundFont drum kit on one channel: each piano-roll row plays a different drum. */
    fun isWholeDrumKit(c: Channel): Boolean = !canTranspose(c)

    /**
     * Whether Channel settings' Pitch applies. Everything but a whole SoundFont drum kit, where
     * shifting the notes would swap which drum each key plays.
     */
    fun canTranspose(c: Channel): Boolean {
        if (c.instrument != Instrument.SOUNDFONT || c.pitch != null) return true
        val source = c.source ?: com.mobiledaw.sound.SoundFontLibrary.BUILTIN
        return sounds.soundFonts.presets(source).firstOrNull { it.index == c.variant }?.isDrumKit != true
    }

    /**
     * A single drum hit from a SoundFont kit is recorded once (when its SoundFont is loaded) and
     * then played like an imported sound. Null until the SoundFont is ready, or if the hit is silent.
     */
    private fun soundFontHitPath(c: Channel): String? {
        val source = c.source ?: com.mobiledaw.sound.SoundFontLibrary.BUILTIN
        val key = c.pitch ?: return null
        val bank = sounds.soundFonts.loadedBank(source) ?: return null
        val path = "$BUILT_IN_PREFIX$source#${c.variant}:$key"
        soundFontHits[path] = Triple(bank, c.variant, key)
        return path.takeIf { decodeSample(it) != null }
    }

    private fun builtInDrumPath(instrument: Instrument) = "$BUILT_IN_PREFIX${instrument.name.lowercase()}"

    private fun decodeSample(path: String): WavDecoder.Decoded? =
        sampleCache[path] ?: run {
            soundFontHits[path]?.let { (bank, variant, key) ->
                val frames = NativeEngine.renderSoundFontHit(bank, variant, key, BUILT_IN_RATE) ?: return null
                return WavDecoder.Decoded(frames, BUILT_IN_RATE).also { sampleCache[path] = it }
            }
            if (path.startsWith(BUILT_IN_PREFIX)) {
                val instrument = Instrument.entries.firstOrNull { builtInDrumPath(it) == path && it.isBuiltInDrum } ?: return null
                return WavDecoder.Decoded(NativeEngine.renderBuiltInDrum(instrument.id, BUILT_IN_RATE), BUILT_IN_RATE)
                    .also { sampleCache[path] = it }
            }
            // Stereo sounds stay stereo all the way to the engine (folding to mono narrows and dulls loops).
            WavDecoder.decode(kits.file(path), maxSecondsFor(path), stereo = true)?.also {
                sampleCache[path] = it
                trimSampleCache()
            }
        }

    /** Which sound an audio clip's engine slot plays: its file, or a pitch-shifted copy of it. */
    private fun stretchKey(clip: AudioClip): String =
        if (clip.keepsTempo) "${clip.path}#stretch${clip.stretch.id}:${clip.pitchSemis}" else clip.path

    /** True while the clip's new pitch is being rendered (it plays its original sound meanwhile). */
    fun isStretching(clip: AudioClip): Boolean = stretchKey(clip) in stretchingKeys

    /**
     * The clip's sound shifted to its pitch at the same length, or null while it's being made.
     * Rendering runs in the background (a few seconds for a long take) and is kept on disk, so it
     * only happens once per clip and pitch; syncEngine() runs again when it's ready.
     */
    private fun stretchedSample(clip: AudioClip, key: String): WavDecoder.Decoded? {
        sampleCache[key]?.let { return it }
        if (key in stretchPending || key in stretchFailed) return null
        stretchPending += key
        stretchingKeys = stretchPending.toSet()
        val src = kits.file(clip.path)
        val semis = clip.pitchSemis
        val mode = clip.stretch.id
        val maxSeconds = maxSecondsFor(clip.path)
        val cacheDir = java.io.File(getApplication<Application>().cacheDir, "stretched")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val file = java.io.File(cacheDir, "%08x.f32".format(
                        "${clip.path}|${src.length()}|${src.lastModified()}|$mode|$semis".hashCode()))
                    StretchCache.read(file) ?: WavDecoder.decode(src, maxSeconds)?.let { d ->
                        WavDecoder.Decoded(NativeEngine.shiftKeepLength(d.frames, d.sampleRate, semis.toFloat(), mode), d.sampleRate)
                            .also { StretchCache.write(file, it) }
                    }
                }.getOrNull()
            }
            stretchPending -= key
            stretchingKeys = stretchPending.toSet()
            if (result == null) {
                stretchFailed += key
                return@launch
            }
            sampleCache[key] = result
            trimSampleCache()
            syncEngine()
        }
        return null
    }

    /** Waits until every clip's new pitch has been rendered (before an export). */
    private suspend fun awaitStretches() {
        while (stretchPending.isNotEmpty()) delay(50)
    }

    private fun maxSecondsFor(path: String): Int {
        val long = path.startsWith(RECORDINGS_DIR) || path.startsWith(com.mobiledaw.model.AudioImport.DIR + "/")
        return if (long) WavDecoder.MAX_RECORDING_SECONDS else 60
    }

    /**
     * Keeps decoded sounds under ~24M frames (about 100 MB of floats): a few long imported loops
     * would otherwise fill memory. The engine keeps its own copy, so dropping one here is safe.
     */
    private fun trimSampleCache() {
        var total = sampleCache.values.sumOf { it.frames.size.toLong() * it.channels }
        val iter = sampleCache.entries.iterator()
        while (total > MAX_CACHED_FRAMES && sampleCache.size > 1 && iter.hasNext()) {
            total -= iter.next().value.let { it.frames.size.toLong() * it.channels }
            iter.remove()
        }
    }

    /** Peak values (0..1) across a sample, for drawing its waveform. */
    fun samplePeaks(path: String, bins: Int): FloatArray {
        val decoded = decodeSample(path) ?: return FloatArray(0)
        val frames = decoded.frames
        val right = decoded.right
        if (frames.isEmpty()) return FloatArray(0)
        val out = FloatArray(bins)
        val per = maxOf(1, frames.size / bins)
        for (b in 0 until bins) {
            var peak = 0f
            val from = b * frames.size / bins
            for (i in from until minOf(frames.size, from + per)) {
                peak = maxOf(peak, kotlin.math.abs(frames[i]))
                if (right != null) peak = maxOf(peak, kotlin.math.abs(right[i]))
            }
            out[b] = peak
        }
        val max = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] /= max
        return out
    }

    /** The loudest absolute sample value in a sound (0..1+), or 0 if it can't be read. */
    fun samplePeak(path: String): Float = samplePeakCache[path] ?: run {
        val decoded = decodeSample(path) ?: return 0f
        var peak = 0f
        for (x in decoded.frames) peak = maxOf(peak, kotlin.math.abs(x))
        decoded.right?.let { r -> for (x in r) peak = maxOf(peak, kotlin.math.abs(x)) }
        samplePeakCache[path] = peak
        peak
    }

    /**
     * Linear gain from a channel's Boost and Normalize settings (1 = untouched). Normalize needs a
     * recorded sound to measure, so synths only get the Boost part.
     */
    fun sampleBoostGain(c: Channel): Float {
        var g = Math.pow(10.0, c.boostDb.coerceIn(0f, BOOST_MAX_DB) / 20.0).toFloat()
        val path = soundPath(c) ?: return g
        if (c.normalize) {
            val peak = samplePeak(path)
            // Up to +24 dB, so a near-silent file doesn't turn into pure noise.
            if (peak > 0f) g *= (1f / peak).coerceIn(0.1f, 16f)
        }
        return g
    }

    fun sampleSeconds(path: String): Float =
        decodeSample(path)?.let { it.frames.size / it.sampleRate } ?: 0f

    /** Plays a kit sound without adding it to the project. */
    fun audition(path: String, name: String = java.io.File(path).nameWithoutExtension) {
        val decoded = decodeSample(path) ?: return
        auditionDecoded(decoded, name)
    }

    /** The library preview that is playing, for the player bar under the sound lists. */
    class LibraryPreview(val name: String, val durationMs: Long, val startedAt: Long)

    var libraryPreview by mutableStateOf<LibraryPreview?>(null)
        private set
    private var libraryPreviewEnd: Job? = null

    private fun startLibraryPreview(name: String, durationMs: Long) {
        val p = LibraryPreview(name, durationMs.coerceAtLeast(100), android.os.SystemClock.uptimeMillis())
        libraryPreview = p
        libraryPreviewEnd?.cancel()
        libraryPreviewEnd = viewModelScope.launch {
            delay(p.durationMs)
            if (libraryPreview === p) libraryPreview = null
        }
    }

    /** The stop button: silences any library preview at once. */
    fun stopAudition() {
        NativeEngine.stopAudition()
        libraryPreviewEnd?.cancel()
        libraryPreview = null
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

    // ---------------------------------------------------------------- sound library (extra engines)

    private fun syncExtraEngine(i: Int, c: Channel) {
        when (c.instrument) {
            Instrument.SOUNDFONT -> {
                val source = c.source ?: com.mobiledaw.sound.SoundFontLibrary.BUILTIN
                val bank = sounds.soundFonts.loadedBank(source)
                if (bank == null) loadSoundFont(source)
                NativeEngine.setChannelEngine(i, c.instrument.id, c.variant, bank ?: -1)
                // A single hit from a drum kit follows the piano roll like a sample does.
                NativeEngine.setChannelDrumKey(i, c.pitch ?: -1)
            }
            Instrument.MACRO, Instrument.MODELED -> NativeEngine.setChannelEngine(i, c.instrument.id, c.variant, -1)
            Instrument.MULTISAMPLE -> {
                NativeEngine.setChannelEngine(i, c.instrument.id, 0, -1)
                val source = c.source
                if (loadedSources[i] != source) {
                    loadedSources[i] = source
                    viewModelScope.launch {
                        val loaded = withContext(Dispatchers.IO) { source?.let { runCatching { sounds.sfz.load(it) }.getOrNull() } }
                        if (loadedSources[i] == source) sounds.sfz.sendTo(i, loaded)
                    }
                }
            }
            else -> loadedSources[i] = null
        }
    }

    /** Loads a SoundFont off the main thread, then re-syncs the engine and runs [then]. */
    fun loadSoundFont(source: String, then: (() -> Unit)? = null) {
        if (sounds.soundFonts.loadedBank(source) != null) {
            then?.invoke()
            return
        }
        if (!pendingFontLoads.add(source)) return
        loadingSoundFont = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { sounds.soundFonts.load(source) } }
            pendingFontLoads.remove(source)
            loadingSoundFont = pendingFontLoads.isNotEmpty()
            syncEngine()
            sounds.changed()
            then?.invoke()
        }
    }

    /** Plays a sound from the library without changing the project. */
    fun auditionChoice(choice: com.mobiledaw.sound.SoundChoice) {
        when (choice) {
            is com.mobiledaw.sound.SoundChoice.Sample -> audition(choice.path, choice.name)
            is com.mobiledaw.sound.SoundChoice.Engine -> {
                val pitch = choice.pitch ?: 60
                // A preset preview is a half-second note plus its tail.
                startLibraryPreview(choice.name, ENGINE_PREVIEW_MS)
                when (choice.instrument) {
                    Instrument.SOUNDFONT -> {
                        val source = choice.source ?: com.mobiledaw.sound.SoundFontLibrary.BUILTIN
                        val bank = sounds.soundFonts.loadedBank(source)
                        if (bank == null) loadSoundFont(source) { auditionChoice(choice) }
                        else NativeEngine.auditionEngine(choice.instrument.id, choice.variant, bank, pitch, 100)
                    }
                    Instrument.MULTISAMPLE -> viewModelScope.launch {
                        val loaded = withContext(Dispatchers.IO) { choice.source?.let { runCatching { sounds.sfz.load(it) }.getOrNull() } }
                        sounds.sfz.sendTo(NativeEngine.AUDITION_SLOT, loaded)
                        NativeEngine.auditionEngine(choice.instrument.id, 0, -1, pitch, 100)
                    }
                    else -> NativeEngine.auditionEngine(choice.instrument.id, choice.variant, -1, pitch, 100)
                }
            }
        }
    }

    /** Plays a decoded sound (an online preview) in the audition slot. */
    fun auditionDecoded(decoded: WavDecoder.Decoded, name: String) {
        NativeEngine.setSample(NativeEngine.AUDITION_SLOT, decoded.frames, decoded.right, decoded.sampleRate)
        NativeEngine.audition()
        startLibraryPreview(name, (decoded.frames.size * 1000L / decoded.sampleRate.coerceAtLeast(1f)).toLong())
    }

    /** Adds the sound as a new channel ([channelId] null) or gives it to an existing channel. */
    fun applyChoice(channelId: Long?, choice: com.mobiledaw.sound.SoundChoice) {
        when (choice) {
            is com.mobiledaw.sound.SoundChoice.Sample -> {
                val sound = KitLibrary.Sound(choice.path, choice.name, choice.group)
                if (channelId == null) addSampleChannel(sound) else setChannelSound(channelId, sound)
            }
            is com.mobiledaw.sound.SoundChoice.Engine -> {
                if (channelId == null) {
                    if (project.channels.size >= NativeEngine.MAX_CHANNELS) return
                    val id = project.nextId()
                    update {
                        it.copy(
                            channels = it.channels + Channel(
                                id, choice.name, choice.instrument,
                                variant = choice.variant, source = choice.source, pitch = choice.pitch,
                            ),
                        )
                    }
                    selectedChannelId = id
                } else {
                    // Keep the channel's own name, as with kit sounds.
                    updateChannel(channelId) {
                        it.copy(instrument = choice.instrument, variant = choice.variant, source = choice.source, pitch = choice.pitch, sample = null)
                    }
                    project.channels.firstOrNull { it.id == channelId }?.let { preview(it) }
                }
            }
        }
    }

    /** What an engine channel plays, for labels ("GeneralUser GS · Grand Piano"). */
    fun soundLabel(c: Channel): String? = sounds.version.let { _ -> soundLabelNow(c) }

    private fun soundLabelNow(c: Channel): String? = when (c.instrument) {
        Instrument.SOUNDFONT -> {
            val source = c.source ?: com.mobiledaw.sound.SoundFontLibrary.BUILTIN
            val preset = sounds.soundFonts.presets(source).firstOrNull { it.index == c.variant }
            val drum = c.pitch?.let { com.mobiledaw.sound.SoundFontLibrary.GM_DRUMS[it] }
            listOfNotNull(preset?.name, drum).joinToString(" · ").ifEmpty { sounds.soundFonts.nameOf(source) }
        }
        Instrument.MACRO -> sounds.macroPresets.getOrNull(c.variant)?.name
        Instrument.MODELED -> sounds.modeledPresets.getOrNull(c.variant)?.name
        Instrument.MULTISAMPLE -> sounds.sfz.nameOf(c.source)
        else -> null
    }

    fun importSoundFont(uri: android.net.Uri) {
        importing = true
        importMessage = null
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) { runCatching { sounds.soundFonts.import(uri) }.getOrNull() }
            importing = false
            importMessage = if (source == null) "That isn't a .sf2 or .sf3 file." else "Imported \"${sounds.soundFonts.nameOf(source)}\"."
            sounds.changed()
        }
    }

    fun importSfz(uri: android.net.Uri) {
        importing = true
        importMessage = null
        viewModelScope.launch {
            val pack = withContext(Dispatchers.IO) { runCatching { sounds.sfz.importZip(uri) }.getOrNull() }
            importing = false
            importMessage = if (pack == null) "No .sfz instrument found in that .zip." else "Imported \"$pack\"."
            sounds.changed()
        }
    }

    /** After an online sound is saved into a kit. */
    fun onKitsChanged() {
        kitsVersion++
        sounds.changed()
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
            chordEdit = pianoCheckpoint(channel.id, pattern.id)
        }
        if (mode != PlayMode.PATTERN) switchMode(PlayMode.PATTERN)
        generateChords()
    }

    /** Keep = leave the chords in the pattern; otherwise put back what was there. */
    fun closeChordGen(keep: Boolean) {
        val s = chordGen ?: return
        chordAudition?.cancel()
        chordGen = null
        val edit = chordEdit
        chordEdit = null
        if (!keep) {
            updatePattern(s.patternId) { pat ->
                pat.copy(bars = s.originalBars, notes = pat.notes + (s.channelId to s.originalNotes))
            }
        }
        // Cancelled (or nothing changed): the chord session leaves no undo step behind.
        val i = if (edit == null) -1 else pianoUndo.indexOfLast { it === edit }
        if (i >= 0 && (!keep || pianoUndo[i].beforeNotes == pianoUndo[i].afterNotes && pianoUndo[i].beforeBars == pianoUndo[i].afterBars)) {
            while (pianoUndo.size > i) pianoUndo.removeAt(pianoUndo.lastIndex)
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
        // Keep the chord session's undo step pointing at the chords now in the pattern. Any taps on
        // the grid since were overwritten by the chords, so their steps go too.
        val edit = chordEdit ?: return
        val i = pianoUndo.indexOfLast { it === edit }
        if (i < 0) { chordEdit = null; return }
        while (pianoUndo.size > i + 1) pianoUndo.removeAt(pianoUndo.lastIndex)
        val (now, nowBars) = noteState(edit.patternId, edit.channelId) ?: return
        val sealed = edit.copy(afterNotes = now, afterBars = nowBars)
        pianoUndo[i] = sealed
        chordEdit = sealed
        pianoRedo.clear()
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
        pianoCheckpoint(channelId)
        updateNotes(channelId) { it + note }
        pianoSealTop()
        project.channels.firstOrNull { it.id == channelId }?.let { editPreview(it, note.pitch) }
    }

    /** Moves/resizes a note. A whole drag is one undo step: call [beginNoteGesture] when it starts. */
    fun replaceNote(channelId: Long, index: Int, note: Note) {
        if (gesturePending) {
            gesturePending = false
            pianoCheckpoint(channelId)
        }
        updateNotes(channelId) { list -> list.mapIndexed { i, n -> if (i == index) note else n } }
        pianoSealTop()
    }

    fun deleteNote(channelId: Long, index: Int) {
        pianoCheckpoint(channelId)
        updateNotes(channelId) { list -> list.filterIndexed { i, _ -> i != index } }
        pianoSealTop()
    }

    // ---------------------------------------------------------------- piano roll tools

    /** A short message shown over the piano roll for a moment ("Copied 12 notes"). */
    var pianoNotice by mutableStateOf<String?>(null)
        private set
    private var noticeJob: Job? = null

    private fun showPianoNotice(text: String) {
        pianoNotice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(2500)
            pianoNotice = null
        }
    }

    /** Notes copied from one channel's piano roll, ready to paste into another. */
    class NoteClipboard(val fromName: String, val notes: List<Note>, val drumPitch: Int?)

    var noteClipboard by mutableStateOf<NoteClipboard?>(null)
        private set

    /** One-shot drums (kits, built-ins, single SoundFont hits) play their sound as is on [stepPitch]. */
    private fun isOneShotDrum(c: Channel) =
        c.instrument.isDrum || (c.instrument == Instrument.SOUNDFONT && c.pitch != null)

    /** Copies every note of the selected channel in this pattern. */
    fun copyNotes() {
        val ch = selectedChannel ?: return
        val notes = selectedPattern.notesFor(ch.id).filter { it.start < selectedPattern.lengthTicks }
        if (notes.isEmpty()) {
            showPianoNotice("No notes to copy on ${ch.name}")
            return
        }
        noteClipboard = NoteClipboard(ch.name, notes, if (isOneShotDrum(ch)) ch.stepPitch else null)
        showPianoNotice("Copied ${notes.size} note${if (notes.size == 1) "" else "s"} from ${ch.name}")
    }

    /**
     * Pastes the copied notes into the selected channel, on top of what it already has. Drum to
     * drum keeps the beat on the new drum's own note; the pattern grows if the notes need more bars.
     */
    fun pasteNotes() {
        val ch = selectedChannel ?: return
        val clip = noteClipboard ?: return
        val shift = if (clip.drumPitch != null && isOneShotDrum(ch)) ch.stepPitch - clip.drumPitch else 0
        val maxTicks = com.mobiledaw.model.MAX_BARS * TICKS_PER_BAR
        val incoming = clip.notes
            .filter { it.start < maxTicks }
            .map { it.copy(pitch = (it.pitch + shift).coerceIn(0, 127), length = minOf(it.length, maxTicks - it.start)) }
        val existing = selectedPattern.notesFor(ch.id)
        val added = incoming.filter { it !in existing }
        if (added.isEmpty()) {
            showPianoNotice("${ch.name} already has these notes")
            return
        }
        val neededBars = (added.maxOf { it.start + it.length } + TICKS_PER_BAR - 1) / TICKS_PER_BAR
        pianoCheckpoint(ch.id)
        updatePattern(selectedPatternId) { pat ->
            pat.copy(
                bars = maxOf(pat.bars, neededBars.coerceAtMost(com.mobiledaw.model.MAX_BARS)),
                notes = pat.notes + (ch.id to (pat.notesFor(ch.id) + added)),
            )
        }
        pianoSealTop()
        showPianoNotice("Pasted ${added.size} note${if (added.size == 1) "" else "s"} into ${ch.name}")
    }

    /**
     * "Loop bars": copies the 4-bar block holding the channel's last notes into the next 4 bars.
     * Notes in bars 1–2 copy bars 1–4 into 5–8; pressing again copies 5–8 into 9–12. The pattern
     * grows when the copy needs more bars (up to 16).
     */
    fun loopBars() {
        val ch = selectedChannel ?: return
        val notes = selectedPattern.notesFor(ch.id).filter { it.start < selectedPattern.lengthTicks }
        if (notes.isEmpty()) {
            showPianoNotice("Place some notes first, then loop them")
            return
        }
        val block = LOOP_BLOCK_BARS * TICKS_PER_BAR
        val from = (notes.maxOf { it.start } / block) * block
        val to = from + block
        val end = to + block
        val neededBars = end / TICKS_PER_BAR
        if (neededBars > com.mobiledaw.model.MAX_BARS) {
            showPianoNotice("No room: a pattern holds up to ${com.mobiledaw.model.MAX_BARS} bars")
            return
        }
        val copies = notes.filter { it.start in from until to }
            .map { it.copy(start = it.start + block, length = minOf(it.length, end - (it.start + block))) }
        pianoCheckpoint(ch.id)
        updatePattern(selectedPatternId) { pat ->
            val kept = pat.notesFor(ch.id).filter { it.start !in to until end }
            pat.copy(bars = maxOf(pat.bars, neededBars), notes = pat.notes + (ch.id to (kept + copies)))
        }
        pianoSealTop()
        val bar = { t: Int -> t / TICKS_PER_BAR }
        showPianoNotice("Bars ${bar(from) + 1}–${bar(to)} looped into bars ${bar(to) + 1}–${bar(end)}")
    }

    // ---------------------------------------------------------------- piano roll undo / redo

    /** One piano-roll edit: a channel's notes (and the pattern length) before and after it. */
    data class NoteEdit(
        val patternId: Long,
        val channelId: Long,
        val beforeNotes: List<Note>,
        val beforeBars: Int,
        val afterNotes: List<Note>,
        val afterBars: Int,
    )

    private val pianoUndo = mutableStateListOf<NoteEdit>()
    private val pianoRedo = mutableStateListOf<NoteEdit>()
    private var gesturePending = false
    /** The undo step the open Chords panel keeps up to date (the whole chord session is one step). */
    private var chordEdit: NoteEdit? = null

    private fun noteState(patternId: Long, channelId: Long): Pair<List<Note>, Int>? {
        if (project.channels.none { it.id == channelId }) return null
        val pat = project.patterns.firstOrNull { it.id == patternId } ?: return null
        return pat.notesFor(channelId) to pat.bars
    }

    /** An edit still lines up with the project (nothing else, like the rack, changed those notes since). */
    private fun NoteEdit.isCurrentAfter() = noteState(patternId, channelId) == (afterNotes to afterBars)
    private fun NoteEdit.isCurrentBefore() = noteState(patternId, channelId) == (beforeNotes to beforeBars)

    val canUndo: Boolean
        get() = chordGen != null || pianoUndo.lastOrNull()?.isCurrentAfter() == true

    val canRedo: Boolean
        get() = chordGen == null && pianoRedo.lastOrNull()?.isCurrentBefore() == true

    /** Marks the start of a note drag; the first change it makes records one undo step. */
    fun beginNoteGesture() {
        gesturePending = true
    }

    private fun pianoCheckpoint(channelId: Long, patternId: Long = selectedPatternId): NoteEdit? {
        val (notes, bars) = noteState(patternId, channelId) ?: return null
        val edit = NoteEdit(patternId, channelId, notes, bars, notes, bars)
        pianoUndo.add(edit)
        while (pianoUndo.size > MAX_PIANO_UNDO) pianoUndo.removeAt(0)
        pianoRedo.clear()
        return edit
    }

    /** Records where the latest edit ended up. */
    private fun pianoSealTop() {
        val i = pianoUndo.lastIndex
        if (i < 0) return
        val e = pianoUndo[i]
        val (notes, bars) = noteState(e.patternId, e.channelId) ?: return
        pianoUndo[i] = e.copy(afterNotes = notes, afterBars = bars)
    }

    private fun applyNoteState(patternId: Long, channelId: Long, notes: List<Note>, bars: Int) {
        updatePattern(patternId) { pat -> pat.copy(bars = bars, notes = pat.notes + (channelId to notes)) }
        if (selectedPatternId != patternId) selectPattern(patternId)
        if (selectedChannelId != channelId) selectedChannelId = channelId
    }

    /** Undoes the last piano-roll edit. With the Chords panel open, takes the chords back out. */
    fun undoNoteEdit() {
        if (chordGen != null) {
            closeChordGen(keep = false)
            return
        }
        val e = pianoUndo.lastOrNull() ?: return
        if (!e.isCurrentAfter()) {
            // Those notes were changed somewhere else since; the old steps no longer apply.
            pianoUndo.clear()
            pianoRedo.clear()
            return
        }
        pianoUndo.removeAt(pianoUndo.lastIndex)
        applyNoteState(e.patternId, e.channelId, e.beforeNotes, e.beforeBars)
        pianoRedo.add(e)
    }

    /** Puts back the last undone piano-roll edit. */
    fun redoNoteEdit() {
        if (chordGen != null) return
        val e = pianoRedo.lastOrNull() ?: return
        if (!e.isCurrentBefore()) {
            pianoRedo.clear()
            return
        }
        pianoRedo.removeAt(pianoRedo.lastIndex)
        applyNoteState(e.patternId, e.channelId, e.afterNotes, e.afterBars)
        pianoUndo.add(e)
    }

    private fun clearPianoHistory() {
        pianoUndo.clear()
        pianoRedo.clear()
        chordEdit = null
        gesturePending = false
    }

    // ---------------------------------------------------------------- playlist

    /** The audio clip under a point on the playlist, if any. */
    fun audioClipAt(lane: Int, tick: Int): AudioClip? =
        project.audioClips.lastOrNull { it.lane == lane && tick >= it.startTick && tick < it.endTick(project.bpm) }

    fun deleteAudioClip(id: Long) {
        val before = project
        update { p -> p.copy(audioClips = p.audioClips.filter { it.id != id }) }
        undoSnapshot = before
        undoLabel = "Deleted audio clip"
    }

    // ---------------------------------------------------------------- playlist editing

    /** Playlist tool, like FL's pencil / slice / mute tools. */
    var playlistTool by mutableStateOf(PlaylistTool.PENCIL)

    /** Grid that moving, resizing, cutting and placing snap to, in ticks (0 = no snap). */
    var playlistSnap by mutableIntStateOf(TICKS_PER_BEAT)

    fun snapTick(tick: Int): Int {
        val g = playlistSnap
        if (g <= 1) return tick.coerceAtLeast(0)
        return (Math.round(tick.toFloat() / g) * g).coerceAtLeast(0)
    }

    /** Index of the pattern clip at [tick] on [lane], topmost (last drawn) first. */
    fun patternClipAt(lane: Int, tick: Int): Int =
        project.clips.indexOfLast { it.lane == lane && tick >= it.startTick && tick < project.clipEnd(it) }

    /** Pencil on an empty spot: places the selected pattern there, on the snap grid. */
    fun addPatternClip(lane: Int, tick: Int) {
        val g = playlistSnap.coerceAtLeast(1)
        val start = (tick / g) * g
        update { it.copy(clips = it.clips + Clip(lane, 0, selectedPatternId).movedTo(lane, start)) }
    }

    fun deletePatternClip(index: Int) {
        if (index !in project.clips.indices) return
        val before = project
        update { p -> p.copy(clips = p.clips.filterIndexed { i, _ -> i != index }) }
        undoSnapshot = before
        undoLabel = "Deleted pattern clip"
    }

    fun setPatternClip(index: Int, clip: Clip) {
        if (index !in project.clips.indices) return
        update { p -> p.copy(clips = p.clips.mapIndexed { i, c -> if (i == index) clip else c }) }
    }

    fun updateAudioClip(id: Long, transform: (AudioClip) -> AudioClip) {
        update { p -> p.copy(audioClips = p.audioClips.map { if (it.id == id) transform(it) else it }) }
    }

    /** Cut tool: splits the clip under [tick] (snapped) in two. Returns false when nothing was cut. */
    fun cutAt(lane: Int, tick: Int): Boolean {
        val p = project
        val at = snapTick(tick)
        val audio = audioClipAt(lane, tick)
        if (audio != null) {
            val (a, b) = com.mobiledaw.model.ClipEdit.split(audio, at, p.bpm, p.nextId())
                ?: com.mobiledaw.model.ClipEdit.split(audio, tick, p.bpm, p.nextId())
                ?: return false
            update { q -> q.copy(audioClips = q.audioClips.flatMap { if (it.id == audio.id) listOf(a, b) else listOf(it) }) }
            return true
        }
        val index = patternClipAt(lane, tick)
        if (index < 0) return false
        val clip = p.clips[index]
        val (a, b) = com.mobiledaw.model.ClipEdit.split(p, clip, at)
            ?: com.mobiledaw.model.ClipEdit.split(p, clip, tick)
            ?: return false
        update { q -> q.copy(clips = q.clips.flatMapIndexed { i, c -> if (i == index) listOf(a, b) else listOf(c) }) }
        return true
    }

    // ---------------------------------------------------------------- playlist rows, zoom, follow

    /** Adds an empty row at the bottom of the playlist. */
    fun addPlaylistRow() = update { p ->
        p.copy(playlistRows = (p.rowCount + 1).coerceAtMost(com.mobiledaw.model.MAX_PLAYLIST_ROWS))
    }

    /** Puts an empty row above [lane]; everything from that row down moves one row lower. */
    fun insertPlaylistRow(lane: Int) = update { p ->
        if (p.rowCount >= com.mobiledaw.model.MAX_PLAYLIST_ROWS) return@update p
        p.copy(
            playlistRows = p.rowCount + 1,
            clips = p.clips.map { if (it.lane >= lane) it.copy(lane = it.lane + 1) else it },
            audioClips = p.audioClips.map { if (it.lane >= lane) it.copy(lane = it.lane + 1) else it },
        )
    }

    /** Removes row [lane] and what is on it (can be undone); the rows below move up. */
    fun deletePlaylistRow(lane: Int) {
        if (project.rowCount <= 1) return
        val before = project
        val hadClips = project.clips.any { it.lane == lane } || project.audioClips.any { it.lane == lane }
        update { p ->
            p.copy(
                playlistRows = p.rowCount - 1,
                clips = p.clips.filter { it.lane != lane }.map { if (it.lane > lane) it.copy(lane = it.lane - 1) else it },
                audioClips = p.audioClips.filter { it.lane != lane }.map { if (it.lane > lane) it.copy(lane = it.lane - 1) else it },
            )
        }
        if (hadClips) {
            undoSnapshot = before
            undoLabel = "Deleted row ${lane + 1}"
        }
    }

    /** Width of one bar on the playlist, in dp (pinch or the − / + buttons). Remembered. */
    var playlistBarDp by mutableFloatStateOf(prefs.getFloat("playlist_bar_dp", PLAYLIST_BAR_DP).coerceIn(PLAYLIST_BAR_MIN_DP, PLAYLIST_BAR_MAX_DP))
        private set

    fun changePlaylistZoom(barDp: Float) {
        playlistBarDp = barDp.coerceIn(PLAYLIST_BAR_MIN_DP, PLAYLIST_BAR_MAX_DP)
        prefs.edit().putFloat("playlist_bar_dp", playlistBarDp).apply()
    }

    /** The playlist pages along with the song playhead while playing (paused while you edit). Remembered. */
    var playlistFollow by mutableStateOf(prefs.getBoolean("playlist_follow", true))
        private set

    fun changePlaylistFollow(on: Boolean) {
        playlistFollow = on
        prefs.edit().putBoolean("playlist_follow", on).apply()
    }

    // ---------------------------------------------------------------- copy, clone, split

    /**
     * Copy: another clip of the same pattern, right after this one on the same row (or the first row
     * below with room). Editing the pattern changes both. Returns the new clip's index.
     */
    fun duplicatePatternClip(index: Int): Int? {
        val p = project
        val clip = p.clips.getOrNull(index) ?: return null
        val len = p.clipLength(clip)
        val start = clip.startTick + len
        val lane = freeLane(p, clip.lane, start, start + len)
        val copy = clip.movedTo(lane, start)
        update { q -> q.copy(clips = q.clips + copy, playlistRows = maxOf(q.rowCount, lane + 1)) }
        return project.clips.lastIndex
    }

    /** A new pattern with the same notes and length, named "Name 2" (then 3, ...). */
    private fun clonedPattern(p: Project, source: Pattern, id: Long): Pattern {
        val base = source.name.replace(Regex(" \\d+$"), "")
        var n = 2
        while (p.patterns.any { it.name == "$base $n" }) n++
        return source.copy(id = id, name = "$base $n")
    }

    /** Clone: a new, independent copy of a pattern (FL's "Make unique" / "Clone"). Selects it. */
    fun clonePattern(id: Long) {
        val source = project.pattern(id) ?: return
        val newId = project.nextId()
        update { p ->
            val i = p.patterns.indexOfFirst { it.id == id }
            val list = p.patterns.toMutableList().apply { add(i + 1, clonedPattern(p, source, newId)) }
            p.copy(patterns = list)
        }
        selectPattern(newId)
    }

    /** Clone for one playlist clip: it gets its own copy of the pattern, so edits only change it. */
    fun makeClipUnique(index: Int) {
        val clip = project.clips.getOrNull(index) ?: return
        val source = project.pattern(clip.patternId) ?: return
        val newId = project.nextId()
        update { p ->
            val i = p.patterns.indexOfFirst { it.id == source.id }
            val list = p.patterns.toMutableList().apply { add(i + 1, clonedPattern(p, source, newId)) }
            p.copy(patterns = list, clips = p.clips.mapIndexed { j, c -> if (j == index) c.copy(patternId = newId) else c })
        }
        selectPattern(newId)
    }

    /** Channels that have notes in [pattern], in rack order. */
    fun channelsIn(pattern: Pattern): List<Channel> =
        project.channels.filter { c -> pattern.notesFor(c.id).any { it.start < pattern.lengthTicks } }

    /**
     * Split by channel, like FL: one new pattern per channel that has notes, each placed where
     * the clip was, on its own row (this row first, then free rows below; rows are added if needed).
     * The original pattern stays in the list. Can be undone.
     */
    fun splitClipByChannel(index: Int) {
        val p = project
        val clip = p.clips.getOrNull(index) ?: return
        val source = p.pattern(clip.patternId) ?: return
        val channels = channelsIn(source)
        if (channels.size < 2) return
        val before = project
        var next = p.nextId()
        var q = p.copy(clips = p.clips.filterIndexed { i, _ -> i != index })
        val start = clip.startTick
        val end = p.clipEnd(clip)
        var lane = clip.lane
        val newPatterns = ArrayList<Pattern>()
        channels.forEach { c ->
            val pat = Pattern(next++, "${source.name} - ${c.name}".take(40), source.bars, mapOf(c.id to source.notesFor(c.id)))
            newPatterns += pat
            lane = freeLane(q, lane, start, end)
            q = q.copy(
                clips = q.clips + clip.copy(lane = lane, patternId = pat.id),
                playlistRows = maxOf(q.rowCount, lane + 1),
            )
            lane++
        }
        val i = q.patterns.indexOfFirst { it.id == source.id }
        q = q.copy(patterns = q.patterns.toMutableList().apply { addAll(i + 1, newPatterns) })
        update { q }
        undoSnapshot = before
        undoLabel = "Split ${source.name} into ${channels.size}"
    }

    /** First row from [from] down that has nothing between the two ticks (may be past the last row). */
    private fun freeLane(p: Project, from: Int, startTick: Int, endTick: Int): Int {
        var lane = from.coerceAtLeast(0)
        while (!p.laneFree(lane, startTick, endTick) && lane < com.mobiledaw.model.MAX_PLAYLIST_ROWS * 2) lane++
        return lane
    }

    /** Mute tool: mutes or unmutes the clip under [tick]. */
    fun toggleMuteAt(lane: Int, tick: Int) {
        val audio = audioClipAt(lane, tick)
        if (audio != null) {
            updateAudioClip(audio.id) { it.copy(muted = !it.muted) }
            return
        }
        val index = patternClipAt(lane, tick)
        if (index >= 0) setPatternClip(index, project.clips[index].let { it.copy(muted = !it.muted) })
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
        takeLane = (0 until project.rowCount).firstOrNull { project.laneFree(it, start, Int.MAX_VALUE) }
            ?: (0 until project.rowCount).firstOrNull { project.laneFree(it, start, start + TICKS_PER_BAR) }
            ?: 0
        takePeaks = FloatArray(0)
        takeState = TakeState.COUNTING
        recordMessage = null
        takeJob = viewModelScope.launch {
            // Open the mic first, so it is already listening when the count-in ends.
            val file = recordingFile("take-${System.currentTimeMillis()}.wav")
            val ok = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { recorder.start(file) }
            if (!isActive) {
                // Stopped while the mic was opening.
                recorder.stop()?.file?.delete()
                return@launch
            }
            if (!ok) {
                countIn = null
                takeState = TakeState.IDLE
                recordMessage = "Couldn't open the microphone."
                return@launch
            }
            syncEngine() // longer loop so the song doesn't wrap under the take
            NativeEngine.seek(start.toLong())
            // The engine clicks one bar in and starts the song on the exact sample after it,
            // so the count-in and the take are sample-accurate (no UI timers involved).
            val beats = BEATS_PER_BAR
            val beatNanos = 60_000_000_000L / project.bpm.coerceAtLeast(1)
            val expectedStart = System.nanoTime() + beats * beatNanos
            playStartNanos = expectedStart // so the lead-in trim skips the count-in from the first peak
            NativeEngine.startCountIn(beats)
            while (isActive && !NativeEngine.isPlaying()) {
                countIn = NativeEngine.countInBeat().takeIf { it > 0 }
                micLevel = recorder.level
                delay(10)
            }
            if (!isActive) return@launch
            takeState = TakeState.RECORDING
            countIn = COUNT_IN_GO
            applyPlaying(true) // the engine is already playing; this just catches the UI up
            playStartNanos = expectedStart
            launch {
                delay((beatNanos / 1_000_000L).coerceAtMost(700))
                if (countIn == COUNT_IN_GO) countIn = null
            }
            while (isActive && recorder.isRunning) {
                // Drop the lead-in the take will lose when it's lined up (see takeLeadInFrames).
                val peaks = recorder.peaksSnapshot()
                val skip = (takeLeadInFrames() / maxOf(1, recorder.sampleRate * Recorder.PEAK_MS / 1000)).toInt()
                takePeaks = if (skip <= 0) peaks else if (skip >= peaks.size) FloatArray(0) else peaks.copyOfRange(skip, peaks.size)
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
            // Stopped during the count-in: the mic was already open, so throw that audio away.
            if (state == TakeState.COUNTING) recorder.stop()?.file?.delete()
            syncEngine()
            return
        }
        val leadIn = takeLeadInFrames()
        val rate = recorder.sampleRate
        val take = recorder.stop()
        takePeaks = FloatArray(0)
        val start = takeStartTick
        if (take == null) {
            syncEngine()
            return
        }
        // Keep the whole file (count-in included) and start playing it at the lead-in, so the
        // take's Timing control can slide it later as well as earlier.
        val lead = (leadIn.toFloat() / rate).coerceIn(0f, (take.seconds - 0.2f).coerceAtLeast(0f))
        placeRecording(take.file, take.seconds, takeLane, start, lead)
        // Park the playhead at the start of the take so play replays it.
        NativeEngine.seek(start.toLong())
        positionTicks = start.toLong()
    }

    /**
     * Mic frames to cut from the front of the take so it lines up with the song. The mic opens
     * before playback starts, and you sing along to what you hear, which arrives [uiDelayMs] after
     * the engine plays it: the moment the first beat reached your ears is where the take begins.
     * The recorder's timestamps also take out the mic's own delay.
     */
    private fun takeLeadInFrames(): Long {
        if (playStartNanos == 0L) return 0L
        val heard = playStartNanos + (uiDelayMs - recordTimingMs) * 1_000_000L
        return recorder.frameAt(heard).coerceAtLeast(0L)
    }

    /** Puts a finished recording (a WAV in the app's files folder) on the playlist. */
    fun placeRecording(file: java.io.File, seconds: Float, lane: Int, startTick: Int, leadIn: Float = 0f) {
        val path = file.relativeTo(getApplication<Application>().filesDir).path
        val number = project.audioClips.size + 1
        update { p ->
            p.copy(
                audioClips = p.audioClips + AudioClip(
                    p.nextId(), lane, startTick, path, seconds - leadIn, "Take $number",
                    offset = leadIn, fileSeconds = seconds, takeTimingMs = recordTimingMs, recorded = true,
                ),
            )
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
                    p.clips.filter { it.lane == lane }.maxOfOrNull { p.clipEnd(it) } ?: 0,
                    p.audioClips.filter { it.lane == lane }.maxOfOrNull { it.endTick(p.bpm) } ?: 0,
                )
                // Start on a bar line.
                val startTick = (laneEnd + TICKS_PER_BAR - 1) / TICKS_PER_BAR * TICKS_PER_BAR
                p = p.copy(
                    mixerTracks = if (trackId != null) p.mixerTracks + MixerTrack(trackId, f.name.take(24)) else p.mixerTracks,
                    audioClips = p.audioClips + AudioClip(p.nextId(), lane, startTick, f.path, f.seconds, f.name, trackId, fileSeconds = f.seconds),
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

    // ---------------------------------------------------------------- projects

    fun savedProjects(): List<String> = store.list()

    fun openProject(name: String) {
        saveNow()
        closeChordGen(keep = true)
        clearPianoHistory()
        val loaded = store.load(name) ?: return
        showProject(loaded)
    }

    private fun showProject(loaded: Project) {
        applyPlaying(false)
        playFromTick = 0
        project = loaded
        selectedPatternId = loaded.patterns.first().id
        selectedChannelId = loaded.channels.firstOrNull()?.id ?: 0L
        syncEngine()
        store.save(loaded)
    }

    fun isCurrentProject(name: String): Boolean = store.sameFile(name, project.name)

    /**
     * Deletes a saved project for good. Deleting the open one opens the most recent other
     * project, or a new empty one when it was the last. Recordings and kits stay in the library.
     */
    fun deleteProject(name: String) {
        if (!isCurrentProject(name)) {
            store.delete(name)
            return
        }
        // Don't let a pending save bring the deleted project back.
        saveJob?.cancel()
        closeChordGen(keep = false)
        saveJob?.cancel()
        clearPianoHistory()
        store.delete(name)
        val next = store.list().firstNotNullOfOrNull { store.load(it) }
        if (next != null) showProject(next) else newProject(saveCurrent = false)
    }

    fun newProject(saveCurrent: Boolean = true) {
        if (saveCurrent) saveNow()
        closeChordGen(keep = true)
        clearPianoHistory()
        applyPlaying(false)
        playFromTick = 0
        val p = DemoProject.empty(store.uniqueName("New project"))
        project = p
        selectedPatternId = p.patterns.first().id
        selectedChannelId = p.channels.firstOrNull()?.id ?: 0L
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

    /** Engine insert index whose effects chain belongs to [trackId], including the master's; -1 if none. */
    private fun fxIndex(trackId: Long): Int =
        if (trackId == MASTER_TRACK_ID) NativeEngine.MASTER_INSERT else insertIndex(trackId)

    private fun syncEffects(t: Int, effects: List<Effect>, p: Project) {
        for (slot in 0 until NativeEngine.MAX_SLOTS) {
            val fx = effects.getOrNull(slot)
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

    private fun syncMixer() {
        val p = project
        val tracks = p.mixerTracks.take(NativeEngine.MAX_INSERTS)
        NativeEngine.setInsertCount(tracks.size)
        tracks.forEachIndexed { t, track ->
            NativeEngine.setInsert(t, track.volume, track.pan, track.mute)
            syncEffects(t, track.effects, p)
        }
        // Tracks past the end get an empty chain, so a deleted track's effects stop running.
        for (t in tracks.size until NativeEngine.MAX_INSERTS) syncEffects(t, emptyList(), p)
        syncEffects(NativeEngine.MASTER_INSERT, p.masterEffects, p)
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
        if (id == MASTER_TRACK_ID) {
            p.copy(masterEffects = transform(p.masterTrack).effects)
        } else {
            p.copy(mixerTracks = p.mixerTracks.map { if (it.id == id) transform(it) else it })
        }
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
            EffectType.CLIP -> Effect(id, type, clip = ClipSettings())
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
        val i = fxIndex(trackId)
        if (i >= 0) NativeEngine.readInsertSignal(i, out) else out.fill(0f)
    }

    /** Detected and corrected pitch (Hz) of a pitch corrector, 0 when nothing is sung. */
    fun readPitch(trackId: Long, effectId: Long, out: FloatArray) {
        val i = fxIndex(trackId)
        val slot = project.mixerTrack(trackId)?.effects?.indexOfFirst { it.id == effectId } ?: -1
        if (i >= 0 && slot in 0 until NativeEngine.MAX_SLOTS) NativeEngine.readPitch(i, slot, out) else out.fill(0f)
    }

    // ---------------------------------------------------------------- export

    var exportOpen by mutableStateOf(false)
        private set
    var exportOptions by mutableStateOf(com.mobiledaw.export.ExportOptions())
    var exportState by mutableStateOf<com.mobiledaw.export.ExportState>(com.mobiledaw.export.ExportState.Idle)
        private set
    @Volatile private var exporting = false
    @Volatile private var exportCancel = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** True when the playlist has something to play (otherwise export defaults to the pattern). */
    val hasArrangement: Boolean
        get() = project.clips.any { !it.muted } || project.audioClips.any { !it.muted }

    fun openExport() {
        if (!exporting) {
            exportState = com.mobiledaw.export.ExportState.Idle
            exportOptions = exportOptions.copy(song = hasArrangement)
        }
        settingsOpen = false
        exportOpen = true
    }

    fun closeExport() {
        if (exporting) return
        exportOpen = false
        if (exportState !is com.mobiledaw.export.ExportState.Running) exportState = com.mobiledaw.export.ExportState.Idle
    }

    fun cancelExport() {
        exportCancel = true
    }

    /** Stems the current options would make (rack channels that play, plus audio clips per mixer track). */
    fun exportStemNames(): List<String> {
        val compiled = if (exportOptions.song) SequenceCompiler.song(project) else SequenceCompiler.pattern(project, selectedPattern)
        return com.mobiledaw.export.Exporter.stemsFor(project, compiled, NativeEngine.MAX_CHANNELS).map { it.name }
    }

    /**
     * Renders the song (or the selected pattern) offline into the chosen formats, saves them to
     * Download/Mobile DAW/<name>/ and keeps them in the cache for sharing.
     */
    fun startExport(name: String) {
        if (exporting) return
        val opts = exportOptions
        if (!opts.any) return
        if (takeState != TakeState.IDLE) stopTake()
        applyPlaying(false)
        val p = project
        val compiled = if (opts.song) SequenceCompiler.song(p) else SequenceCompiler.pattern(p, selectedPattern)
        val stems = com.mobiledaw.export.Exporter.stemsFor(p, compiled, NativeEngine.MAX_CHANNELS)
        val base = com.mobiledaw.export.Exporter.safeName(name)
        val ctx = getApplication<Application>()
        exporting = true
        exportCancel = false
        exportState = com.mobiledaw.export.ExportState.Running(0f, "Starting")
        viewModelScope.launch {
            try {
                // Clips pitched with Stretch / Vocal must have their shifted sound ready first.
                if (stretchPending.isNotEmpty()) {
                    exportState = com.mobiledaw.export.ExportState.Running(0f, "Preparing pitched clips")
                    awaitStretches()
                }
                val done = withContext(Dispatchers.Default) {
                    com.mobiledaw.export.ExportStorage.clearWork(ctx)
                    val dir = com.mobiledaw.export.ExportStorage.workDir(ctx)
                    val files = com.mobiledaw.export.Exporter(
                        p, compiled, stems, opts, dir, base,
                        progress = { f, label ->
                            mainHandler.post {
                                if (exportState is com.mobiledaw.export.ExportState.Running) {
                                    exportState = com.mobiledaw.export.ExportState.Running(f, label)
                                }
                            }
                        },
                        cancelled = { exportCancel },
                    ).run()
                    // Stems and MIDI come as many files: they go out as one .zip instead.
                    val zip = if ((opts.stems || opts.midi) && files.size > 1) {
                        java.io.File(dir, "$base.zip").also { out ->
                            com.mobiledaw.export.ExportStorage.zip(
                                files, dir, base, out,
                                onProgress = { f ->
                                    mainHandler.post {
                                        if (exportState is com.mobiledaw.export.ExportState.Running) {
                                            exportState = com.mobiledaw.export.ExportState.Running(f, "Packing everything into one .zip")
                                        }
                                    }
                                },
                                cancelled = { exportCancel },
                            )
                        }
                    } else null
                    mainHandler.post { exportState = com.mobiledaw.export.ExportState.Running(1f, "Saving to Downloads") }
                    if (zip != null) {
                        com.mobiledaw.export.ExportStorage.saveToDownloads(ctx, zip, base, null)
                    } else {
                        files.forEach { f ->
                            val sub = f.parentFile?.takeIf { it != dir }?.name
                            com.mobiledaw.export.ExportStorage.saveToDownloads(ctx, f, base, sub)
                        }
                    }
                    com.mobiledaw.export.ExportState.Done(
                        folder = com.mobiledaw.export.ExportStorage.folderLabel(ctx, base),
                        mix = files.filter { it.parentFile == dir && it.extension != "mid" },
                        stems = files.filter { it.parentFile != dir },
                        midi = files.firstOrNull { it.extension == "mid" },
                        zip = zip,
                    )
                }
                exportState = done
            } catch (e: com.mobiledaw.export.ExportCancelled) {
                exportState = com.mobiledaw.export.ExportState.Idle
            } catch (e: Exception) {
                exportState = com.mobiledaw.export.ExportState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                exporting = false
                if (visible) NativeEngine.start()
                syncEngine()
            }
        }
    }

    fun shareExport(files: List<java.io.File>) {
        runCatching { com.mobiledaw.export.ExportStorage.share(getApplication<Application>(), files) }
    }

    override fun onCleared() {
        if (recorder.isRunning) recorder.stop()
        saveNow()
    }

    companion object {
        /** How long a preset preview is shown in the library player bar. */
        const val ENGINE_PREVIEW_MS = 1500L
        const val SCOPE_BINS = 96
        private const val MAX_PIANO_UNDO = 100
        /** "Loop bars" always works in blocks of this many bars. */
        private const val LOOP_BLOCK_BARS = 4
        const val RECORDINGS_DIR = "recordings"
        /** Playlist zoom: default, narrowest and widest bar, in dp. */
        const val PLAYLIST_BAR_DP = 56f
        const val PLAYLIST_BAR_MIN_DP = 14f
        const val PLAYLIST_BAR_MAX_DP = 220f
        /** Most a sample channel can be boosted, in dB. */
        const val BOOST_MAX_DB = 18f
        /** Pseudo sample path for a built-in drum's recorded hit, e.g. "builtin:hat". */
        private const val BUILT_IN_PREFIX = "builtin:"
        private const val BUILT_IN_RATE = 48000f
        /** The app is in 4/4: metronome accents and the record count-in use this. */
        const val BEATS_PER_BAR = com.mobiledaw.model.TICKS_PER_BAR / com.mobiledaw.model.TICKS_PER_BEAT
        /** [countIn] value for the "GO" shown as the take starts. */
        const val COUNT_IN_GO = 0
        /** Fine-tune range around the measured delay (Auto), in ms. */
        const val LATENCY_OFFSET_MIN = -300
        const val LATENCY_OFFSET_MAX = 500
        /** Recording timing range (each way), in ms. */
        const val RECORD_TIMING_RANGE = 500
        private const val MAX_CACHED_FRAMES = 24_000_000L
        /** Ticks per minute per BPM, i.e. TICKS_PER_BEAT. Used to size the loop while recording. */
        private const val TICKS_PER_BEAT_PER_MINUTE = com.mobiledaw.model.TICKS_PER_BEAT
    }
}

/** Playlist tools: pencil adds, moves and resizes clips; cut slices them; mute silences them. */
enum class PlaylistTool { PENCIL, CUT, MUTE }
