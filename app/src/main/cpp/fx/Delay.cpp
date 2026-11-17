#include "Delay.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

Delay::Delay() {
    size_ = static_cast<int>(kMaxSeconds * kMaxRate) + 4;
    bufL_.assign(static_cast<size_t>(size_), 0.0f);
    bufR_.assign(static_cast<size_t>(size_), 0.0f);
}

void Delay::prepare(float sampleRate) {
    if (sampleRate > 0.0f && sampleRate != sampleRate_) {
        sampleRate_ = sampleRate;
        reset();
    }
}

void Delay::reset() {
    // Only the part this sample rate can reach ever gets read.
    const size_t used = std::min(static_cast<size_t>(size_),
                                 static_cast<size_t>(kMaxSeconds * std::min(sampleRate_, kMaxRate)) + 4);
    std::fill(bufL_.begin(), bufL_.begin() + static_cast<long>(used), 0.0f);
    std::fill(bufR_.begin(), bufR_.begin() + static_cast<long>(used), 0.0f);
    write_ = 0;
    toneL_ = toneR_ = 0.0f;
    primed_ = false;
}

// Linear interpolation `delaySamples` behind the write head.
float Delay::read(const std::vector<float>& buf, float delaySamples) const {
    float pos = static_cast<float>(write_) - delaySamples;
    while (pos < 0.0f) pos += static_cast<float>(size_);
    const int i = static_cast<int>(pos);
    const float frac = pos - static_cast<float>(i);
    const int j = i + 1 >= size_ ? 0 : i + 1;
    return buf[static_cast<size_t>(i)] + (buf[static_cast<size_t>(j)] - buf[static_cast<size_t>(i)]) * frac;
}

void Delay::process(float* left, float* right, int frames, const float* p) {
    const float sr = sampleRate_;
    const float maxDelay = std::min(kMaxSeconds * std::min(sr, kMaxRate), static_cast<float>(size_ - 4));
    const float baseT = std::clamp(p[kDelayTime], 0.001f, kMaxSeconds) * sr;
    const float offT = std::clamp(p[kDelayOffset], -0.05f, 0.05f) * sr;
    const float targetL = std::clamp(baseT, 1.0f, maxDelay);
    const float targetR = std::clamp(baseT + offT, 1.0f, maxDelay);
    const float targetFb = std::clamp(p[kDelayFeedback], 0.0f, 0.95f);
    const float targetDry = std::clamp(p[kDelayDry], 0.0f, 1.0f);
    const float targetWet = std::clamp(p[kDelayWet], 0.0f, 1.0f);
    const bool pingPong = static_cast<int>(p[kDelayMode] + 0.5f) == kDelayPingPong;
    const float toneHz = std::clamp(p[kDelayTone], 200.0f, 0.45f * sr);
    const float toneA = 1.0f - std::exp(-2.0f * 3.14159265f * toneHz / sr);

    if (!primed_) {
        timeL_ = targetL;
        timeR_ = targetR;
        fb_ = targetFb;
        dry_ = targetDry;
        wet_ = targetWet;
        primed_ = true;
    }
    // About 60 ms to glide to a new time; levels a little faster.
    const float glide = 1.0f - std::exp(-1.0f / (0.06f * sr));
    const float ease = 1.0f - std::exp(-1.0f / (0.02f * sr));

    for (int i = 0; i < frames; ++i) {
        timeL_ += (targetL - timeL_) * glide;
        timeR_ += (targetR - timeR_) * glide;
        fb_ += (targetFb - fb_) * ease;
        dry_ += (targetDry - dry_) * ease;
        wet_ += (targetWet - wet_) * ease;

        const float inL = left[i], inR = right[i];
        float echoL = read(bufL_, timeL_);
        float echoR = read(bufR_, timeR_);
        // Darken each repeat.
        toneL_ += (echoL - toneL_) * toneA;
        toneR_ += (echoR - toneR_) * toneA;
        if (std::fabs(toneL_) < 1e-18f) toneL_ = 0.0f;
        if (std::fabs(toneR_) < 1e-18f) toneR_ = 0.0f;
        echoL = toneL_;
        echoR = toneR_;

        float wl, wr;
        if (pingPong) {
            // Both inputs start on the left; each repeat crosses to the other side.
            wl = 0.5f * (inL + inR) + echoR * fb_;
            wr = echoL * fb_;
        } else {
            wl = inL + echoL * fb_;
            wr = inR + echoR * fb_;
        }
        // Keep runaway feedback (with loud input) from blowing up.
        bufL_[static_cast<size_t>(write_)] = std::clamp(wl, -4.0f, 4.0f);
        bufR_[static_cast<size_t>(write_)] = std::clamp(wr, -4.0f, 4.0f);
        if (++write_ >= size_) write_ = 0;

        left[i] = inL * dry_ + echoL * wet_;
        right[i] = inR * dry_ + echoR * wet_;
    }
}

}  // namespace daw::fx
