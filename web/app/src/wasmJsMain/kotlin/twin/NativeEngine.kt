// Web version of app/src/main/java/com/mobiledaw/audio/NativeEngine.kt: same functions, backed by
// engine.wasm (web/engine/bridge.cpp mirrors the JNI bridge one to one).
@file:Suppress("PackageDirectoryMismatch", "UNUSED_PARAMETER")

package com.mobiledaw.audio

import com.mobiledaw.web.Engine
import com.mobiledaw.web.Engine.BOTH
import com.mobiledaw.web.Engine.LIVE
import com.mobiledaw.web.Engine.PAGE
import com.mobiledaw.web.Vfs

object NativeEngine {
    const val MAX_CHANNELS = 32
    const val AUDITION_SLOT = MAX_CHANNELS

    /** The browser's audio starts when the page loads (see platform.js); sound needs a first tap. */
    fun start(): Boolean = true
    fun stop() {}

    fun setPlaying(playing: Boolean) { Engine.call(LIVE, "ne_setPlaying", playing) }
    fun panic() { Engine.call(LIVE, "ne_panic") }
    fun seek(tick: Long) { Engine.call(LIVE, "ne_seek", tick) }
    fun setBpm(bpm: Float) { Engine.call(BOTH, "ne_setBpm", bpm) }
    fun setMasterVolume(volume: Float) { Engine.call(BOTH, "ne_setMasterVolume", volume) }
    fun setChannelCount(count: Int) { Engine.call(BOTH, "ne_setChannelCount", count) }
    fun setChannel(index: Int, instrument: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean) {
        Engine.call(BOTH, "ne_setChannel", index, instrument, volume, pan, mute, solo)
    }
    fun setChannelSampler(index: Int, cutItself: Boolean, trimStart: Float, trimEnd: Float, envDelay: Float, attack: Float, hold: Float, decay: Float, sustain: Float, release: Float, tension: Float, pitchSemis: Int, reverse: Boolean) {
        Engine.call(BOTH, "ne_setChannelSampler", index, cutItself, trimStart, trimEnd, envDelay, attack, hold, decay, sustain, release, tension, pitchSemis, reverse)
    }
    fun setChannelBoost(index: Int, gain: Float) { Engine.call(BOTH, "ne_setChannelBoost", index, gain) }
    fun setChannelClip(index: Int, resumeMidway: Boolean) { Engine.call(BOTH, "ne_setChannelClip", index, resumeMidway) }
    fun setSequence(events: IntArray, loopTicks: Int) { Engine.call(BOTH, "ne_setSequence", events, loopTicks) }
    fun previewNote(channel: Int, pitch: Int, velocity: Int) { Engine.call(LIVE, "ne_previewNote", channel, pitch, velocity) }
    fun setSample(slot: Int, frames: FloatArray?, right: FloatArray?, sampleRate: Float) {
        Engine.call(BOTH, "ne_setSample", slot, frames, right, sampleRate)
    }

    fun renderSoundFontHit(bank: Int, variant: Int, key: Int, sampleRate: Float): FloatArray? {
        val n = Engine.call(PAGE, "ne_renderSoundFontHit", bank, variant, key, sampleRate).toInt()
        return if (n < 0) null else Engine.resultFloats(n)
    }
    fun renderBuiltInDrum(instrument: Int, sampleRate: Float): FloatArray =
        Engine.resultFloats(Engine.call(PAGE, "ne_renderBuiltInDrum", instrument, sampleRate).toInt())
    fun shiftKeepLength(frames: FloatArray, sampleRate: Float, semis: Float, mode: Int): FloatArray =
        Engine.resultFloats(Engine.call(PAGE, "ne_shiftKeepLength", frames, sampleRate, semis, mode).toInt())

    fun audition() { Engine.call(LIVE, "ne_audition") }
    fun stopAudition() { Engine.call(LIVE, "ne_stopAudition") }
    fun positionTicks(): Long = Engine.status("pos").toLong()
    fun setMetronome(on: Boolean, beatsPerBar: Int) { Engine.call(BOTH, "ne_setMetronome", on, beatsPerBar) }
    fun startCountIn(beats: Int) { Engine.call(LIVE, "ne_startCountIn", beats) }
    fun countInBeat(): Int = Engine.status("countIn").toInt()
    fun isPlaying(): Boolean = Engine.status("playing") != 0.0

    fun outputLatencyMs(): Double = Engine.status("outputLatencyMs")
    fun bufferLatencyMs(): Double = Engine.status("bufferLatencyMs")
    fun outputDeviceId(): Int = 0

    fun readLevels(out: FloatArray) = Engine.statusArray("levels", out)
    fun readScope(out: FloatArray) {
        Engine.want("scope", out.size)
        Engine.statusArray("scope", out)
    }

    fun setChannelEngine(slot: Int, instrument: Int, variant: Int, bank: Int) { Engine.call(BOTH, "ne_setChannelEngine", slot, instrument, variant, bank) }
    fun setChannelDrumKey(slot: Int, key: Int) { Engine.call(BOTH, "ne_setChannelDrumKey", slot, key) }
    fun loadSoundFontAsset(assets: android.content.res.AssetManager, name: String): Int {
        val bytes = com.mobiledaw.web.Assets.get(name) ?: return -1
        return Engine.call(BOTH, "ne_loadSoundFont", bytes).toInt()
    }
    fun loadSoundFontFile(path: String): Int {
        val bytes = Vfs.read(Vfs.normalize(path)) ?: return -1
        return Engine.call(BOTH, "ne_loadSoundFont", bytes).toInt()
    }
    fun soundFontPresets(bank: Int): Array<String> = Engine.resultLines(Engine.call(PAGE, "ne_soundFontPresets", bank).toInt())
    fun enginePresets(instrument: Int): Array<String> = Engine.resultLines(Engine.call(PAGE, "ne_enginePresets", instrument).toInt())
    fun setMultiSample(slot: Int, zones: Array<FloatArray>?, rates: FloatArray?, meta: IntArray?, tuning: FloatArray?, release: Float) {
        if (zones == null || rates == null || meta == null || tuning == null) {
            Engine.call(BOTH, "ne_setMultiSample", slot, -1, null, null, null, null, null, release)
            return
        }
        val total = zones.sumOf { it.size }
        val all = FloatArray(total)
        var at = 0
        for (z in zones) { z.copyInto(all, at); at += z.size }
        val lengths = IntArray(zones.size) { zones[it].size }
        Engine.call(BOTH, "ne_setMultiSample", slot, zones.size, all, lengths, rates, meta, tuning, release)
    }
    fun auditionEngine(instrument: Int, variant: Int, bank: Int, pitch: Int, velocity: Int) {
        Engine.call(LIVE, "ne_auditionEngine", instrument, variant, bank, pitch, velocity)
    }

    const val MAX_INSERTS = 8
    const val MAX_SLOTS = 8
    const val MASTER_INSERT = MAX_INSERTS

    fun setChannelInsert(channel: Int, insert: Int) { Engine.call(BOTH, "ne_setChannelInsert", channel, insert) }
    fun setInsertCount(count: Int) { Engine.call(BOTH, "ne_setInsertCount", count) }
    fun setInsert(index: Int, volume: Float, pan: Float, mute: Boolean) { Engine.call(BOTH, "ne_setInsert", index, volume, pan, mute) }
    fun setInsertEffect(index: Int, slot: Int, type: Int, bypass: Boolean) { Engine.call(BOTH, "ne_setInsertEffect", index, slot, type, bypass) }
    fun setEffectParams(index: Int, slot: Int, values: FloatArray) { Engine.call(BOTH, "ne_setEffectParams", index, slot, values) }
    fun readInsertLevels(out: FloatArray) = Engine.statusArray("inserts", out)
    fun readInsertSignal(index: Int, out: FloatArray) {
        Engine.want("signal", index, out.size)
        if (Engine.status("signalIndex").toInt() == index) Engine.statusArray("signal", out) else out.fill(0f)
    }
    fun readPitch(index: Int, slot: Int, out: FloatArray) {
        Engine.want("pitch", index, slot)
        if (Engine.status("pitchIndex").toInt() == index && Engine.status("pitchSlot").toInt() == slot) Engine.statusArray("pitch", out) else out.fill(0f)
    }

    // ---- export: rendered by the page's own copy of the engine, so the sound goes on meanwhile
    fun offlineBegin(): Int = Engine.call(PAGE, "ne_offlineBegin").toInt()
    fun offlineStart(mask: Long) { Engine.call(PAGE, "ne_offlineStart", mask) }
    fun offlineRender(out: FloatArray, frames: Int): Boolean {
        val playing = Engine.call(PAGE, "ne_offlineRender", Engine.out(frames * 2), frames) != 0.0
        Engine.lastOut(out)
        return playing
    }
    fun offlineEnd() { Engine.call(PAGE, "ne_offlineEnd") }

    fun mp3Open(sampleRate: Int, kbps: Int): Long = Engine.call(PAGE, "ne_mp3Open", sampleRate, kbps).toLong()
    fun mp3Encode(handle: Long, pcm: FloatArray, frames: Int): ByteArray =
        Engine.resultBytes(Engine.call(PAGE, "ne_mp3Encode", handle, pcm.copyOf(frames * 2), frames).toInt())
    fun mp3Finish(handle: Long): ByteArray = Engine.resultBytes(Engine.call(PAGE, "ne_mp3Finish", handle).toInt())
}
