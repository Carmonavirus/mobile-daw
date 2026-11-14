package com.mobiledaw.model

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One note found in a sound. Times in seconds, [midi] fractional (69.0 = A4), [level] 0..1. */
data class FoundNote(val start: Float, val end: Float, val midi: Float, val level: Float) {
    val pitch: Int get() = midi.roundToInt()
    val cents: Int get() = ((midi - pitch) * 100f).roundToInt()
    val seconds: Float get() = end - start
    val name: String get() = noteName(pitch)
}

/** Which part of which sound the note viewer looks at, and how it is played back. */
data class NoteSource(
    val title: String,
    val path: String,
    /** Used part of the file, as fractions 0..1. */
    val from: Float = 0f,
    val to: Float = 1f,
    /** Shown transposed by this much, the way the clip or channel plays it. */
    val pitchSemis: Int = 0,
    /** Playback speed (1 = as recorded); a resampled pitch change also changes speed. */
    val rate: Float = 1f,
    val reverse: Boolean = false,
) {
    val cacheKey: String get() = "$path|$from|$to"
}

/**
 * The notes in a sound over time (see fx::detectNotesFlat): a pitch line every [hopSeconds]
 * (0 = no pitch there) and the notes it was grouped into.
 */
class NoteAnalysis(
    val hopSeconds: Float,
    val pitch: FloatArray,
    val level: FloatArray,
    val notes: List<FoundNote>,
) {
    val seconds: Float get() = pitch.size * hopSeconds

    /** As played: transposed, sped up or slowed down, and/or backwards. */
    fun asPlayed(src: NoteSource): NoteAnalysis {
        if (src.pitchSemis == 0 && src.rate == 1f && !src.reverse) return this
        val hop = hopSeconds / src.rate
        val total = pitch.size * hop
        val shift = src.pitchSemis.toFloat()
        val p = FloatArray(pitch.size) { i -> pitch[if (src.reverse) pitch.size - 1 - i else i].let { if (it > 0f) it + shift else 0f } }
        val l = FloatArray(level.size) { i -> level[if (src.reverse) level.size - 1 - i else i] }
        val n = notes.map { note ->
            val a = note.start / src.rate
            val b = note.end / src.rate
            if (src.reverse) note.copy(start = total - b, end = total - a, midi = note.midi + shift) else note.copy(start = a, end = b, midi = note.midi + shift)
        }.sortedBy { it.start }
        return NoteAnalysis(hop, p, l, n)
    }

    /** The key the notes fit best (time-weighted, Krumhansl-Kessler profiles), or null. */
    fun key(): DetectedKey? {
        if (notes.size < 3) return null
        val hist = DoubleArray(12)
        for (n in notes) hist[n.pitch.mod(12)] += n.seconds.toDouble() * (0.3 + 0.7 * n.level)
        var best = -1
        var bestScore = -2.0
        for (k in 0 until 24) {
            val profile = if (k < 12) MAJOR else MINOR
            val s = correlate(hist, profile, k % 12)
            if (s > bestScore) { bestScore = s; best = k }
        }
        return if (bestScore > 0.3) DetectedKey.of(best) else null
    }

    companion object {
        private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        private fun correlate(x: DoubleArray, profile: DoubleArray, rotate: Int): Double {
            val mx = x.average()
            val mp = profile.average()
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (i in 0 until 12) {
                val a = x[(i + rotate) % 12] - mx
                val b = profile[i] - mp
                sxy += a * b
                sxx += a * a
                syy += b * b
            }
            return if (sxx <= 1e-12) 0.0 else sxy / sqrt(sxx * syy)
        }

        /** Reads the flat layout [hop, F, N, F pitches, F levels, N x (start, end, midi, level)]. */
        fun fromFlat(flat: FloatArray): NoteAnalysis? {
            if (flat.size < 3) return null
            val hop = flat[0]
            val f = flat[1].toInt()
            val n = flat[2].toInt()
            if (f < 0 || n < 0 || flat.size < 3 + 2 * f + 4 * n) return null
            val pitch = flat.copyOfRange(3, 3 + f)
            val level = flat.copyOfRange(3 + f, 3 + 2 * f)
            val notes = List(n) { i ->
                val o = 3 + 2 * f + 4 * i
                FoundNote(flat[o], flat[o + 1], flat[o + 2], flat[o + 3])
            }
            return NoteAnalysis(hop, pitch, level, notes)
        }

        fun semitoneRate(semis: Int): Float = 2.0.pow(semis / 12.0).toFloat()
    }
}
