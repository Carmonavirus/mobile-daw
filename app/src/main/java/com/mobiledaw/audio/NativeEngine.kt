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

    /** Cut itself, trim (0..1 of the sample) and attack/release fades in seconds. */
    external fun setChannelSampler(index: Int, cutItself: Boolean, trimStart: Float, trimEnd: Float, attack: Float, release: Float, pitchSemis: Int, reverse: Boolean)

    /** [events] is flat: channel, startTick, lengthTicks, pitch, velocity (0..127) per note. */
    external fun setSequence(events: IntArray, loopTicks: Int)
    external fun previewNote(channel: Int, pitch: Int, velocity: Int)
    /** Slot 0..MAX_CHANNELS-1 is a channel, [AUDITION_SLOT] is the kit browser preview. */
    external fun setSample(slot: Int, frames: FloatArray?, sampleRate: Float)
    external fun audition()
    external fun positionTicks(): Long

    /** Fills per-channel peaks; the last slot receives the master peak. */
    external fun readLevels(out: FloatArray)
    external fun readScope(out: FloatArray)
}
