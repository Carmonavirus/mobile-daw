// WebAssembly mirror of the Android JNI bridge (app/src/main/cpp/jni_bridge.cpp, synth_jni.cpp,
// export_jni.cpp), one `ne_` export per NativeEngine / SynthEngine function, so the APK's own
// Kotlin code (compiled for the web) drives the very same engine.
//
// Arrays go in as (pointer, length) into this module's memory. Arrays and strings that come back
// are left in a result buffer: the call returns its length, and ne_result() gives its address.
#include <cmath>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

#include "DawCore.h"
#include "SoundEngines.h"
#include "Synth.h"
#include "export/Mp3Encoder.h"
#include "fx/ClipStretch.h"
#include "wavesynth/WaveSynth.h"

#define EXPORT extern "C" __attribute__((used, visibility("default")))

daw::Core& webCore();  // web_api.cpp

namespace {
daw::Core& core() { return webCore(); }

std::vector<uint8_t> gResult;

int resultFloats(const std::vector<float>& v) {
    gResult.resize(v.size() * sizeof(float));
    if (!v.empty()) std::memcpy(gResult.data(), v.data(), gResult.size());
    return static_cast<int>(v.size());
}

int resultBytes(const std::vector<uint8_t>& v) {
    gResult = v;
    return static_cast<int>(v.size());
}

int resultString(const std::string& s) {
    gResult.assign(s.begin(), s.end());
    return static_cast<int>(s.size());
}

// Same as export_jni.cpp: render silence until everything has died down (at most 3 s).
void settle(daw::Core& c) {
    const int block = 1024;
    std::vector<float> buf(block * 2);
    const int maxBlocks = 3 * c.sampleRate() / block;
    int quiet = 0;
    for (int b = 0; b < maxBlocks && quiet < 4; ++b) {
        c.renderMix(buf.data(), block);
        float peak = 0.0f;
        for (float s : buf) peak = std::fmax(peak, std::fabs(s));
        quiet = peak < 1e-5f ? quiet + 1 : 0;
    }
}
}  // namespace

EXPORT const uint8_t* ne_result() { return gResult.data(); }

// ---- NativeEngine
EXPORT void ne_setPlaying(int on) { core().setPlaying(on != 0); }
EXPORT void ne_panic() { core().panic(); }
EXPORT void ne_seek(double tick) { core().seek(static_cast<int64_t>(tick)); }
EXPORT void ne_setBpm(float bpm) { core().setBpm(bpm); }
EXPORT void ne_setMasterVolume(float v) { core().setMasterVolume(v); }
EXPORT void ne_setChannelCount(int n) { core().setChannelCount(n); }
EXPORT void ne_setChannel(int i, int instrument, float volume, float pan, int mute, int solo) {
    core().setChannel(i, instrument, volume, pan, mute != 0, solo != 0);
}
EXPORT void ne_setChannelSampler(int i, int cutItself, float trimStart, float trimEnd, float envDelay, float attack, float hold,
                                 float decay, float sustain, float release, float tension, int pitchSemis, int reverse) {
    core().setChannelSampler(i, cutItself != 0, trimStart, trimEnd, envDelay, attack, hold, decay, sustain, release, tension,
                             pitchSemis, reverse != 0);
}
EXPORT void ne_setChannelBoost(int i, float gain) { core().setChannelBoost(i, gain); }
EXPORT void ne_setChannelClip(int i, int resumeMidway) { core().setChannelClip(i, resumeMidway != 0); }
EXPORT void ne_setSequence(const int32_t* data, int len, int loopTicks) {
    std::vector<daw::NoteEvent> notes;
    notes.reserve(static_cast<size_t>(len / 5));
    for (int i = 0; i + 4 < len; i += 5) notes.push_back({data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4] / 127.0f});
    core().setSequence(std::move(notes), loopTicks);
}
EXPORT void ne_previewNote(int channel, int pitch, int velocity) { core().previewNote(channel, pitch, velocity / 127.0f); }
EXPORT void ne_setSample(int slot, const float* left, int nl, const float* right, int nr, float sampleRate) {
    std::vector<float> l, r;
    if (left != nullptr && nl > 0) l.assign(left, left + nl);
    if (right != nullptr && nr > 0) r.assign(right, right + nr);
    core().setSample(slot, std::move(l), std::move(r), sampleRate);
}
EXPORT int ne_renderBuiltInDrum(int instrument, float sampleRate) {
    return resultFloats(daw::Synth::renderOneShot(instrument, sampleRate));
}
EXPORT int ne_renderSoundFontHit(int bank, int variant, int key, float sampleRate) {
    const auto out = core().engines().renderHit(bank, variant, key, sampleRate);
    return out.empty() ? -1 : resultFloats(out);
}
EXPORT int ne_shiftKeepLength(const float* frames, int n, float sampleRate, float semis, int mode) {
    return resultFloats(daw::fx::shiftKeepLength(frames, static_cast<size_t>(n), sampleRate, semis, mode));
}
EXPORT void ne_audition() { core().audition(); }
EXPORT void ne_stopAudition() { core().stopAudition(); }
EXPORT double ne_positionTicks() {
    core().collectGarbage();
    return static_cast<double>(core().positionTicks());
}
EXPORT void ne_setMetronome(int on, int beatsPerBar) {
    core().setBeatsPerBar(beatsPerBar);
    core().setMetronome(on != 0);
}
EXPORT void ne_startCountIn(int beats) { core().startCountIn(beats); }
EXPORT int ne_countInBeat() { return core().countInBeat(); }
EXPORT int ne_isPlaying() { return core().isPlaying() ? 1 : 0; }
EXPORT void ne_readLevels(float* out, int len) {
    core().readLevels(out, len);
    if (len > 0) out[len - 1] = core().masterLevel();  // last slot = master
}
EXPORT void ne_readScope(float* out, int len) { core().readScope(out, len); }

// ---- extra engines
EXPORT void ne_setChannelEngine(int slot, int instrument, int variant, int bank) {
    core().engines().setChannel(slot, instrument, variant, bank);
}
EXPORT void ne_setChannelDrumKey(int slot, int key) { core().engines().setDrumKey(slot, key); }
EXPORT int ne_loadSoundFont(const void* data, int size) { return core().engines().loadSoundFont(data, size); }
// "bank|program|name" per preset, one per line.
EXPORT int ne_soundFontPresets(int bank) {
    auto& e = core().engines();
    std::string s;
    for (int i = 0; i < e.soundFontPresetCount(bank); ++i) {
        const auto ids = e.soundFontPresetIds(bank, i);
        const char* name = e.soundFontPresetName(bank, i);
        if (i > 0) s += '\n';
        s += std::to_string(ids.size() > 0 ? ids[0] : 0) + "|" + std::to_string(ids.size() > 1 ? ids[1] : 0) + "|" + (name ? name : "");
    }
    return resultString(s);
}
EXPORT int ne_enginePresets(int instrument) {
    std::string s;
    for (int i = 0; i < daw::SoundEngines::presetCount(instrument); ++i) {
        if (i > 0) s += '\n';
        s += daw::SoundEngines::presetInfo(instrument, i);
    }
    return resultString(s);
}
// frames: every zone's frames back to back; lengths/rates per zone; meta 8 ints and tuning 2 floats per zone.
EXPORT void ne_setMultiSample(int slot, int zones, const float* frames, int, const int32_t* lengths, int, const float* rates, int,
                              const int32_t* meta, int, const float* tuning, int, float release) {
    if (zones < 0) {
        core().engines().setMultiSample(slot, nullptr);
        return;
    }
    auto* set = new daw::MultiSampleSet();
    set->release = release;
    size_t at = 0;
    for (int i = 0; i < zones; ++i) {
        daw::Zone z;
        const size_t len = static_cast<size_t>(lengths[i]);
        z.frames.assign(frames + at, frames + at + len);
        at += len;
        z.sampleRate = rates[i];
        const int32_t* mm = meta + i * 8;
        z.loKey = mm[0]; z.hiKey = mm[1]; z.root = mm[2]; z.loVel = mm[3]; z.hiVel = mm[4];
        z.loop = mm[5] != 0; z.loopStart = mm[6]; z.loopEnd = mm[7];
        z.tuneCents = tuning[i * 2];
        z.gain = tuning[i * 2 + 1];
        set->zones.push_back(std::move(z));
    }
    core().engines().setMultiSample(slot, set);
}
EXPORT void ne_auditionEngine(int instrument, int variant, int bank, int pitch, int velocity) {
    core().engines().audition(instrument, variant, bank, pitch, velocity / 127.0f);
}

// ---- mixer tracks
EXPORT void ne_setChannelInsert(int channel, int insert) { core().setChannelInsert(channel, insert); }
EXPORT void ne_setInsertCount(int n) { core().setInsertCount(n); }
EXPORT void ne_setInsert(int i, float volume, float pan, int mute) { core().setInsert(i, volume, pan, mute != 0); }
EXPORT void ne_setInsertEffect(int i, int slot, int type, int bypass) { core().setInsertEffect(i, slot, type, bypass != 0); }
EXPORT void ne_setEffectParams(int i, int slot, const float* values, int n) {
    for (int p = 0; p < n; ++p) core().setEffectParam(i, slot, p, values[p]);
}
EXPORT void ne_readInsertLevels(float* out, int len) { core().readInsertLevels(out, len); }
EXPORT void ne_readInsertSignal(int index, float* out, int len) { core().readInsertSignal(index, out, len); }
EXPORT void ne_readPitch(int index, int slot, float* out, int len) {
    float tmp[2];
    core().readPitch(index, slot, tmp);
    for (int i = 0; i < len && i < 2; ++i) out[i] = tmp[i];
}
EXPORT void ne_readKey(int index, int slot, float* out, int len) {
    float tmp[daw::fx::kKeyOutCount];
    core().readKey(index, slot, tmp);
    for (int i = 0; i < len && i < daw::fx::kKeyOutCount; ++i) out[i] = tmp[i];
}

// ---- export: offline rendering on this instance (the page's own copy of the engine)
EXPORT int ne_offlineBegin() { return core().sampleRate(); }
EXPORT void ne_offlineStart(double mask) {
    auto& c = core();
    c.setStopAtEnd(true);
    c.setRenderMask(-1);
    c.panic();
    c.collectGarbage();
    settle(c);
    c.setRenderMask(static_cast<int64_t>(mask));
    c.seek(0);
    c.setPlaying(true);
}
EXPORT int ne_offlineRender(float* out, int /*len*/, int frames) {
    core().renderMix(out, frames);
    return core().isPlaying() ? 1 : 0;
}
EXPORT void ne_offlineEnd() {
    auto& c = core();
    c.setPlaying(false);
    c.panic();
    c.setRenderMask(-1);
    c.setStopAtEnd(false);
    c.seek(0);
}
EXPORT int ne_mp3Open(int sampleRate, int kbps) {
    auto* enc = new daw::Mp3Encoder(sampleRate, kbps);
    if (!enc->ok()) {
        delete enc;
        return 0;
    }
    return static_cast<int>(reinterpret_cast<intptr_t>(enc));
}
EXPORT int ne_mp3Encode(int handle, const float* pcm, int /*len*/, int frames) {
    auto* enc = reinterpret_cast<daw::Mp3Encoder*>(static_cast<intptr_t>(handle));
    std::vector<uint8_t> out;
    if (enc && frames > 0) enc->encode(pcm, frames, out);
    return resultBytes(out);
}
EXPORT int ne_mp3Finish(int handle) {
    auto* enc = reinterpret_cast<daw::Mp3Encoder*>(static_cast<intptr_t>(handle));
    std::vector<uint8_t> out;
    if (enc) {
        enc->finish(out);
        delete enc;
    }
    return resultBytes(out);
}

// ---- SynthEngine
EXPORT int se_paramNames() {
    std::string s;
    for (int i = 0; i < wavesynth::kParamCount; ++i) {
        if (i > 0) s += '\n';
        s += wavesynth::paramInfo(i).name;
    }
    return resultString(s);
}
EXPORT int se_paramRanges() {
    std::vector<float> data;
    for (int i = 0; i < wavesynth::kParamCount; ++i) {
        const auto& p = wavesynth::paramInfo(i);
        data.insert(data.end(), {p.min, p.max, p.def, p.stepped ? 1.0f : 0.0f});
    }
    return resultFloats(data);
}
EXPORT int se_tableNames() {
    const auto& bank = wavesynth::Bank::get();
    std::string s;
    for (int i = 0; i < bank.count(); ++i) {
        if (i > 0) s += '\n';
        s += bank.table(i).name;
    }
    return resultString(s);
}
EXPORT void se_tableShape(int table, float position, float* out, int n) { wavesynth::Bank::get().shape(table, position, out, n); }
EXPORT void se_setParam(int channel, int param, float value) { core().setSynthParam(channel, param, value); }
EXPORT void se_setParams(int channel, const float* values, int n) {
    for (int i = 0; i < n && i < wavesynth::kParamCount; ++i) core().setSynthParam(channel, i, values[i]);
}
EXPORT void se_noteHold(int channel, int pitch, int velocity, int on) {
    core().previewHold(channel, pitch, velocity / 127.0f, on != 0);
}
