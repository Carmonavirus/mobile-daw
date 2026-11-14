// Parametric EQ: up to eight bands of RBJ-cookbook biquads (bell, shelves, cuts, notch).
// Portable C++, no platform headers. The UI draws the same curve from app/.../fx/EqMath.kt,
// so keep the band types and formulas here and there in step.
#pragma once

#include <array>

namespace daw::fx {

enum class BandType : int { Bell = 0, LowShelf = 1, HighShelf = 2, LowCut = 3, HighCut = 4, Notch = 5 };

constexpr int kEqBands = 8;
// Parameters per band: on (0/1), type, frequency (Hz), gain (dB), Q, slope (1 = 12 dB/oct, 2 = 24, 4 = 48).
constexpr int kEqBandParams = 6;
// Parameter layout: band b's values start at b * kEqBandParams; the output gain (dB) comes last.
constexpr int kEqOutputParam = kEqBands * kEqBandParams;
constexpr int kEqParamCount = kEqOutputParam + 1;

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    // Transposed direct form II state, one pair per stereo side.
    double z1[2] = {0, 0}, z2[2] = {0, 0};

    inline float process(float x, int ch) {
        const double y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return static_cast<float>(y);
    }
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
    // Sets coefficients for one stage. Returns false for a band that does nothing.
    static Biquad design(BandType type, double freq, double gainDb, double q, double sampleRate);
};

class ParametricEq {
public:
    void prepare(float sampleRate);
    void reset();
    // params: kEqParamCount values (see layout above). Coefficients update only when they change.
    void process(float* left, float* right, int frames, const float* params);

private:
    static constexpr int kMaxStages = 4;  // a 48 dB/oct cut is four cascaded 12 dB stages
    struct Band {
        std::array<Biquad, kMaxStages> stages;
        int stageCount = 0;
        std::array<float, kEqBandParams> last{};
    };
    void updateBand(int index, const float* p);

    float sampleRate_ = 48000.0f;
    std::array<Band, kEqBands> bands_{};
    float lastOutput_ = 0.0f;
    float outputGain_ = 1.0f;
};

}  // namespace daw::fx
