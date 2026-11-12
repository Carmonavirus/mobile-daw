package com.mobiledaw.audio

/** Thin JNI wrapper around the C++ engine in app/src/main/cpp. */
object NativeEngine {
    /** Channel slots in the engine, plus one extra slot for the master meter. */
    const val MAX_CHANNELS = 32
    const val AUDITION_SLOT = MAX_CHANNELS

    init {
        System.loadLibrary("mobiledaw")
    }

    external fun start(): Boolean
    external fun stop()
    external fun setPlaying(playing: Boolean)
    external fun seek(tick: Long)
    external fun setBpm(bpm: Float)
    external fun setMasterVolume(volume: Float)
    external fun setChannelCount(count: Int)
    external fun setChannel(index: Int, instrument: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean)

    /** Cut itself, trim (0..1 of the sample) and the sampler envelope (delay/attack/hold/decay/sustain/release/tension in s / level). */
    external fun setChannelSampler(index: Int, cutItself: Boolean, trimStart: Float, trimEnd: Float, envDelay: Float, attack: Float, hold: Float, decay: Float, sustain: Float, release: Float, tension: Float, pitchSemis: Int, reverse: Boolean)

    /** Marks a slot as an audio-clip lane: starting playback inside one of its notes plays from there. */
    external fun setChannelClip(index: Int, resumeMidway: Boolean)

    /** [events] is flat: channel, startTick, lengthTicks, pitch, velocity (0..127) per note. */
    external fun setSequence(events: IntArray, loopTicks: Int)
    external fun previewNote(channel: Int, pitch: Int, velocity: Int)
    /** Slot 0..MAX_CHANNELS-1 is a channel, [AUDITION_SLOT] is the kit browser preview. */
    external fun setSample(slot: Int, frames: FloatArray?, sampleRate: Float)
    external fun audition()
    external fun positionTicks(): Long

    /** Time from the engine writing audio to it leaving the speaker / earbuds, in ms (stream timestamps). < 0 = unknown. */
    external fun outputLatencyMs(): Double
    /** The output buffer alone, in ms. < 0 = no stream. */
    external fun bufferLatencyMs(): Double
    /** AudioDeviceInfo id of the output in use, 0 = unknown. */
    external fun outputDeviceId(): Int

    /** Fills per-channel peaks; the last slot receives the master peak. */
    external fun readLevels(out: FloatArray)
    external fun readScope(out: FloatArray)

    // ---- mixer tracks (see app/src/main/cpp/fx)
    const val MAX_INSERTS = 8
    const val MAX_SLOTS = 8

    /** Routes a channel slot to mixer track [insert] (0 until MAX_INSERTS), or -1 for the master. */
    external fun setChannelInsert(channel: Int, insert: Int)
    external fun setInsertCount(count: Int)
    external fun setInsert(index: Int, volume: Float, pan: Float, mute: Boolean)
    /** [type] is an EffectType id, 0 empties the slot. */
    external fun setInsertEffect(index: Int, slot: Int, type: Int, bypass: Boolean)
    external fun setEffectParams(index: Int, slot: Int, values: FloatArray)
    external fun readInsertLevels(out: FloatArray)
    /** Newest post-effects samples of a mixer track (mono), oldest first. */
    external fun readInsertSignal(index: Int, out: FloatArray)
    /** out[0] = detected Hz, out[1] = corrected Hz; 0 when nothing is sung. */
    external fun readPitch(index: Int, slot: Int, out: FloatArray)
}
