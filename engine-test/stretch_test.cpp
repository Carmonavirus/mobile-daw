// Desktop tests for clip pitch shifting that keeps the length (Stretch and Vocal modes).
// Writes out/stretch_*.wav so the result can be heard.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "fx/ClipStretch.h"

using namespace daw::fx;

static int failures = 0;
static void check(bool ok, const char* what, double value) {
    std::printf("%s %-58s %.3f\n", ok ? "ok  " : "FAIL", what, value);
    if (!ok) ++failures;
}

constexpr float kSr = 48000.0f;
constexpr double kPi = 3.14159265358979;

static void writeWav(const char* path, const std::vector<float>& mono) {
    FILE* f = std::fopen(path, "wb");
    if (!f) return;
    auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    const uint32_t bytes = static_cast<uint32_t>(mono.size() * 2);
    std::fwrite("RIFF", 1, 4, f); w32(36 + bytes); std::fwrite("WAVEfmt ", 1, 8, f);
    w32(16); w16(1); w16(1); w32(48000); w32(96000); w16(2); w16(16);
    std::fwrite("data", 1, 4, f); w32(bytes);
    for (float s : mono) w16(static_cast<uint16_t>(static_cast<int16_t>(std::fmax(-1.f, std::fmin(1.f, s)) * 32767)));
    std::fclose(f);
}

// Frequency from the first strong autocorrelation peak (70..1500 Hz).
static double measureHz(const std::vector<float>& x, int from, int len) {
    std::vector<double> ac(700, 0.0);
    double maxAc = 0;
    for (int lag = 32; lag < 690; ++lag) {
        double s = 0, e = 0;
        for (int i = from; i < from + len; ++i) {
            s += double(x[i]) * x[i + lag];
            e += double(x[i + lag]) * x[i + lag];
        }
        ac[lag] = s / std::sqrt(e + 1e-12);
        maxAc = std::max(maxAc, ac[lag]);
    }
    for (int lag = 33; lag < 689; ++lag) {
        if (ac[lag] > 0.9 * maxAc && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) {
            const double a = ac[lag - 1], b = ac[lag], c = ac[lag + 1];
            return kSr / (lag + 0.5 * (a - c) / (a - 2 * b + c));
        }
    }
    return 0;
}

static double rms(const std::vector<float>& x, int from, int len) {
    double s = 0;
    for (int i = from; i < from + len; ++i) s += double(x[i]) * x[i];
    return std::sqrt(s / len);
}

static std::vector<float> voice(double hz, int n) {
    std::vector<float> x(n);
    double phase = 0;
    for (int i = 0; i < n; ++i) {
        phase += 2 * kPi * hz / kSr;
        x[i] = static_cast<float>(0.3 * std::sin(phase) + 0.15 * std::sin(2 * phase) + 0.08 * std::sin(3 * phase) + 0.04 * std::sin(4 * phase));
    }
    return x;
}

// Short tone bursts every `every` samples: their start times are the "tempo".
static std::vector<float> bursts(double hz, int n, int every, int burst) {
    std::vector<float> x(n, 0.0f);
    for (int s = every / 4; s + burst < n; s += every)
        for (int i = 0; i < burst; ++i) x[s + i] = static_cast<float>(0.5 * std::sin(2 * kPi * hz * i / kSr));
    return x;
}

// Sample where the signal first gets loud after `from` (smoothed envelope over 0.1).
static int onsetAfter(const std::vector<float>& x, int from) {
    double env = 0;
    for (int i = from; i < static_cast<int>(x.size()); ++i) {
        env += (std::fabs(x[i]) - env) * 0.01;
        if (env > 0.1) return i;
    }
    return -1;
}

static double cents(double hz, double want) { return 1200 * std::log2(hz / want); }

int main() {
    const int n = static_cast<int>(kSr * 2);
    {
        auto in = voice(440.0, n);
        for (int semis : {-5, -12, 7}) {
            auto out = shiftKeepLength(in.data(), in.size(), kSr, static_cast<float>(semis), kClipStretch);
            const double want = 440.0 * std::pow(2.0, semis / 12.0);
            const double hz = measureHz(out, n / 2, 4096);
            char what[96];
            std::snprintf(what, sizeof what, "stretch %+d st: same length (samples)", semis);
            check(out.size() == in.size(), what, double(out.size()));
            std::snprintf(what, sizeof what, "stretch %+d st: pitch within 10 cents (cents off)", semis);
            check(std::fabs(cents(hz, want)) < 10, what, cents(hz, want));
            std::snprintf(what, sizeof what, "stretch %+d st: level within 3 dB (dB)", semis);
            const double db = 20 * std::log10(rms(out, n / 4, n / 2) / rms(in, n / 4, n / 2));
            check(std::fabs(db) < 3, what, db);
            if (semis == -5) writeWav("out/stretch_tone_down5.wav", out);
        }
    }
    {
        // The beat stays where it was: bursts start at the same times after the shift.
        const int every = static_cast<int>(kSr * 0.25);
        auto in = bursts(330.0, n, every, static_cast<int>(kSr * 0.06));
        auto out = shiftKeepLength(in.data(), in.size(), kSr, -5.0f, kClipStretch);
        double worst = 0;
        for (int b = 1; b < 7; ++b) {
            const int from = b * every - every / 8;
            const int a = onsetAfter(in, from), c = onsetAfter(out, from);
            worst = std::max(worst, std::fabs(double(c - a)) / kSr * 1000);
            std::printf("  burst %d: %+.1f ms\n", b, double(c - a) / kSr * 1000);
        }
        check(worst < 15, "stretch -5 st: beats stay in time (worst ms)", worst);
        writeWav("out/stretch_bursts_in.wav", in);
        writeWav("out/stretch_bursts_down5.wav", out);
    }
    {
        auto in = voice(220.0, n);
        for (int semis : {-5, 4}) {
            auto out = shiftKeepLength(in.data(), in.size(), kSr, static_cast<float>(semis), kClipVocal);
            const double want = 220.0 * std::pow(2.0, semis / 12.0);
            const double hz = measureHz(out, n / 2, 4096);
            char what[96];
            std::snprintf(what, sizeof what, "vocal %+d st: same length (samples)", semis);
            check(out.size() == in.size(), what, double(out.size()));
            std::snprintf(what, sizeof what, "vocal %+d st: pitch within 15 cents (cents off)", semis);
            check(std::fabs(cents(hz, want)) < 15, what, cents(hz, want));
            if (semis == -5) writeWav("out/stretch_vocal_down5.wav", out);
        }
    }
    {
        auto in = voice(440.0, 4800);
        auto same = shiftKeepLength(in.data(), in.size(), kSr, 0.0f, kClipStretch);
        check(same == in, "0 st: untouched", 0);
        auto none = shiftKeepLength(in.data(), 0, kSr, -3.0f, kClipStretch);
        check(none.empty(), "empty clip: empty result", 0);
    }

    std::printf(failures ? "\n%d check(s) failed\n" : "\nall stretch checks passed\n", failures);
    return failures ? 1 : 0;
}
