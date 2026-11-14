// Key Detector: listens to the sound going through a mixer track and works out which key it
// is in (e.g. "A minor"), live. The sound passes through untouched.
//
// How: the track is mixed to mono, filtered and slowed down to ~6 kHz, and every ~170 ms the
// newest ~0.7 s is turned into a spectrum. Spectral peaks between 55 Hz and 2 kHz are folded
// onto the 12 note names (a "chroma"), weighted by how loud they are and how close they sit to
// a real note. Chromas pile up over the chosen memory (older ones fade), and the total is
// compared with a profile of how often each note is used in every major and minor key
// (Krumhansl-Kessler). The best-matching key wins; how far it beats the runner-up gives the
// confidence. Silence adds nothing, so the result holds while the song is stopped. Portable C++.
#pragma once

#include <array>
#include <atomic>

namespace daw::fx {

// Parameter slots: 0 "start over" counter (any change clears what was heard),
// 1 memory in seconds (0 = keep everything since the last start over).
enum KeyParam : int { kKeyReset = 0, kKeyMemory = 1, kKeyParamCount = 2 };

// Readout layout (see KeyDetector::read).
enum KeyReadout : int {
    kKeyOutKey = 0,         // best key: 0..11 = C..B major, 12..23 = C..B minor, -1 = not enough heard
    kKeyOutConfidence = 1,  // 0..1
    kKeyOutSecond = 2,      // runner-up key, same numbering
    kKeyOutBestScore = 3,   // correlation of the best key with its profile (-1..1)
    kKeyOutSecondScore = 4, // correlation of the runner-up
    kKeyOutHeard = 5,       // seconds of sound behind the result (after fading)
    kKeyOutChroma = 6,      // 12 values, C..B, loudest = 1
    kKeyOutCount = kKeyOutChroma + 12,
};

class KeyDetector {
public:
    static constexpr int kFft = 4096;          // decimated samples per spectrum (~0.7 s)
    static constexpr int kHop = 1024;          // analyse every 1024 decimated samples (~170 ms)
    static constexpr int kStages = 4;          // anti-alias biquads before decimating

    KeyDetector();
    void prepare(float sampleRate);
    void reset();
    // Listens only; the buffers are not changed.
    void process(const float* left, const float* right, int frames, const float* params);
    // Fills kKeyOutCount floats. Safe to call from any thread.
    void read(float* out) const;

    // Correlation of a 12-bin chroma with all 24 key profiles (0..11 major, 12..23 minor).
    static void scoreKeys(const double* chroma, double* scores);

private:
    struct Biquad {
        float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        float z1 = 0, z2 = 0;
        float run(float x) {
            const float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    };

    void analyse();
    void clearHeard();
    void publish();

    float sampleRate_ = 0.0f;
    int decimate_ = 8;
    float rate_ = 6000.0f;  // decimated sample rate
    int phase_ = 0;
    std::array<Biquad, kStages> filters_{};

    std::array<float, kFft> ring_{};
    int write_ = 0;
    int sinceHop_ = 0;
    double hopEnergy_ = 0.0;
    std::array<float, kFft> re_{};
    std::array<float, kFft> im_{};

    std::array<double, 12> heard_{};
    double heardSeconds_ = 0.0;
    float lastReset_ = 0.0f;
    float memory_ = 0.0f;
    bool primed_ = false;

    std::array<std::atomic<float>, kKeyOutCount> out_{};
};

}  // namespace daw::fx
