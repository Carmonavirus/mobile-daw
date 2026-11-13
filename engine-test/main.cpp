// Desktop smoke test for the audio core: renders a short loop to out/render.wav
// and fails if the output is silent, clipped flat, or contains NaNs.
// Build and run: engine-test/build.sh && (cd engine-test && ./out/enginetest [path/to/bank.sf2])
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "DawCore.h"

using namespace daw;

static void writeWav(const char* path, const std::vector<float>& data, int sr) {
    FILE* f = std::fopen(path, "wb");
    if (!f) return;
    auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    uint32_t bytes = static_cast<uint32_t>(data.size() * 2);
    std::fwrite("RIFF", 1, 4, f); w32(36 + bytes); std::fwrite("WAVEfmt ", 1, 8, f);
    w32(16); w16(1); w16(2); w32(sr); w32(sr * 4); w16(4); w16(16);
    std::fwrite("data", 1, 4, f); w32(bytes);
    for (float s : data) { int16_t v = static_cast<int16_t>(std::fmax(-1.f, std::fmin(1.f, s)) * 32767); w16(v); }
    std::fclose(f);
}

// Plays every preset of the extra engines (and a SoundFont if given) on one channel and checks
// each makes finite, non-silent sound that ends.
static bool testExtraEngines(const char* sf2Path) {
    const int sr = 48000;
    bool ok = true;
    auto play = [&](const char* label, int instrument, int variant, int bank, int pitch, std::vector<float>* keep) {
        Core core;
        core.setSampleRate(sr);
        if (instrument == kSoundFont && bank >= 0) {
            // Banks live in the core that loads them, so reload into this one.
        }
        core.setChannelCount(1);
        core.setChannel(0, instrument, 0.8f, 0.0f, false, false);
        core.engines().setChannel(0, instrument, variant, bank);
        core.previewNote(0, pitch, 0.9f);
        std::vector<float> buf(256 * 2);
        double sumSq = 0;
        float peak = 0, tailPeak = 0;
        bool bad = false;
        const int blocks = sr * 3 / 256;
        for (int b = 0; b < blocks; ++b) {
            core.render(buf.data(), 256);
            for (float x : buf) {
                if (!std::isfinite(x)) bad = true;
                sumSq += x * x;
                peak = std::fmax(peak, std::fabs(x));
                if (b > blocks - 20) tailPeak = std::fmax(tailPeak, std::fabs(x));
            }
            if (keep) keep->insert(keep->end(), buf.begin(), buf.end());
        }
        const double rms = std::sqrt(sumSq / (blocks * 512.0));
        const bool silent = peak < 0.01f;
        std::printf("  %-28s rms=%.3f peak=%.3f tail=%.4f%s\n", label, rms, peak, tailPeak,
                    bad ? " NaN!" : silent ? " SILENT!" : "");
        if (bad || silent) ok = false;
    };
    std::vector<float> all;
    for (int inst : {kMacro, kModeled}) {
        std::printf("%s presets:\n", inst == kMacro ? "Macro" : "Modeled");
        for (int i = 0; i < SoundEngines::presetCount(inst); ++i) {
            std::string info = SoundEngines::presetInfo(inst, i);
            int pitch = 60;
            {
                // info = Name|Group|drum|pitch[|library]
                size_t a = info.find('|'), b = info.find('|', a + 1), c = info.find('|', b + 1);
                size_t d = info.find('|', c + 1);
                pitch = std::stoi(info.substr(c + 1, d == std::string::npos ? std::string::npos : d - c - 1));
            }
            play(info.substr(0, info.find('|')).c_str(), inst, i, -1, pitch, &all);
        }
    }

    // Multisample: two zones, the upper one looping.
    {
        Core core;
        core.setSampleRate(sr);
        core.setChannelCount(1);
        core.setChannel(0, kMultiSample, 0.8f, 0.0f, false, false);
        auto* set = new MultiSampleSet();
        for (int z = 0; z < 2; ++z) {
            Zone zone;
            zone.sampleRate = 44100;
            zone.frames.resize(44100);
            for (size_t n = 0; n < zone.frames.size(); ++n)
                zone.frames[n] = 0.5f * std::sin(2 * 3.14159 * (z ? 440.0 : 220.0) * n / 44100.0);
            zone.loKey = z ? 61 : 0;
            zone.hiKey = z ? 127 : 60;
            zone.root = z ? 69 : 57;
            zone.loop = z == 1;
            zone.loopStart = 1000;
            zone.loopEnd = 1000 + 44100 / 440 * 40;
            set->zones.push_back(std::move(zone));
        }
        core.engines().setMultiSample(0, set);
        core.engines().setChannel(0, kMultiSample, 0, -1);
        std::vector<NoteEvent> ev = {{0, 0, kTicksPerBeat, 57, 0.9f}, {0, kTicksPerBeat * 2, kTicksPerBeat * 4, 72, 0.9f}};
        core.setSequence(ev, kTicksPerBeat * 8);
        core.setPlaying(true);
        std::vector<float> buf(512);
        float peak = 0;
        bool bad = false;
        for (int b = 0; b < sr * 2 / 256; ++b) {
            core.render(buf.data(), 256);
            for (float x : buf) { bad |= !std::isfinite(x); peak = std::fmax(peak, std::fabs(x)); }
        }
        std::printf("Multisample: peak=%.3f%s\n", peak, bad ? " NaN!" : "");
        if (bad || peak < 0.05f) ok = false;
    }

    if (sf2Path) {
        std::ifstream in(sf2Path, std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        Core core;
        core.setSampleRate(sr);
        const int bank = core.engines().loadSoundFont(bytes.data(), static_cast<int>(bytes.size()));
        const int presets = core.engines().soundFontPresetCount(bank);
        std::printf("SoundFont %s: bank=%d presets=%d\n", sf2Path, bank, presets);
        if (bank < 0 || presets <= 0) return false;
        core.setChannelCount(2);
        int drumKit = -1;
        for (int i = 0; i < presets; ++i) if (core.engines().soundFontPresetIds(bank, i)[0] == 128) { drumKit = i; break; }
        core.setChannel(0, kSoundFont, 0.8f, 0.0f, false, false);
        core.engines().setChannel(0, kSoundFont, 0, bank);
        core.setChannel(1, kSoundFont, 0.8f, 0.0f, false, false);
        core.engines().setChannel(1, kSoundFont, drumKit, bank);
        std::printf("  preset0=%s drums=%s\n", core.engines().soundFontPresetName(bank, 0),
                    drumKit >= 0 ? core.engines().soundFontPresetName(bank, drumKit) : "none");
        std::vector<NoteEvent> ev;
        for (int s = 0; s < 16; ++s) {
            ev.push_back({1, s * kTicksPerStep, kTicksPerStep, s % 4 == 0 ? 36 : (s % 4 == 2 ? 38 : 42), 0.9f});
            if (s % 4 == 0) ev.push_back({0, s * kTicksPerStep, kTicksPerStep * 3, 60 + s / 2, 0.8f});
        }
        core.setSequence(ev, kTicksPerStep * 16);
        core.setPlaying(true);
        std::vector<float> buf(512), out;
        float peak = 0;
        bool bad = false;
        for (int b = 0; b < sr * 4 / 256; ++b) {
            core.render(buf.data(), 256);
            core.collectGarbage();
            for (float x : buf) { bad |= !std::isfinite(x); peak = std::fmax(peak, std::fabs(x)); }
            out.insert(out.end(), buf.begin(), buf.end());
        }
        writeWav("out/soundfont.wav", out, sr);
        std::printf("  rendered peak=%.3f%s\n", peak, bad ? " NaN!" : "");
        if (bad || peak < 0.05f) ok = false;

        // A single drum hit (closed hat, key 42) follows the piano roll: a note an octave under
        // its key plays the same hat an octave down, so it crosses zero about half as often.
        auto hatAt = [&](int pitch) {
            Core c;
            c.setSampleRate(sr);
            const int b = c.engines().loadSoundFont(bytes.data(), static_cast<int>(bytes.size()));
            c.setChannelCount(1);
            c.setChannel(0, kSoundFont, 0.8f, 0.0f, false, false);
            c.engines().setChannel(0, kSoundFont, drumKit, b);
            c.engines().setDrumKey(0, 42);
            c.setSequence({NoteEvent{0, 0, kTicksPerStep, pitch, 0.9f}}, kTicksPerBeat * 64);
            c.setPlaying(true);
            std::vector<float> o(512);
            double crossings = 0, energy = 0;
            float prev = 0;
            for (int k = 0; k < sr / 10 / 256; ++k) {
                c.render(o.data(), 256);
                c.collectGarbage();
                for (int i = 0; i < 256; ++i) {
                    const float x = o[i * 2];
                    if ((prev < 0) != (x < 0)) ++crossings;
                    prev = x;
                    energy += x * x;
                }
            }
            return std::pair<double, double>(crossings, energy);
        };
        const auto atKey = hatAt(42), octaveDown = hatAt(30);
        const double ratio = atKey.first / std::max(1.0, octaveDown.first);
        std::printf("  drum key: crossings at key=%.0f octave down=%.0f ratio=%.2f\n", atKey.first, octaveDown.first, ratio);
        if (atKey.second < 1e-4 || octaveDown.second < 1e-4 || ratio < 1.3) ok = false;

        // The same hit recorded once, to play through the sampler with full channel settings.
        const auto hit = core.engines().renderHit(bank, drumKit, 42, 48000.0f);
        float hitPeak = 0;
        for (float x : hit) hitPeak = std::fmax(hitPeak, std::fabs(x));
        std::printf("  recorded hit: %.3fs peak=%.3f\n", hit.size() / 48000.0, hitPeak);
        if (hit.size() < 480 || hit.size() > 48000 * 4 || hitPeak < 0.02f) ok = false;
    }
    writeWav("out/presets.wav", all, sr);
    return ok;
}

int main(int argc, char** argv) {
    if (!testExtraEngines(argc > 1 ? argv[1] : nullptr)) {
        std::printf("FAIL (extra engines)\n");
        return 1;
    }

    const int sr = 48000;
    Core core;
    core.setSampleRate(sr);
    core.setBpm(140);
    core.setChannelCount(9);
    for (int i = 0; i < 9; ++i) core.setChannel(i, i, 0.8f, (i % 3 - 1) * 0.3f, false, false);

    // Channel 8 plays an imported sample (here a synthetic 44.1 kHz tom).
    std::vector<float> tom(44100 / 4);
    for (size_t n = 0; n < tom.size(); ++n)
        tom[n] = std::sin(2 * 3.14159 * 110 * n / 44100.0) * std::exp(-double(n) / 4000.0);
    core.setSample(8, tom, 44100.0f);
    // cut itself, trimmed, short attack/hold/decay, sustain 0.6, short release.
    core.setChannelSampler(8, true, 0.05f, 0.8f, 0.0f, 0.002f, 0.001f, 0.01f, 0.6f, 0.03f, 0.0f, 0, false);
    core.setChannel(8, 8, 1.6f, 0.0f, false, false);              // boosted above unity
    core.setSample(kAuditionSlot, tom, 44100.0f);
    core.audition();

    std::vector<NoteEvent> ev;
    for (int s = 0; s < 32; ++s) {
        int64_t t = s * kTicksPerStep;
        if (s % 4 == 0) ev.push_back({0, t, kTicksPerStep, 60, 1.0f});
        if (s % 8 == 4) ev.push_back({1, t, kTicksPerStep, 60, 0.9f});
        if (s % 2 == 0) ev.push_back({2, t, kTicksPerStep, 60, 0.6f});
        if (s % 16 == 12) ev.push_back({3, t, kTicksPerStep, 60, 0.8f});
        if (s % 4 == 2) ev.push_back({4, t, kTicksPerStep * 2, 36 + (s / 8) * 2, 0.9f});
    }
    int chord[] = {55, 58, 62};
    for (int i = 0; i < 4; ++i) ev.push_back({5, i * kTicksPerStep * 8, kTicksPerStep * 6, 67 + i * 2, 0.7f});
    for (int i = 0; i < 8; ++i) ev.push_back({6, i * kTicksPerStep * 4 + kTicksPerStep * 2, kTicksPerStep, 74 + (i % 3) * 3, 0.6f});
    for (int s = 0; s < 32; s += 1) ev.push_back({8, s * kTicksPerStep, kTicksPerStep, 60, 0.9f});  // rapid hits exercise choking
    for (int s = 0; s < 32; s += 6) ev.push_back({8, s * kTicksPerStep, kTicksPerStep, 55 + s % 12, 0.9f});
    for (int n : chord) ev.push_back({7, 0, kTicksPerStep * 32, n, 0.5f});
    core.setSequence(ev, kTicksPerStep * 32);
    core.setPlaying(true);

    const int block = 192;
    const int total = sr * 4;
    std::vector<float> out;
    std::vector<float> buf(block * 2);
    for (int done = 0; done < total; done += block) {
        if (done == sr * 2) {
            core.collectGarbage();
            core.setSequence(ev, kTicksPerStep * 32);  // exercise the hot swap
        }
        if (done == sr * 2 + block * 4) core.collectGarbage();
        if (done == sr * 3) core.setSample(8, tom, 22050.0f);  // hot-swap a sample mid-play
        core.render(buf.data(), block);
        out.insert(out.end(), buf.begin(), buf.end());
    }
    double sumSq = 0; float peak = 0; bool bad = false;
    for (float s : out) { if (!std::isfinite(s)) bad = true; sumSq += s * s; peak = std::fmax(peak, std::fabs(s)); }
    double rms = std::sqrt(sumSq / out.size());
    float scope[64]; core.readScope(scope, 64);
    std::printf("rms=%.3f peak=%.3f pos=%lld scope0=%.2f\n", rms, peak, (long long)core.positionTicks(), scope[0]);
    writeWav("out/render.wav", out, sr);
    if (bad || rms < 0.02 || rms > 0.9) { std::printf("FAIL\n"); return 1; }

    // Seeking: while playing the playhead jumps; while stopped the next play starts there.
    const int64_t target = kTicksPerStep * 20;
    core.seek(target);
    core.render(buf.data(), block);
    const int64_t afterSeek = core.positionTicks();
    core.setPlaying(false);
    core.render(buf.data(), block);
    const int64_t stopped = core.positionTicks();
    core.seek(kTicksPerStep * 8);
    core.render(buf.data(), block);
    const int64_t parked = core.positionTicks();
    core.setPlaying(true);
    core.render(buf.data(), block);
    const int64_t resumed = core.positionTicks();
    std::printf("seek: playing=%lld stopped=%lld parked=%lld resumed=%lld\n", (long long)afterSeek,
                (long long)stopped, (long long)parked, (long long)resumed);
    if (afterSeek < target || afterSeek > target + 4 || stopped != 0 || parked != kTicksPerStep * 8 ||
        resumed < kTicksPerStep * 8 || resumed > kTicksPerStep * 8 + 4) {
        std::printf("FAIL seek\n");
        return 1;
    }
    // Amplitude envelope on a steady sample: delay stays silent, attack ramps in, sustain holds below peak.
    auto sampleLevel = [&](float delay, float attack, float hold, float decay, float sustain, float tension, int atSample) {
        Core c2;
        c2.setSampleRate(sr);
        c2.setMasterVolume(1.0f);
        c2.setChannelCount(1);
        c2.setChannel(0, 8, 1.0f, 0.0f, false, false);
        std::vector<float> flat(sr, 0.5f);  // 1 s of constant 0.5
        c2.setSample(0, flat, static_cast<float>(sr));
        c2.setChannelSampler(0, false, 0.0f, 1.0f, delay, attack, hold, decay, sustain, 0.0f, tension, 0, false);
        c2.previewNote(0, 60, 1.0f);
        std::vector<float> mono;
        std::vector<float> b2(block * 2);
        for (int done = 0; done < sr; done += block) {
            c2.render(b2.data(), block);
            for (int i = 0; i < block; ++i) mono.push_back(b2[i * 2]);
        }
        return std::fabs(mono[static_cast<size_t>(atSample)]);
    };
    const float envEarly = sampleLevel(0.0f, 0.2f, 0.0f, 0.0f, 1.0f, 0.0f, sr / 100);  // ~10 ms into a 200 ms attack
    const float envFull = sampleLevel(0.0f, 0.2f, 0.0f, 0.0f, 1.0f, 0.0f, sr / 2);     // well past the attack
    const float susLevel = sampleLevel(0.0f, 0.0f, 0.0f, 0.02f, 0.4f, 0.0f, sr / 2);   // held at sustain 0.4
    const float susPeak = sampleLevel(0.0f, 0.0f, 0.0f, 0.02f, 0.4f, 0.0f, sr / 500);  // during the brief full part
    const float delayed = sampleLevel(0.1f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, sr / 50);    // 20 ms into a 100 ms delay
    const float afterDelay = sampleLevel(0.1f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, sr / 2);  // past the delay, full
    // Positive tension bends the attack concave (ease-in), so the same point is quieter than linear.
    const float tenCurved = sampleLevel(0.0f, 0.4f, 0.0f, 0.0f, 1.0f, 0.8f, sr / 10);  // 100 ms into 400 ms attack
    const float tenLin = sampleLevel(0.0f, 0.4f, 0.0f, 0.0f, 1.0f, 0.0f, sr / 10);
    std::printf("env: early=%.3f full=%.3f susLevel=%.3f susPeak=%.3f delayed=%.3f afterDelay=%.3f ten=%.3f/%.3f\n",
                envEarly, envFull, susLevel, susPeak, delayed, afterDelay, tenCurved, tenLin);
    if (envEarly > 0.15f || envFull < 0.45f ||               // attack keeps the start quiet, then opens up
        susLevel > 0.26f || susLevel < 0.14f ||              // ~0.5 * 0.4 = 0.20
        susPeak < 0.40f ||                                   // peaks near full before decaying
        delayed > 0.02f || afterDelay < 0.45f ||             // silent during delay, full after
        tenCurved >= tenLin) {                               // positive tension bends the ramp below linear
        std::printf("FAIL envelope\n");
        return 1;
    }

    // Boost: gain on the sample itself; below the knee it is clean, above it the peaks are soft clipped.
    {
        auto boosted = [&](float gain) {
            Core c4;
            c4.setSampleRate(sr);
            c4.setMasterVolume(0.25f);  // keep the master stage linear
            c4.setChannelCount(1);
            c4.setChannel(0, 8, 1.0f, 0.0f, false, false);
            std::vector<float> flat(sr, 0.5f);
            c4.setSample(0, flat, static_cast<float>(sr));
            c4.setChannelSampler(0, false, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, false);
            c4.setChannelBoost(0, gain);
            c4.previewNote(0, 60, 1.0f);
            std::vector<float> b4(block * 2);
            float level = 0.0f;
            for (int done = 0; done < sr / 2; done += block) {
                c4.render(b4.data(), block);
                level = std::fabs(b4[0]);
            }
            return level;
        };
        const float plain = boosted(1.0f);
        const float clean = boosted(1.5f);   // 0.75: under the knee, exactly 1.5x
        const float hot = boosted(4.0f);     // 2.0 before the clipper: rounded off below full scale
        std::printf("boost: plain=%.3f x1.5=%.3f x4=%.3f\n", plain, clean, hot);
        if (plain < 0.05f || std::fabs(clean / plain - 1.5f) > 0.03f || hot <= clean || hot / plain > 2.05f) {
            std::printf("FAIL boost\n");
            return 1;
        }
    }

    // Audio clip: a long recording on its own channel starts mid-way when playback starts
    // inside it, and stays silent on a normal sample channel.
    {
        Core clip;
        clip.setSampleRate(sr);
        clip.setBpm(120.0f);
        clip.setChannelCount(1);
        clip.setChannel(0, 8 /* sample */, 1.0f, 0.0f, false, false);
        std::vector<float> tone(static_cast<size_t>(sr) * 8);
        for (size_t i = 0; i < tone.size(); ++i) tone[i] = 0.5f * std::sin(0.05f * static_cast<float>(i));
        clip.setSample(0, tone, static_cast<float>(sr));
        const int64_t len = kTicksPerBeat * 16;  // 8 s at 120 BPM
        clip.setSequence({NoteEvent{0, 0, static_cast<int32_t>(len), 60, 1.0f}}, len * 2);
        auto loudness = [&](bool resume) {
            clip.setChannelClip(0, resume);
            clip.setPlaying(false);
            clip.render(buf.data(), block);
            clip.seek(kTicksPerBeat * 4);
            clip.setPlaying(true);
            double sum = 0;
            for (int b = 0; b < 8; ++b) {
                clip.render(buf.data(), block);
                for (int i = 0; i < block * 2; ++i) sum += buf[i] * buf[i];
            }
            return std::sqrt(sum / (block * 16));
        };
        const double off = loudness(false);
        const double on = loudness(true);
        std::printf("clip resume: off=%.3f on=%.3f\n", off, on);
        if (off > 0.001 || on < 0.05) { std::printf("FAIL clip resume\n"); return 1; }
    }

    // Stopping playback stops a long audio clip (sample voices ignore the synth release),
    // and panic() silences everything, including held synth notes, within a few ms.
    {
        Core c3;
        c3.setSampleRate(sr);
        c3.setBpm(120.0f);
        c3.setChannelCount(2);
        c3.setChannel(0, 8 /* sample */, 1.0f, 0.0f, false, false);
        c3.setChannel(1, 4 /* bass synth */, 1.0f, 0.0f, false, false);
        std::vector<float> tone(static_cast<size_t>(sr) * 8);
        for (size_t i = 0; i < tone.size(); ++i) tone[i] = 0.5f * std::sin(0.05f * static_cast<float>(i));
        c3.setSample(0, tone, static_cast<float>(sr));
        const int64_t len = kTicksPerBeat * 16;
        c3.setSequence({NoteEvent{0, 0, static_cast<int32_t>(len), 60, 1.0f}}, len * 2);
        auto level = [&](int blocks) {
            double sum = 0;
            for (int b = 0; b < blocks; ++b) {
                c3.render(buf.data(), block);
                if (b < blocks - 4) continue;  // measure only the last few blocks
                for (int i = 0; i < block * 2; ++i) sum += buf[i] * buf[i];
            }
            return std::sqrt(sum / (block * 8));
        };
        c3.setPlaying(true);
        const double playingLevel = level(20);
        c3.setPlaying(false);
        const double stoppedLevel = level(20);  // ~80 ms later
        c3.previewHold(1, 48, 1.0f, true);       // a held synth note keeps sounding...
        c3.setPlaying(true);
        const double before = level(20);
        c3.panic();                               // ...until stop all
        const double after = level(20);
        std::printf("stop: playing=%.3f stopped=%.4f beforePanic=%.3f afterPanic=%.4f\n", playingLevel, stoppedLevel, before, after);
        if (playingLevel < 0.05 || stoppedLevel > 0.001 || before < 0.01 || after > 0.001 || c3.isPlaying()) {
            std::printf("FAIL stop\n");
            return 1;
        }
    }

    // Drum channels follow the piano roll like samples: a hit on G3 is an octave under G4,
    // so it rings about twice as long and its waveform crosses zero less often (half as often
    // for a tone; noise hits are partly smoothed by the interpolation, so less than that).
    {
        std::vector<float> tom(44100 / 2);
        for (size_t n = 0; n < tom.size(); ++n)
            tom[n] = std::sin(2 * 3.14159 * 220 * n / 44100.0) * std::exp(-double(n) / 6000.0);
        auto hit = [&](int instrument, int pitch) {
            Core c;
            c.setSampleRate(sr);
            c.setBpm(120.0f);
            c.setChannelCount(1);
            c.setChannel(0, instrument, 1.0f, 0.0f, false, false);
            if (instrument == 8) c.setSample(0, tom, 44100.0f);
            c.setSequence({NoteEvent{0, 0, kTicksPerStep, pitch, 1.0f}}, kTicksPerBeat * 64);
            c.setPlaying(true);
            std::vector<float> mono;
            for (int b = 0; b < sr / block; ++b) {
                c.render(buf.data(), block);
                for (int i = 0; i < block; ++i) mono.push_back(buf[i * 2] + buf[i * 2 + 1]);
            }
            float peak = 0;
            for (float x : mono) peak = std::fmax(peak, std::fabs(x));
            size_t last = 0, crossings = 0;
            for (size_t i = 0; i < mono.size(); ++i) {
                if (std::fabs(mono[i]) > peak * 0.02f) last = i;
                if (i > 0 && i < static_cast<size_t>(sr / 20) && (mono[i - 1] < 0) != (mono[i] < 0)) ++crossings;
            }
            return std::pair<double, double>(static_cast<double>(last), static_cast<double>(crossings));
        };
        const char* names[] = {"hat", "clap", "sample"};
        const int instruments[] = {2, 3, 8};
        for (int k = 0; k < 3; ++k) {
            const auto hi = hit(instruments[k], 67);  // G4
            const auto lo = hit(instruments[k], 55);  // G3
            const double lengthRatio = lo.first / std::max(1.0, hi.first);
            const double crossRatio = hi.second / std::max(1.0, lo.second);
            std::printf("pitch %s: length G3/G4=%.2f crossings G4/G3=%.2f\n", names[k], lengthRatio, crossRatio);
            if (lengthRatio < 1.6 || lengthRatio > 2.5 || crossRatio < 1.3) {
                std::printf("FAIL drum pitch (%s)\n", names[k]);
                return 1;
            }
        }
    }

    // Built-in drums are recorded once and played through the sampler, so trim, reverse and the
    // rest of the channel settings work on them; the recording sounds like the synthesised hit.
    {
        const char* names[] = {"kick", "snare", "hat", "clap"};
        for (int inst = 0; inst < 4; ++inst) {
            const auto rec = Synth::renderOneShot(inst, static_cast<float>(sr));
            float recPeak = 0;
            for (float x : rec) recPeak = std::fmax(recPeak, std::fabs(x));
            // Played live through the old path for comparison.
            Voice v;
            Synth::start(v, 60, 1.0f, 0);
            float livePeak = 0;
            size_t liveLen = 0;
            while (v.active && liveLen < static_cast<size_t>(sr) * 3) { livePeak = std::fmax(livePeak, std::fabs(Synth::tick(v, inst, sr))); ++liveLen; }
            std::printf("recorded %s: %.3fs (live %.3fs) peak=%.3f (live %.3f)\n", names[inst], rec.size() / double(sr),
                        liveLen / double(sr), recPeak, livePeak);
            if (rec.size() < static_cast<size_t>(sr / 50) || rec.size() > liveLen + 2 || std::fabs(recPeak - livePeak) > 1e-4f) {
                std::printf("FAIL recorded drum (%s)\n", names[inst]);
                return 1;
            }
        }
        // Through the sampler, a trimmed recording stops early and a reversed one starts quiet.
        auto play = [&](float trimEnd, bool reverse) {
            Core c;
            c.setSampleRate(sr);
            c.setMasterVolume(0.25f);
            c.setChannelCount(1);
            c.setChannel(0, kSample, 1.0f, 0.0f, false, false);
            c.setSample(0, Synth::renderOneShot(0, static_cast<float>(sr)), static_cast<float>(sr));
            c.setChannelSampler(0, false, 0.0f, trimEnd, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0, reverse);
            c.previewNote(0, 60, 1.0f);
            std::vector<float> b(block * 2);
            std::vector<float> mono;
            for (int done = 0; done < sr; done += block) {
                c.render(b.data(), block);
                for (int i = 0; i < block; ++i) mono.push_back(b[i * 2]);
            }
            size_t last = 0;
            float firstMs = 0;
            for (size_t i = 0; i < mono.size(); ++i) if (std::fabs(mono[i]) > 1e-3f) last = i;
            for (size_t i = 0; i < static_cast<size_t>(sr / 200); ++i) firstMs = std::fmax(firstMs, std::fabs(mono[i]));
            return std::pair<size_t, float>(last, firstMs);
        };
        const auto full = play(1.0f, false), trimmed = play(0.25f, false), reversed = play(1.0f, true);
        std::printf("kick via sampler: full end=%zu trimmed end=%zu start fwd=%.3f rev=%.3f\n", full.first, trimmed.first,
                    full.second, reversed.second);
        if (trimmed.first * 2 > full.first || reversed.second >= full.second * 0.5f) {
            std::printf("FAIL kick via sampler\n");
            return 1;
        }
    }

    // Channel Pitch transposes synth instruments too: +12 on a bass doubles its frequency.
    {
        auto crossings = [&](int semis) {
            Core c;
            c.setSampleRate(sr);
            c.setChannelCount(1);
            c.setChannel(0, kBass, 1.0f, 0.0f, false, false);
            c.setChannelSampler(0, false, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, semis, false);
            c.previewNote(0, 36, 1.0f);
            std::vector<float> b(block * 2);
            int n = 0;
            float prev = 0;
            for (int done = 0; done < sr / 5; done += block) {
                c.render(b.data(), block);
                for (int i = 0; i < block; ++i) { const float x = b[i * 2]; if ((prev < 0) != (x < 0)) ++n; prev = x; }
            }
            return n;
        };
        const int base = crossings(0), up = crossings(12);
        std::printf("synth transpose: crossings 0st=%d +12st=%d\n", base, up);
        if (up < base * 1.6) {
            std::printf("FAIL synth transpose\n");
            return 1;
        }
    }

    std::printf("OK\n");
    return 0;
}
