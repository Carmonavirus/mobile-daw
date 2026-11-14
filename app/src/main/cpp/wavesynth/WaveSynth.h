// WaveSynth: a two-oscillator wavetable synthesiser.
//
// Portable C++17 with no platform dependencies (no Android, Oboe, JNI or allocation on the
// audio thread), so the same code can be compiled for desktop tests or to WebAssembly.
//
// Signal flow per voice:
//   Osc A + Osc B (wavetables, unison) + Sub + Noise -> Drive -> Filter -> Amp envelope
// Modulation: a second "mod" envelope and one LFO, each with fixed destinations
// (cutoff, wavetable position, pitch, and for the LFO also amplitude).
#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace wavesynth {

// ------------------------------------------------------------------------------------------
// Parameters. The string names are stored in preset files, so never rename or reuse one;
// add new parameters at the end of the list.

enum Param : int {
    kAOn, kATable, kAPos, kAOct, kASemi, kAFine, kALevel, kAUnison, kADetune, kASpread,
    kBOn, kBTable, kBPos, kBOct, kBSemi, kBFine, kBLevel, kBUnison, kBDetune, kBSpread,
    kSubLevel, kSubOct, kNoiseLevel,
    kFltOn, kFltType, kFltCutoff, kFltRes, kFltDrive, kFltKey, kFltEnv,
    kAmpA, kAmpD, kAmpS, kAmpR,
    kModA, kModD, kModS, kModR, kModPitch, kModPos,
    kLfoShape, kLfoRate, kLfoSync, kLfoDiv, kLfoRetrig, kLfoCutoff, kLfoPos, kLfoPitch, kLfoAmp,
    kMono, kGlide, kVelAmt, kDrive, kVolume,
    kParamCount
};

struct ParamInfo {
    const char* name;
    float min;
    float max;
    float def;
    bool stepped;  // whole numbers only (switches, choices)
};

const ParamInfo& paramInfo(int index);
int paramIndex(const char* name);  // -1 if unknown

enum FilterType : int { kLowpass12 = 0, kLowpass24 = 1, kHighpass = 2, kBandpass = 3, kNotch = 4 };
enum LfoShape : int { kSine = 0, kTriangle = 1, kSawDown = 2, kSquare = 3, kRandom = 4 };

// Tempo-synced LFO lengths, in beats, for kLfoDiv. Labels: 1/1 1/2 1/4 1/8 1/16 1/32 1/4T 1/8T 1/16T 2/1
constexpr int kLfoDivCount = 10;
float lfoDivBeats(int index);

/// A full set of parameter values.
struct Patch {
    std::array<float, kParamCount> v{};
    Patch();  // all defaults
    float operator[](int i) const { return v[static_cast<size_t>(i)]; }
};

// ------------------------------------------------------------------------------------------
// Wavetables. Each table holds kFrames single-cycle frames; "position" morphs across them.
// Every frame is stored at several band-limited levels so high notes don't alias.

constexpr int kFrames = 32;
constexpr int kMaxHarmonics = 512;
constexpr int kLevels = 10;

class Wavetable {
public:
    struct Level {
        int size = 0;    // power of two
        int offset = 0;  // into data, per frame: size + 1 samples (wrap guard)
    };
    const char* name = "";
    std::array<Level, kLevels> levels{};
    int frameStride = 0;  // samples per frame across all levels
    std::vector<float> data;

    const float* frame(int f, int level) const {
        return data.data() + static_cast<size_t>(f) * static_cast<size_t>(frameStride) +
               static_cast<size_t>(levels[static_cast<size_t>(level)].offset);
    }
};

/// The built-in tables. Building them takes a moment, so call Bank::get() once off the
/// audio thread (the engine does this in its constructor).
class Bank {
public:
    static const Bank& get();
    /// True once get() has finished building, so the audio thread never waits for it.
    static bool ready();
    int count() const { return static_cast<int>(tables_.size()); }
    const Wavetable& table(int index) const;
    /// Draws one cycle at `position` (0..1) into out[0..n), for the editor's display.
    void shape(int table, float position, float* out, int n) const;

private:
    Bank();
    std::vector<Wavetable> tables_;
};

// ------------------------------------------------------------------------------------------
// The playable instrument: one per channel, a fixed pool of voices.

constexpr int kVoices = 8;
constexpr int kMaxUnison = 7;
constexpr int kControlRate = 16;  // samples between modulation updates

/// Values derived from a Patch once per audio block.
struct Cooked {
    float sampleRate = 48000.0f;
    float bpm = 120.0f;
    Patch p;
    // Per-oscillator unison layout.
    struct Osc {
        bool on = false;
        int table = 0;
        float pitchOffset = 0.0f;  // semitones
        float level = 0.0f;
        int unison = 1;
        std::array<float, kMaxUnison> detune{};  // semitones
        std::array<float, kMaxUnison> gainL{};
        std::array<float, kMaxUnison> gainR{};
    };
    std::array<Osc, 2> osc{};
    float lfoHz = 1.0f;
    int lfoShape = 0;
    bool lfoRetrig = true;
    // Envelope coefficients: amp runs per sample, mod at control rate.
    float ampAttack = 1.0f, ampDecay = 0.0f, ampSustain = 1.0f, ampRelease = 0.0f, chokeRelease = 0.0f;
    float modAttack = 1.0f, modDecay = 0.0f, modSustain = 0.0f, modRelease = 0.0f;
    float glideCoef = 1.0f;  // per control tick, 1 = no glide
    bool mono = false;
    float velAmt = 0.5f;
    float volume = 0.7f;
    float subLevel = 0.0f;
    int subOct = 1;
    float noiseLevel = 0.0f;
    bool filterOn = true;
    int filterType = 0;
    float filterK = 1.4f;
    float preDrive = 1.0f, preDriveNorm = 1.0f;    // tanh gain before the filter
    float postDrive = 1.0f, postDriveNorm = 1.0f;  // tanh gain at the output
};

struct Envelope {
    enum Stage : uint8_t { kIdle, kAttack, kDecay, kRelease };
    Stage stage = kIdle;
    float value = 0.0f;
    void start() { stage = kAttack; }
    void release() { if (stage != kIdle) stage = kRelease; }
    float next(float attack, float decayCoef, float sustain, float releaseCoef);
};

struct Svf {
    float ic1 = 0.0f, ic2 = 0.0f;
};

struct Voice {
    bool active = false;
    int note = 60;
    float velocity = 1.0f;
    float pitch = 60.0f;        // current (gliding) pitch
    float targetPitch = 60.0f;
    float glideCoef = 0.0f;
    int64_t gate = 0;           // samples until release, <0 = held until noteOff
    int64_t age = 0;
    bool choking = false;
    Envelope amp, mod;
    double phase[2][kMaxUnison]{};
    double subPhase = 0.0;
    double lfoPhase = 0.0;
    float lfoHold = 0.0f;       // sample & hold value
    uint32_t seed = 1;
    int controlCountdown = 0;
    // Control-rate state
    float inc[2][kMaxUnison]{};
    int level[2]{};
    int frameA[2]{}, frameB[2]{};
    float frameMix[2]{};
    float subInc = 0.0f;
    float ampLfo = 1.0f;
    float a1[2]{1.0f, 1.0f}, a2[2]{}, a3[2]{};  // filter coefficients per stage
    float cutoffSmooth = -1.0f;
    Svf svf[2][2];              // [stage][L/R]
};

class Instrument {
public:
    /// Recomputes derived values. Call at the start of each audio block.
    void prepare(const Patch& patch, float sampleRate, float bpm);

    /// gateSamples > 0: released automatically after that many samples. <= 0: held until noteOff.
    void noteOn(int note, float velocity, int64_t gateSamples);
    void noteOff(int note);
    void releaseAll();
    /// Fades out everything that is sounding within a few milliseconds ("cut itself").
    void choke();
    void silence();

    /// Renders one stereo sample, adding into l and r.
    void tick(float& l, float& r);

    bool anyActive() const;

private:
    void startVoice(Voice& v, int note, float velocity, int64_t gate, bool legato);
    void control(Voice& v);

    Cooked c_;
    std::array<Voice, kVoices> voices_{};
    double freeLfoPhase_ = 0.0;
    int lastNote_ = -1;
    uint32_t seed_ = 12345;
};

}  // namespace wavesynth
