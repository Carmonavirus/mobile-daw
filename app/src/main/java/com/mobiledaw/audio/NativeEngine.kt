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
    external fun setChannelSampler(index: Int, cutItself: Boolean, trimStart: Float, trimEnd: Float, attack: Float, hold: Float, decay: Float, sustain: Float, release: Float, pitchSemis: Int, reverse: Boolean)

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

    // ---- extra engines (SoundFont, Macro, Modeled, Multisample); see cpp/SoundEngines.h

    /** What a channel slot plays with an extra engine: preset/model [variant] and SoundFont [bank]. */
    external fun setChannelEngine(slot: Int, instrument: Int, variant: Int, bank: Int)
    /** Loads a SoundFont from the APK's assets; returns a bank id, or -1. Slow: call off the main thread. */
    external fun loadSoundFontAsset(assets: android.content.res.AssetManager, name: String): Int
    /** Loads a .sf2/.sf3 file; returns a bank id, or -1. Slow: call off the main thread. */
    external fun loadSoundFontFile(path: String): Int
    /** One "bank|program|name" string per preset index. */
    external fun soundFontPresets(bank: Int): Array<String>
    /** Built-in presets of an engine: "Name|Group|drum(0/1)|pitch[|library]". */
    external fun enginePresets(instrument: Int): Array<String>
    /** Key/velocity zones for a multisample slot; see jni_bridge.cpp for the array layout. */
    external fun setMultiSample(slot: Int, zones: Array<FloatArray>?, rates: FloatArray?, meta: IntArray?, tuning: FloatArray?, release: Float)
    /** Plays one note of any extra engine without touching a channel. */
    external fun auditionEngine(instrument: Int, variant: Int, bank: Int, pitch: Int, velocity: Int)
}
