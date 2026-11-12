package com.mobiledaw.audio

/** Thin JNI wrapper around the C++ engine in app/src/main/cpp. */
object NativeEngine {
    /** Channel slots in the engine, plus one extra slot for the master meter. */
    const val MAX_CHANNELS = 32

    init {
        System.loadLibrary("mobiledaw")
    }

    external fun start(): Boolean
    external fun stop()
    external fun setPlaying(playing: Boolean)
    external fun setBpm(bpm: Float)
    external fun setMasterVolume(volume: Float)
    external fun setChannelCount(count: Int)
    external fun setChannel(index: Int, instrument: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean)

    /** [events] is flat: channel, startTick, lengthTicks, pitch, velocity (0..127) per note. */
    external fun setSequence(events: IntArray, loopTicks: Int)
    external fun previewNote(channel: Int, pitch: Int, velocity: Int)
    external fun positionTicks(): Long

    /** Fills per-channel peaks; the last slot receives the master peak. */
    external fun readLevels(out: FloatArray)
    external fun readScope(out: FloatArray)
}
