package com.mobiledaw.export

import com.mobiledaw.model.Project
import com.mobiledaw.model.TICKS_PER_BEAT
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes the notes of a compiled sequence as a Standard MIDI File (type 1): a tempo track, then one
 * track per rack channel, named after the channel. FL Studio and other DAWs open it with every
 * channel on its own track, at the project's tempo and key.
 */
object MidiWriter {
    /** [events] is the engine's flat list: channel slot, start, length, pitch, velocity per note. */
    fun write(file: File, project: Project, events: IntArray) {
        val tracks = ArrayList<ByteArray>()
        tracks += tempoTrack(project)
        var melodic = 0
        project.channels.forEachIndexed { slot, channel ->
            val notes = (0 until events.size / 5).map { it * 5 }.filter { events[it] == slot }
            if (notes.isEmpty()) return@forEachIndexed
            // Drum sounds on MIDI channel 10, everything else on its own channel (skipping 10).
            val midiChannel = if (channel.instrument.isDrum) 9 else (melodic++ % 15).let { if (it >= 9) it + 1 else it }
            tracks += noteTrack(channel.name, midiChannel, notes.map { i ->
                Note(events[i + 1], events[i + 2], events[i + 3].coerceIn(0, 127), events[i + 4].coerceIn(1, 127))
            })
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(int32(6))
        out.write(int16(1))
        out.write(int16(tracks.size))
        out.write(int16(TICKS_PER_BEAT))
        tracks.forEach { t ->
            out.write("MTrk".toByteArray())
            out.write(int32(t.size))
            out.write(t)
        }
        file.writeBytes(out.toByteArray())
    }

    private class Note(val start: Int, val length: Int, val pitch: Int, val velocity: Int)

    private class Track {
        private val events = ArrayList<Pair<Int, ByteArray>>()  // absolute tick to event bytes
        fun add(tick: Int, vararg bytes: Int) {
            events += tick to ByteArray(bytes.size) { bytes[it].toByte() }
        }
        fun meta(tick: Int, type: Int, data: ByteArray) {
            events += tick to (byteArrayOf(0xFF.toByte(), type.toByte()) + varLen(data.size) + data)
        }
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            var last = 0
            // Stable sort keeps note-offs (added first per tick, see noteTrack) ahead of note-ons.
            events.sortedBy { it.first }.forEach { (tick, data) ->
                out.write(varLen(tick - last))
                out.write(data)
                last = tick
            }
            out.write(varLen(0))
            out.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
            return out.toByteArray()
        }
    }

    private fun tempoTrack(project: Project): ByteArray {
        val t = Track()
        t.meta(0, 0x03, project.name.toByteArray())
        val usPerBeat = 60_000_000 / project.bpm.coerceAtLeast(1)
        t.meta(0, 0x51, byteArrayOf((usPerBeat shr 16).toByte(), (usPerBeat shr 8).toByte(), usPerBeat.toByte()))
        t.meta(0, 0x58, byteArrayOf(4, 2, 24, 8))  // 4/4
        t.meta(0, 0x59, byteArrayOf(keySharps(project.key, project.minor).toByte(), if (project.minor) 1 else 0))
        return t.bytes()
    }

    private fun noteTrack(name: String, ch: Int, notes: List<Note>): ByteArray {
        val t = Track()
        t.meta(0, 0x03, name.toByteArray())
        // Note-offs first, so a note that ends where the next one starts doesn't cut it.
        notes.forEach { n -> t.add(n.start + maxOf(1, n.length), 0x80 or ch, n.pitch, 0) }
        notes.forEach { n -> t.add(n.start, 0x90 or ch, n.pitch, n.velocity) }
        return t.bytes()
    }

    /** Sharps (+) or flats (-) in the key signature of [key] (0 = C) major or minor. */
    private fun keySharps(key: Int, minor: Boolean): Int {
        val major = if (minor) (key + 3) % 12 else key
        return intArrayOf(0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)[major]
    }

    private fun varLen(value: Int): ByteArray {
        var v = value.coerceAtLeast(0)
        val bytes = ArrayList<Byte>()
        bytes += (v and 0x7F).toByte()
        v = v shr 7
        while (v > 0) {
            bytes += ((v and 0x7F) or 0x80).toByte()
            v = v shr 7
        }
        return bytes.reversed().toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
}
