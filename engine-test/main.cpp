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
    std::printf("OK\n");
    return 0;
}
