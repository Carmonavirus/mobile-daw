// Desktop smoke test for the audio core: renders a short loop to out/render.wav
// and fails if the output is silent, clipped flat, or contains NaNs.
// Build: g++ -std=c++17 -O2 -I../app/src/main/cpp main.cpp ../app/src/main/cpp/DawCore.cpp -o out/enginetest
#include <cmath>
#include <cstdio>
#include <cstdint>
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

int main() {
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
    core.setChannelSampler(8, true, 0.05f, 0.8f, 0.002f, 0.001f, 0.01f, 0.6f, 0.03f, 0, false);
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
    // Amplitude envelope on a steady sample: attack ramps in, sustain holds below peak.
    auto sampleLevel = [&](float attack, float hold, float decay, float sustain, int atSample) {
        Core c2;
        c2.setSampleRate(sr);
        c2.setMasterVolume(1.0f);
        c2.setChannelCount(1);
        c2.setChannel(0, 8, 1.0f, 0.0f, false, false);
        std::vector<float> flat(sr, 0.5f);  // 1 s of constant 0.5
        c2.setSample(0, flat, static_cast<float>(sr));
        c2.setChannelSampler(0, false, 0.0f, 1.0f, attack, hold, decay, sustain, 0.0f, 0, false);
        c2.previewNote(0, 60, 1.0f);
        std::vector<float> mono;
        std::vector<float> b2(block * 2);
        for (int done = 0; done < sr; done += block) {
            c2.render(b2.data(), block);
            for (int i = 0; i < block; ++i) mono.push_back(b2[i * 2]);
        }
        return std::fabs(mono[static_cast<size_t>(atSample)]);
    };
    const float envEarly = sampleLevel(0.2f, 0.0f, 0.0f, 1.0f, sr / 100);  // ~10 ms into a 200 ms attack
    const float envFull = sampleLevel(0.2f, 0.0f, 0.0f, 1.0f, sr / 2);     // well past the attack
    const float susLevel = sampleLevel(0.0f, 0.0f, 0.02f, 0.4f, sr / 2);   // held at sustain 0.4
    const float susPeak = sampleLevel(0.0f, 0.0f, 0.02f, 0.4f, sr / 500);  // during the brief full part
    std::printf("env: early=%.3f full=%.3f susLevel=%.3f susPeak=%.3f\n", envEarly, envFull, susLevel, susPeak);
    if (envEarly > 0.15f || envFull < 0.45f ||               // attack keeps the start quiet, then opens up
        susLevel > 0.26f || susLevel < 0.14f ||              // ~0.5 * 0.4 = 0.20
        susPeak < 0.40f) {                                   // peaks near full before decaying
        std::printf("FAIL envelope\n");
        return 1;
    }

    std::printf("OK\n");
    return 0;
}
