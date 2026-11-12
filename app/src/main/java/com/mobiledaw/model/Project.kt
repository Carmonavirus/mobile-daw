package com.mobiledaw.model

import kotlin.math.pow
import kotlinx.serialization.Serializable

const val TICKS_PER_BEAT = 96
const val TICKS_PER_STEP = TICKS_PER_BEAT / 4
const val STEPS_PER_BAR = 16
const val TICKS_PER_BAR = TICKS_PER_STEP * STEPS_PER_BAR
const val MAX_BARS = 16

/** Built-in instruments. The id must match the enum in app/src/main/cpp/Synth.h. */
enum class Instrument(val id: Int, val label: String, val isDrum: Boolean, val defaultPitch: Int) {
    KICK(0, "Kick", true, 60),
    SNARE(1, "Snare", true, 60),
    HAT(2, "Hi-hat", true, 60),
    CLAP(3, "Clap", true, 60),
    BASS(4, "Bass", false, 36),
    LEAD(5, "Lead", false, 72),
    PLUCK(6, "Pluck", false, 72),
    PAD(7, "Pad", false, 60),

    /** Plays an imported sound from a kit; see [Channel.sample]. */
    SAMPLE(8, "Sample", true, 60),

    /** Two-oscillator wavetable synth; its sound lives in [Channel.synth]. */
    WAVETABLE(13, "Wavetable", false, 60),
}

/** A note inside a pattern. Times are in ticks (96 per beat). */
@Serializable
data class Note(
    val start: Int,
    val length: Int,
    val pitch: Int,
    val velocity: Int = 100,
)

@Serializable
data class Channel(
    val id: Long,
    val name: String,
    val instrument: Instrument,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** For [Instrument.SAMPLE]: the sound's path relative to the app's files folder. */
    val sample: String? = null,
    /** A new hit stops the previous one on this channel (FL's "cut itself"). */
    val cutItself: Boolean = false,
    /** Part of the sample that plays, as fractions 0..1. */
    val trimStart: Float = 0f,
    val trimEnd: Float = 1f,
    /** Amplitude envelope: delay/attack/hold/decay/release in seconds, sustain 0..1, tension -1..1. */
    val envDelay: Float = 0f,
    val attack: Float = 0f,
    val hold: Float = 0f,
    val decay: Float = 0f,
    val sustain: Float = 1f,
    val release: Float = 0f,
    val tension: Float = 0f,
    /** Transpose in semitones (-12..12) and reverse playback, for sample channels. */
    val pitchSemis: Int = 0,
    val reverse: Boolean = false,
    /** For [Instrument.WAVETABLE]: the synth's sound. Null means the default ("Init") sound. */
    val synth: SynthPatch? = null,
    /** Mixer track this channel plays through; null goes straight to the master. */
    val mixerTrack: Long? = null,
)

@Serializable
data class Pattern(
    val id: Long,
    val name: String,
    val bars: Int = 1,
    /** Notes per channel id. */
    val notes: Map<Long, List<Note>> = emptyMap(),
) {
    val lengthTicks: Int get() = bars * TICKS_PER_BAR
    fun notesFor(channelId: Long): List<Note> = notes[channelId].orEmpty()
}

/**
 * A pattern placed on the playlist. It starts [nudge] ticks after bar [bar] and shows the part of
 * the pattern from [trim] for [length] ticks (0 = to the end of the pattern), so its edges can be
 * dragged in and it can be cut in two. A clip never runs past the end of its pattern.
 */
@Serializable
data class Clip(
    val lane: Int,
    val bar: Int,
    val patternId: Long,
    val nudge: Int = 0,
    val trim: Int = 0,
    val length: Int = 0,
    val muted: Boolean = false,
) {
    val startTick: Int get() = bar * TICKS_PER_BAR + nudge

    /** Same clip moved to start at [tick] on [lane]. */
    fun movedTo(lane: Int, tick: Int): Clip {
        val t = maxOf(0, tick)
        return copy(lane = lane, bar = t / TICKS_PER_BAR, nudge = t % TICKS_PER_BAR)
    }
}

/**
 * A recorded (or imported) piece of audio placed on the playlist.
 * [path] is relative to the app's files folder, e.g. "recordings/take-1731412345.wav".
 * Its length on the timeline follows the tempo, so it is stored in seconds.
 */
@Serializable
data class AudioClip(
    val id: Long,
    val lane: Int,
    val startTick: Int,
    val path: String,
    val seconds: Float,
    val name: String = "Audio",
    /** Mixer track this clip plays through; null = the project's recording track. */
    val trackId: Long? = null,
    /** Where the used part starts in the file, in seconds; [seconds] is how much of the file is used. */
    val offset: Float = 0f,
    /** Length of the whole file in seconds; 0 in older projects, where the clip used the whole file. */
    val fileSeconds: Float = 0f,
    val reverse: Boolean = false,
    /** Transpose in semitones (-12..12). Like a turntable, higher also plays faster (shorter clip). */
    val pitchSemis: Int = 0,
    /** Clip volume, 0..2 (1 = as recorded). */
    val gain: Float = 1f,
    val muted: Boolean = false,
    /** Total "Timing" nudge applied in the clip window, in ms (positive = later). For display. */
    val timingMs: Int = 0,
    /** For recordings: the output's recording timing (ms) in effect when this take was made. */
    val takeTimingMs: Int = 0,
    /** True for mic recordings made in the app (they keep the count-in before [offset]). */
    val recorded: Boolean = false,
) {
    /** Playback speed from the pitch change. */
    val rate: Float get() = 2.0.pow(pitchSemis / 12.0).toFloat()

    /** Length of the whole source file in seconds. */
    val sourceSeconds: Float get() = if (fileSeconds > 0f) fileSeconds else offset + seconds

    /** How long the clip plays, in seconds of real time. */
    val playSeconds: Float get() = seconds / rate

    fun lengthTicks(bpm: Int): Int = maxOf(1, (playSeconds * bpm / 60f * TICKS_PER_BEAT).toInt())
    fun endTick(bpm: Int): Int = startTick + lengthTicks(bpm)
}

@Serializable
data class Project(
    val name: String,
    val bpm: Int = 140,
    val key: Int = 0, // 0 = C .. 11 = B
    val minor: Boolean = true,
    val masterVolume: Float = 0.7f,
    val channels: List<Channel>,
    val patterns: List<Pattern>,
    val clips: List<Clip> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    /** Mixer tracks with effect chains. Every project starts with a generic "Vocals" track. */
    val mixerTracks: List<MixerTrack> = listOf(MixerTrack.vocals()),
    /** Mixer track recorded audio plays through (the Vocals track unless changed); null = master. */
    val recordingTrackId: Long? = VOCALS_TRACK_ID,
) {
    fun pattern(id: Long): Pattern? = patterns.firstOrNull { it.id == id }

    /** Ticks a pattern clip lasts: the shown part of its pattern, never past the pattern's end. */
    fun clipLength(clip: Clip): Int {
        val patternTicks = pattern(clip.patternId)?.lengthTicks ?: TICKS_PER_BAR
        val rest = patternTicks - clip.trim
        val len = if (clip.length > 0) minOf(clip.length, rest) else rest
        return maxOf(TICKS_PER_STEP, len)
    }

    fun clipEnd(clip: Clip): Int = clip.startTick + clipLength(clip)

    /** Bars from the clip's bar line to (at least) its end, so `bar + clipBars` is a bar past the clip. */
    fun clipBars(clip: Clip): Int = maxOf(1, (clip.nudge + clipLength(clip) + TICKS_PER_BAR - 1) / TICKS_PER_BAR)

    val keyLabel: String get() = NOTE_NAMES[key] + if (minor) "m" else ""

    /** Bars in the arrangement, at least one. */
    val songBars: Int get() = maxOf(
        1,
        clips.maxOfOrNull { (clipEnd(it) + TICKS_PER_BAR - 1) / TICKS_PER_BAR } ?: 1,
        audioClips.maxOfOrNull { (it.endTick(bpm) + TICKS_PER_BAR - 1) / TICKS_PER_BAR } ?: 1,
    )

    /** True when [lane] has nothing between the two ticks (pattern or audio clips). */
    fun laneFree(lane: Int, fromTick: Int, toTick: Int, ignoreAudioId: Long? = null): Boolean =
        clips.none { it.lane == lane && fromTick < clipEnd(it) && it.startTick < toTick } &&
            audioClips.none { it.id != ignoreAudioId && it.lane == lane && fromTick < it.endTick(bpm) && it.startTick < toTick }

    fun mixerTrack(id: Long?): MixerTrack? = id?.let { t -> mixerTracks.firstOrNull { it.id == t } }

    fun nextMixerTrackId(): Long = (mixerTracks.maxOfOrNull { it.id } ?: 0L) + 1

    fun nextEffectId(): Long = (mixerTracks.flatMap { it.effects }.maxOfOrNull { it.id } ?: 0L) + 1

    fun nextId(): Long =
        (channels.map { it.id } + patterns.map { it.id } + audioClips.map { it.id }).maxOrNull()?.plus(1) ?: 1L
}

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(pitch: Int): String = NOTE_NAMES[pitch.mod(12)] + (pitch / 12 - 1)

private val MAJOR_STEPS = setOf(0, 2, 4, 5, 7, 9, 11)
private val MINOR_STEPS = setOf(0, 2, 3, 5, 7, 8, 10)

/** True when the pitch belongs to the major or natural minor scale starting on `key` (0 = C). */
fun inScale(pitch: Int, key: Int, minor: Boolean): Boolean =
    (pitch - key).mod(12) in (if (minor) MINOR_STEPS else MAJOR_STEPS)

fun isBlackKey(pitch: Int): Boolean = pitch.mod(12) in setOf(1, 3, 6, 8, 10)
