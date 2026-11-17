package com.mobiledaw.export

import com.mobiledaw.model.Project
import com.mobiledaw.model.TICKS_PER_BEAT
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes the notes of a compiled sequence as Standard MIDI Files, in two shapes:
 *
 *  - [writeSong]: one type 1 file, a tempo track then one track per rack channel, named after the
 *    channel. FL Studio's File › Import › MIDI file turns each track into its own channel.
 *  - [writeChannel]: one type 0 file per channel, a single track holding just that channel's notes
 *    (plus the tempo). Dropped on a channel in FL Studio's channel rack, or into its piano roll, the
 *    notes land on that channel with no dialog about which track to take.
 *
 * Every file carries the song's tempo, 4/4 and key, uses [TICKS_PER_BEAT] (96) ticks per quarter
 * note, and ends at the end of the song, so a part with silence at the end keeps its full length.
 */
object MidiWriter {
    class Note(val start: Int, val length: Int, val pitch: Int, val velocity: Int)

    /** One rack channel's notes. */
    class Part(val name: String, val drum: Boolean, val notes: List<Note>)

    /** What every file's header carries. */
    class Song(val name: String, val bpm: Int, val key: Int, val minor: Boolean, val endTick: Int)

    fun song(project: Project, endTick: Int) = Song(project.name, project.bpm, project.key, project.minor, endTick)

    /**
     * The rack channels that play something, in rack order. [events] is the engine's flat list:
     * channel slot, start, length, pitch, velocity per note. Audio clip slots (past the rack) are
     * left out: they have no notes.
     */
    fun parts(project: Project, events: IntArray): List<Part> {
        val bySlot = HashMap<Int, ArrayList<Note>>()
        for (i in 0 until events.size / 5) {
            val e = i * 5
            bySlot.getOrPut(events[e]) { ArrayList() } +=
                Note(events[e + 1], events[e + 2], events[e + 3].coerceIn(0, 127), events[e + 4].coerceIn(1, 127))
        }
        return project.channels.mapIndexedNotNull { slot, channel ->
            bySlot[slot]?.let { Part(channel.name, channel.instrument.isDrum, it) }
        }
    }

    /** All [parts] in one type 1 file, a track each. */
    fun writeSong(file: File, song: Song, parts: List<Part>) = file.writeBytes(songBytes(song, parts))

    /** One part alone in a type 0 file, ready to drop on one channel. */
    fun writeChannel(file: File, song: Song, part: Part) = file.writeBytes(channelBytes(song, part))

    fun songBytes(song: Song, parts: List<Part>): ByteArray {
        val conductor = Track().apply {
            name(song.name)
            songMeta(song)
            end(song.endTick)
        }
        var melodic = 0
        val tracks = listOf(conductor.bytes()) + parts.map { part ->
            // Drum sounds on MIDI channel 10, everything else on its own channel (skipping 10).
            val ch = if (part.drum) 9 else (melodic++ % 15).let { if (it >= 9) it + 1 else it }
            Track().apply {
                name(part.name)
                notes(part.notes, ch)
                end(song.endTick)
            }.bytes()
        }
        return file(format = 1, tracks)
    }

    fun channelBytes(song: Song, part: Part): ByteArray {
        // MIDI channel 1 for everything: the receiving channel decides the sound, and FL Studio
        // doesn't treat channel 10 specially when notes are dropped on a channel.
        val track = Track().apply {
            name(part.name)
            songMeta(song)
            notes(part.notes, 0)
            end(song.endTick)
        }
        return file(format = 0, listOf(track.bytes()))
    }

    private fun file(format: Int, tracks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(int32(6))
        out.write(int16(format))
        out.write(int16(tracks.size))
        out.write(int16(TICKS_PER_BEAT))
        tracks.forEach { t ->
            out.write("MTrk".toByteArray())
            out.write(int32(t.size))
            out.write(t)
        }
        return out.toByteArray()
    }

    private class Track {
        private val events = ArrayList<Pair<Int, ByteArray>>()  // absolute tick to event bytes
        private var endTick = 0

        fun add(tick: Int, vararg bytes: Int) {
            events += tick to ByteArray(bytes.size) { bytes[it].toByte() }
        }
        fun meta(tick: Int, type: Int, data: ByteArray) {
            events += tick to (byteArrayOf(0xFF.toByte(), type.toByte()) + varLen(data.size) + data)
        }
        fun name(text: String) = meta(0, 0x03, text.encodeToByteArray())

        fun songMeta(song: Song) {
            val usPerBeat = 60_000_000 / song.bpm.coerceAtLeast(1)
            meta(0, 0x51, byteArrayOf((usPerBeat shr 16).toByte(), (usPerBeat shr 8).toByte(), usPerBeat.toByte()))
            meta(0, 0x58, byteArrayOf(4, 2, 24, 8))  // 4/4
            meta(0, 0x59, byteArrayOf(keySharps(song.key, song.minor).toByte(), if (song.minor) 1 else 0))
        }

        fun notes(notes: List<Note>, ch: Int) {
            // Note-offs first, so a note that ends where the next one starts doesn't cut it.
            notes.forEach { n -> add(n.start + maxOf(1, n.length), 0x80 or ch, n.pitch, 0) }
            notes.forEach { n -> add(n.start, 0x90 or ch, n.pitch, n.velocity) }
        }

        /** The track ends at [tick], or after its last event if that is later. */
        fun end(tick: Int) {
            endTick = tick
        }

        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            var last = 0
            // Stable sort keeps meta events and note-offs (added first per tick) ahead of note-ons.
            events.sortedBy { it.first }.forEach { (tick, data) ->
                out.write(varLen(tick - last))
                out.write(data)
                last = tick
            }
            out.write(varLen(maxOf(0, endTick - last)))
            out.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
            return out.toByteArray()
        }
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
