// Soft Clipper: drives the signal into a smooth curve that rounds off peaks instead of
// chopping them. Gain pushes the sound in before the curve, Threshold sets where the curve
// starts to bend (below it the sound is untouched; peaks never pass 0 dBFS), and Post sets
// the level that comes out. The curve runs at 4x the sample rate so loud drive doesn't fold
// harsh, out-of-tune overtones back down into the audible range. Portable C++.
#pragma once

#include <array>

namespace daw::fx {

// Parameter slots: 0 gain (dB), 1 threshold (dB, <= 0), 2 post (dB).
enum SoftClipParam : int { kClipGain = 0, kClipThreshold = 1, kClipPost = 2, kClipParamCount = 3 };

class SoftClipper {
public:
    static constexpr int kFactor = 4;     // oversampling
    static constexpr int kTaps = 96;      // anti-alias FIR length at the oversampled rate
    static constexpr int kPhaseTaps = kTaps / kFactor;

    SoftClipper();
    void prepare(float sampleRate);
    void reset();
    void process(float* left, float* right, int frames, const float* params);

    // The static curve: unity below `threshold`, then bends smoothly up to a ceiling of 1.
    static float shape(float x, float threshold);

private:
    struct Channel {
        std::array<float, kPhaseTaps * 2> up{};    // input history (doubled for wrap-free reads)
        std::array<float, kTaps * 2> down{};       // oversampled history
        int upPos = 0;
        int downPos = 0;
    };
    float run(Channel& ch, float x, float drive, float threshold);

    std::array<float, kTaps> fir_{};
    Channel ch_[2];
    // Smoothed parameters so knob moves don't click.
    float gain_ = 1.0f, threshold_ = 1.0f, post_ = 1.0f;
    bool primed_ = false;
    float sampleRate_ = 48000.0f;
};

}  // namespace daw::fx
