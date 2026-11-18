// Stacked notes (a chord) in the piano roll must all sound, on every kind of instrument.
// Each chord note's fundamental is measured in a chord render and compared with the same note
// rendered alone in the same context: a note that was dropped or choked loses almost all of its
// energy. (Plucked models excite each note with noise, so the context has to match.)
#include <cmath>
#include <cstdio>
#include <fstream>
#include <functional>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

#include "DawCore.h"
#include "SoundEngines.h"
#include "wavesynth/WaveSynth.h"

using namespace daw;

static const int sr = 48000;
static std::vector<char> sf2;

static double goertzel(const std::vector<float>& x, double freq) {
    const double w = 2 * M_PI * freq / sr, c = 2 * std::cos(w);
    double s1 = 0, s2 = 0;
    for (size_t i = 0; i < x.size(); ++i) {
        // Hann window keeps neighbouring notes from leaking in.
        const double win = 0.5 - 0.5 * std::cos(2 * M_PI * i / (x.size() - 1));
        const double s = x[i] * win + c * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    return s1 * s1 + s2 * s2 - c * s1 * s2;
}

// Sets up channel 0 as one instrument; returns false when it can't run here.
using Setup = std::function<bool(Core&)>;

// Renders `pitches` stacked at bar 3. With `progression`, three other chords play in bars 0-2
// first, so the chord also has to find voices while the earlier chords' tails ring out.
static std::vector<float> render(const Setup& setup, const std::vector<int>& pitches, bool progression) {
    Core core;
    core.setSampleRate(sr);
    core.setBpm(120);
    core.setChannelCount(1);
    if (!setup(core)) return {};
    std::vector<NoteEvent> ev;
    const int64_t bar = kTicksPerBeat * 4;
    if (progression) {
        const int before[3][4] = {{65, 69, 72, 76}, {62, 65, 69, 72}, {55, 59, 62, 65}};  // F, Dm, G7
        for (int c = 0; c < 3; ++c)
            for (int p : before[c]) ev.push_back({0, c * bar, static_cast<int32_t>(bar), p, 0.8f});
    }
    for (int p : pitches) ev.push_back({0, bar * 3, kTicksPerBeat * 2, p, 0.8f});
    core.setSequence(ev, kTicksPerBeat * 64);
    core.setPlaying(true);
    std::vector<float> buf(512), mono;
    const int start = sr * 6;  // bar 3 at 120 bpm
    for (int done = 0; done < start + sr * 6 / 10; done += 256) {
        core.render(buf.data(), 256);
        core.collectGarbage();
        if (done >= start) for (int i = 0; i < 256; ++i) mono.push_back(0.5f * (buf[i * 2] + buf[i * 2 + 1]));
    }
    // Skip the attack: measure 0.15 s .. 0.6 s.
    return std::vector<float>(mono.begin() + sr * 15 / 100, mono.end());
}

static double hz(int pitch) { return 440.0 * std::pow(2.0, (pitch - 69) / 12.0); }

static bool check(const char* name, const Setup& setup, const std::vector<int>& chord, bool progression = false) {
    const auto all = render(setup, chord, progression);
    if (all.empty()) { std::printf("  %-22s skipped\n", name); return true; }
    bool ok = true;
    std::printf("  %-22s", name);
    for (int p : chord) {
        const auto alone = render(setup, {p}, progression);
        const double ref = goertzel(alone, hz(p)), got = goertzel(all, hz(p));
        const double ratio = ref > 0 ? got / ref : 0;
        // A note playing alongside others keeps at least a fifth of its own level (mixing,
        // a polyphony gain or a limiter may lower it); a missing note is near zero.
        const bool heard = ref > 1e-6 && ratio > 0.2;
        std::printf(" %d:%s(%.2f)", p, heard ? "ok" : "MISSING", ratio);
        ok &= heard;
    }
    std::printf("\n");
    return ok;
}

int main(int argc, char** argv) {
    if (argc > 1) {
        std::ifstream in(argv[1], std::ios::binary);
        sf2.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    }
    wavesynth::Bank::get();  // build the wavetables up front (the app does it in the background)
    const std::vector<int> chord = {60, 64, 67, 71};  // Cmaj7, stacked in the piano roll
    std::vector<std::pair<std::string, Setup>> instruments;
    for (int inst : {kBass, kLead, kPluck, kPad})
        instruments.push_back({"built-in " + std::to_string(inst), [inst](Core& c) { c.setChannel(0, inst, 0.8f, 0, false, false); return true; }});
    instruments.push_back({"wavetable init", [](Core& c) { c.setChannel(0, kWavetable, 0.8f, 0, false, false); return true; }});
    // A sampled piano note (an imported one-shot): a decaying 261.6 Hz tone.
    std::vector<float> tone(sr * 2);
    for (size_t n = 0; n < tone.size(); ++n) tone[n] = 0.6f * std::sin(2 * M_PI * hz(60) * n / sr) * std::exp(-double(n) / sr);
    instruments.push_back({"sample", [&](Core& c) {
        c.setChannel(0, kSample, 0.8f, 0, false, false);
        c.setSample(0, tone, static_cast<float>(sr));
        return true;
    }});
    instruments.push_back({"macro", [](Core& c) {
        c.setChannel(0, kMacro, 0.8f, 0, false, false);
        c.engines().setChannel(0, kMacro, 0, -1);
        return true;
    }});
    // FM e-piano and STK piano string. (The plucked strings start each note from a random noise
    // burst, so their level per note varies too much to measure this way.)
    for (int preset : {12, 27}) {
        instruments.push_back({"modeled " + std::to_string(preset), [preset](Core& c) {
            c.setChannel(0, kModeled, 0.8f, 0, false, false);
            c.engines().setChannel(0, kModeled, preset, -1);
            return true;
        }});
    }
    instruments.push_back({"multisample", [&](Core& c) {
        auto* set = new MultiSampleSet();
        Zone z;
        z.frames = tone;
        z.sampleRate = static_cast<float>(sr);
        z.root = 60;
        set->zones.push_back(std::move(z));
        c.engines().setMultiSample(0, set);
        c.setChannel(0, kMultiSample, 0.8f, 0, false, false);
        c.engines().setChannel(0, kMultiSample, 0, -1);
        return true;
    }});
    instruments.push_back({"soundfont piano", [&](Core& c) {
        if (sf2.empty()) return false;
        const int bank = c.engines().loadSoundFont(sf2.data(), static_cast<int>(sf2.size()));
        if (bank < 0) return false;
        c.setChannel(0, kSoundFont, 0.8f, 0, false, false);
        c.engines().setChannel(0, kSoundFont, 0, bank);
        return true;
    }});
    bool ok = true;
    for (bool progression : {false, true}) {
        std::printf(progression ? "Chord after F, Dm, G7 (C E G B):\n" : "Chord on its own (C E G B):\n");
        for (auto& [name, setup] : instruments) ok &= check(name.c_str(), setup, chord, progression);
    }
    // Mono synth presets (808s, basses, some leads) play one note at a time on purpose: the
    // last of the stacked notes wins. Keep that behaviour pinned so the two cases stay distinct.
    {
        auto mono = [](Core& c) {
            c.setChannel(0, kWavetable, 0.8f, 0, false, false);
            c.setSynthParam(0, wavesynth::kMono, 1.0f);
            return true;
        };
        const auto all = render(mono, chord, false);
        int heard = 0;
        for (int p : chord) heard += goertzel(all, hz(p)) > 0.2 * goertzel(render(mono, {p}, false), hz(p));
        std::printf("Mono wavetable preset: %d of 4 notes sound (expected 1)\n", heard);
        ok &= heard == 1;
    }
    std::printf(ok ? "PASS\n" : "FAIL (chord notes missing)\n");
    return ok ? 0 : 1;
}
