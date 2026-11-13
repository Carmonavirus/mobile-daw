// WebAssembly entry points for the web app (web/src/audio/). The same platform-independent
// engine as the Android app (app/src/main/cpp/DawCore), driven from an AudioWorklet instead of
// Oboe/JNI. Everything runs on the worklet's thread, so the Core's lock-free hand-offs are
// simply called in order.
#include <cstdint>
#include <cstdlib>
#include <vector>

#include "DawCore.h"
#include "Synth.h"
#include "wavesynth/WaveSynth.h"

#define EXPORT extern "C" __attribute__((used, visibility("default")))

namespace {
daw::Core* gCore = nullptr;
daw::Core& core() { return *gCore; }
}  // namespace

// Shared with bridge.cpp.
daw::Core& webCore() {
    if (gCore == nullptr) gCore = new daw::Core();
    return *gCore;
}

// ---- memory, for passing arrays in and out
EXPORT void* daw_malloc(int bytes) { return std::malloc(static_cast<size_t>(bytes > 0 ? bytes : 1)); }
EXPORT void daw_free(void* p) { std::free(p); }

// ---- lifetime
EXPORT void daw_init(int sampleRate) {
    if (gCore == nullptr) gCore = new daw::Core();
    core().setSampleRate(sampleRate);
    wavesynth::Bank::get();  // builds the wavetables now, before the first note
}

// Interleaved stereo into `out` (frames * 2 floats).
EXPORT void daw_render(float* out, int frames) { core().render(out, frames); }
EXPORT void daw_collect() { core().collectGarbage(); }

// ---- transport
EXPORT void daw_set_playing(int on) { core().setPlaying(on != 0); }
EXPORT void daw_panic() { core().panic(); }
EXPORT void daw_seek(double tick) { core().seek(static_cast<int64_t>(tick)); }
EXPORT void daw_set_bpm(float bpm) { core().setBpm(bpm); }
EXPORT void daw_set_master(float v) { core().setMasterVolume(v); }
EXPORT void daw_set_metronome(int on, int beatsPerBar) {
    core().setBeatsPerBar(beatsPerBar);
    core().setMetronome(on != 0);
}
EXPORT double daw_position() { return static_cast<double>(core().positionTicks()); }
EXPORT int daw_is_playing() { return core().isPlaying() ? 1 : 0; }

// events: [channel, startTick, lengthTicks, pitch, velocity 0..127] per note.
EXPORT void daw_set_sequence(const int32_t* events, int count, int loopTicks) {
    std::vector<daw::NoteEvent> notes;
    notes.reserve(static_cast<size_t>(count / 5));
    for (int i = 0; i + 4 < count; i += 5)
        notes.push_back({events[i], events[i + 1], events[i + 2], events[i + 3], events[i + 4] / 127.0f});
    core().setSequence(std::move(notes), loopTicks);
}

// ---- channels
EXPORT void daw_set_channel_count(int n) { core().setChannelCount(n); }
EXPORT void daw_set_channel(int i, int instrument, float volume, float pan, int mute, int solo) {
    core().setChannel(i, instrument, volume, pan, mute != 0, solo != 0);
}
EXPORT void daw_set_channel_sampler(int i, int cutItself, float trimStart, float trimEnd, float envDelay, float attack,
                                    float hold, float decay, float sustain, float release, float tension, int pitchSemis,
                                    int reverse) {
    core().setChannelSampler(i, cutItself != 0, trimStart, trimEnd, envDelay, attack, hold, decay, sustain, release,
                             tension, pitchSemis, reverse != 0);
}
EXPORT void daw_set_channel_boost(int i, float gain) { core().setChannelBoost(i, gain); }
EXPORT void daw_set_channel_clip(int i, int resumeMidway) { core().setChannelClip(i, resumeMidway != 0); }
EXPORT void daw_preview_note(int channel, int pitch, int velocity) {
    core().previewNote(channel, pitch, velocity / 127.0f);
}
EXPORT void daw_preview_hold(int channel, int pitch, int velocity, int on) {
    core().previewHold(channel, pitch, velocity / 127.0f, on != 0);
}

// Copies `frames` floats from left (and right, or null for mono) into a sample slot.
EXPORT void daw_set_sample(int slot, const float* left, const float* right, int frames, float sampleRate) {
    std::vector<float> l, r;
    if (left != nullptr && frames > 0) l.assign(left, left + frames);
    if (right != nullptr && frames > 0) r.assign(right, right + frames);
    core().setSample(slot, std::move(l), std::move(r), sampleRate);
}

// Records one hit of a built-in drum (0 kick .. 3 clap) and loads it into a slot, as the app does.
EXPORT void daw_load_builtin_drum(int slot, int instrument) {
    const float rate = static_cast<float>(core().sampleRate());
    core().setSample(slot, daw::Synth::renderOneShot(instrument, rate), {}, rate);
}

// ---- wavetable synth
EXPORT void daw_set_synth_param(int channel, int param, float value) { core().setSynthParam(channel, param, value); }
EXPORT int daw_param_count() { return wavesynth::kParamCount; }
EXPORT const char* daw_param_name(int i) { return wavesynth::paramInfo(i).name; }
// out[0..3] = min, max, default, stepped (0/1)
EXPORT void daw_param_info(int i, float* out) {
    const auto& p = wavesynth::paramInfo(i);
    out[0] = p.min;
    out[1] = p.max;
    out[2] = p.def;
    out[3] = p.stepped ? 1.0f : 0.0f;
}
EXPORT int daw_table_count() { return wavesynth::Bank::get().count(); }
EXPORT const char* daw_table_name(int i) { return wavesynth::Bank::get().table(i).name; }

// ---- mixer tracks and effects
EXPORT void daw_set_channel_insert(int channel, int insert) { core().setChannelInsert(channel, insert); }
EXPORT void daw_set_insert_count(int n) { core().setInsertCount(n); }
EXPORT void daw_set_insert(int i, float volume, float pan, int mute) { core().setInsert(i, volume, pan, mute != 0); }
EXPORT void daw_set_insert_effect(int i, int slot, int type, int bypass) {
    core().setInsertEffect(i, slot, type, bypass != 0);
}
EXPORT void daw_set_effect_param(int i, int slot, int param, float value) { core().setEffectParam(i, slot, param, value); }

// ---- meters
EXPORT void daw_read_levels(float* out, int count) { core().readLevels(out, count); }
EXPORT float daw_master_level() { return core().masterLevel(); }
EXPORT void daw_read_insert_levels(float* out, int count) { core().readInsertLevels(out, count); }
