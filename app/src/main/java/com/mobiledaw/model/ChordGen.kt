package com.mobiledaw.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Chord progression generator used by the piano roll's Chords panel.
 *
 * Our own approach: every chord the key allows is a [ChordCandidate] with a harmonic "function"
 * (home, away, tension) and a rarity. A progression is built left to right; each next chord is
 * drawn with weights from how naturally it follows the previous one (function flow and root
 * motion), scaled by the Conventional ↔ Adventurous setting, which lets rarer chords (borrowed,
 * secondary dominants, chromatic) and richer qualities (7ths, sus, add9) in.
 * Voicings are chosen to move as little as possible from chord to chord.
 */

enum class ChordQuality(val suffix: String, val intervals: List<Int>) {
    MAJ("", listOf(0, 4, 7)),
    MIN("m", listOf(0, 3, 7)),
    DIM("dim", listOf(0, 3, 6)),
    AUG("+", listOf(0, 4, 8)),
    SUS2("sus2", listOf(0, 2, 7)),
    SUS4("sus4", listOf(0, 5, 7)),
    MAJ7("maj7", listOf(0, 4, 7, 11)),
    MIN7("m7", listOf(0, 3, 7, 10)),
    DOM7("7", listOf(0, 4, 7, 10)),
    HALFDIM7("m7♭5", listOf(0, 3, 6, 10)),
    ADD9("add9", listOf(0, 4, 7, 14)),
    MADD9("m(add9)", listOf(0, 3, 7, 14)),
    MAJ9("maj9", listOf(0, 4, 7, 11, 14)),
    MIN9("m9", listOf(0, 3, 7, 10, 14)),
}

/** What a chord does in the key: rests at home, moves away, or builds tension back home. */
enum class ChordFunction { HOME, AWAY, TENSION }

/** One chord the key offers. [offset] is the root in semitones above the key's root. */
data class ChordCandidate(
    val offset: Int,
    val quality: ChordQuality,
    val roman: String,
    val function: ChordFunction,
    /** 0 = everyday chord in this key, 1 = very unusual. */
    val rarity: Float,
)

/** A chord in the progression. [inversion] shifts the auto voicing: +1 lifts the lowest note an octave. */
data class ChordSlot(
    val chord: ChordCandidate,
    val locked: Boolean = false,
    val inversion: Int = 0,
)

enum class ChordRhythm(val label: String) { HOLD("Hold"), BEATS("Beats"), ARP("Arp") }

data class ChordSettings(
    val count: Int = 4,
    val octave: Int = 4,
    val lengthTicks: Int = TICKS_PER_BAR,
    /** 0 = conventional, 1 = adventurous. */
    val adventure: Float = 0.3f,
    val bass: Boolean = true,
    val rhythm: ChordRhythm = ChordRhythm.HOLD,
)

object ChordGen {
    const val MIN_COUNT = 2
    const val MAX_COUNT = 8
    const val MIN_OCTAVE = 2
    const val MAX_OCTAVE = 6

    private val FLAT_NAMES = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")

    /** Keys written with flats (F major, G minor, ...), so their chords read B♭ rather than A#. */
    fun usesFlats(key: Int, minor: Boolean): Boolean =
        if (minor) key in setOf(2, 7, 0, 5, 10, 3) else key in setOf(5, 10, 3, 8, 1, 6)

    fun chordName(key: Int, minor: Boolean, c: ChordCandidate): String {
        val pc = (key + c.offset).mod(12)
        return (if (usesFlats(key, minor)) FLAT_NAMES[pc] else NOTE_NAMES[pc]) + c.quality.suffix
    }

    /** All the chords to choose from in a major or (natural) minor key. */
    fun candidates(minor: Boolean): List<ChordCandidate> {
        val h = ChordFunction.HOME
        val a = ChordFunction.AWAY
        val t = ChordFunction.TENSION
        fun c(offset: Int, quality: ChordQuality, roman: String, f: ChordFunction, rarity: Float) =
            ChordCandidate(offset, quality, roman, f, rarity)
        val base = if (!minor) listOf(
            c(0, ChordQuality.MAJ, "I", h, 0f),
            c(2, ChordQuality.MIN, "ii", a, 0.2f),
            c(4, ChordQuality.MIN, "iii", h, 0.35f),
            c(5, ChordQuality.MAJ, "IV", a, 0f),
            c(7, ChordQuality.MAJ, "V", t, 0f),
            c(9, ChordQuality.MIN, "vi", h, 0f),
            c(11, ChordQuality.DIM, "vii°", t, 0.5f),
            // Borrowed from the parallel minor.
            c(5, ChordQuality.MIN, "iv", a, 0.55f),
            c(8, ChordQuality.MAJ, "♭VI", a, 0.6f),
            c(10, ChordQuality.MAJ, "♭VII", t, 0.55f),
            c(3, ChordQuality.MAJ, "♭III", a, 0.75f),
            // Secondary dominants.
            c(2, ChordQuality.MAJ, "V/V", t, 0.6f),
            c(4, ChordQuality.MAJ, "V/vi", t, 0.65f),
            c(9, ChordQuality.MAJ, "V/ii", t, 0.75f),
            // Chromatic colour.
            c(1, ChordQuality.MAJ, "♭II", t, 0.95f),
            c(8, ChordQuality.AUG, "♭VI+", t, 1f),
        ) else listOf(
            c(0, ChordQuality.MIN, "i", h, 0f),
            c(2, ChordQuality.DIM, "ii°", a, 0.45f),
            c(3, ChordQuality.MAJ, "III", h, 0.1f),
            c(5, ChordQuality.MIN, "iv", a, 0f),
            c(7, ChordQuality.MIN, "v", t, 0.2f),
            c(8, ChordQuality.MAJ, "VI", a, 0f),
            c(10, ChordQuality.MAJ, "VII", t, 0f),
            // Borrowed: harmonic/melodic minor and the parallel major.
            c(7, ChordQuality.MAJ, "V", t, 0.3f),
            c(5, ChordQuality.MAJ, "IV", a, 0.6f),
            c(0, ChordQuality.MAJ, "I", h, 0.8f),
            c(2, ChordQuality.MIN, "ii", a, 0.65f),
            // Neapolitan and secondary dominants.
            c(1, ChordQuality.MAJ, "♭II", t, 0.7f),
            c(0, ChordQuality.DOM7, "V/iv", t, 0.65f),
            c(3, ChordQuality.DOM7, "V/VI", t, 0.75f),
            // Chromatic colour.
            c(4, ChordQuality.MAJ, "III♮", t, 0.95f),
            c(9, ChordQuality.MIN, "vi♮", a, 0.9f),
        )
        return base
    }

    private fun functionFlow(from: ChordFunction, to: ChordFunction): Float = when (from) {
        ChordFunction.HOME -> when (to) { ChordFunction.HOME -> 1.1f; ChordFunction.AWAY -> 3f; ChordFunction.TENSION -> 1.6f }
        ChordFunction.AWAY -> when (to) { ChordFunction.HOME -> 1.2f; ChordFunction.AWAY -> 1f; ChordFunction.TENSION -> 3f }
        ChordFunction.TENSION -> when (to) { ChordFunction.HOME -> 3.5f; ChordFunction.AWAY -> 0.8f; ChordFunction.TENSION -> 0.6f }
    }

    /** How well [next] follows [prev] (both may be null at the ends). */
    private fun flow(prev: ChordCandidate?, next: ChordCandidate, adventure: Float): Float {
        if (prev == null) return 1f
        var w = functionFlow(prev.function, next.function)
        val motion = (next.offset - prev.offset).mod(12)
        when (motion) {
            5 -> w *= 1.8f                // down a fifth: the strongest pull
            7 -> w *= 1.2f                // up a fifth
            2, 10 -> w *= 1.25f           // step
            3, 4, 8, 9 -> w *= 1.1f + 0.4f * adventure // thirds: mediant colour
            0 -> w *= if (prev.quality == next.quality) 0.35f else 0.8f
        }
        // A secondary dominant wants to land on the chord a fifth below it.
        if (next.roman.startsWith("V/") && prev.roman.startsWith("V/")) w *= 0.3f
        return w
    }

    private fun rarityWeight(rarity: Float, adventure: Float): Float =
        exp(-rarity * 6f * (1f - adventure)) * (1f + 2f * adventure * rarity)

    private fun weight(
        prev: ChordCandidate?,
        cand: ChordCandidate,
        next: ChordCandidate?,
        index: Int,
        count: Int,
        settings: ChordSettings,
    ): Float {
        val a = settings.adventure
        var w = flow(prev, cand, a)
        if (next != null) w *= flow(cand, next, a)
        w *= rarityWeight(cand.rarity, a)
        if (index == 0) {
            // Conventional progressions usually start at home.
            if (cand.function == ChordFunction.HOME && cand.rarity < 0.2f) w *= 1f + 8f * (1f - a)
        }
        if (index == count - 1 && count >= 3) {
            // The last chord leads back to the start of the loop.
            w *= when (cand.function) {
                ChordFunction.TENSION -> 1f + 2f * (1f - a)
                ChordFunction.AWAY -> 1.3f
                ChordFunction.HOME -> 0.5f + 0.4f * a
            }
        }
        // Flatter odds when adventurous.
        return w.pow(1f - 0.45f * a)
    }

    /** Turns a plain triad into a richer chord now and then, more often when adventurous. */
    private fun colour(c: ChordCandidate, adventure: Float, random: Random): ChordCandidate {
        if (c.quality != ChordQuality.MAJ && c.quality != ChordQuality.MIN && c.quality != ChordQuality.DIM) return c
        val r = random.nextFloat()
        val p7 = 0.04f + 0.5f * adventure
        val pSus = 0.3f * adventure
        val pAdd = 0.25f * adventure
        val dominant = c.function == ChordFunction.TENSION && c.quality == ChordQuality.MAJ
        val q = when {
            r < p7 -> when (c.quality) {
                ChordQuality.MAJ -> if (dominant) ChordQuality.DOM7 else if (adventure > 0.6f && random.nextFloat() < 0.4f) ChordQuality.MAJ9 else ChordQuality.MAJ7
                ChordQuality.MIN -> if (adventure > 0.6f && random.nextFloat() < 0.4f) ChordQuality.MIN9 else ChordQuality.MIN7
                else -> ChordQuality.HALFDIM7
            }
            r < p7 + pSus && c.quality != ChordQuality.DIM -> if (dominant || random.nextBoolean()) ChordQuality.SUS4 else ChordQuality.SUS2
            r < p7 + pSus + pAdd && c.quality != ChordQuality.DIM -> if (c.quality == ChordQuality.MAJ) ChordQuality.ADD9 else ChordQuality.MADD9
            else -> c.quality
        }
        return if (q == c.quality) c else c.copy(quality = q)
    }

    private fun pick(
        prev: ChordCandidate?,
        next: ChordCandidate?,
        index: Int,
        settings: ChordSettings,
        minor: Boolean,
        random: Random,
        avoid: ChordCandidate? = null,
    ): ChordCandidate {
        val pool = candidates(minor).filter { avoid == null || it.offset != avoid.offset || it.quality != avoid.quality }
        val weights = pool.map { weight(prev, it, next, index, settings.count, settings).coerceAtLeast(1e-6f) }
        var r = random.nextFloat() * weights.sum()
        var chosen = pool.last()
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0f) { chosen = pool[i]; break }
        }
        val coloured = colour(chosen, settings.adventure, random)
        // Avoid handing back the very chord being rerolled after colouring.
        return if (avoid != null && coloured == avoid) chosen else coloured
    }

    /** A new progression. Locked slots in [current] stay as they are. */
    fun generate(current: List<ChordSlot>, settings: ChordSettings, minor: Boolean, random: Random): List<ChordSlot> {
        val out = ArrayList<ChordSlot>(settings.count)
        for (i in 0 until settings.count) {
            val old = current.getOrNull(i)
            if (old != null && old.locked) { out += old; continue }
            // Look ahead to the next locked chord so we lead into it.
            val next = current.getOrNull(i + 1)?.takeIf { it.locked }?.chord
                ?: if (i == settings.count - 1) out.firstOrNull()?.chord else null
            out += ChordSlot(pick(out.lastOrNull()?.chord, next, i, settings, minor, random))
        }
        return out
    }

    /** Replaces one chord, fitting it between its neighbours. */
    fun reroll(slots: List<ChordSlot>, index: Int, settings: ChordSettings, minor: Boolean, random: Random): List<ChordSlot> {
        val prev = slots.getOrNull(index - 1)?.chord
        val next = slots.getOrNull(index + 1)?.chord ?: slots.firstOrNull()?.takeIf { index == slots.size - 1 && slots.size > 2 }?.chord
        val chord = pick(prev, next, index, settings, minor, random, avoid = slots[index].chord)
        return slots.mapIndexed { i, s -> if (i == index) s.copy(chord = chord, inversion = 0) else s }
    }

    /** Pitches of each chord (ascending), voiced to move smoothly, plus an optional bass note first. */
    fun voice(slots: List<ChordSlot>, settings: ChordSettings, key: Int): List<List<Int>> {
        val center = (settings.octave + 1) * 12 + 6 // middle of the chosen octave
        var prev: List<Int>? = null
        return slots.map { slot ->
            val root = (key + slot.chord.offset).mod(12)
            val intervals = slot.chord.quality.intervals
            val candidates = ArrayList<List<Int>>()
            // Every inversion, at every octave that lands near the chosen one.
            for (inv in intervals.indices) {
                for (oct in -2..2) {
                    val pitches = intervals.mapIndexed { i, iv -> iv + if (i < inv) 12 else 0 }.sorted()
                    val base = (settings.octave + 1 + oct) * 12 + root
                    candidates += pitches.map { base + it }
                }
            }
            fun cost(v: List<Int>): Float {
                val mid = (v.first() + v.last()) / 2f
                val drift = abs(mid - center)
                val p = prev ?: return drift
                // Sum of each note's distance to the nearest note of the previous chord.
                val moves = v.sumOf { n -> p.minOf { abs(it - n) } }.toFloat()
                return moves + 0.6f * drift
            }
            var best = candidates.minBy { cost(it) }
            // Manual inversion on top of the auto voicing.
            repeat(abs(slot.inversion)) {
                best = if (slot.inversion > 0) (best.drop(1) + (best.first() + 12)) else (listOf(best.last() - 12) + best.dropLast(1))
            }
            prev = best
            val clamped = best.map { it.coerceIn(12, 108) }
            if (settings.bass) {
                var bass = root + 12 * ((clamped.first() - 7 - root).floorDiv(12))
                while (bass > clamped.first() - 7) bass -= 12
                if (bass < 12) bass += 12
                listOf(bass) + clamped
            } else {
                clamped
            }
        }
    }

    /** The notes to write into a pattern, starting at tick 0. */
    fun notes(slots: List<ChordSlot>, settings: ChordSettings, key: Int): List<Note> {
        val voiced = voice(slots, settings, key)
        val len = settings.lengthTicks
        val out = ArrayList<Note>()
        voiced.forEachIndexed { i, pitches ->
            val start = i * len
            val bass = if (settings.bass) pitches.first() else null
            val upper = if (settings.bass) pitches.drop(1) else pitches
            if (bass != null) out += Note(start, len, bass, 105)
            when (settings.rhythm) {
                ChordRhythm.HOLD -> upper.forEach { out += Note(start, len, it, 96) }
                ChordRhythm.BEATS -> {
                    var t = 0
                    while (t < len) {
                        val l = minOf(TICKS_PER_BEAT * 3 / 4, len - t)
                        val vel = if (t % TICKS_PER_BAR == 0) 100 else 84
                        upper.forEach { out += Note(start + t, l, it, vel) }
                        t += TICKS_PER_BEAT
                    }
                }
                ChordRhythm.ARP -> {
                    val step = TICKS_PER_STEP * 2 // eighth notes, up and back down
                    val order = if (upper.size > 2) upper + upper.subList(1, upper.size - 1).reversed() else upper
                    var t = 0
                    var k = 0
                    while (t < len) {
                        out += Note(start + t, minOf(step, len - t), order[k % order.size], if (k % order.size == 0) 100 else 86)
                        t += step
                        k++
                    }
                }
            }
        }
        return out
    }
}
