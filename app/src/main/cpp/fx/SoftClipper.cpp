#include "SoftClipper.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

namespace {
constexpr double kPi = 3.14159265358979323846;

double besselI0(double x) {
    double sum = 1.0, term = 1.0;
    for (int k = 1; k < 32; ++k) {
        term *= (x / (2.0 * k)) * (x / (2.0 * k));
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }
}  // namespace

SoftClipper::SoftClipper() {
    // Kaiser-windowed sinc low-pass at the original Nyquist (cutoff 0.5 / kFactor of the
    // oversampled rate, pulled in a little so the transition band ends at Nyquist).
    const double cutoff = 0.5 / kFactor * 0.9;
    const double beta = 7.0;  // about 70 dB stop band
    const double mid = (kTaps - 1) / 2.0;
    double sum = 0.0;
    std::array<double, kTaps> h{};
    for (int i = 0; i < kTaps; ++i) {
        const double t = i - mid;
        const double sinc = t == 0.0 ? 2.0 * cutoff : std::sin(2.0 * kPi * cutoff * t) / (kPi * t);
        const double r = t / mid;
        const double w = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / besselI0(beta);
        h[static_cast<size_t>(i)] = sinc * w;
        sum += h[static_cast<size_t>(i)];
    }
    for (int i = 0; i < kTaps; ++i) fir_[static_cast<size_t>(i)] = static_cast<float>(h[static_cast<size_t>(i)] / sum);
}

void SoftClipper::prepare(float sampleRate) { sampleRate_ = sampleRate; }

void SoftClipper::reset() {
    ch_[0] = Channel{};
    ch_[1] = Channel{};
    primed_ = false;
}

float SoftClipper::shape(float x, float threshold) {
    const float a = std::fabs(x);
    if (a <= threshold) return x;
    if (threshold >= 0.999f) return x > 0.0f ? 1.0f : -1.0f;  // 0 dB threshold: plain clip at the ceiling
    // Above the threshold the slope eases from 1 to 0 along a tanh, so the knee is smooth
    // (no corner) and the output approaches the ceiling without ever passing it.
    const float room = 1.0f - threshold;
    const float y = threshold + room * std::tanh((a - threshold) / room);
    return x > 0.0f ? y : -y;
}

// One input sample in, one out: upsample (polyphase), shape each oversampled point, then
// filter and keep every kFactor-th point.
float SoftClipper::run(Channel& ch, float x, float drive, float threshold) {
    ch.upPos = (ch.upPos + kPhaseTaps - 1) % kPhaseTaps;
    ch.up[static_cast<size_t>(ch.upPos)] = x;
    ch.up[static_cast<size_t>(ch.upPos + kPhaseTaps)] = x;
    const float* hist = &ch.up[static_cast<size_t>(ch.upPos)];
    float out = 0.0f;
    for (int phase = 0; phase < kFactor; ++phase) {
        // Zero-stuffed upsampling: phase p uses taps p, p+F, p+2F, ... (gain F restores level).
        float s = 0.0f;
        for (int k = 0; k < kPhaseTaps; ++k) s += fir_[static_cast<size_t>(phase + k * kFactor)] * hist[k];
        const float shaped = shape(s * static_cast<float>(kFactor) * drive, threshold);
        ch.downPos = (ch.downPos + kTaps - 1) % kTaps;
        ch.down[static_cast<size_t>(ch.downPos)] = shaped;
        ch.down[static_cast<size_t>(ch.downPos + kTaps)] = shaped;
    }
    // Decimate: only the newest point's filter output is kept.
    const float* d = &ch.down[static_cast<size_t>(ch.downPos)];
    for (int k = 0; k < kTaps; ++k) out += fir_[static_cast<size_t>(k)] * d[k];
    return out;
}

void SoftClipper::process(float* left, float* right, int frames, const float* params) {
    const float gain = dbToGain(std::clamp(params[kClipGain], -24.0f, 36.0f));
    const float threshold = dbToGain(std::clamp(params[kClipThreshold], -36.0f, 0.0f));
    const float post = dbToGain(std::clamp(params[kClipPost], -36.0f, 12.0f));
    if (!primed_) {
        gain_ = gain;
        threshold_ = threshold;
        post_ = post;
        primed_ = true;
    }
    const float k = 1.0f - std::exp(-1.0f / (0.01f * sampleRate_));  // ~10 ms glide
    for (int i = 0; i < frames; ++i) {
        gain_ += (gain - gain_) * k;
        threshold_ += (threshold - threshold_) * k;
        post_ += (post - post_) * k;
        left[i] = run(ch_[0], left[i], gain_, threshold_) * post_;
        right[i] = run(ch_[1], right[i], gain_, threshold_) * post_;
    }
}

}  // namespace daw::fx
