package com.mobiledaw.model

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

/** A pattern placed on the playlist. */
@Serializable
data class Clip(
    val lane: Int,
    val bar: Int,
    val patternId: Long,
)

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
) {
    fun pattern(id: Long): Pattern? = patterns.firstOrNull { it.id == id }

    fun clipBars(clip: Clip): Int = pattern(clip.patternId)?.bars ?: 1

    val keyLabel: String get() = NOTE_NAMES[key] + if (minor) "m" else ""

    /** Bars in the arrangement, at least one. */
    val songBars: Int get() = maxOf(1, clips.maxOfOrNull { it.bar + clipBars(it) } ?: 1)

    fun nextId(): Long =
        (channels.map { it.id } + patterns.map { it.id }).maxOrNull()?.plus(1) ?: 1L
}

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(pitch: Int): String = NOTE_NAMES[pitch.mod(12)] + (pitch / 12 - 1)

fun isBlackKey(pitch: Int): Boolean = pitch.mod(12) in setOf(1, 3, 6, 8, 10)
