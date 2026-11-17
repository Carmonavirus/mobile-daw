// Reverb: puts the sound in a room. A short pre-delay, then a bank of feedback echoes that
// blur into a smooth tail (a Schroeder/Moorer design: parallel damped combs into series
// all-passes, slightly different on each side for width). Size sets how big the room is,
// Decay how long the tail lasts, High cut how dark it gets as it fades, Low cut keeps the
// tail from muddying the bass. Portable C++.
#pragma once

#include <array>
#include <vector>

namespace daw::fx {

// Parameter slots: 0 pre-delay (ms), 1 size (0..1), 2 decay (s), 3 high cut (Hz),
// 4 low cut (Hz), 5 width (0..1), 6 dry level (0..1), 7 wet level (0..1).
enum ReverbParam : int {
    kRevPreDelay = 0,
    kRevSize = 1,
    kRevDecay = 2,
    kRevHighCut = 3,
    kRevLowCut = 4,
    kRevWidth = 5,
    kRevDry = 6,
    kRevWet = 7,
    kRevParamCount = 8,
};

class Reverb {
public:
    static constexpr int kCombs = 8;
    static constexpr int kAllpasses = 4;
    static constexpr float kMaxRate = 96000.0f;
    static constexpr float kMaxPreDelayMs = 250.0f;

    Reverb();
    void prepare(float sampleRate);
    void reset();
    void process(float* left, float* right, int frames, const float* params);

private:
    struct Line {
        std::vector<float> buf;
        int write = 0;
        float store = 0.0f;  // comb damping state
        void clear();
        float tap(int delay) const;
        void push(float x);
    };

    // Tuning in samples at 44.1 kHz for size 1 (scaled for the sample rate and Size).
    static const std::array<int, kCombs> kCombTune;
    static const std::array<int, kAllpasses> kAllpassTune;
    static constexpr int kSpread = 23;

    Line comb_[2][kCombs];
    Line allpass_[2][kAllpasses];
    Line pre_;
    float lowL_ = 0.0f, lowR_ = 0.0f;  // low-cut state
    float size_ = 0.5f, dry_ = 1.0f, wet_ = 0.0f, width_ = 1.0f, preMs_ = 0.0f;
    bool primed_ = false;
    float sampleRate_ = 48000.0f;
};

}  // namespace daw::fx
