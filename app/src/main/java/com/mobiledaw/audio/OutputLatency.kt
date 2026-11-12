package com.mobiledaw.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Keeps the screen in step with what you hear.
 *
 * Audio leaves the engine some time before it reaches your ears: a few ms on the phone speaker,
 * often 150–300 ms on Bluetooth earbuds. The playhead, step lights and meters are read straight
 * from the engine, so without help they run ahead of the sound. [VisualDelay] remembers the
 * recent engine readings and hands back the one from [delayMs] ago instead.
 */
class VisualDelay {
    class Frame(
        val nanos: Long,
        val tick: Long,
        val levels: FloatArray,
        val scope: FloatArray,
        val trackLevels: FloatArray,
    )

    private val frames = ArrayDeque<Frame>()

    fun clear() = frames.clear()

    fun push(frame: Frame) {
        frames.addLast(frame)
        // Keep a little over the longest delay the setting allows.
        val oldest = frame.nanos - (MAX_DELAY_MS + 250) * 1_000_000L
        while (frames.size > 2 && frames[1].nanos < oldest) frames.removeFirst()
    }

    /**
     * The reading the listener is hearing now, i.e. the one from [delayMs] ago. The tick is
     * interpolated between readings so the playhead glides; it never interpolates across a
     * loop wrap or a jump. Null when nothing has been pushed yet.
     */
    fun at(nowNanos: Long, delayMs: Int): Frame? {
        if (frames.isEmpty()) return null
        val target = nowNanos - delayMs * 1_000_000L
        // Before the first reading (just pressed play / jumped): hold the first one.
        if (target <= frames.first().nanos) return frames.first()
        var i = frames.size - 1
        while (i > 0 && frames[i].nanos > target) i--
        val a = frames[i]
        val b = frames.getOrNull(i + 1) ?: return a
        val span = b.nanos - a.nanos
        if (span <= 0 || b.tick < a.tick || b.tick - a.tick > MAX_GLIDE_TICKS) return a
        val t = (target - a.nanos).toDouble() / span
        val tick = a.tick + ((b.tick - a.tick) * t).toLong()
        return Frame(target, tick, a.levels, a.scope, a.trackLevels)
    }

    companion object {
        const val MAX_DELAY_MS = 1000
        /** More than this between two readings is a jump (seek), not playback: don't glide. */
        private const val MAX_GLIDE_TICKS = 480L
    }
}

/** The output the app is playing through, as shown in Settings. [key] names it in saved settings. */
data class OutputDevice(val key: String, val label: String, val wireless: Boolean)

object OutputDevices {
    private val PREFERENCE = intArrayOf(
        26, // TYPE_BLE_HEADSET (API 31)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        27, // TYPE_BLE_SPEAKER (API 31)
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )
    private val WIRELESS = setOf(26, 27, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID)

    /**
     * Describes the output with AudioDeviceInfo id [id] (from the engine's stream). When the
     * stream doesn't say, guesses the way Android routes media: Bluetooth, then wired, then speaker.
     */
    fun describe(context: Context, id: Int): OutputDevice {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val device = outputs.firstOrNull { id != 0 && it.id == id && it.type in PREFERENCE }
            ?: PREFERENCE.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
            ?: return OutputDevice("speaker", "Phone speaker", false)
        val name = device.productName?.toString()?.trim().orEmpty()
        val label = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            else -> name.ifEmpty { if (device.type in WIRELESS) "Bluetooth audio" else "USB audio" }
        }
        val key = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            else -> "${device.type}:$name"
        }
        return OutputDevice(key, label, device.type in WIRELESS)
    }
}

/** Smooths the engine's latency readings (they wobble by a few ms) with a running median. */
class LatencyMeter {
    private val readings = ArrayDeque<Double>()

    fun reset() = readings.clear()

    /** Adds a reading in ms; ignores nonsense. Returns the smoothed value, or null with no readings yet. */
    fun add(ms: Double): Int? {
        if (ms > 0 && ms < 2000) {
            readings.addLast(ms)
            while (readings.size > 9) readings.removeFirst()
        }
        if (readings.isEmpty()) return null
        val sorted = readings.sorted()
        return Math.round(sorted[sorted.size / 2]).toInt()
    }
}
