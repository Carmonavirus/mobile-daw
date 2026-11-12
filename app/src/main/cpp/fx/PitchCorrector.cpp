#include "PitchCorrector.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

namespace {
constexpr float kPi = 3.14159265358979f;

float midiOf(float hz) { return 69.0f + 12.0f * std::log2(hz / 440.0f); }
float hzOf(float midi) { return 440.0f * std::exp2((midi - 69.0f) / 12.0f); }
}  // namespace

PitchCorrector::PitchCorrector() { diff_.resize(kAnalysisSize, 0.0f); }

void PitchCorrector::prepare(float sampleRate) {
    if (sampleRate == sampleRate_) return;
    sampleRate_ = sampleRate;
    reset();
}

void PitchCorrector::reset() {
    lp1_ = lp2_ = 0.0f;
    decimPhase_ = 0;
    ring_.fill(0.0f);
    ringWrite_ = 0;
    sinceHop_ = 0;
    level_ = 0.0f;
    periodSamples_ = 0.0f;
    targetNote_ = -1;
    shiftSemis_ = 0.0f;
    voicedFrames_ = 0.0f;
    delay_.fill(0.0f);
    delayWrite_ = 0;
    tapPhase_ = 0.0f;
    windowLen_ = pendingWindow_ = 0.025f * sampleRate_;
    ratio_ = 1.0f;
    detectedHz_.store(0.0f);
    targetHz_.store(0.0f);
}

// Classic YIN: cumulative-mean-normalised difference, first dip under the threshold,
// parabolic interpolation. Works on the newest kWindow + maxTau decimated samples.
float PitchCorrector::yin(int minTau, int maxTau, float& clarity) const {
    const int span = kWindow + maxTau;
    const int start = (ringWrite_ - span + kAnalysisSize * 4) % kAnalysisSize;
    auto at = [&](int i) { return ring_[static_cast<size_t>((start + i) % kAnalysisSize)]; };
    float running = 0.0f;
    diff_[0] = 1.0f;
    for (int tau = 1; tau <= maxTau; ++tau) {
        float d = 0.0f;
        for (int i = 0; i < kWindow; ++i) {
            const float delta = at(i) - at(i + tau);
            d += delta * delta;
        }
        running += d;
        diff_[static_cast<size_t>(tau)] = running > 0.0f ? d * tau / running : 1.0f;
    }
    int best = -1;
    for (int tau = std::max(minTau, 2); tau < maxTau; ++tau) {
        if (diff_[static_cast<size_t>(tau)] < 0.15f) {
            while (tau + 1 < maxTau && diff_[static_cast<size_t>(tau + 1)] < diff_[static_cast<size_t>(tau)]) ++tau;
            best = tau;
            break;
        }
    }
    if (best < 0) {
        clarity = 0.0f;
        return 0.0f;
    }
    clarity = 1.0f - diff_[static_cast<size_t>(best)];
    const float a = diff_[static_cast<size_t>(best - 1)], b = diff_[static_cast<size_t>(best)], c = diff_[static_cast<size_t>(best + 1)];
    const float denom = a - 2.0f * b + c;
    const float offset = std::fabs(denom) > 1e-9f ? std::clamp(0.5f * (a - c) / denom, -0.5f, 0.5f) : 0.0f;
    return static_cast<float>(best) + offset;
}

int PitchCorrector::nearestAllowed(float midi, int mask, int current) {
    mask &= 0xFFF;
    if (mask == 0) mask = 0xFFF;
    // Stick with the current note until the voice is clearly closer to another one.
    if (current >= 0 && (mask >> (current % 12) & 1) && std::fabs(midi - static_cast<float>(current)) < 0.62f) return current;
    const int base = static_cast<int>(std::lround(midi));
    int best = base;
    float bestDist = 1e9f;
    for (int n = base - 6; n <= base + 6; ++n) {
        if (n < 0 || !(mask >> (n % 12) & 1)) continue;
        const float d = std::fabs(midi - static_cast<float>(n));
        if (d < bestDist) {
            bestDist = d;
            best = n;
        }
    }
    return best;
}

void PitchCorrector::analyse(const float* params) {
    const int range = std::clamp(static_cast<int>(std::lround(params[kPitchRange])), 0, 2);
    static constexpr float kMinHz[] = {60.0f, 80.0f, 140.0f};
    static constexpr float kMaxHz[] = {500.0f, 900.0f, 1300.0f};
    const float rate = sampleRate_ / kDecimate;
    const int maxTau = std::min(static_cast<int>(rate / kMinHz[range]), kAnalysisSize - kWindow - 2);
    const int minTau = std::max(2, static_cast<int>(rate / kMaxHz[range]));
    const float hopSeconds = static_cast<float>(kHop * kDecimate) / sampleRate_;

    float clarity = 0.0f;
    const float tau = level_ > 0.004f ? yin(minTau, maxTau, clarity) : 0.0f;
    const float transpose = std::clamp(params[kPitchTranspose], -12.0f, 12.0f);
    float desired = transpose;
    if (tau > 0.0f) {
        const float hz = rate / tau;
        periodSamples_ = tau * kDecimate;
        const float midi = midiOf(hz);
        targetNote_ = nearestAllowed(midi, static_cast<int>(params[kPitchNoteMask]), targetNote_);
        voicedFrames_ += hopSeconds;
        // Humanize: a held note keeps part of its natural drift once it has settled.
        const float sustain = std::clamp((voicedFrames_ - 0.12f) / 0.3f, 0.0f, 1.0f);
        const float amount = std::clamp(params[kPitchAmount], 0.0f, 1.0f) *
                             (1.0f - 0.6f * std::clamp(params[kPitchHumanize], 0.0f, 1.0f) * sustain);
        desired = (static_cast<float>(targetNote_) - midi) * amount + transpose;
        detectedHz_.store(hz, std::memory_order_relaxed);
        targetHz_.store(hzOf(midi + desired), std::memory_order_relaxed);
        const float retune = std::clamp(params[kPitchRetuneMs], 0.0f, 400.0f) / 1000.0f;
        const float k = retune < 0.0015f ? 1.0f : 1.0f - std::exp(-hopSeconds / retune);
        shiftSemis_ += (desired - shiftSemis_) * k;
        // Shifter window: an even number of periods (at least 12 ms), so the two taps, half a
        // window apart, stay in phase on a steady voice instead of comb-filtering it.
        const float minWindow = 0.012f * sampleRate_;
        const float pairs = std::ceil(minWindow / (2.0f * periodSamples_));
        pendingWindow_ = std::min(2.0f * periodSamples_ * pairs, 0.045f * sampleRate_);
    } else {
        periodSamples_ = 0.0f;
        targetNote_ = -1;
        voicedFrames_ = 0.0f;
        detectedHz_.store(0.0f, std::memory_order_relaxed);
        targetHz_.store(0.0f, std::memory_order_relaxed);
        shiftSemis_ += (desired - shiftSemis_) * (1.0f - std::exp(-hopSeconds / 0.05f));
    }
}

void PitchCorrector::process(float* left, float* right, int frames, const float* params) {
    const float mix = std::clamp(params[kPitchMix], 0.0f, 1.0f);
    // Low-pass at about a quarter of the decimated rate before dropping samples.
    const float lpK = 1.0f - std::exp(-2.0f * kPi * (sampleRate_ / kDecimate * 0.22f) / sampleRate_);
    const float ratioSmooth = 1.0f - std::exp(-1.0f / (0.002f * sampleRate_));
    for (int i = 0; i < frames; ++i) {
        const float x = 0.5f * (left[i] + right[i]);

        // ---- analysis
        lp1_ += (x - lp1_) * lpK;
        lp2_ += (lp1_ - lp2_) * lpK;
        if (++decimPhase_ >= kDecimate) {
            decimPhase_ = 0;
            ring_[static_cast<size_t>(ringWrite_)] = lp2_;
            ringWrite_ = (ringWrite_ + 1) % kAnalysisSize;
            level_ += (std::fabs(lp2_) - level_) * 0.01f;
            if (++sinceHop_ >= kHop) {
                sinceHop_ = 0;
                analyse(params);
            }
        }

        // ---- shifting: two taps sweep through a window of the delay line, half a window apart,
        // each faded with sin^2 so their gains always add up to one.
        const float targetRatio = std::exp2(shiftSemis_ / 12.0f);
        ratio_ += (targetRatio - ratio_) * ratioSmooth;
        windowLen_ += (pendingWindow_ - windowLen_) * 0.002f;
        delay_[static_cast<size_t>(delayWrite_)] = x;
        float wet = 0.0f;
        for (int tap = 0; tap < 2; ++tap) {
            float phase = tapPhase_ + 0.5f * static_cast<float>(tap);
            if (phase >= 1.0f) phase -= 1.0f;
            const float d = 1.0f + phase * windowLen_;
            float pos = static_cast<float>(delayWrite_) - d;
            if (pos < 0.0f) pos += kDelaySize;
            const int i0 = static_cast<int>(pos);
            const float frac = pos - static_cast<float>(i0);
            const float a = delay_[static_cast<size_t>(i0 & (kDelaySize - 1))];
            const float b = delay_[static_cast<size_t>((i0 + 1) & (kDelaySize - 1))];
            const float s = std::sin(kPi * phase);
            wet += (a + (b - a) * frac) * s * s;
        }
        delayWrite_ = (delayWrite_ + 1) & (kDelaySize - 1);
        tapPhase_ += (1.0f - ratio_) / windowLen_;
        tapPhase_ -= std::floor(tapPhase_);

        left[i] = left[i] * (1.0f - mix) + wet * mix;
        right[i] = right[i] * (1.0f - mix) + wet * mix;
    }
}

}  // namespace daw::fx
