package com.mobiledaw.export

import com.mobiledaw.audio.NativeEngine
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Where rendered audio goes, block by block (interleaved stereo floats). */
interface AudioSink {
    fun write(pcm: FloatArray, frames: Int)
    fun close()
}

/** Streams a stereo PCM .wav file (16- or 24-bit); the header's sizes are filled in on [close]. */
class WavWriter(private val file: File, private val sampleRate: Int, private val bits: Int) : AudioSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bytesPerSample = bits / 8
    private var dataBytes = 0L
    private var buf = ByteArray(0)
    private var seed = 0x2545F491

    init {
        require(bits == 16 || bits == 24)
        out.write(header(0))
    }

    private fun header(dataSize: Long): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataSize).coerceAtMost(0xFFFFFFFFL).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate)
        h.putInt(sampleRate * 2 * bytesPerSample).putShort((2 * bytesPerSample).toShort()).putShort(bits.toShort())
        h.put("data".toByteArray()).putInt(dataSize.coerceAtMost(0xFFFFFFFFL).toInt())
        return h.array()
    }

    /** Triangular dither of one LSB, only for 16-bit (24-bit is far below hearing). */
    private fun dither(): Float {
        seed = seed * 1664525 + 1013904223
        val a = (seed ushr 8) / 16777216f
        seed = seed * 1664525 + 1013904223
        val b = (seed ushr 8) / 16777216f
        return a - b
    }

    override fun write(pcm: FloatArray, frames: Int) {
        val n = frames * 2
        if (buf.size < n * bytesPerSample) buf = ByteArray(n * bytesPerSample)
        var o = 0
        for (i in 0 until n) {
            val x = pcm[i].coerceIn(-1f, 1f)
            if (bits == 16) {
                val v = (x * 32767f + dither()).roundToInt().coerceIn(-32768, 32767)
                buf[o++] = v.toByte()
                buf[o++] = (v shr 8).toByte()
            } else {
                val v = (x * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                buf[o++] = v.toByte()
                buf[o++] = (v shr 8).toByte()
                buf[o++] = (v shr 16).toByte()
            }
        }
        out.write(buf, 0, o)
        dataBytes += o
    }

    override fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(dataBytes)) }
    }
}

/** Streams a constant-bitrate stereo MP3 through the engine's LAME encoder. */
class Mp3Writer(file: File, sampleRate: Int, kbps: Int) : AudioSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val handle = NativeEngine.mp3Open(sampleRate, kbps)

    init {
        if (handle == 0L) {
            out.close()
            throw IllegalStateException("The MP3 encoder could not start at $sampleRate Hz / $kbps kbps")
        }
    }

    override fun write(pcm: FloatArray, frames: Int) {
        out.write(NativeEngine.mp3Encode(handle, pcm, frames))
    }

    override fun close() {
        out.write(NativeEngine.mp3Finish(handle))
        out.close()
    }
}
