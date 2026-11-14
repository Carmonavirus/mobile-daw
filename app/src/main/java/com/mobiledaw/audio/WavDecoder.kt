package com.mobiledaw.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads WAV files (8/16/24/32-bit PCM and 32-bit float) into floats: mono by default, or with
 * `stereo = true` the left channel in [Decoded.frames] and the right one in [Decoded.right].
 */
object WavDecoder {
    /** [frames] is mono (or the left channel); [right] is the right channel of a stereo sound, else null. */
    class Decoded(val frames: FloatArray, val sampleRate: Float, val right: FloatArray? = null) {
        val channels: Int get() = if (right != null) 2 else 1
    }

    /** Longest sound we load, to keep memory in check on long loops. */
    private const val MAX_SECONDS = 60

    /** Longest recording we load (audio clips on the playlist). */
    const val MAX_RECORDING_SECONDS = 300

    fun decode(file: File, maxSeconds: Int = MAX_SECONDS, stereo: Boolean = false): Decoded? =
        runCatching { decode(file.readBytes(), maxSeconds, stereo) }.getOrNull()

    fun decode(bytes: ByteArray, maxSeconds: Int = MAX_SECONDS, stereo: Boolean = false): Decoded? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(buf, 0) != "RIFF" || tag(buf, 8) != "WAVE") return null
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(buf, pos)
            val len = buf.getInt(pos + 4).toLong().and(0xFFFFFFFFL).toInt()
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                    if (format == 0xFFFE && len >= 26) format = buf.getShort(body + 24).toInt() and 0xFFFF
                }
                "data" -> {
                    dataStart = body
                    dataLen = minOf(len, bytes.size - body)
                }
            }
            if (dataStart >= 0 && channels > 0) break
            pos = body + len + (len and 1)
        }
        if (dataStart < 0 || channels <= 0 || rate <= 0) return null
        val isFloat = format == 3
        if (!isFloat && format != 1) return null
        val bytesPerSample = bits / 8
        if (bytesPerSample !in 1..4) return null
        val frameSize = bytesPerSample * channels
        val frameCount = minOf(dataLen / frameSize, rate * maxSeconds)
        fun sample(f: Int, c: Int): Float {
            val o = dataStart + f * frameSize + c * bytesPerSample
            return when {
                isFloat && bytesPerSample == 4 -> buf.getFloat(o)
                bytesPerSample == 1 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                bytesPerSample == 2 -> buf.getShort(o) / 32768f
                bytesPerSample == 3 -> {
                    val v = (bytes[o].toInt() and 0xFF) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        (bytes[o + 2].toInt() shl 16)
                    v / 8388608f
                }
                else -> buf.getInt(o) / 2147483648f
            }
        }
        if (stereo && channels >= 2) {
            val left = FloatArray(frameCount) { sample(it, 0) }
            val right = FloatArray(frameCount) { sample(it, 1) }
            return Decoded(left, rate.toFloat(), right)
        }
        val out = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) sum += sample(f, c)
            out[f] = sum / channels
        }
        return Decoded(out, rate.toFloat())
    }

    private fun tag(buf: ByteBuffer, at: Int): String =
        String(CharArray(4) { (buf.get(at + it).toInt() and 0xFF).toChar() })
}
