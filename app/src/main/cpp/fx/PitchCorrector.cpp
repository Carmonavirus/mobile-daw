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
    // 25 ms: room for a full two-period grain down to 80 Hz before it has to be played.
    latency_ = std::clamp(static_cast<int>(std::lround(0.025f * sampleRate_)), 256, 2048);
    maxHalf_ = static_cast<float>(latency_ - 2) * 0.5f;
    lp1_ = lp2_ = 0.0f;
    decimPhase_ = 0;
    ring_.fill(0.0f);
    ringWrite_ = 0;
    sinceHop_ = 0;
    level_ = 0.0f;
    voiced_ = false;
    inPeriod_ = outPeriod_ = 0.005f * sampleRate_;
    outMidi_ = 0.0f;
    targetNote_ = -1;
    shiftSemis_ = 0.0f;
    voicedTime_ = 0.0f;
    harmonyGain_ = 0.0f;
    input_.fill(0.0f);
    output_.fill(0.0f);
    now_ = 0;
    for (auto& v : voices_) v = Voice{};
    activeVoices_ = 1;
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

// The decimated estimate is only good to a sample or so at the full rate; grains need the
// period exactly or neighbouring grains drift out of phase. Search a few lags around it with a
// normalised correlation on the full-rate input.
float PitchCorrector::refinePeriod(float period) const {
    const int centre = static_cast<int>(std::lround(period));
    const int n = std::clamp(centre * 2, 256, 1024);
    constexpr int kSpan = 5;
    float corr[2 * kSpan + 1];
    int best = 0;
    for (int k = -kSpan; k <= kSpan; ++k) {
        const int lag = centre + k;
        double xy = 0.0, xx = 0.0, yy = 0.0;
        for (int i = 0; i < n; ++i) {
            const int64_t t = now_ - i;
            const float x = input_[static_cast<size_t>(t & kRingMask)];
            const float y = input_[static_cast<size_t>((t - lag) & kRingMask)];
            xy += double(x) * y;
            xx += double(x) * x;
            yy += double(y) * y;
        }
        corr[k + kSpan] = static_cast<float>(xy / std::sqrt(xx * yy + 1e-20));
        if (corr[k + kSpan] > corr[best + kSpan]) best = k;
    }
    if (best == -kSpan || best == kSpan) return period;  // no clear peak nearby: keep the estimate
    const float a = corr[best + kSpan - 1], b = corr[best + kSpan], c = corr[best + kSpan + 1];
    const float denom = a - 2.0f * b + c;
    const float offset = std::fabs(denom) > 1e-9f ? std::clamp(0.5f * (a - c) / denom, -0.5f, 0.5f) : 0.0f;
    return static_cast<float>(centre + best) + offset;
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

// The note `steps` scale degrees above `note` (2 = a third, 4 = a fifth). With (nearly) every
// note allowed there is no scale to follow, so it falls back to a major third / perfect fifth.
int PitchCorrector::scaleStep(int note, int steps, int mask) {
    mask &= 0xFFF;
    int count = 0;
    for (int i = 0; i < 12; ++i) count += (mask >> i) & 1;
    if (count < 5 || count > 9) return note + (steps == 2 ? 4 : steps == 4 ? 7 : 2 * steps);
    int n = note;
    for (int found = 0; found < steps && n < note + 12;) {
        ++n;
        if (mask >> (n % 12) & 1) ++found;
    }
    return n;
}

void PitchCorrector::analyse(const float* params) {
    const int range = std::clamp(static_cast<int>(std::lround(params[kPitchRange])), 0, 2);
    static constexpr float kMinHz[] = {60.0f, 80.0f, 140.0f};
    static constexpr float kMaxHz[] = {500.0f, 900.0f, 1300.0f};
    const float rate = sampleRate_ / kDecimate;
    const int maxTau = std::min(static_cast<int>(rate / kMinHz[range]), kAnalysisSize - kWindow - 2);
    const int minTau = std::max(2, static_cast<int>(rate / kMaxHz[range]));
    const float hopSeconds = static_cast<float>(kHop * kDecimate) / sampleRate_;
    const int mask = static_cast<int>(params[kPitchNoteMask]) & 0xFFF;

    float clarity = 0.0f;
    const float tau = level_ > 0.004f ? yin(minTau, maxTau, clarity) : 0.0f;
    const float transpose = std::clamp(params[kPitchTranspose], -12.0f, 12.0f);
    if (tau > 0.0f) {
        const float period = refinePeriod(tau * kDecimate);
        const float hz = sampleRate_ / period;
        const float midi = midiOf(hz);
        targetNote_ = nearestAllowed(midi, mask, targetNote_);
        voicedTime_ += hopSeconds;
        // Humanize: a held note keeps part of its natural drift once it has settled.
        const float sustain = std::clamp((voicedTime_ - 0.12f) / 0.3f, 0.0f, 1.0f);
        const float amount = std::clamp(params[kPitchAmount], 0.0f, 1.0f) *
                             (1.0f - 0.6f * std::clamp(params[kPitchHumanize], 0.0f, 1.0f) * sustain);
        const float desired = (static_cast<float>(targetNote_) - midi) * amount + transpose;
        const float retune = std::clamp(params[kPitchRetuneMs], 0.0f, 400.0f) / 1000.0f;
        const float k = retune < 0.0015f ? 1.0f : 1.0f - std::exp(-hopSeconds / retune);
        shiftSemis_ += (desired - shiftSemis_) * k;
        voiced_ = true;
        inPeriod_ = period;
        outMidi_ = midi + shiftSemis_;
        // The output pitch is set directly, so with an instant retune it sits exactly on the note
        // between analyses too: that flat, stepped line is the hard-tuned sound.
        outPeriod_ = std::clamp(sampleRate_ / hzOf(outMidi_), period * 0.45f, period * 2.2f);
        detectedHz_.store(hz, std::memory_order_relaxed);
        targetHz_.store(sampleRate_ / outPeriod_, std::memory_order_relaxed);
    } else {
        // Breaths and consonants pass through untouched: grains at a neutral 5 ms, no shift.
        voiced_ = false;
        targetNote_ = -1;
        voicedTime_ = 0.0f;
        inPeriod_ = outPeriod_ = 0.005f * sampleRate_;
        detectedHz_.store(0.0f, std::memory_order_relaxed);
        targetHz_.store(0.0f, std::memory_order_relaxed);
        shiftSemis_ += (transpose - shiftSemis_) * (1.0f - std::exp(-hopSeconds / 0.05f));
    }

    // Extra voices follow the lead, in the key.
    const int mode = std::clamp(static_cast<int>(std::lround(params[kPitchVoices])), 0, 4);
    const float level = std::clamp(params[kPitchVoicesLevel], 0.0f, 1.0f);
    harmonyGain_ += ((voiced_ ? 1.0f : 0.0f) - harmonyGain_) * (1.0f - std::exp(-hopSeconds / 0.02f));
    const int lead = targetNote_ >= 0 ? targetNote_ : 60;
    float semis[2] = {0.0f, 0.0f};
    float gains[2] = {0.0f, 0.0f};
    switch (mode) {
        case kVoicesDouble:
            semis[0] = 0.08f;
            semis[1] = -0.08f;
            gains[0] = gains[1] = 0.7f;
            break;
        case kVoicesOctaveDown:
            semis[0] = -12.0f;
            gains[0] = 0.9f;
            break;
        case kVoicesThird:
            semis[0] = static_cast<float>(scaleStep(lead, 2, mask) - lead);
            gains[0] = 0.8f;
            break;
        case kVoicesChord:
            semis[0] = static_cast<float>(scaleStep(lead, 2, mask) - lead);
            semis[1] = static_cast<float>(scaleStep(lead, 4, mask) - lead);
            gains[0] = gains[1] = 0.65f;
            break;
        default:
            break;
    }
    voices_[0].semis = 0.0f;
    voices_[0].gain = 1.0f;
    for (int i = 0; i < 2; ++i) {
        // Keep the old interval while unvoiced so a fading voice doesn't jump.
        if (voiced_ || gains[i] == 0.0f) voices_[static_cast<size_t>(i + 1)].semis = semis[i];
        voices_[static_cast<size_t>(i + 1)].gain = gains[i] * level * harmonyGain_;
    }
}

// Lays down every grain of one voice whose input is complete. A grain is two sung periods of
// input (Hann windowed) centred on an analysis mark, added to the output centred on a synthesis
// mark; synthesis marks are one corrected period apart. Analysis marks stay one sung period
// apart, so grains repeat (pitch up) or get skipped (pitch down) without breaking the waveform.
void PitchCorrector::placeGrains(Voice& v, float outPeriod, float gain) {
    const float half = std::min(inPeriod_, maxHalf_);
    const double now = static_cast<double>(now_);
    // After a long stretch with nothing placed (shouldn't happen), resync instead of catching up.
    if (v.synthMark < now - 2.0 * maxHalf_ - 2.0) v.synthMark = v.analysisMark = now - half;
    while (v.synthMark + half <= now) {
        while (v.analysisMark + inPeriod_ <= v.synthMark) v.analysisMark += inPeriod_;
        if (v.analysisMark > v.synthMark) v.analysisMark = v.synthMark;
        if (gain > 1e-4f) {
            // Grains overlap by 2*half/outPeriod on average; scale so they add up to unity. Keeping
            // the voice's tone puts raised notes lower on its (falling) spectrum, so they also
            // get back most of the level that costs.
            const float up = std::clamp(inPeriod_ / outPeriod, 1.0f, 2.0f);
            const float g = gain * std::min(outPeriod / half, 2.0f) * std::pow(up, 0.9f);
            const double s = v.synthMark;
            const int64_t first = static_cast<int64_t>(std::ceil(s - half));
            const int64_t last = static_cast<int64_t>(std::floor(s + half));
            const double shift = v.analysisMark - s;
            for (int64_t o = first; o <= last; ++o) {
                const double rel = static_cast<double>(o) - s;
                if (std::fabs(rel) >= half) continue;
                const float w = 0.5f + 0.5f * std::cos(kPi * static_cast<float>(rel) / half);
                const double src = static_cast<double>(o) + shift;
                const double fl = std::floor(src);
                const int64_t i0 = static_cast<int64_t>(fl);
                const float frac = static_cast<float>(src - fl);
                const float a = input_[static_cast<size_t>(i0 & kRingMask)];
                const float b = input_[static_cast<size_t>((i0 + 1) & kRingMask)];
                output_[static_cast<size_t>(o & kRingMask)] += (a + (b - a) * frac) * w * g;
            }
        }
        v.synthMark += outPeriod;
    }
}

void PitchCorrector::process(float* left, float* right, int frames, const float* params) {
    const float mix = std::clamp(params[kPitchMix], 0.0f, 1.0f);
    // Low-pass at about a quarter of the decimated rate before dropping samples.
    const float lpK = 1.0f - std::exp(-2.0f * kPi * (sampleRate_ / kDecimate * 0.22f) / sampleRate_);
    for (int i = 0; i < frames; ++i) {
        const float x = 0.5f * (left[i] + right[i]);
        input_[static_cast<size_t>(now_ & kRingMask)] = x;

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

        // ---- synthesis
        for (auto& v : voices_) {
            const float period = outPeriod_ / std::exp2(v.semis / 12.0f);
            placeGrains(v, std::max(period, 16.0f), v.gain);
        }
        const int64_t out = now_ - latency_;
        const size_t o = static_cast<size_t>(out & kRingMask);
        const float wet = output_[o];
        output_[o] = 0.0f;
        const float dry = input_[o];  // delayed by the same amount, so the mix doesn't comb
        ++now_;

        const float y = dry * (1.0f - mix) + wet * mix;
        left[i] = y;
        right[i] = y;
    }
}

}  // namespace daw::fx
