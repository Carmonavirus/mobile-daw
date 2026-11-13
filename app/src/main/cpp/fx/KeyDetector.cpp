#include "KeyDetector.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr float kLowHz = 55.0f;
constexpr float kHighHz = 2000.0f;
constexpr float kTargetRate = 6000.0f;

// Krumhansl-Kessler probe-tone profiles, tonic first.
constexpr double kMajor[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
constexpr double kMinor[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

struct Tables {
    std::array<float, KeyDetector::kFft> window{};
    std::array<float, KeyDetector::kFft / 2> cosT{};
    std::array<float, KeyDetector::kFft / 2> sinT{};
    Tables() {
        constexpr int n = KeyDetector::kFft;
        for (int i = 0; i < n; ++i) window[static_cast<size_t>(i)] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * i / n));
        for (int i = 0; i < n / 2; ++i) {
            cosT[static_cast<size_t>(i)] = static_cast<float>(std::cos(2.0 * kPi * i / n));
            sinT[static_cast<size_t>(i)] = static_cast<float>(-std::sin(2.0 * kPi * i / n));
        }
    }
};

const Tables& tables() {
    static const Tables t;
    return t;
}

// In-place radix-2 complex FFT of size KeyDetector::kFft.
void fft(float* re, float* im) {
    constexpr int n = KeyDetector::kFft;
    const Tables& t = tables();
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const int half = len >> 1;
        const int step = n / len;
        for (int i = 0; i < n; i += len) {
            for (int k = 0; k < half; ++k) {
                const float wr = t.cosT[static_cast<size_t>(k * step)];
                const float wi = t.sinT[static_cast<size_t>(k * step)];
                const int a = i + k, b = a + half;
                const float xr = re[b] * wr - im[b] * wi;
                const float xi = re[b] * wi + im[b] * wr;
                re[b] = re[a] - xr;
                im[b] = im[a] - xi;
                re[a] += xr;
                im[a] += xi;
            }
        }
    }
}

double correlate(const double* x, const double* profile, int rotate) {
    double mx = 0, mp = 0;
    for (int i = 0; i < 12; ++i) {
        mx += x[i];
        mp += profile[i];
    }
    mx /= 12.0;
    mp /= 12.0;
    double sxy = 0, sxx = 0, syy = 0;
    for (int i = 0; i < 12; ++i) {
        const double a = x[(i + rotate) % 12] - mx;
        const double b = profile[i] - mp;
        sxy += a * b;
        sxx += a * a;
        syy += b * b;
    }
    if (sxx <= 1e-12 || syy <= 1e-12) return 0.0;
    return sxy / std::sqrt(sxx * syy);
}
}  // namespace

KeyDetector::KeyDetector() {
    for (auto& o : out_) o.store(0.0f, std::memory_order_relaxed);
    out_[kKeyOutKey].store(-1.0f, std::memory_order_relaxed);
    out_[kKeyOutSecond].store(-1.0f, std::memory_order_relaxed);
}

void KeyDetector::prepare(float sampleRate) {
    if (sampleRate == sampleRate_) return;
    sampleRate_ = sampleRate;
    decimate_ = std::max(1, static_cast<int>(std::lround(sampleRate / kTargetRate)));
    rate_ = sampleRate / static_cast<float>(decimate_);
    // Butterworth-ish low-pass (four stacked 2nd-order sections) well under the new Nyquist.
    const double fc = std::min(0.38 * rate_, 0.45 * sampleRate);
    const double w0 = 2.0 * kPi * fc / sampleRate;
    const double q = 0.7071;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double cw = std::cos(w0);
    const double a0 = 1.0 + alpha;
    for (auto& f : filters_) {
        f.b0 = static_cast<float>((1.0 - cw) / 2.0 / a0);
        f.b1 = static_cast<float>((1.0 - cw) / a0);
        f.b2 = f.b0;
        f.a1 = static_cast<float>(-2.0 * cw / a0);
        f.a2 = static_cast<float>((1.0 - alpha) / a0);
    }
    reset();
}

void KeyDetector::reset() {
    for (auto& f : filters_) f.z1 = f.z2 = 0.0f;
    ring_.fill(0.0f);
    write_ = 0;
    sinceHop_ = 0;
    hopEnergy_ = 0.0;
    phase_ = 0;
    primed_ = false;
    clearHeard();
}

void KeyDetector::clearHeard() {
    heard_.fill(0.0);
    heardSeconds_ = 0.0;
    publish();
}

void KeyDetector::process(const float* left, const float* right, int frames, const float* params) {
    if (!primed_ || params[kKeyReset] != lastReset_) {
        if (primed_) clearHeard();
        lastReset_ = params[kKeyReset];
        primed_ = true;
    }
    memory_ = std::max(0.0f, params[kKeyMemory]);
    for (int i = 0; i < frames; ++i) {
        float x = 0.5f * (left[i] + right[i]);
        for (auto& f : filters_) x = f.run(x);
        if (++phase_ < decimate_) continue;
        phase_ = 0;
        if (!std::isfinite(x)) x = 0.0f;
        ring_[static_cast<size_t>(write_)] = x;
        write_ = (write_ + 1) % kFft;
        hopEnergy_ += static_cast<double>(x) * x;
        if (++sinceHop_ >= kHop) {
            analyse();
            sinceHop_ = 0;
            hopEnergy_ = 0.0;
        }
    }
}

void KeyDetector::analyse() {
    const double hopSeconds = kHop / static_cast<double>(rate_);
    const double rms = std::sqrt(hopEnergy_ / kHop);
    const double db = 20.0 * std::log10(rms + 1e-12);
    // Quieter than -60 dBFS counts as silence; full weight from -35 dBFS up.
    const double loud = std::clamp((db + 60.0) / 25.0, 0.0, 1.0);
    if (loud <= 0.0) return;

    const Tables& t = tables();
    for (int i = 0; i < kFft; ++i) {
        re_[static_cast<size_t>(i)] = ring_[static_cast<size_t>((write_ + i) % kFft)] * t.window[static_cast<size_t>(i)];
        im_[static_cast<size_t>(i)] = 0.0f;
    }
    fft(re_.data(), im_.data());

    const float binHz = rate_ / kFft;
    const int lo = std::max(2, static_cast<int>(kLowHz / binHz));
    const int hi = std::min(kFft / 2 - 2, static_cast<int>(kHighHz / binHz) + 1);
    // Magnitudes kept in re_ (the FFT output is not needed after this).
    float peak = 0.0f;
    for (int k = lo - 1; k <= hi + 1; ++k) {
        const float m = std::sqrt(re_[static_cast<size_t>(k)] * re_[static_cast<size_t>(k)] + im_[static_cast<size_t>(k)] * im_[static_cast<size_t>(k)]);
        re_[static_cast<size_t>(k)] = m;
        peak = std::max(peak, m);
    }
    if (peak <= 1e-9f) return;

    double frame[12] = {};
    const float floor = peak * 0.01f;  // ignore peaks more than 40 dB under the loudest
    for (int k = lo; k <= hi; ++k) {
        const float b = re_[static_cast<size_t>(k)];
        const float a = re_[static_cast<size_t>(k - 1)];
        const float c = re_[static_cast<size_t>(k + 1)];
        if (b <= floor || b <= a || b < c) continue;
        // Parabolic interpolation on the log magnitude for the true peak frequency.
        const float la = std::log(a + 1e-9f), lb = std::log(b + 1e-9f), lc = std::log(c + 1e-9f);
        const float den = la - 2.0f * lb + lc;
        const float off = std::fabs(den) > 1e-9f ? std::clamp(0.5f * (la - lc) / den, -0.5f, 0.5f) : 0.0f;
        const float hz = (static_cast<float>(k) + off) * binHz;
        if (hz < kLowHz || hz > kHighHz) continue;
        const double midi = 69.0 + 12.0 * std::log2(hz / 440.0);
        const double nearest = std::round(midi);
        const double dev = midi - nearest;  // -0.5..0.5 semitones
        const double close = std::cos(kPi * dev);
        // Loudness on a compressed (dB-like) scale so quiet chord tones still count.
        const double weight = std::log1p(100.0 * b / peak) * close * close;
        frame[(static_cast<int>(nearest) % 12 + 12) % 12] += weight;
    }
    double sum = 0.0;
    for (double v : frame) sum += v;
    if (sum <= 0.0) return;

    const double fade = memory_ > 0.0f ? std::exp(-hopSeconds / memory_) : 1.0;
    for (int i = 0; i < 12; ++i) heard_[static_cast<size_t>(i)] = heard_[static_cast<size_t>(i)] * fade + frame[i] / sum * loud * hopSeconds;
    heardSeconds_ = heardSeconds_ * fade + loud * hopSeconds;
    publish();
}

void KeyDetector::scoreKeys(const double* chroma, double* scores) {
    for (int k = 0; k < 12; ++k) {
        scores[k] = correlate(chroma, kMajor, k);
        scores[k + 12] = correlate(chroma, kMinor, k);
    }
}

void KeyDetector::publish() {
    double chroma[12];
    double top = 0.0;
    for (int i = 0; i < 12; ++i) {
        chroma[i] = heard_[static_cast<size_t>(i)];
        top = std::max(top, chroma[i]);
    }
    for (int i = 0; i < 12; ++i)
        out_[static_cast<size_t>(kKeyOutChroma + i)].store(top > 0.0 ? static_cast<float>(chroma[i] / top) : 0.0f, std::memory_order_relaxed);
    out_[kKeyOutHeard].store(static_cast<float>(heardSeconds_), std::memory_order_relaxed);
    if (heardSeconds_ < 0.5 || top <= 0.0) {
        out_[kKeyOutKey].store(-1.0f, std::memory_order_relaxed);
        out_[kKeyOutSecond].store(-1.0f, std::memory_order_relaxed);
        out_[kKeyOutConfidence].store(0.0f, std::memory_order_relaxed);
        out_[kKeyOutBestScore].store(0.0f, std::memory_order_relaxed);
        out_[kKeyOutSecondScore].store(0.0f, std::memory_order_relaxed);
        return;
    }
    double scores[24];
    scoreKeys(chroma, scores);
    int best = 0, second = 1;
    if (scores[second] > scores[best]) std::swap(best, second);
    for (int k = 2; k < 24; ++k) {
        if (scores[k] > scores[best]) {
            second = best;
            best = k;
        } else if (scores[k] > scores[second]) {
            second = k;
        }
    }
    const double r1 = scores[best], r2 = scores[second];
    // Sure when the best key fits well and clearly beats the next one; grows with listening time.
    const double fit = std::clamp((r1 - 0.3) / 0.5, 0.0, 1.0);
    const double margin = std::clamp((r1 - r2) / 0.15, 0.0, 1.0);
    const double time = std::clamp(heardSeconds_ / 4.0, 0.0, 1.0);
    const double confidence = std::sqrt(fit * margin) * (0.4 + 0.6 * time);
    out_[kKeyOutKey].store(static_cast<float>(best), std::memory_order_relaxed);
    out_[kKeyOutSecond].store(static_cast<float>(second), std::memory_order_relaxed);
    out_[kKeyOutConfidence].store(static_cast<float>(confidence), std::memory_order_relaxed);
    out_[kKeyOutBestScore].store(static_cast<float>(r1), std::memory_order_relaxed);
    out_[kKeyOutSecondScore].store(static_cast<float>(r2), std::memory_order_relaxed);
}

void KeyDetector::read(float* out) const {
    for (int i = 0; i < kKeyOutCount; ++i) out[i] = out_[static_cast<size_t>(i)].load(std::memory_order_relaxed);
}

}  // namespace daw::fx
