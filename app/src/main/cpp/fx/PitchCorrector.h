// Real-time pitch correction ("auto-tune" style) for a single voice.
// Detects the sung pitch with the YIN method on a 4x-decimated copy of the input, picks the
// nearest allowed note, glides towards it at the retune speed and shifts the voice with a
// two-tap delay-line pitch shifter whose window follows the detected period.
// Portable C++, no platform headers.
#pragma once

#include <array>
#include <atomic>
#include <vector>

namespace daw::fx {

enum PitchParam : int {
    kPitchNoteMask = 0,   // 12-bit mask of allowed pitch classes (bit 0 = C)
    kPitchRetuneMs = 1,   // 0 = instant (robotic), up to 400 ms (natural)
    kPitchAmount = 2,     // 0..1, how far towards the target note
    kPitchHumanize = 3,   // 0..1, lets long notes keep a little of their natural drift
    kPitchTranspose = 4,  // semitones, -12..12
    kPitchMix = 5,        // 0..1 dry/wet
    kPitchRange = 6,      // 0 low voice, 1 mid, 2 high: limits the detector's search
    kPitchParamCount = 7,
};

class PitchCorrector {
public:
    PitchCorrector();
    void prepare(float sampleRate);
    void reset();
    void process(float* left, float* right, int frames, const float* params);

    // Latest analysis, for the UI: detected frequency (Hz, 0 = no pitch) and the corrected one.
    float detectedHz() const { return detectedHz_.load(std::memory_order_relaxed); }
    float targetHz() const { return targetHz_.load(std::memory_order_relaxed); }

private:
    void analyse(const float* params);
    float yin(int minTau, int maxTau, float& clarity) const;
    static int nearestAllowed(float midi, int mask, int current);

    static constexpr int kDecimate = 4;
    static constexpr int kAnalysisSize = 1024;  // decimated samples (~85 ms at 48 kHz) in the ring
    static constexpr int kWindow = 384;         // YIN integration window, decimated samples
    static constexpr int kHop = 96;             // analyse every 96 decimated samples (8 ms)
    static constexpr int kDelaySize = 8192;     // shifter delay line, must be a power of two

    float sampleRate_ = 48000.0f;

    // Decimator: one-pole pair low-pass then keep every 4th sample.
    float lp1_ = 0.0f, lp2_ = 0.0f;
    int decimPhase_ = 0;
    std::array<float, kAnalysisSize> ring_{};
    int ringWrite_ = 0;
    int sinceHop_ = 0;
    mutable std::vector<float> diff_;
    float level_ = 0.0f;

    // Pitch state
    float periodSamples_ = 0.0f;  // detected period at the full rate, 0 when unvoiced
    int targetNote_ = -1;
    float shiftSemis_ = 0.0f;     // current correction in semitones
    float voicedFrames_ = 0.0f;   // how long the current note has been held

    // Shifter
    std::array<float, kDelaySize> delay_{};
    int delayWrite_ = 0;
    float tapPhase_ = 0.0f;
    float windowLen_ = 1200.0f;
    float pendingWindow_ = 1200.0f;
    float ratio_ = 1.0f;

    std::atomic<float> detectedHz_{0.0f};
    std::atomic<float> targetHz_{0.0f};
};

}  // namespace daw::fx
