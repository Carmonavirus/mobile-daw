package com.mobiledaw.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads WAV files (8/16/24/32-bit PCM and 32-bit float) into mono floats. */
object WavDecoder {
    class Decoded(val frames: FloatArray, val sampleRate: Float)

    /** Longest sound we load, to keep memory in check on long loops. */
    private const val MAX_SECONDS = 60

    fun decode(file: File): Decoded? = runCatching { decode(file.readBytes()) }.getOrNull()

    fun decode(bytes: ByteArray): Decoded? {
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
        val frameCount = minOf(dataLen / frameSize, rate * MAX_SECONDS)
        val out = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) {
                val o = dataStart + f * frameSize + c * bytesPerSample
                sum += when {
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
            out[f] = sum / channels
        }
        return Decoded(out, rate.toFloat())
    }

    private fun tag(buf: ByteBuffer, at: Int): String =
        String(CharArray(4) { (buf.get(at + it).toInt() and 0xFF).toChar() })
}
