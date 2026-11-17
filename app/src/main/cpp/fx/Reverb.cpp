#include "Reverb.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

const std::array<int, Reverb::kCombs> Reverb::kCombTune = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
const std::array<int, Reverb::kAllpasses> Reverb::kAllpassTune = {556, 441, 341, 225};

namespace {
constexpr float kRefRate = 44100.0f;
constexpr float kMaxScale = 1.6f;  // biggest room, relative to the tuning above
constexpr float kMinScale = 0.25f;
constexpr float kPi = 3.14159265f;

int maxLen(int tune) { return static_cast<int>(tune * kMaxScale * (Reverb::kMaxRate / kRefRate)) + 64; }
float sizeScale(float size) { return kMinScale + (kMaxScale - kMinScale) * size; }
}  // namespace

void Reverb::Line::clear() {
    std::fill(buf.begin(), buf.end(), 0.0f);
    write = 0;
    store = 0.0f;
}

float Reverb::Line::tap(int delay) const {
    int i = write - delay;
    const int n = static_cast<int>(buf.size());
    if (i < 0) i += n;
    return buf[static_cast<size_t>(i)];
}

void Reverb::Line::push(float x) {
    buf[static_cast<size_t>(write)] = x;
    if (++write >= static_cast<int>(buf.size())) write = 0;
}

Reverb::Reverb() {
    for (int s = 0; s < 2; ++s) {
        for (int c = 0; c < kCombs; ++c) comb_[s][c].buf.assign(static_cast<size_t>(maxLen(kCombTune[c] + kSpread)), 0.0f);
        for (int a = 0; a < kAllpasses; ++a) allpass_[s][a].buf.assign(static_cast<size_t>(maxLen(kAllpassTune[a] + kSpread)), 0.0f);
    }
    pre_.buf.assign(static_cast<size_t>(kMaxPreDelayMs * 0.001f * kMaxRate) + 4, 0.0f);
}

void Reverb::prepare(float sampleRate) {
    if (sampleRate > 0.0f && sampleRate != sampleRate_) {
        sampleRate_ = sampleRate;
        reset();
    }
}

void Reverb::reset() {
    for (int s = 0; s < 2; ++s) {
        for (auto& c : comb_[s]) c.clear();
        for (auto& a : allpass_[s]) a.clear();
    }
    pre_.clear();
    lowL_ = lowR_ = 0.0f;
    primed_ = false;
}

void Reverb::process(float* left, float* right, int frames, const float* p) {
    const float sr = std::min(sampleRate_, kMaxRate);
    const float targetSize = std::clamp(p[kRevSize], 0.0f, 1.0f);
    const float decay = std::clamp(p[kRevDecay], 0.1f, 20.0f);
    const float highCut = std::clamp(p[kRevHighCut], 500.0f, 0.45f * sr);
    const float lowCut = std::clamp(p[kRevLowCut], 10.0f, 2000.0f);
    const float targetWidth = std::clamp(p[kRevWidth], 0.0f, 1.0f);
    const float targetDry = std::clamp(p[kRevDry], 0.0f, 1.0f);
    const float targetWet = std::clamp(p[kRevWet], 0.0f, 1.0f);
    const float targetPre = std::clamp(p[kRevPreDelay], 0.0f, kMaxPreDelayMs);

    if (!primed_) {
        size_ = targetSize;
        dry_ = targetDry;
        wet_ = targetWet;
        width_ = targetWidth;
        preMs_ = targetPre;
        primed_ = true;
    }
    // Size and pre-delay move per block (they change tap positions); levels ease per sample.
    size_ += (targetSize - size_) * 0.2f;
    preMs_ += (targetPre - preMs_) * 0.2f;
    const float scale = sizeScale(size_) * (sr / kRefRate);

    int combLen[2][kCombs];
    float combFb[2][kCombs];
    for (int s = 0; s < 2; ++s) {
        for (int c = 0; c < kCombs; ++c) {
            const int len = std::max(8, static_cast<int>((kCombTune[c] + s * kSpread) * scale));
            combLen[s][c] = std::min(len, static_cast<int>(comb_[s][c].buf.size()) - 1);
            // Each pass loses enough to fall 60 dB in `decay` seconds.
            combFb[s][c] = std::pow(10.0f, -3.0f * static_cast<float>(combLen[s][c]) / (decay * sr));
        }
    }
    int apLen[2][kAllpasses];
    for (int s = 0; s < 2; ++s)
        for (int a = 0; a < kAllpasses; ++a)
            apLen[s][a] = std::min(std::max(4, static_cast<int>((kAllpassTune[a] + s * kSpread) * scale)),
                                   static_cast<int>(allpass_[s][a].buf.size()) - 1);
    const int preLen = std::min(static_cast<int>(preMs_ * 0.001f * sr), static_cast<int>(pre_.buf.size()) - 1);

    const float damp = std::exp(-2.0f * kPi * highCut / sr);     // one-pole low-pass in each comb
    const float lowA = 1.0f - std::exp(-2.0f * kPi * lowCut / sr);  // high-pass on the wet signal
    const float ease = 1.0f - std::exp(-1.0f / (0.02f * sr));
    constexpr float kInGain = 0.03f;   // combs add up; keep the tail near the input's loudness
    constexpr float kApFb = 0.5f;

    for (int i = 0; i < frames; ++i) {
        dry_ += (targetDry - dry_) * ease;
        wet_ += (targetWet - wet_) * ease;
        width_ += (targetWidth - width_) * ease;

        const float inL = left[i], inR = right[i];
        pre_.push((inL + inR) * kInGain);
        const float x = preLen > 0 ? pre_.tap(preLen + 1) : (inL + inR) * kInGain;

        float out[2];
        for (int s = 0; s < 2; ++s) {
            float sum = 0.0f;
            for (int c = 0; c < kCombs; ++c) {
                Line& l = comb_[s][c];
                const float y = l.tap(combLen[s][c]);
                l.store = y * (1.0f - damp) + l.store * damp;
                if (std::fabs(l.store) < 1e-18f) l.store = 0.0f;
                l.push(x + l.store * combFb[s][c]);
                sum += y;
            }
            for (int a = 0; a < kAllpasses; ++a) {
                Line& l = allpass_[s][a];
                const float b = l.tap(apLen[s][a]);
                l.push(sum + b * kApFb);
                sum = b - sum;
            }
            out[s] = sum;
        }
        // Low cut on the tail only.
        lowL_ += (out[0] - lowL_) * lowA;
        lowR_ += (out[1] - lowR_) * lowA;
        if (std::fabs(lowL_) < 1e-18f) lowL_ = 0.0f;
        if (std::fabs(lowR_) < 1e-18f) lowR_ = 0.0f;
        const float wl = out[0] - lowL_, wr = out[1] - lowR_;
        // Width: 0 = both sides the same (mono tail), 1 = fully spread.
        const float w1 = 0.5f * (1.0f + width_), w2 = 0.5f * (1.0f - width_);
        left[i] = inL * dry_ + (wl * w1 + wr * w2) * wet_;
        right[i] = inR * dry_ + (wr * w1 + wl * w2) * wet_;
    }
}

}  // namespace daw::fx
