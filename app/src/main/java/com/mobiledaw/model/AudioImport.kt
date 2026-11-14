package com.mobiledaw.model

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.mobiledaw.sound.AudioFiles
import com.mobiledaw.audio.WavDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Audio files brought into the app, from the playlist's "Add audio file" picker or from
 * "Share" / "Open with" in a browser, Files or Downloads (e.g. a loop downloaded from Looperman).
 * Any format Android can decode (WAV, MP3, OGG, FLAC, M4A...) is converted to WAV and copied
 * into the app (files/imports/), so projects keep working even after the download is deleted.
 */
class AudioImport(private val context: Context) {
    private val dir = File(context.filesDir, DIR)

    class Imported(val path: String, val name: String, val seconds: Float)

    /** The audio files an incoming share / open-with intent carries (none for a normal launch). */
    fun urisFrom(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val list = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamListExtra()
            else -> return emptyList()
        }
        // Some apps put the files only in ClipData.
        val clip = intent.clipData
        val fromClip = if (list.isEmpty() && clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return (list + fromClip).distinct()
    }

    /** Copies and converts one file. Returns null if it isn't audio Android can read. */
    fun import(uri: Uri): Imported? {
        val display = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "audio"
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.$ext")
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (copied <= 0) return null
            dir.mkdirs()
            val name = cleanName(display)
            val target = uniqueFile(name)
            val seconds = convert(tmp, target)
            if (seconds == null || seconds <= 0f) {
                target.delete()
                return null
            }
            return Imported(target.relativeTo(context.filesDir).path, name, seconds)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Converts a file the app downloaded itself (e.g. a reel's video from a link) and names the
     * clip [display]. The audio track is decoded; any video is ignored. [source] is left in place.
     */
    fun importFile(source: File, display: String): Imported? {
        dir.mkdirs()
        val name = cleanName(display)
        val target = uniqueFile(name)
        val seconds = runCatching { convert(source, target) }.getOrNull()
        if (seconds == null || seconds <= 0f) {
            target.delete()
            return null
        }
        return Imported(target.relativeTo(context.filesDir).path, name, seconds)
    }

    /**
     * Writes [source] as 16-bit WAV to [target] at its own sample rate, up to [MAX_SECONDS]. Stereo
     * stays stereo (left/right kept apart, like FL Studio); files with more channels keep the first two. Compressed formats are
     * decoded straight to the file block by block, so a long MP3 never sits in memory as floats.
     * Returns the length in seconds, or null if it can't be read.
     */
    private fun convert(source: File, target: File): Float? {
        if (source.extension.equals("wav", ignoreCase = true)) {
            WavDecoder.decode(source, MAX_SECONDS, stereo = true)?.let { d ->
                if (d.frames.isEmpty() || d.sampleRate <= 0f) return null
                AudioFiles.writeWav(target, d.frames, d.sampleRate.roundToInt(), d.right)
                return d.frames.size / d.sampleRate
            }
        }
        return runCatching { streamToWav(source, target) }.getOrNull()
    }

    private fun streamToWav(source: File, target: File): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            var frames = 0L
            var outChannels = 0  // fixed by the first decoded block
            RandomAccessFile(target, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44)) // header, filled in at the end
                val out = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                fun flush() {
                    raf.write(out.array(), 0, out.position())
                    out.clear()
                }
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    val full = frames >= rate.toLong() * MAX_SECONDS
                    if (!inputDone) {
                        val inIndex = c.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = c.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0 || full) {
                                c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        outIndex >= 0 -> {
                            val buffer = c.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val ch = channels.coerceAtLeast(1)
                            val keep = minOf(ch, 2)  // stereo stays stereo; extra channels are dropped
                            if (outChannels == 0) outChannels = keep
                            val limit = rate.toLong() * MAX_SECONDS
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buffer.asFloatBuffer()
                                while (fb.remaining() >= ch && frames < limit) {
                                    if (out.remaining() < 4) flush()
                                    val l = fb.get()
                                    val r = if (ch >= 2) fb.get() else l
                                    repeat(ch - minOf(ch, 2)) { fb.get() }
                                    out.putShort((l.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                                    if (outChannels == 2) out.putShort((r.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                                    frames++
                                }
                            } else {
                                val sb = buffer.asShortBuffer()
                                while (sb.remaining() >= ch && frames < limit) {
                                    if (out.remaining() < 4) flush()
                                    val l = sb.get()
                                    val r = if (ch >= 2) sb.get() else l
                                    repeat(ch - minOf(ch, 2)) { sb.get() }
                                    out.putShort(l)
                                    if (outChannels == 2) out.putShort(r)
                                    frames++
                                }
                            }
                            c.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                flush()
                if (frames == 0L || rate <= 0) return null
                val dataBytes = (frames * 2 * outChannels).toInt()
                val header = AudioFiles.wavHeader(dataBytes, rate, outChannels)
                raf.seek(0)
                raf.write(header.array())
            }
            return frames.toFloat() / rate
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** "looperman-l-1234567-0345678-dark-trap-melody.mp3" -> "dark trap melody". */
    private fun cleanName(display: String): String {
        var name = display.substringBeforeLast('.').trim()
        name = name.replace(Regex("^looperman-[a-z]-\\d+-\\d+-?", RegexOption.IGNORE_CASE), "")
        name = name.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        name = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return name.take(40).trim().ifEmpty { "Audio" }
    }

    private fun uniqueFile(base: String): File {
        var f = File(dir, "$base.wav")
        var i = 2
        while (f.exists()) f = File(dir, "$base ${i++}.wav")
        return f
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()

    companion object {
        const val DIR = "imports"

        /** Longest audio file we import (5 minutes), the same as a recording. */
        const val MAX_SECONDS = WavDecoder.MAX_RECORDING_SECONDS
    }
}
