#include "WaveSynth.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace wavesynth {

namespace {

constexpr float kPi = 3.14159265358979f;
constexpr float kTwoPi = 2.0f * kPi;

const ParamInfo kParams[kParamCount] = {
    {"a_on", 0, 1, 1, true},
    {"a_table", 0, 12, 0, true},
    {"a_pos", 0, 1, 0, false},
    {"a_oct", -3, 3, 0, true},
    {"a_semi", -12, 12, 0, true},
    {"a_fine", -100, 100, 0, false},
    {"a_level", 0, 1, 0.8f, false},
    {"a_unison", 1, kMaxUnison, 1, true},
    {"a_detune", 0, 1, 0.25f, false},
    {"a_spread", 0, 1, 0.6f, false},
    {"b_on", 0, 1, 0, true},
    {"b_table", 0, 12, 0, true},
    {"b_pos", 0, 1, 0, false},
    {"b_oct", -3, 3, 0, true},
    {"b_semi", -12, 12, 0, true},
    {"b_fine", -100, 100, 0, false},
    {"b_level", 0, 1, 0.8f, false},
    {"b_unison", 1, kMaxUnison, 1, true},
    {"b_detune", 0, 1, 0.25f, false},
    {"b_spread", 0, 1, 0.6f, false},
    {"sub_level", 0, 1, 0, false},
    {"sub_oct", 1, 2, 1, true},
    {"noise_level", 0, 1, 0, false},
    {"flt_on", 0, 1, 1, true},
    {"flt_type", 0, 4, 0, true},
    {"flt_cutoff", 20, 20000, 12000, false},
    {"flt_res", 0, 1, 0.1f, false},
    {"flt_drive", 0, 1, 0, false},
    {"flt_key", 0, 1, 0, false},
    {"flt_env", -1, 1, 0, false},
    {"amp_a", 0, 10, 0.003f, false},
    {"amp_d", 0.001f, 10, 0.4f, false},
    {"amp_s", 0, 1, 0.8f, false},
    {"amp_r", 0.001f, 10, 0.2f, false},
    {"mod_a", 0, 10, 0.003f, false},
    {"mod_d", 0.001f, 10, 0.4f, false},
    {"mod_s", 0, 1, 0, false},
    {"mod_r", 0.001f, 10, 0.3f, false},
    {"mod_pitch", -48, 48, 0, false},
    {"mod_pos", -1, 1, 0, false},
    {"lfo_shape", 0, 4, 0, true},
    {"lfo_rate", 0.05f, 30, 2, false},
    {"lfo_sync", 0, 1, 0, true},
    {"lfo_div", 0, kLfoDivCount - 1, 2, true},
    {"lfo_retrig", 0, 1, 1, true},
    {"lfo_cutoff", -1, 1, 0, false},
    {"lfo_pos", -1, 1, 0, false},
    {"lfo_pitch", 0, 12, 0, false},
    {"lfo_amp", 0, 1, 0, false},
    {"mono", 0, 1, 0, true},
    {"glide", 0, 1, 0, false},
    {"vel_amt", 0, 1, 0.5f, false},
    {"drive", 0, 1, 0, false},
    {"volume", 0, 1, 0.7f, false},
};

inline float noteHz(float note) { return 440.0f * std::exp2((note - 69.0f) / 12.0f); }

// Time constant such that a decay/release of `seconds` has fallen ~60 dB by then.
inline float expCoef(float seconds, float rate) {
    return std::exp(-6.9f / std::max(seconds * rate, 1.0f));
}

inline uint32_t lcg(uint32_t& s) {
    s = s * 1664525u + 1013904223u;
    return s;
}

inline float rnd(uint32_t& s) { return static_cast<float>(lcg(s) >> 8) / 8388608.0f - 1.0f; }

inline float readTable(const float* d, int size, double phase) {
    const float x = static_cast<float>(phase) * size;
    const int i = static_cast<int>(x);
    const float f = x - i;
    return d[i] + (d[i + 1] - d[i]) * f;
}

}  // namespace

const ParamInfo& paramInfo(int index) { return kParams[std::clamp(index, 0, kParamCount - 1)]; }

int paramIndex(const char* name) {
    for (int i = 0; i < kParamCount; ++i)
        if (std::strcmp(kParams[i].name, name) == 0) return i;
    return -1;
}

float lfoDivBeats(int index) {
    static const float beats[kLfoDivCount] = {4.0f, 2.0f, 1.0f, 0.5f, 0.25f, 0.125f,
                                              2.0f / 3.0f, 1.0f / 3.0f, 1.0f / 6.0f, 8.0f};
    return beats[std::clamp(index, 0, kLfoDivCount - 1)];
}

Patch::Patch() {
    for (int i = 0; i < kParamCount; ++i) v[static_cast<size_t>(i)] = kParams[i].def;
}

// ------------------------------------------------------------------------------------------

float Envelope::next(float attack, float decayCoef, float sustain, float releaseCoef) {
    switch (stage) {
        case kAttack:
            value += attack;
            if (value >= 1.0f) { value = 1.0f; stage = kDecay; }
            break;
        case kDecay:
            value = sustain + (value - sustain) * decayCoef;
            if (sustain < 1e-4f && value < 1e-4f) { value = 0.0f; stage = kIdle; }
            break;
        case kRelease:
            value *= releaseCoef;
            if (value < 1e-4f) { value = 0.0f; stage = kIdle; }
            break;
        case kIdle:
            break;
    }
    return value;
}

// ------------------------------------------------------------------------------------------

void Instrument::prepare(const Patch& patch, float sampleRate, float bpm) {
    c_.sampleRate = sampleRate > 0 ? sampleRate : 48000.0f;
    c_.bpm = bpm > 1 ? bpm : 120.0f;
    auto& p = c_.p;
    for (int i = 0; i < kParamCount; ++i) {
        const auto& info = kParams[i];
        float x = patch[i];
        if (!(x == x)) x = info.def;  // NaN guard
        x = std::clamp(x, info.min, info.max);
        if (info.stepped) x = std::round(x);
        p.v[static_cast<size_t>(i)] = x;
    }
    const float sr = c_.sampleRate;
    const float ctl = sr / kControlRate;
    const int tables = Bank::ready() ? Bank::get().count() : 1;

    for (int o = 0; o < 2; ++o) {
        const int base = o == 0 ? kAOn : kBOn;
        auto& osc = c_.osc[static_cast<size_t>(o)];
        osc.on = p[base + (kAOn - kAOn)] > 0.5f && p[base + (kALevel - kAOn)] > 0.0f;
        osc.table = std::clamp(static_cast<int>(p[base + (kATable - kAOn)]), 0, tables - 1);
        osc.pitchOffset = p[base + (kAOct - kAOn)] * 12.0f + p[base + (kASemi - kAOn)] + p[base + (kAFine - kAOn)] / 100.0f;
        osc.level = p[base + (kALevel - kAOn)];
        osc.unison = std::clamp(static_cast<int>(p[base + (kAUnison - kAOn)]), 1, kMaxUnison);
        const float detune = p[base + (kADetune - kAOn)];
        const float spread = p[base + (kASpread - kAOn)];
        const float norm = osc.level / std::sqrt(static_cast<float>(osc.unison));
        for (int u = 0; u < osc.unison; ++u) {
            const float x = osc.unison == 1 ? 0.0f : static_cast<float>(u) / (osc.unison - 1) * 2.0f - 1.0f;
            osc.detune[static_cast<size_t>(u)] = x * detune * detune * 1.2f;
            const float angle = (x * spread + 1.0f) * 0.25f * kPi;
            osc.gainL[static_cast<size_t>(u)] = std::cos(angle) * 1.41421356f * norm;
            osc.gainR[static_cast<size_t>(u)] = std::sin(angle) * 1.41421356f * norm;
        }
    }

    c_.ampAttack = 1.0f / std::max(p[kAmpA] * sr, 32.0f);  // >= ~0.7 ms, avoids clicks
    c_.ampDecay = expCoef(p[kAmpD], sr);
    c_.ampSustain = p[kAmpS];
    c_.ampRelease = expCoef(std::max(p[kAmpR], 0.004f), sr);
    c_.chokeRelease = expCoef(0.004f, sr);
    c_.modAttack = 1.0f / std::max(p[kModA] * ctl, 1.0f);
    c_.modDecay = expCoef(p[kModD], ctl);
    c_.modSustain = p[kModS];
    c_.modRelease = expCoef(p[kModR], ctl);

    c_.lfoShape = static_cast<int>(p[kLfoShape]);
    c_.lfoRetrig = p[kLfoRetrig] > 0.5f;
    c_.lfoHz = p[kLfoSync] > 0.5f ? c_.bpm / 60.0f / lfoDivBeats(static_cast<int>(p[kLfoDiv])) : p[kLfoRate];

    c_.mono = p[kMono] > 0.5f;
    const float glide = p[kGlide];
    c_.glideCoef = glide <= 0.0005f ? 1.0f : 1.0f - std::exp(-1.0f / (glide * 0.35f * ctl));
    c_.velAmt = p[kVelAmt];
    c_.volume = p[kVolume];
    c_.subLevel = p[kSubLevel];
    c_.subOct = static_cast<int>(p[kSubOct]);
    c_.noiseLevel = p[kNoiseLevel];
    c_.filterOn = p[kFltOn] > 0.5f;
    c_.filterType = static_cast<int>(p[kFltType]);
    c_.filterK = 2.0f - 1.96f * p[kFltRes];
    c_.preDrive = 1.0f + p[kFltDrive] * 9.0f;
    c_.preDriveNorm = 1.0f / std::sqrt(c_.preDrive);
    c_.postDrive = 1.0f + p[kDrive] * 7.0f;
    c_.postDriveNorm = 1.0f / std::tanh(c_.postDrive);
}

bool Instrument::anyActive() const {
    for (const auto& v : voices_) if (v.active) return true;
    return false;
}

void Instrument::silence() {
    for (auto& v : voices_) v.active = false;
}

void Instrument::releaseAll() {
    for (auto& v : voices_) {
        v.amp.release();
        v.mod.release();
        v.gate = 0;
    }
}

void Instrument::choke() {
    for (auto& v : voices_) {
        if (!v.active) continue;
        v.choking = true;
        v.amp.release();
        v.mod.release();
        v.gate = 0;
    }
}

void Instrument::noteOff(int note) {
    for (auto& v : voices_) {
        if (v.active && v.note == note && v.gate <= 0 && v.amp.stage != Envelope::kRelease) {
            v.amp.release();
            v.mod.release();
        }
    }
}

void Instrument::noteOn(int note, float velocity, int64_t gateSamples) {
    if (c_.mono) {
        Voice& v = voices_[0];
        for (size_t i = 1; i < voices_.size(); ++i) voices_[i].amp.release();
        const bool held = v.active && v.amp.stage != Envelope::kRelease && v.amp.stage != Envelope::kIdle;
        startVoice(v, note, velocity, gateSamples, held && c_.glideCoef < 1.0f);
        return;
    }
    Voice* target = nullptr;
    for (auto& v : voices_) if (!v.active) { target = &v; break; }
    if (!target) {
        // Steal: prefer the quietest released voice, otherwise the oldest.
        for (auto& v : voices_)
            if (v.amp.stage == Envelope::kRelease && (!target || v.amp.value < target->amp.value)) target = &v;
        if (!target) {
            target = &voices_[0];
            for (auto& v : voices_) if (v.age > target->age) target = &v;
        }
    }
    startVoice(*target, note, velocity, gateSamples, false);
}

void Instrument::startVoice(Voice& v, int note, float velocity, int64_t gate, bool legato) {
    const bool wasActive = v.active;
    v.note = note;
    v.targetPitch = static_cast<float>(note);
    v.gate = gate > 0 ? gate : -1;
    if (legato) {
        // Slide to the new note without restarting envelopes.
        lastNote_ = note;
        return;
    }
    v.active = true;
    v.velocity = std::clamp(velocity, 0.0f, 1.0f);
    // Glide from the previous note in mono mode, otherwise start on pitch.
    v.pitch = (c_.mono && c_.glideCoef < 1.0f && lastNote_ >= 0) ? (wasActive ? v.pitch : static_cast<float>(lastNote_))
                                                                   : static_cast<float>(note);
    lastNote_ = note;
    v.age = 0;
    if (!wasActive) {
        v.amp.value = 0.0f;
        v.mod.value = 0.0f;
        for (auto& stage : v.svf)
            for (auto& s : stage) s = {};
        v.cutoffSmooth = -1.0f;
    }
    v.choking = false;
    v.amp.start();
    v.mod.start();
    v.seed = seed_ = seed_ * 747796405u + 2891336453u;
    for (int o = 0; o < 2; ++o) {
        const int n = c_.osc[static_cast<size_t>(o)].unison;
        for (int u = 0; u < kMaxUnison; ++u)
            v.phase[o][u] = n == 1 ? 0.0 : (lcg(v.seed) >> 8) / 16777216.0;
    }
    v.subPhase = 0.0;
    v.lfoPhase = c_.lfoRetrig ? 0.0 : freeLfoPhase_;
    v.lfoHold = rnd(v.seed);
    v.controlCountdown = 0;
}

void Instrument::control(Voice& v) {
    const auto& p = c_.p;
    const float sr = c_.sampleRate;

    if (c_.glideCoef >= 1.0f) v.pitch = v.targetPitch;
    else v.pitch += (v.targetPitch - v.pitch) * c_.glideCoef;

    const float mod = v.mod.next(c_.modAttack, c_.modDecay, c_.modSustain, c_.modRelease);

    // LFO, bipolar -1..1
    v.lfoPhase += static_cast<double>(c_.lfoHz) * kControlRate / sr;
    if (v.lfoPhase >= 1.0) {
        v.lfoPhase -= std::floor(v.lfoPhase);
        v.lfoHold = rnd(v.seed);
    }
    const float ph = static_cast<float>(v.lfoPhase);
    float lfo = 0.0f;
    switch (c_.lfoShape) {
        case kSine: lfo = std::sin(kTwoPi * ph); break;
        case kTriangle: lfo = 1.0f - 4.0f * std::fabs(ph - 0.5f); break;
        case kSawDown: lfo = 1.0f - 2.0f * ph; break;
        case kSquare: lfo = ph < 0.5f ? 1.0f : -1.0f; break;
        default: lfo = v.lfoHold; break;
    }
    v.ampLfo = 1.0f - p[kLfoAmp] * (0.5f + 0.5f * lfo);

    const float pitch = v.pitch + p[kModPitch] * mod + p[kLfoPitch] * lfo;
    for (int o = 0; o < 2; ++o) {
        const auto& osc = c_.osc[static_cast<size_t>(o)];
        if (!osc.on) continue;
        float maxInc = 0.0f;
        for (int u = 0; u < osc.unison; ++u) {
            const float inc = noteHz(pitch + osc.pitchOffset + osc.detune[static_cast<size_t>(u)]) / sr;
            v.inc[o][u] = std::min(inc, 0.49f);
            maxInc = std::max(maxInc, inc);
        }
        int level = 0;
        while (level < kLevels - 1 && static_cast<float>(kMaxHarmonics >> level) * maxInc > 0.48f) ++level;
        v.level[o] = level;
        const int posParam = o == 0 ? kAPos : kBPos;
        const float pos = std::clamp(p[posParam] + p[kModPos] * mod + p[kLfoPos] * lfo, 0.0f, 1.0f);
        const float fp = pos * (kFrames - 1);
        v.frameA[o] = std::min(static_cast<int>(fp), kFrames - 1);
        v.frameB[o] = std::min(v.frameA[o] + 1, kFrames - 1);
        v.frameMix[o] = fp - v.frameA[o];
    }
    v.subInc = noteHz(pitch - 12.0f * c_.subOct) / sr;

    if (c_.filterOn) {
        // Cutoff in octaves relative to the knob, modulated then smoothed.
        const float octs = p[kFltKey] * (v.pitch - 60.0f) / 12.0f + p[kFltEnv] * 8.0f * mod + p[kLfoCutoff] * 4.0f * lfo;
        const float target = std::log2(p[kFltCutoff]) + octs;
        v.cutoffSmooth = v.cutoffSmooth < 0.0f ? target : v.cutoffSmooth + (target - v.cutoffSmooth) * 0.35f;
        const float hz = std::clamp(std::exp2(v.cutoffSmooth), 16.0f, sr * 0.45f);
        const float g = std::tan(kPi * hz / sr);
        const float ks[2] = {c_.filterK, 1.41421356f};
        for (int s = 0; s < 2; ++s) {
            v.a1[s] = 1.0f / (1.0f + g * (g + ks[s]));
            v.a2[s] = g * v.a1[s];
            v.a3[s] = g * v.a2[s];
        }
    }
}

void Instrument::tick(float& outL, float& outR) {
    freeLfoPhase_ += static_cast<double>(c_.lfoHz) / c_.sampleRate;
    if (freeLfoPhase_ >= 1.0) freeLfoPhase_ -= 1.0;
    if (!Bank::ready()) return;  // still being built on another thread

    const Bank& bank = Bank::get();
    float sumL = 0.0f, sumR = 0.0f;
    for (auto& v : voices_) {
        if (!v.active) continue;
        if (--v.controlCountdown <= 0) {
            control(v);
            v.controlCountdown = kControlRate;
        }
        float l = 0.0f, r = 0.0f;
        for (int o = 0; o < 2; ++o) {
            const auto& osc = c_.osc[static_cast<size_t>(o)];
            if (!osc.on) continue;
            const Wavetable& wt = bank.table(osc.table);
            const int size = wt.levels[static_cast<size_t>(v.level[o])].size;
            const float* fa = wt.frame(v.frameA[o], v.level[o]);
            const float* fb = wt.frame(v.frameB[o], v.level[o]);
            const float mix = v.frameMix[o];
            for (int u = 0; u < osc.unison; ++u) {
                double& ph = v.phase[o][u];
                const float a = readTable(fa, size, ph);
                const float b = readTable(fb, size, ph);
                const float s = a + (b - a) * mix;
                ph += v.inc[o][u];
                if (ph >= 1.0) ph -= 1.0;
                l += s * osc.gainL[static_cast<size_t>(u)];
                r += s * osc.gainR[static_cast<size_t>(u)];
            }
        }
        if (c_.subLevel > 0.0f) {
            const float s = std::sin(kTwoPi * static_cast<float>(v.subPhase)) * c_.subLevel;
            v.subPhase += v.subInc;
            if (v.subPhase >= 1.0) v.subPhase -= 1.0;
            l += s;
            r += s;
        }
        if (c_.noiseLevel > 0.0f) {
            const float n = rnd(v.seed) * c_.noiseLevel * 0.5f;
            l += n;
            r += n;
        }
        if (c_.preDrive > 1.0f) {
            l = std::tanh(l * c_.preDrive) * c_.preDriveNorm;
            r = std::tanh(r * c_.preDrive) * c_.preDriveNorm;
        }
        if (c_.filterOn) {
            float io[2] = {l, r};
            const int stages = c_.filterType == kLowpass24 ? 2 : 1;
            for (int ch = 0; ch < 2; ++ch) {
                float x = io[ch];
                for (int s = 0; s < stages; ++s) {
                    Svf& f = v.svf[s][ch];
                    const float v3 = x - f.ic2;
                    const float v1 = v.a1[s] * f.ic1 + v.a2[s] * v3;
                    const float v2 = f.ic2 + v.a2[s] * f.ic1 + v.a3[s] * v3;
                    f.ic1 = 2.0f * v1 - f.ic1;
                    f.ic2 = 2.0f * v2 - f.ic2;
                    const float k = s == 0 ? c_.filterK : 1.41421356f;
                    switch (c_.filterType) {
                        case kHighpass: x = x - k * v1 - v2; break;
                        case kBandpass: x = v1 * k; break;
                        case kNotch: x = x - k * v1; break;
                        default: x = v2; break;
                    }
                }
                io[ch] = x;
            }
            l = io[0];
            r = io[1];
        }
        const float env = v.amp.next(c_.ampAttack, c_.ampDecay, c_.ampSustain, v.choking ? c_.chokeRelease : c_.ampRelease);
        const float vel = 1.0f - c_.velAmt + c_.velAmt * v.velocity;
        const float gain = env * vel * v.ampLfo * c_.volume;
        sumL += l * gain;
        sumR += r * gain;

        ++v.age;
        if (v.gate > 0 && --v.gate == 0) {
            v.amp.release();
            v.mod.release();
        }
        if (v.amp.stage == Envelope::kIdle) v.active = false;
    }
    if (c_.postDrive > 1.0f) {
        sumL = std::tanh(sumL * c_.postDrive) * c_.postDriveNorm;
        sumR = std::tanh(sumR * c_.postDrive) * c_.postDriveNorm;
    }
    outL += sumL;
    outR += sumR;
}

}  // namespace wavesynth
