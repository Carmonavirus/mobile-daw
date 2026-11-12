#include "ParametricEq.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

Biquad Biquad::design(BandType type, double freq, double gainDb, double q, double sampleRate) {
    Biquad f;
    freq = std::clamp(freq, 10.0, sampleRate * 0.49);
    q = std::clamp(q, 0.1, 30.0);
    const double w0 = 2.0 * kPi * freq / sampleRate;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double A = std::pow(10.0, gainDb / 40.0);
    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
        case BandType::Bell:
            b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
            a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
            break;
        case BandType::LowShelf: {
            const double s = 2 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) - (A - 1) * cw + s);
            b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) - (A - 1) * cw - s);
            a0 = (A + 1) + (A - 1) * cw + s;
            a1 = -2 * ((A - 1) + (A + 1) * cw);
            a2 = (A + 1) + (A - 1) * cw - s;
            break;
        }
        case BandType::HighShelf: {
            const double s = 2 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) + (A - 1) * cw + s);
            b1 = -2 * A * ((A - 1) + (A + 1) * cw);
            b2 = A * ((A + 1) + (A - 1) * cw - s);
            a0 = (A + 1) - (A - 1) * cw + s;
            a1 = 2 * ((A - 1) - (A + 1) * cw);
            a2 = (A + 1) - (A - 1) * cw - s;
            break;
        }
        case BandType::LowCut:
            b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
        case BandType::HighCut:
            b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
        case BandType::Notch:
            b0 = 1; b1 = -2 * cw; b2 = 1;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
    }
    f.b0 = b0 / a0; f.b1 = b1 / a0; f.b2 = b2 / a0; f.a1 = a1 / a0; f.a2 = a2 / a0;
    return f;
}

void ParametricEq::prepare(float sampleRate) {
    if (sampleRate == sampleRate_) return;
    sampleRate_ = sampleRate;
    for (auto& b : bands_) b.last[0] = -1.0f;  // force a redesign
}

void ParametricEq::reset() {
    for (auto& b : bands_)
        for (auto& s : b.stages) s.reset();
}

void ParametricEq::updateBand(int index, const float* p) {
    Band& band = bands_[static_cast<size_t>(index)];
    bool same = true;
    for (int i = 0; i < kEqBandParams; ++i) same &= band.last[static_cast<size_t>(i)] == p[i];
    if (same) return;
    const bool wasOn = band.stageCount > 0;
    std::copy(p, p + kEqBandParams, band.last.begin());
    if (p[0] < 0.5f) {
        band.stageCount = 0;
        return;
    }
    const auto type = static_cast<BandType>(std::clamp(static_cast<int>(std::lround(p[1])), 0, 5));
    const double freq = p[2], gain = std::clamp(p[3], -30.0f, 30.0f), q = p[4];
    int stages = 1;
    if (type == BandType::LowCut || type == BandType::HighCut)
        stages = std::clamp(static_cast<int>(std::lround(p[5])), 1, kMaxStages);
    for (int s = 0; s < stages; ++s) {
        // Extra cut stages are plain Butterworth so only the first carries the resonance.
        Biquad next = Biquad::design(type, freq, gain, s == 0 ? q : 0.7071, sampleRate_);
        auto& st = band.stages[static_cast<size_t>(s)];
        // Keep the filter state so dragging a band doesn't click.
        st.b0 = next.b0; st.b1 = next.b1; st.b2 = next.b2; st.a1 = next.a1; st.a2 = next.a2;
        if (!wasOn || s >= band.stageCount) st.reset();
    }
    band.stageCount = stages;
}

void ParametricEq::process(float* left, float* right, int frames, const float* params) {
    for (int b = 0; b < kEqBands; ++b) updateBand(b, params + b * kEqBandParams);
    if (params[kEqOutputParam] != lastOutput_) {
        lastOutput_ = params[kEqOutputParam];
        outputGain_ = std::pow(10.0f, std::clamp(lastOutput_, -24.0f, 24.0f) / 20.0f);
    }
    for (auto& band : bands_) {
        for (int s = 0; s < band.stageCount; ++s) {
            auto& st = band.stages[static_cast<size_t>(s)];
            for (int i = 0; i < frames; ++i) {
                left[i] = st.process(left[i], 0);
                right[i] = st.process(right[i], 1);
            }
        }
    }
    if (outputGain_ != 1.0f) {
        for (int i = 0; i < frames; ++i) {
            left[i] *= outputGain_;
            right[i] *= outputGain_;
        }
    }
}

}  // namespace daw::fx
