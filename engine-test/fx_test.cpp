// Desktop tests for the mixer effects: parametric EQ response, pitch correction accuracy,
// and mixer-track routing in the core. Writes out/pitch_*.wav so the result can be heard.
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>

#include "DawCore.h"
#include "fx/ParametricEq.h"
#include "fx/PitchCorrector.h"

using namespace daw;
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

// RMS gain of the EQ on a sine at `hz`, after it has settled.
static double eqGain(const float* params, double hz) {
    ParametricEq eq;
    eq.prepare(kSr);
    const int n = 24000;
    std::vector<float> l(n), r(n);
    for (int i = 0; i < n; ++i) l[i] = r[i] = static_cast<float>(0.25 * std::sin(2 * kPi * hz * i / kSr));
    for (int i = 0; i < n; i += 256) eq.process(&l[i], &r[i], std::min(256, n - i), params);
    double in = 0, out = 0;
    for (int i = n / 2; i < n; ++i) {
        const double x = 0.25 * std::sin(2 * kPi * hz * i / kSr);
        in += x * x;
        out += double(l[i]) * l[i];
    }
    return 20 * std::log10(std::sqrt(out / in));
}

// Frequency of a periodic signal from its autocorrelation peak (between 70 and 1500 Hz).
static double measureHz(const std::vector<float>& x, int from, int len) {
    int bestLag = 0;
    double best = -1e9;
    std::vector<double> ac(700);
    for (int lag = 32; lag < 690; ++lag) {
        double s = 0, e = 0;
        for (int i = from; i < from + len; ++i) {
            s += double(x[i]) * x[i + lag];
            e += double(x[i + lag]) * x[i + lag];
        }
        ac[lag] = s / std::sqrt(e + 1e-12);
    }
    // First strong peak, so octave errors don't win.
    double maxAc = 0;
    for (int lag = 32; lag < 690; ++lag) maxAc = std::max(maxAc, ac[lag]);
    for (int lag = 33; lag < 689; ++lag) {
        if (ac[lag] > 0.9 * maxAc && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) {
            bestLag = lag;
            best = ac[lag];
            break;
        }
    }
    if (bestLag == 0) return 0;
    const double a = ac[bestLag - 1], b = best, c = ac[bestLag + 1];
    const double off = 0.5 * (a - c) / (a - 2 * b + c);
    return kSr / (bestLag + off);
}

// Level of one frequency (Goertzel), as RMS.
static double toneLevel(const std::vector<float>& x, int from, int len, double hz) {
    const double w = 2 * kPi * hz / kSr, c = 2 * std::cos(w);
    double s1 = 0, s2 = 0;
    for (int i = from; i < from + len; ++i) {
        const double hann = 0.5 - 0.5 * std::cos(2 * kPi * (i - from) / len);
        const double s0 = x[i] * hann + c * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    return std::sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (len * 0.25);
}

// A voice-like test tone: a few harmonics with a slow vibrato around `hz`.
static std::vector<float> voice(double hz, int n, double vibratoCents) {
    std::vector<float> x(n);
    double phase = 0;
    for (int i = 0; i < n; ++i) {
        const double f = hz * std::pow(2.0, vibratoCents * std::sin(2 * kPi * 5.0 * i / kSr) / 1200.0);
        phase += 2 * kPi * f / kSr;
        x[i] = static_cast<float>(0.3 * std::sin(phase) + 0.15 * std::sin(2 * phase) + 0.08 * std::sin(3 * phase) + 0.04 * std::sin(4 * phase));
    }
    return x;
}

static std::vector<float> correct(std::vector<float> in, float mask, float retuneMs, float transpose, PitchCorrector* keep = nullptr,
                                  int voices = kVoicesOff, float voicesLevel = 0.0f, float mix = 1.0f) {
    PitchCorrector local;
    PitchCorrector& pc = keep ? *keep : local;
    pc.prepare(kSr);
    float p[kPitchParamCount] = {};
    p[kPitchNoteMask] = mask;
    p[kPitchRetuneMs] = retuneMs;
    p[kPitchAmount] = 1.0f;
    p[kPitchTranspose] = transpose;
    p[kPitchMix] = mix;
    p[kPitchRange] = 1.0f;
    p[kPitchVoices] = static_cast<float>(voices);
    p[kPitchVoicesLevel] = voicesLevel;
    std::vector<float> r = in;
    for (size_t i = 0; i < in.size(); i += 192) {
        const int n = static_cast<int>(std::min<size_t>(192, in.size() - i));
        pc.process(&in[i], &r[i], n, p);
    }
    return in;
}

int main() {
    // ---- EQ
    float p[kEqParamCount] = {};
    auto band = [&](int b, BandType t, float f, float g, float q, float slope = 1) {
        float* v = p + b * kEqBandParams;
        v[0] = 1; v[1] = static_cast<float>(t); v[2] = f; v[3] = g; v[4] = q; v[5] = slope;
    };
    band(0, BandType::Bell, 1000, 12, 1);
    check(std::fabs(eqGain(p, 1000) - 12) < 0.5, "EQ bell +12 dB at its centre (dB)", eqGain(p, 1000));
    check(std::fabs(eqGain(p, 100)) < 1.0, "EQ bell leaves 100 Hz alone (dB)", eqGain(p, 100));
    band(1, BandType::LowCut, 200, 0, 0.707f, 2);
    check(eqGain(p, 50) < -20, "EQ 24 dB/oct low cut at 200 Hz, 50 Hz (dB)", eqGain(p, 50));
    p[0 * kEqBandParams] = 0;  // bell off, so only the low cut and output gain remain
    p[kEqOutputParam] = -6;
    check(std::fabs(eqGain(p, 5000) + 6) < 0.2, "EQ output gain -6 dB (dB)", eqGain(p, 5000));
    p[1 * kEqBandParams] = 0;
    p[kEqOutputParam] = 0;
    check(std::fabs(eqGain(p, 1000)) < 0.05, "EQ with every band off is flat (dB)", eqGain(p, 1000));

    // ---- pitch correction
    const int n = static_cast<int>(kSr * 2);
    const float cMajor = static_cast<float>((1 << 0) | (1 << 2) | (1 << 4) | (1 << 5) | (1 << 7) | (1 << 9) | (1 << 11));
    const float chromatic = 4095.0f;
    {
        // 40 cents sharp of A4 -> pulled to 440 Hz.
        auto in = voice(440.0 * std::pow(2.0, 40 / 1200.0), n, 0);
        auto out = correct(in, cMajor, 0, 0);
        const double hz = measureHz(out, n / 2, 4096);
        check(std::fabs(1200 * std::log2(hz / 440.0)) < 8, "pitch: A4 +40 cents corrected to 440 Hz (Hz)", hz);
        writeWav("out/pitch_sharp_in.wav", in);
        writeWav("out/pitch_sharp_out.wav", out);
    }
    {
        // C#4 is not in C major: snaps to C4 or D4, whichever is nearer (C#4 + 30 cents -> D4).
        auto out = correct(voice(277.18 * std::pow(2.0, 30 / 1200.0), n, 0), cMajor, 0, 0);
        const double hz = measureHz(out, n / 2, 4096);
        check(std::fabs(1200 * std::log2(hz / 293.66)) < 10, "pitch: out-of-key C#4+30c snaps to D4 (Hz)", hz);
    }
    {
        // Low male voice with vibrato, chromatic: lands on G2 on average.
        auto out = correct(voice(98.0 * std::pow(2.0, -35 / 1200.0), n, 20), chromatic, 0, 0);
        const double hz = measureHz(out, n / 2, 8192);
        check(std::fabs(1200 * std::log2(hz / 98.0)) < 15, "pitch: low G2 -35 cents with vibrato -> G2 (Hz)", hz);
    }
    {
        auto out = correct(voice(440.0, n, 0), chromatic, 20, 12);
        const double hz = measureHz(out, n / 2, 4096);
        check(std::fabs(1200 * std::log2(hz / 880.0)) < 10, "pitch: +12 transpose moves A4 to A5 (Hz)", hz);
    }
    {
        // Already in tune: the output keeps the pitch and its level.
        auto in = voice(329.63, n, 0);
        PitchCorrector pc;
        auto out = correct(in, cMajor, 30, 0, &pc);
        double ein = 0, eout = 0;
        for (int i = n / 2; i < n; ++i) { ein += double(in[i]) * in[i]; eout += double(out[i]) * out[i]; }
        const double db = 10 * std::log10(eout / ein);
        check(std::fabs(db) < 1.5, "pitch: in-tune E4 keeps its level (dB)", db);
        check(std::fabs(pc.detectedHz() - 329.63) < 3, "pitch: detector reads E4 (Hz)", pc.detectedHz());
    }
    {
        // Hard tune: 0 ms retune flattens a wide vibrato into a straight line on the note.
        auto in = voice(440.0, n, 60);
        auto out = correct(in, cMajor, 0, 0);
        double worstIn = 0, worstOut = 0;
        for (int k = 0; k < 16; ++k) {
            const int from = n / 2 + k * 2400;
            worstIn = std::max(worstIn, std::fabs(1200 * std::log2(measureHz(in, from, 1200) / 440.0)));
            worstOut = std::max(worstOut, std::fabs(1200 * std::log2(measureHz(out, from, 1200) / 440.0)));
        }
        check(worstIn > 40 && worstOut < 8, "pitch: hard tune flattens 60c vibrato, worst (cents)", worstOut);
        writeWav("out/pitch_vibrato_in.wav", in);
        writeWav("out/pitch_hardtune_out.wav", out);
    }
    {
        // Slow retune keeps most of the vibrato (natural setting).
        auto in = voice(440.0, n, 60);
        auto out = correct(in, cMajor, 200, 0);
        double worstOut = 0;
        for (int k = 0; k < 16; ++k)
            worstOut = std::max(worstOut, std::fabs(1200 * std::log2(measureHz(out, n / 2 + k * 2400, 1200) / 440.0)));
        check(worstOut > 30, "pitch: 200 ms retune keeps the vibrato (cents)", worstOut);
    }
    {
        // A bigger move keeps roughly its level: +5 semitones. (The shifter keeps the tone, so a
        // sparse test tone loses a little; real voices have a smoother spectrum.)
        auto in = voice(440.0, n, 0);
        auto out = correct(in, chromatic, 0, 5);
        const double hz = measureHz(out, n / 2, 4096);
        check(std::fabs(1200 * std::log2(hz / 587.33)) < 10, "pitch: +5 transpose moves A4 to D5 (Hz)", hz);
        double ein = 0, eout = 0;
        for (int i = n / 2; i < n; ++i) { ein += double(in[i]) * in[i]; eout += double(out[i]) * out[i]; }
        check(std::fabs(10 * std::log10(eout / ein)) < 3.5, "pitch: +5 transpose keeps the level (dB)", 10 * std::log10(eout / ein));
        writeWav("out/pitch_up5_out.wav", out);
    }
    {
        // Mix 0 is the dry voice, delayed by exactly the reported latency.
        auto in = voice(300.0, n, 30);
        PitchCorrector pc;
        auto out = correct(in, cMajor, 0, 0, &pc, kVoicesOff, 0, 0.0f);
        const int lat = pc.latency();
        double err = 0;
        for (int i = lat; i < n; ++i) err = std::max(err, double(std::fabs(out[i] - in[i - lat])));
        check(err < 1e-5 && lat > 0 && lat < 0.03 * kSr, "pitch: dry path delayed by the latency (max err)", err);
    }
    {
        // Harmony voices: A4 in C major gets C5 (a third up) and E5 (a fifth up).
        auto in = voice(440.0, n, 0);
        auto plain = correct(in, cMajor, 0, 0);
        auto chord = correct(in, cMajor, 0, 0, nullptr, kVoicesChord, 1.0f);
        const int from = n / 2, len = 16384;
        const double c5 = toneLevel(chord, from, len, 523.25), e5 = toneLevel(chord, from, len, 659.26);
        const double c5Plain = toneLevel(plain, from, len, 523.25);
        check(c5 > 0.07 && e5 > 0.07 && c5Plain < 0.02, "pitch: chord voices add C5 and E5 (level)", std::min(c5, e5));
        writeWav("out/pitch_chord_out.wav", chord);
        auto low = correct(in, cMajor, 0, 0, nullptr, kVoicesOctaveDown, 1.0f);
        check(toneLevel(low, from, len, 220.0) > 0.1, "pitch: octave-down voice adds A3 (level)", toneLevel(low, from, len, 220.0));
        auto dbl = correct(in, cMajor, 0, 0, nullptr, kVoicesDouble, 1.0f);
        const double hz = measureHz(dbl, n / 2, 4096);
        check(std::fabs(1200 * std::log2(hz / 440.0)) < 10, "pitch: double voices stay on A4 (Hz)", hz);
    }
    {
        // Silence in, silence out, and no NaNs.
        std::vector<float> zero(n, 0.0f);
        auto out = correct(zero, cMajor, 0, 5);
        double peak = 0;
        bool nan = false;
        for (float s : out) { peak = std::max(peak, double(std::fabs(s))); nan |= std::isnan(s); }
        check(peak < 1e-6 && !nan, "pitch: silence stays silent", peak);
    }

    // ---- mixer tracks in the core
    auto renderPeak = [](bool routed, bool trackMuted) {
        Core core;
        core.setSampleRate(48000);
        core.setChannelCount(1);
        core.setChannel(0, 5, 0.8f, 0.0f, false, false);  // lead synth
        core.setInsertCount(1);
        core.setInsert(0, 1.0f, 0.0f, trackMuted);
        core.setInsertEffect(0, 0, kFxEq, false);
        core.setChannelInsert(0, routed ? 0 : -1);
        std::vector<NoteEvent> ev{{0, 0, kTicksPerBeat * 4, 69, 1.0f}};
        core.setSequence(ev, kTicksPerBeat * 4);
        core.setPlaying(true);
        std::vector<float> buf(2 * 4800 * 2);
        core.render(buf.data(), 4800 * 2);  // bigger than one block: exercises the split
        double peak = 0;
        for (float s : buf) peak = std::max(peak, double(std::fabs(s)));
        float lv[1];
        core.readInsertLevels(lv, 1);
        return std::make_pair(peak, double(lv[0]));
    };
    auto direct = renderPeak(false, false);
    auto routed = renderPeak(true, false);
    auto muted = renderPeak(true, true);
    check(direct.first > 0.05, "core: channel straight to master is audible", direct.first);
    check(std::fabs(routed.first - direct.first) < 0.02, "core: through a flat mixer track, same level", routed.first);
    check(routed.second > 0.05, "core: mixer track meter moves", routed.second);
    check(muted.first < 1e-4, "core: muted mixer track silences its channels", muted.first);

    std::printf(failures ? "\n%d check(s) failed\n" : "\nall effect checks passed\n", failures);
    return failures ? 1 : 0;
}
