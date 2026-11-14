// Real-time pitch correction ("auto-tune" style) for a single voice.
//
// Detection: YIN on a 4x-decimated copy of the input, refined at the full rate, every 4 ms.
// Correction: picks the nearest allowed note and glides towards it at the retune speed. At 0 ms
// the output sits exactly on the note (the hard-tuned sound) and jumps between notes.
// Shifting: pitch-synchronous overlap-add (PSOLA). Two-period grains are cut at the sung period
// and laid down again at the corrected period, so the pitch moves but the voice keeps its own
// tone (formants) instead of sounding sped up. Extra harmony voices reuse the same grains.
// Portable C++, no platform headers.
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <vector>

namespace daw::fx {

enum PitchParam : int {
    kPitchNoteMask = 0,     // 12-bit mask of allowed pitch classes (bit 0 = C)
    kPitchRetuneMs = 1,     // 0 = instant (hard-tuned), up to 400 ms (natural)
    kPitchAmount = 2,       // 0..1, how far towards the target note
    kPitchHumanize = 3,     // 0..1, lets long notes keep a little of their natural drift
    kPitchTranspose = 4,    // semitones, -12..12
    kPitchMix = 5,          // 0..1 dry/wet
    kPitchRange = 6,        // 0 low voice, 1 mid, 2 high: limits the detector's search
    kPitchVoices = 7,       // PitchVoices: extra voices sung along with the corrected one
    kPitchVoicesLevel = 8,  // 0..1, level of the extra voices
    kPitchParamCount = 9,
};

enum PitchVoices : int {
    kVoicesOff = 0,
    kVoicesDouble = 1,      // two copies a few cents either side: a thick, double-tracked lead
    kVoicesOctaveDown = 2,  // the same melody an octave lower
    kVoicesThird = 3,       // a third above, in the key
    kVoicesChord = 4,       // a third and a fifth above, in the key
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
    // Fixed processing delay, in samples (the wet and dry paths are both delayed by it).
    int latency() const { return latency_; }

private:
    struct Voice {
        double synthMark = 0.0;     // next grain centre, in output time
        double analysisMark = 0.0;  // grain centre in the input, kept one sung period apart
        float semis = 0.0f;         // offset from the lead voice
        float gain = 0.0f;
    };

    void analyse(const float* params);
    float yin(int minTau, int maxTau, float& clarity) const;
    float refinePeriod(float period) const;
    static int nearestAllowed(float midi, int mask, int current);
    static int scaleStep(int note, int steps, int mask);
    void placeGrains(Voice& v, float outPeriod, float gain);

    static constexpr int kDecimate = 4;
    static constexpr int kAnalysisSize = 1024;  // decimated samples (~85 ms at 48 kHz) in the ring
    static constexpr int kWindow = 384;         // YIN integration window, decimated samples
    static constexpr int kHop = 48;             // analyse every 48 decimated samples (4 ms)
    static constexpr int kRingSize = 8192;      // full-rate input / output rings, power of two
    static constexpr int kRingMask = kRingSize - 1;
    static constexpr int kMaxVoices = 3;        // lead + up to two harmony voices

    float sampleRate_ = 48000.0f;
    int latency_ = 1200;
    float maxHalf_ = 600.0f;  // longest half-grain that still fits the latency

    // Decimator: one-pole pair low-pass then keep every 4th sample.
    float lp1_ = 0.0f, lp2_ = 0.0f;
    int decimPhase_ = 0;
    std::array<float, kAnalysisSize> ring_{};
    int ringWrite_ = 0;
    int sinceHop_ = 0;
    mutable std::vector<float> diff_;
    float level_ = 0.0f;

    // Pitch state
    bool voiced_ = false;
    float inPeriod_ = 240.0f;     // sung period at the full rate (a neutral 5 ms when unvoiced)
    float outPeriod_ = 240.0f;    // corrected period of the lead voice
    float outMidi_ = 0.0f;
    int targetNote_ = -1;
    float shiftSemis_ = 0.0f;     // current correction in semitones
    float voicedTime_ = 0.0f;     // how long the current note has been held
    float harmonyGain_ = 0.0f;    // fades harmony voices in and out with the voice

    // PSOLA
    std::array<float, kRingSize> input_{};
    std::array<float, kRingSize> output_{};
    int64_t now_ = 0;  // input samples written so far
    std::array<Voice, kMaxVoices> voices_{};
    int activeVoices_ = 1;

    std::atomic<float> detectedHz_{0.0f};
    std::atomic<float> targetHz_{0.0f};
};

}  // namespace daw::fx
