// Built-in instruments. Every sound is synthesised, so the app ships without sample files.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace daw {

enum Instrument : int {
    kKick = 0,
    kSnare = 1,
    kHiHat = 2,
    kClap = 3,
    kBass = 4,
    kLead = 5,
    kPluck = 6,
    kPad = 7,
    kSample = 8,  // plays an imported audio file, rendered by Core
    // 9-12 are reserved for the sound-source instruments.
    kWavetable = 13,  // two-oscillator wavetable synth (wavesynth/), rendered by Core in stereo
    kInstrumentCount = 14,
};

inline bool isDrum(int instrument) { return instrument <= kClap || instrument == kSample; }

struct Voice {
    bool active = false;
    int pitch = 60;
    float velocity = 1.0f;
    double phase1 = 0.0;
    double phase2 = 0.0;
    double phase3 = 0.0;
    float env = 0.0f;           // amplitude envelope value
    int envStage = 0;           // 0 attack, 1 decay/sustain, 2 release
    int64_t age = 0;            // samples since trigger
    int64_t gate = 0;           // samples until release
    float filterLow = 0.0f;     // SVF state
    float filterBand = 0.0f;
    float hpState = 0.0f;       // noise high-pass memory
    float lastNoise = 0.0f;
    uint32_t noiseSeed = 22222;
    float fade = 1.0f;      // choke fade-out multiplier ("cut itself")
    float fadeStep = 0.0f;  // > 0 while the voice is being choked
    // Repitched noise drums (hat, clap): their sound is rendered at its own clock, which runs
    // faster or slower than the output like a sample played back at another pitch.
    double srcPos = 0.0;    // fraction of the way from srcPrev to srcCur
    int64_t srcAge = 0;     // source samples rendered so far
    float srcPrev = 0.0f;
    float srcCur = 0.0f;
};

class Synth {
public:
    static void start(Voice& v, int pitch, float velocity, int64_t gateSamples) {
        v.active = true;
        v.pitch = pitch;
        v.velocity = velocity;
        v.phase1 = v.phase2 = v.phase3 = 0.0;
        v.env = 0.0f;
        v.envStage = 0;
        v.age = 0;
        v.gate = gateSamples;
        v.filterLow = v.filterBand = 0.0f;
        v.hpState = v.lastNoise = 0.0f;
        v.noiseSeed = 22222u + static_cast<uint32_t>(pitch) * 7919u;
        v.fade = 1.0f;
        v.fadeStep = 0.0f;
        v.srcPos = 0.0;
        v.srcAge = 0;
        v.srcPrev = v.srcCur = 0.0f;
    }

    // Renders one hit of a built-in drum (kick/snare/hat/clap) at C4, full velocity, until it has
    // died away (at most maxSeconds), with the silent tail cut off. The app plays built-in drums
    // from this recording through the sampler, so their channels get trim, pitch, reverse, boost
    // and the envelope like any imported sound.
    static std::vector<float> renderOneShot(int instrument, float sampleRate, float maxSeconds = 3.0f) {
        std::vector<float> out;
        if (instrument < kKick || instrument > kClap || sampleRate <= 0.0f) return out;
        Voice v;
        start(v, 60, 1.0f, 0);
        const auto limit = static_cast<size_t>(maxSeconds * sampleRate);
        out.reserve(static_cast<size_t>(sampleRate));
        while (v.active && out.size() < limit) out.push_back(tick(v, instrument, sampleRate));
        // Hat and clap come out of the resampler one sample late; drop that leading zero.
        if (!out.empty() && out.front() == 0.0f && (instrument == kHiHat || instrument == kClap)) out.erase(out.begin());
        size_t end = out.size();
        while (end > 0 && std::fabs(out[end - 1]) < 1e-4f) --end;
        // Keep a couple of milliseconds after the last audible sample.
        end = std::min(out.size(), end + static_cast<size_t>(0.002f * sampleRate));
        out.resize(std::max<size_t>(end, 2));
        return out;
    }

    static void release(Voice& v) {
        if (v.active && v.envStage < 2) v.envStage = 2;
    }

    // Renders one mono sample for this voice and advances its state.
    static float tick(Voice& v, int instrument, float sampleRate) {
        if (!v.active) return 0.0f;
        const float t = static_cast<float>(v.age) / sampleRate;
        float out = 0.0f;
        switch (instrument) {
            case kKick: out = kick(v, t, sampleRate); break;
            case kSnare: out = snare(v, t, sampleRate); break;
            // Every semitone away from C4 (60) repitches the hit like a sample: G3 is an octave under G4.
            case kHiHat: out = resampled(v, sampleRate, [&v](float st) { return hat(v, st); }); break;
            case kClap: out = resampled(v, sampleRate, [&v](float st) { return clap(v, st); }); break;
            case kBass: out = bass(v, sampleRate); break;
            case kLead: out = lead(v, sampleRate); break;
            case kPluck: out = pluck(v, t, sampleRate); break;
            case kPad: out = pad(v, sampleRate); break;
            default: break;
        }
        v.age++;
        if (v.gate > 0) {
            v.gate--;
            if (v.gate == 0) release(v);
        }
        return out * v.velocity;
    }

private:
    static constexpr double kTwoPi = 6.283185307179586;

    static float midiToHz(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }

    static float noise(Voice& v) {
        v.noiseSeed = v.noiseSeed * 1664525u + 1013904223u;
        return static_cast<float>(v.noiseSeed >> 8) / 8388608.0f - 1.0f;
    }

    // First-difference high-pass, good enough for hats and snare sizzle.
    static float brightNoise(Voice& v) {
        const float n = noise(v);
        const float hp = n - v.lastNoise;
        v.lastNoise = n;
        return hp * 0.6f;
    }

    static float polyBlep(double t, double dt) {
        if (t < dt) { t /= dt; return static_cast<float>(t + t - t * t - 1.0); }
        if (t > 1.0 - dt) { t = (t - 1.0) / dt; return static_cast<float>(t * t + t + t + 1.0); }
        return 0.0f;
    }

    static float saw(double& phase, double inc) {
        float s = static_cast<float>(2.0 * phase - 1.0) - polyBlep(phase, inc);
        phase += inc;
        if (phase >= 1.0) phase -= 1.0;
        return s;
    }

    static float square(double& phase, double inc) {
        float s = phase < 0.5 ? 1.0f : -1.0f;
        s += polyBlep(phase, inc);
        double p2 = phase + 0.5;
        if (p2 >= 1.0) p2 -= 1.0;
        s -= polyBlep(p2, inc);
        phase += inc;
        if (phase >= 1.0) phase -= 1.0;
        return s;
    }

    // Chamberlin state-variable low-pass.
    static float lowpass(Voice& v, float in, float cutoffHz, float sampleRate, float q = 0.7f) {
        float f = 2.0f * std::sin(3.14159265f * std::fmin(cutoffHz, sampleRate * 0.2f) / sampleRate);
        float damp = 1.0f / q;
        v.filterLow += f * v.filterBand;
        float high = in - v.filterLow - damp * v.filterBand;
        v.filterBand += f * high;
        return v.filterLow;
    }

    // Linear-attack, exponential decay/release ADSR. Deactivates the voice when silent.
    static float adsr(Voice& v, float sampleRate, float attack, float decay, float sustain, float releaseTime) {
        switch (v.envStage) {
            case 0:
                v.env += 1.0f / std::fmax(attack * sampleRate, 1.0f);
                if (v.env >= 1.0f) { v.env = 1.0f; v.envStage = 1; }
                break;
            case 1: {
                float k = 1.0f - std::exp(-1.0f / std::fmax(decay * sampleRate, 1.0f));
                v.env += (sustain - v.env) * k;
                if (sustain <= 0.0005f && v.env < 0.0005f) v.active = false;
                break;
            }
            default: {
                float k = std::exp(-1.0f / std::fmax(releaseTime * sampleRate, 1.0f));
                v.env *= k;
                if (v.env < 0.0005f) v.active = false;
                break;
            }
        }
        return v.env;
    }

    // Plays a generator at 2^((pitch - 60) / 12) times its own speed with linear interpolation,
    // which is how a sampler repitches a recording (shorter and brighter when higher).
    // At pitch 60 it outputs the generator unchanged, one sample late.
    template <typename Gen>
    static float resampled(Voice& v, float sampleRate, Gen gen) {
        const double ratio = std::clamp(std::pow(2.0, (v.pitch - 60) / 12.0), 1.0 / 16.0, 16.0);
        v.srcPos += ratio;
        while (v.srcPos >= 1.0 && v.active) {
            v.srcPos -= 1.0;
            v.srcPrev = v.srcCur;
            v.srcCur = gen(static_cast<float>(v.srcAge) / sampleRate);
            v.srcAge++;
        }
        return v.srcPrev + (v.srcCur - v.srcPrev) * static_cast<float>(std::min(v.srcPos, 1.0));
    }

    static float decayEnv(Voice& v, float t, float seconds) {
        float e = std::exp(-t / seconds);
        if (t > seconds * 7.0f) v.active = false;
        return e;
    }

    static float kick(Voice& v, float t, float sr) {
        const float tune = std::pow(2.0f, (v.pitch - 60) / 12.0f);
        const float freq = (45.0f + 110.0f * std::exp(-t / 0.035f)) * tune;
        v.phase1 += freq / sr;
        if (v.phase1 >= 1.0) v.phase1 -= 1.0;
        float body = static_cast<float>(std::sin(kTwoPi * v.phase1));
        float click = noise(v) * std::exp(-t / 0.002f) * 0.3f;
        return std::tanh((body * decayEnv(v, t, 0.28f) + click) * 1.6f);
    }

    static float snare(Voice& v, float t, float sr) {
        const float tune = std::pow(2.0f, (v.pitch - 60) / 12.0f);
        v.phase1 += 190.0f * tune / sr;
        if (v.phase1 >= 1.0) v.phase1 -= 1.0;
        float tone = static_cast<float>(std::sin(kTwoPi * v.phase1)) * std::exp(-t / 0.06f);
        float rattle = brightNoise(v) * 1.4f * decayEnv(v, t, 0.12f);
        return 0.55f * tone + 0.8f * rattle;
    }

    static float hat(Voice& v, float t) {
        return brightNoise(v) * 1.2f * decayEnv(v, t, 0.035f);
    }

    static float clap(Voice& v, float t) {
        // Three quick bursts followed by a short tail.
        float bursts = 0.0f;
        for (float offset : {0.0f, 0.011f, 0.022f}) {
            float local = t - offset;
            if (local >= 0.0f) bursts += std::exp(-local / 0.006f);
        }
        float tail = decayEnv(v, t > 0.022f ? t - 0.022f : 0.0f, 0.09f);
        float n = noise(v);
        // crude band-pass: high-passed noise smoothed by a one-pole low-pass
        float hp = n - v.lastNoise;
        v.lastNoise = n;
        v.hpState += 0.45f * (hp - v.hpState);
        return v.hpState * 1.8f * (0.6f * bursts + tail);
    }

    static float bass(Voice& v, float sr) {
        const double inc = midiToHz(static_cast<float>(v.pitch)) / sr;
        float s = saw(v.phase1, inc) * 0.6f;
        v.phase2 += inc * 0.5;
        if (v.phase2 >= 1.0) v.phase2 -= 1.0;
        s += static_cast<float>(std::sin(kTwoPi * v.phase2)) * 0.7f;
        float env = adsr(v, sr, 0.003f, 0.25f, 0.7f, 0.08f);
        float cutoff = 180.0f + 900.0f * env;
        return lowpass(v, s, cutoff, sr, 0.9f) * env;
    }

    static float lead(Voice& v, float sr) {
        const float hz = midiToHz(static_cast<float>(v.pitch));
        float s = saw(v.phase1, hz * 1.003 / sr) + saw(v.phase2, hz * 0.997 / sr);
        float env = adsr(v, sr, 0.008f, 0.3f, 0.65f, 0.2f);
        return lowpass(v, s * 0.4f, 1200.0f + 3500.0f * env, sr, 1.1f) * env;
    }

    static float pluck(Voice& v, float t, float sr) {
        const float hz = midiToHz(static_cast<float>(v.pitch));
        float s = square(v.phase1, hz / sr) * 0.5f + saw(v.phase2, hz * 2.0 / sr) * 0.15f;
        float env = adsr(v, sr, 0.002f, 0.35f, 0.0f, 0.1f);
        float cutoff = 300.0f + 5000.0f * std::exp(-t / 0.08f);
        return lowpass(v, s, cutoff, sr, 1.3f) * env;
    }

    static float pad(Voice& v, float sr) {
        const float hz = midiToHz(static_cast<float>(v.pitch));
        float s = saw(v.phase1, hz * 1.006 / sr) + saw(v.phase2, hz * 0.994 / sr) +
                  saw(v.phase3, hz * 0.5 / sr) * 0.6f;
        float env = adsr(v, sr, 0.35f, 0.8f, 0.8f, 0.6f);
        return lowpass(v, s * 0.25f, 1400.0f, sr, 0.8f) * env;
    }
};

}  // namespace daw
