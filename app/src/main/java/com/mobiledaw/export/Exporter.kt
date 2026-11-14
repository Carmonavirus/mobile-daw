package com.mobiledaw.export

import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.model.Project
import com.mobiledaw.model.SequenceCompiler
import com.mobiledaw.model.TICKS_PER_BEAT
import java.io.File
import kotlin.math.abs

/** What to export and how. */
data class ExportOptions(
    val wav: Boolean = true,
    val mp3: Boolean = true,
    val stems: Boolean = false,
    val midi: Boolean = false,
    /** True: the whole playlist; false: the selected pattern, once. */
    val song: Boolean = true,
    val wavBits: Int = 24,
    val mp3Kbps: Int = 320,
) {
    val any: Boolean get() = wav || mp3 || stems || midi
}

/** One stem: the engine slots it is made of, and its file name. */
data class Stem(val name: String, val mask: Long)

class ExportCancelled : Exception()

/**
 * Bounces the project offline through the engine (renderMix: same sound as playback, never the
 * metronome) into WAV / MP3 / stem WAVs and writes a MIDI file, all into [outDir].
 *
 * The full mix is always rendered first: its length (the song plus the time its last sounds take
 * to ring out) is used for every stem, so all stems are exactly as long and line up at bar 1.
 */
class Exporter(
    private val project: Project,
    private val compiled: SequenceCompiler.Compiled,
    private val stems: List<Stem>,
    private val options: ExportOptions,
    private val outDir: File,
    private val baseName: String,
    /** (0..1, what is happening) */
    private val progress: (Float, String) -> Unit,
    private val cancelled: () -> Boolean,
) {
    private val block = 2048

    fun run(): List<File> {
        val files = ArrayList<File>()
        val passes = 1 + if (options.stems) stems.size else 0
        val sampleRate = NativeEngine.offlineBegin()
        try {
            NativeEngine.setSequence(compiled.events, compiled.loopTicks)
            val songFrames = (compiled.loopTicks.toDouble() / TICKS_PER_BEAT * 60.0 / project.bpm * sampleRate).toLong()

            // 1. The full mix (sets the length every stem gets).
            val mixSinks = ArrayList<AudioSink>()
            if (options.wav) File(outDir, "$baseName.wav").also { files += it; mixSinks += WavWriter(it, sampleRate, options.wavBits) }
            if (options.mp3) File(outDir, "$baseName.mp3").also { files += it; mixSinks += Mp3Writer(it, sampleRate, options.mp3Kbps) }
            val total = render(-1L, mixSinks, sampleRate, songFrames, null) { f -> progress(f / passes, "Rendering the mix") }

            // 2. Stems, one pass each, all exactly `total` frames long.
            if (options.stems && stems.isNotEmpty()) {
                val dir = File(outDir, "$baseName stems").apply { mkdirs() }
                val used = HashMap<String, Int>()
                stems.forEachIndexed { i, stem ->
                    val n = (used[stem.name] ?: 0) + 1
                    used[stem.name] = n
                    val name = safeName(if (n > 1) "${stem.name} $n" else stem.name)
                    val file = File(dir, "${(i + 1).toString().padStart(2, '0')} $name.wav")
                    files += file
                    render(stem.mask, listOf(WavWriter(file, sampleRate, options.wavBits)), sampleRate, songFrames, total) { f ->
                        progress((1 + i + f) / passes, "Stem ${i + 1} of ${stems.size}: ${stem.name}")
                    }
                }
            }
        } finally {
            NativeEngine.offlineEnd()
        }
        if (options.midi) {
            File(outDir, "$baseName.mid").also { MidiWriter.write(it, project, compiled.events); files += it }
        }
        progress(1f, "Done")
        return files
    }

    /**
     * One bounce into [sinks]. With [exactFrames] it renders exactly that many frames; otherwise it
     * renders until the song is over and the tail has gone quiet (at most 10 s of tail). Returns
     * the frames rendered.
     */
    private fun render(mask: Long, sinks: List<AudioSink>, sampleRate: Int, songFrames: Long, exactFrames: Long?, onProgress: (Float) -> Unit): Long {
        val buf = FloatArray(block * 2)
        var frames = 0L
        var tail = 0L
        var quietTail = 0L
        try {
            NativeEngine.offlineStart(mask)
            while (true) {
                if (cancelled()) throw ExportCancelled()
                val want = if (exactFrames != null) minOf(block.toLong(), exactFrames - frames).toInt() else block
                if (want <= 0) break
                val playing = NativeEngine.offlineRender(buf, want)
                sinks.forEach { it.write(buf, want) }
                frames += want
                if (exactFrames == null && !playing) {
                    tail += want
                    var peak = 0f
                    for (i in 0 until want * 2) peak = maxOf(peak, abs(buf[i]))
                    // -80 dB for a quarter of a second counts as silence.
                    quietTail = if (peak < 1e-4f) quietTail + want else 0
                    if (quietTail >= sampleRate / 4 || tail >= 10L * sampleRate) break
                }
                val expected = exactFrames ?: (songFrames + sampleRate / 2)
                if (frames % (block * 8) == 0L) onProgress((frames.toFloat() / expected).coerceIn(0f, 1f))
            }
        } finally {
            sinks.forEach { runCatching { it.close() } }
        }
        onProgress(1f)
        return frames
    }

    companion object {
        /** Characters a file name can't hold on Android's storage, replaced with "_". */
        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "Untitled" }.take(80)

        /**
         * The stems of a compiled sequence: one per rack channel that plays something, and one per
         * mixer track for the playlist's audio clips (recordings and imported audio).
         */
        fun stemsFor(project: Project, compiled: SequenceCompiler.Compiled, maxSlots: Int): List<Stem> {
            val playing = HashSet<Int>()
            for (i in 0 until compiled.events.size / 5) playing += compiled.events[i * 5]
            val out = ArrayList<Stem>()
            project.channels.take(maxSlots).forEachIndexed { slot, c ->
                if (slot in playing && !c.mute) out += Stem(c.name, 1L shl slot)
            }
            val audio = LinkedHashMap<String, Long>()
            SequenceCompiler.audioSlots(project, maxSlots).forEach { (slot, clip) ->
                if (slot !in playing || clip.muted) return@forEach
                val track = project.mixerTrack(clip.trackId ?: project.recordingTrackId)
                val name = track?.name ?: "Audio"
                audio[name] = (audio[name] ?: 0L) or (1L shl slot)
            }
            audio.forEach { (name, mask) -> out += Stem(name, mask) }
            return out
        }
    }
}
