// Web version of app/src/main/java/com/mobiledaw/audio/Recorder.kt: the microphone through the
// browser (getUserMedia + a small AudioWorklet in platform.js), written to the same 16-bit WAV.
@file:Suppress("PackageDirectoryMismatch")

package com.mobiledaw.audio

import com.mobiledaw.web.toKotlin
import org.khronos.webgl.Float32Array
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private external fun micStart(peakFrames: Int): Boolean
private external fun micRate(): Int
private external fun micLevel(): Double
private external fun micFrames(): Double
private external fun micStartedAt(): Double
private external fun micPeaks(): Float32Array
private external fun micStop(): Float32Array

class Recorder {
    class Take(val file: File, val seconds: Float)

    var level = 0f
        get() = if (running) micLevel().toFloat() else 0f
        private set

    private var running = false
    private var file: File? = null

    val isRunning: Boolean get() = running
    val sampleRate: Int get() = micRate()

    /** Which frame of the take was being captured at [nanos] (System.nanoTime). */
    fun frameAt(nanos: Long): Long {
        val startedNanos = (micStartedAt() * 1_000_000.0).toLong()
        return (nanos - startedNanos) * sampleRate / 1_000_000_000L
    }

    fun peaksSnapshot(): FloatArray = micPeaks().toKotlin()

    fun start(out: File): Boolean {
        if (running) return false
        if (!micStart(sampleRate * PEAK_MS / 1000)) return false
        file = out
        running = true
        return true
    }

    fun stop(): Take? {
        if (!running) return null
        running = false
        val samples = micStop().toKotlin()
        val out = file ?: return null
        file = null
        val rate = sampleRate
        val max = rate * WavDecoder.MAX_RECORDING_SECONDS
        val n = minOf(samples.size, max)
        if (n < rate / 5) return null // under 0.2 s: treat as an accidental tap
        val data = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".encodeToByteArray()); data.putInt(36 + n * 2); data.put("WAVE".encodeToByteArray())
        data.put("fmt ".encodeToByteArray()); data.putInt(16); data.putShort(1); data.putShort(1)
        data.putInt(rate); data.putInt(rate * 2); data.putShort(2); data.putShort(16)
        data.put("data".encodeToByteArray()); data.putInt(n * 2)
        for (i in 0 until n) data.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        out.parentFile?.mkdirs()
        out.writeBytes(data.array())
        return Take(out, n.toFloat() / rate)
    }

    companion object {
        const val PEAK_MS = 25
    }
}

