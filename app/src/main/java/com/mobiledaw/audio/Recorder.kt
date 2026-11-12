package com.mobiledaw.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Records the microphone straight into a 16-bit mono WAV file on a background thread.
 * Keeps a coarse list of peaks (one every [PEAK_MS] ms) so the playlist can draw the take live.
 * The caller must hold the RECORD_AUDIO permission before calling [start].
 */
class Recorder {
    class Take(val file: File, val seconds: Float)

    @Volatile var level = 0f
        private set

    private val peaks = ArrayList<Float>()
    private var thread: Thread? = null
    @Volatile private var running = false
    private var file: File? = null
    private var frames = 0L
    private var rate = 48000

    val isRunning: Boolean get() = running

    /** Peaks so far, each covering [PEAK_MS] ms. */
    fun peaksSnapshot(): FloatArray = synchronized(peaks) { peaks.toFloatArray() }

    @SuppressLint("MissingPermission")
    fun start(out: File): Boolean {
        if (running) return false
        val (record, sampleRate) = open() ?: return false
        rate = sampleRate
        out.parentFile?.mkdirs()
        val raf = RandomAccessFile(out, "rw").apply { setLength(0); write(ByteArray(44)) }
        file = out
        frames = 0
        synchronized(peaks) { peaks.clear() }
        level = 0f
        running = true
        try {
            record.startRecording()
        } catch (e: Exception) {
            running = false
            record.release()
            raf.close()
            out.delete()
            return false
        }
        thread = Thread({ loop(record, raf, sampleRate) }, "mic-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /** Stops and finishes the WAV file. Returns null if nothing usable was recorded. */
    fun stop(): Take? {
        if (!running && thread == null) return null
        running = false
        thread?.join(2000)
        thread = null
        level = 0f
        val out = file ?: return null
        file = null
        val seconds = frames.toFloat() / rate
        if (frames < rate / 5) { // under 0.2 s: treat as an accidental tap
            out.delete()
            return null
        }
        return Take(out, seconds)
    }

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, Int>? {
        for (sampleRate in intArrayOf(48000, 44100)) {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min * 2, sampleRate / 5 * 2),
                )
            } catch (e: Exception) {
                null
            } ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to sampleRate
            record.release()
        }
        return null
    }

    private fun loop(record: AudioRecord, raf: RandomAccessFile, sampleRate: Int) {
        val chunk = ShortArray(sampleRate / 50) // 20 ms
        val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        val maxFrames = sampleRate.toLong() * WavDecoder.MAX_RECORDING_SECONDS
        val perPeak = sampleRate * PEAK_MS / 1000
        var peak = 0f
        var inPeak = 0
        try {
            while (running && frames < maxFrames) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                bytes.clear()
                var blockPeak = 0f
                for (i in 0 until n) {
                    val s = chunk[i]
                    bytes.putShort(s)
                    val a = abs(s / 32768f)
                    if (a > blockPeak) blockPeak = a
                    if (a > peak) peak = a
                    if (++inPeak >= perPeak) {
                        synchronized(peaks) { peaks += peak }
                        peak = 0f
                        inPeak = 0
                    }
                }
                raf.write(bytes.array(), 0, n * 2)
                frames += n
                level = blockPeak
            }
        } finally {
            running = false
            try { record.stop() } catch (_: Exception) {}
            record.release()
            writeHeader(raf, sampleRate, frames)
            raf.close()
        }
    }

    private fun writeHeader(raf: RandomAccessFile, sampleRate: Int, frames: Long) {
        val dataLen = (frames * 2).toInt()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        raf.seek(0)
        raf.write(h.array())
    }

    companion object {
        const val PEAK_MS = 25
    }
}
