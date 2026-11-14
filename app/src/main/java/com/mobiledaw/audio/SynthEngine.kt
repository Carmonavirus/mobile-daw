package com.mobiledaw.audio

/**
 * JNI wrapper for the wavetable synth in app/src/main/cpp/wavesynth (see synth_jni.cpp).
 * Parameter and wavetable names come from the C++ side, which is the single source of truth.
 */
object SynthEngine {
    init {
        System.loadLibrary("mobiledaw") // same library as NativeEngine; loading twice is harmless
    }

    external fun paramNames(): Array<String>

    /** Flat [min, max, default, stepped] per parameter, in [paramNames] order. */
    external fun paramRanges(): FloatArray
    external fun tableNames(): Array<String>

    /** One cycle of [table] at [position] (0..1), for drawing. */
    external fun tableShape(table: Int, position: Float, out: FloatArray)

    external fun setParam(channel: Int, param: Int, value: Float)
    external fun setParams(channel: Int, values: FloatArray)

    /** Press ([on] = true) or release a note on a channel, e.g. from the synth's keyboard. */
    external fun noteHold(channel: Int, pitch: Int, velocity: Int, on: Boolean)
}
