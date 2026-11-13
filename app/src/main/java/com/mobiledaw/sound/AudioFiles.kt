package com.mobiledaw.sound

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.mobiledaw.audio.WavDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Decodes audio files of any format Android supports (MP3, OGG, FLAC, AAC, WAV...) to mono floats. */
object AudioFiles {
    private const val MAX_SECONDS = 60

    fun decode(file: File): WavDecoder.Decoded? {
        if (file.extension.equals("wav", ignoreCase = true)) {
            WavDecoder.decode(file)?.let { return it }
        }
        return runCatching { decodeWithMediaCodec(file) }.getOrNull()
    }

    private fun decodeWithMediaCodec(file: File): WavDecoder.Decoded? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = FloatList()
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val limit = { out.size >= rate.toLong() * MAX_SECONDS }
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || limit()) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        appendMono(buffer, encoding, channels.coerceAtLeast(1), out)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()
            if (out.size == 0) return null
            return WavDecoder.Decoded(out.toArray(), rate.toFloat())
        } finally {
            extractor.release()
        }
    }

    private fun appendMono(buffer: ByteBuffer, encoding: Int, channels: Int, out: FloatList) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buffer.asFloatBuffer()
            while (fb.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += fb.get() }
                out.add(sum / channels)
            }
        } else {
            val sb = buffer.asShortBuffer()
            while (sb.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += sb.get() / 32768f }
                out.add(sum / channels)
            }
        }
    }

    /** Writes 16-bit PCM WAV: mono, or stereo when [right] is given (same length as [frames]). */
    fun writeWav(file: File, frames: FloatArray, sampleRate: Int, right: FloatArray? = null) {
        file.parentFile?.mkdirs()
        val ch = if (right != null && right.size == frames.size) 2 else 1
        val bytes = frames.size * 2 * ch
        val data = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun put(s: Float) = data.putShort((s.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        for (i in frames.indices) {
            put(frames[i])
            if (ch == 2) put(right!![i])
        }
        val header = wavHeader(bytes, sampleRate, ch)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            raf.write(data.array())
        }
    }

    /** The 44-byte header of a 16-bit PCM WAV holding [dataBytes] of samples. */
    fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int): ByteBuffer =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(sampleRate * 2 * channels); putShort((2 * channels).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }

    /** Growable float array without boxing. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
