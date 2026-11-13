#include "ClipStretch.h"

#include <algorithm>
#include <array>
#include <cmath>

#include "PitchCorrector.h"

namespace daw::fx {

namespace {
constexpr double kPi = 3.14159265358979323846;

// Windowed-sinc table for resampling: kZeros zero crossings each side, kOversample steps each.
constexpr int kZeros = 12;
constexpr int kOversample = 256;

const std::vector<float>& sincTable() {
    static const std::vector<float> table = [] {
        std::vector<float> t(static_cast<size_t>(kZeros * kOversample + 2));
        for (size_t i = 0; i < t.size(); ++i) {
            const double x = static_cast<double>(i) / kOversample;
            if (x >= kZeros) {
                t[i] = 0.0f;
                continue;
            }
            const double sinc = x < 1e-9 ? 1.0 : std::sin(kPi * x) / (kPi * x);
            // Blackman window over [-kZeros, kZeros].
            const double u = 0.5 + 0.5 * x / kZeros;
            const double w = 0.42 - 0.5 * std::cos(2 * kPi * u) + 0.08 * std::cos(4 * kPi * u);
            t[i] = static_cast<float>(sinc * w);
        }
        return t;
    }();
    return table;
}

std::vector<float> vocalShift(const float* in, size_t n, float sampleRate, float semis) {
    PitchCorrector pc;
    pc.prepare(sampleRate);
    pc.reset();
    float p[kPitchParamCount] = {};
    p[kPitchNoteMask] = 4095.0f;
    p[kPitchRetuneMs] = 0.0f;
    p[kPitchAmount] = 0.0f;  // no correction: every note just moves by the transpose
    p[kPitchTranspose] = std::clamp(semis, -12.0f, 12.0f);
    p[kPitchMix] = 1.0f;
    p[kPitchRange] = 1.0f;
    p[kPitchVoices] = static_cast<float>(kVoicesOff);
    const size_t latency = static_cast<size_t>(pc.latency());
    std::vector<float> out(n, 0.0f);
    constexpr size_t kBlock = 256;
    std::array<float, kBlock> l{}, r{};
    // Run `latency` samples past the end so the tail comes out too, and drop the first `latency`.
    for (size_t pos = 0; pos < n + latency; pos += kBlock) {
        const size_t len = std::min(kBlock, n + latency - pos);
        for (size_t i = 0; i < len; ++i) l[i] = r[i] = pos + i < n ? in[pos + i] : 0.0f;
        pc.process(l.data(), r.data(), static_cast<int>(len), p);
        for (size_t i = 0; i < len; ++i) {
            const size_t at = pos + i;
            if (at >= latency && at - latency < n) out[at - latency] = l[i];
        }
    }
    return out;
}
}  // namespace

// WSOLA: frames of ~46 ms with a Hann window, laid down every half frame in the output and read
// every half frame / factor in the input. Each frame's read position may move by up to 10 ms
// so it lines up with how the previous frame would have carried on, which keeps the waveform
// continuous (no phasey smearing on voices, transients stay sharp).
std::vector<float> wsolaStretch(const float* in, size_t n, float sampleRate, double factor) {
    const long frame = std::clamp(2L * std::lround(0.023 * sampleRate), 256L, 8192L);
    const long hop = frame / 2;
    const double readHop = static_cast<double>(hop) / factor;
    const long tolerance = std::max(16L, std::lround(0.010 * sampleRate));
    const size_t outLen = static_cast<size_t>(std::ceil(static_cast<double>(n) * factor));
    std::vector<float> out(outLen + static_cast<size_t>(frame) + 1, 0.0f);
    if (n == 0) {
        out.resize(outLen);
        return out;
    }

    std::vector<float> window(static_cast<size_t>(frame));
    for (long j = 0; j < frame; ++j) window[static_cast<size_t>(j)] = static_cast<float>(0.5 - 0.5 * std::cos(2 * kPi * j / frame));
    const long len = static_cast<long>(n);
    auto at = [&](long i) { return i >= 0 && i < len ? in[i] : 0.0f; };

    long prev = 0;
    for (long k = 0; k * hop < static_cast<long>(outLen) + hop; ++k) {
        long pos = 0;
        if (k > 0) {
            const long nominal = std::lround(static_cast<double>(k) * readHop);
            const long natural = prev + hop;  // where the previous frame's audio carries on
            const long lo = std::max(0L, nominal - tolerance);
            const long hi = std::max(lo, nominal + tolerance);
            // Coarse search on every 4th lag and sample, then refine around the best one.
            auto score = [&](long cand, long step) {
                double s = 0.0;
                for (long j = 0; j < hop; j += step) s += static_cast<double>(at(cand + j)) * at(natural + j);
                return s;
            };
            // Ties (e.g. silence) keep the nominal position, so the timing doesn't drift.
            long best = std::clamp(nominal, lo, hi);
            double bestScore = score(best, 4);
            for (long c = lo; c <= hi; c += 4) {
                const double s = score(c, 4);
                if (s > bestScore + 1e-9) {
                    bestScore = s;
                    best = c;
                }
            }
            long refined = best;
            bestScore = score(best, 1);
            for (long c = std::max(lo, best - 3); c <= std::min(hi, best + 3); ++c) {
                const double s = score(c, 1);
                if (s > bestScore + 1e-9) {
                    bestScore = s;
                    refined = c;
                }
            }
            pos = refined;
        }
        const long o = k * hop;
        for (long j = 0; j < frame; ++j) {
            // The first frame starts at full level instead of fading in.
            const float w = (k == 0 && j < hop) ? 1.0f : window[static_cast<size_t>(j)];
            const size_t idx = static_cast<size_t>(o + j);
            if (idx < out.size()) out[idx] += w * at(pos + j);
        }
        prev = pos;
    }
    out.resize(outLen);
    return out;
}

// Band-limited resampling: reads `in` every `step` samples (step > 1 also low-passes so the
// raised pitch doesn't alias).
std::vector<float> resampleTo(const std::vector<float>& in, double step, size_t outLen) {
    const auto& table = sincTable();
    const double fc = std::min(1.0, 1.0 / step);
    const long half = static_cast<long>(std::ceil(kZeros / fc));
    const long len = static_cast<long>(in.size());
    std::vector<float> out(outLen, 0.0f);
    for (size_t i = 0; i < outLen; ++i) {
        const double t = static_cast<double>(i) * step;
        const long c = static_cast<long>(std::floor(t));
        double acc = 0.0;
        for (long j = c - half + 1; j <= c + half; ++j) {
            if (j < 0 || j >= len) continue;
            const double x = std::fabs(t - static_cast<double>(j)) * fc * kOversample;
            const long xi = static_cast<long>(x);
            if (xi >= kZeros * kOversample) continue;
            const double frac = x - static_cast<double>(xi);
            const double k = table[static_cast<size_t>(xi)] + (table[static_cast<size_t>(xi + 1)] - table[static_cast<size_t>(xi)]) * frac;
            acc += in[static_cast<size_t>(j)] * k;
        }
        out[i] = static_cast<float>(acc * fc);
    }
    return out;
}

std::vector<float> shiftKeepLength(const float* in, size_t n, float sampleRate, float semis, int mode) {
    semis = std::clamp(semis, -24.0f, 24.0f);
    if (n == 0 || std::fabs(semis) < 1e-4f || mode == kClipResample || sampleRate <= 0.0f) return std::vector<float>(in, in + n);
    if (mode == kClipVocal) return vocalShift(in, n, sampleRate, semis);
    // Make it `ratio` times longer at the same pitch, then play that `ratio` times faster.
    const double ratio = std::exp2(semis / 12.0);
    const auto stretched = wsolaStretch(in, n, sampleRate, ratio);
    return resampleTo(stretched, ratio, n);
}

}  // namespace daw::fx
