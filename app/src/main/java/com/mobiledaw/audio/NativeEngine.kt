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

    /** Stop all sound: stops playback and silences every voice, synth tail and audio clip at once. */
    external fun panic()
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

    /** Metronome click, made in the engine on each beat (beat 1 accented). Never in a bounce/export. */
    external fun setMetronome(on: Boolean, beatsPerBar: Int)
    /** Clicks [beats] beats (from stopped), then starts playback from the seek position on the next beat. */
    external fun startCountIn(beats: Int)
    /** The count-in beat just clicked (1..beats), or 0 when not counting in. */
    external fun countInBeat(): Int
    external fun isPlaying(): Boolean

    /** Time from the engine writing audio to it leaving the speaker / earbuds, in ms (stream timestamps). < 0 = unknown. */
    external fun outputLatencyMs(): Double
    /** The output buffer alone, in ms. < 0 = no stream. */
    external fun bufferLatencyMs(): Double
    /** AudioDeviceInfo id of the output in use, 0 = unknown. */
    external fun outputDeviceId(): Int

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

    // ---- export (see app/src/main/cpp/export_jni.cpp)

    /** Takes the engine off the live output (silent meanwhile) for offline rendering. Returns the sample rate. */
    external fun offlineBegin(): Int
    /** Starts a bounce from the top of the current sequence; [mask] bit n = slot n is heard, -1 = full mix. */
    external fun offlineStart(mask: Long)
    /** Renders [frames] interleaved stereo frames into [out]. False once the song has ended (the tail goes on). */
    external fun offlineRender(out: FloatArray, frames: Int): Boolean
    /** Stops, silences and hands the engine back to the live output. */
    external fun offlineEnd()

    /** A stereo CBR MP3 encoder (LAME); 0 if it can't start. Free it with [mp3Finish]. */
    external fun mp3Open(sampleRate: Int, kbps: Int): Long
    external fun mp3Encode(handle: Long, pcm: FloatArray, frames: Int): ByteArray
    /** Flushes and frees the encoder, returning the last bytes. */
    external fun mp3Finish(handle: Long): ByteArray
}
