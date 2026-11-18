// Modeled instrument voice: DaisySP (MIT, Electrosmith) drum/voice models and
// STK (MIT-style, Perry R. Cook and Gary P. Scavone) physical models. Presets are ours.
#include "ModelVoice.h"

#include <algorithm>
#include <cmath>

#include "Control/adsr.h"
#include "Drums/analogbassdrum.h"
#include "Drums/analogsnaredrum.h"
#include "Drums/hihat.h"
#include "Drums/synthbassdrum.h"
#include "Drums/synthsnaredrum.h"
#include "Noise/particle.h"
#include "PhysicalModeling/drip.h"
#include "PhysicalModeling/modalvoice.h"
#include "PhysicalModeling/stringvoice.h"
#include "Synthesis/fm2.h"
#include "Synthesis/formantosc.h"
#include "Synthesis/vosim.h"

#include "BandedWG.h"
#include "BlowBotl.h"
#include "Bowed.h"
#include "Brass.h"
#include "Clarinet.h"
#include "Flute.h"
#include "Recorder.h"
#include "Saxofony.h"
#include "Shakers.h"
#include "Sitar.h"
#include "StifKarp.h"

namespace daw {

namespace {

enum Kind {
    // DaisySP
    kAnalogKick, kSynthKick, kAnalogSnare, kSynthSnare, kHat, kString, kModal,
    kFm, kFormant, kVosim, kDrip, kParticle,
    // STK
    kFlute, kClarinet, kSax, kBrass, kBowed, kBottle, kSitar, kStiff, kRecorder, kBanded, kShaker,
};

struct Preset {
    const char* info;
    int kind;
    float a, b, c;  // model-specific: tone/structure, decay/brightness, extra
    float gain = 1.0f;
};

const Preset kPresets[] = {
    // DaisySP
    {"Analog kick|Drums|1|36|DaisySP", kAnalogKick, 0.5f, 0.6f, 0.5f, 1.6f},
    {"Boom kick|Drums|1|31|DaisySP", kAnalogKick, 0.3f, 0.9f, 0.3f, 3.0f},
    {"Synth kick|Drums|1|36|DaisySP", kSynthKick, 0.5f, 0.5f, 0.3f},
    {"Analog snare|Drums|1|52|DaisySP", kAnalogSnare, 0.5f, 0.5f, 0.6f},
    {"Synth snare|Drums|1|52|DaisySP", kSynthSnare, 0.4f, 0.5f, 0.7f},
    {"Closed hat|Drums|1|76|DaisySP", kHat, 0.6f, 0.25f, 0.8f, 3.0f},
    {"Open hat|Drums|1|76|DaisySP", kHat, 0.6f, 0.75f, 0.8f},
    {"Water drop|Drums|1|60|DaisySP", kDrip, 0.0f, 0.0f, 0.0f, 2.5f},
    {"Plucked string|Plucks|0|60|DaisySP", kString, 0.3f, 0.5f, 0.5f, 1.4f},
    {"Harp|Plucks|0|60|DaisySP", kString, 0.15f, 0.7f, 0.7f},
    {"Mallet|Plucks|0|72|DaisySP", kModal, 0.3f, 0.4f, 0.5f},
    {"Glass bell|Plucks|0|72|DaisySP", kModal, 0.8f, 0.8f, 0.75f},
    {"FM e-piano|Keys|0|60|DaisySP", kFm, 1.0f, 1.2f, 0.0f, 1.8f},
    {"FM bell|Keys|0|72|DaisySP", kFm, 3.5f, 2.0f, 0.0f, 1.8f},
    {"FM bass|Bass|0|36|DaisySP", kFm, 0.5f, 2.5f, 0.0f, 1.8f},
    {"Formant voice|Textures|0|60|DaisySP", kFormant, 700.0f, 0.0f, 0.0f, 2.0f},
    {"VOSIM|Textures|0|60|DaisySP", kVosim, 600.0f, 1500.0f, 0.3f, 2.0f},
    {"Crackle|Textures|0|72|DaisySP", kParticle, 0.6f, 0.4f, 0.0f, 3.0f},
    // STK
    {"Flute|Winds|0|72|STK", kFlute, 0.0f, 0.0f, 0.0f, 2.0f},
    {"Clarinet|Winds|0|60|STK", kClarinet, 0.0f, 0.0f, 0.0f},
    {"Sax|Winds|0|60|STK", kSax, 0.0f, 0.0f, 0.0f},
    {"Recorder|Winds|0|72|STK", kRecorder, 0.0f, 0.0f, 0.0f, 1.4f},
    {"Blown bottle|Winds|0|60|STK", kBottle, 0.0f, 0.0f, 0.0f},
    {"Brass|Brass|0|60|STK", kBrass, 0.0f, 0.0f, 0.0f, 8.0f},
    {"Bowed string|Strings|0|60|STK", kBowed, 0.0f, 0.0f, 0.0f, 3.0f},
    {"Sitar|Strings|0|60|STK", kSitar, 0.0f, 0.0f, 0.0f, 3.0f},
    {"Piano string|Strings|0|60|STK", kStiff, 0.0f, 0.0f, 0.0f, 1.6f},
    {"Bowed bar|Strings|0|72|STK", kBanded, 0.0f, 0.0f, 0.0f, 16.0f},
    {"Glass harmonica|Strings|0|72|STK", kBanded, 2.0f, 0.0f, 0.0f, 14.0f},
    {"Singing bowl|Strings|0|60|STK", kBanded, 3.0f, 0.0f, 0.0f, 6.0f},
    {"Maraca|Percussion|1|60|STK", kShaker, 0.0f, 0.0f, 0.0f, 8.0f},
    {"Cabasa|Percussion|1|60|STK", kShaker, 1.0f, 0.0f, 0.0f, 7.0f},
    {"Tambourine|Percussion|1|60|STK", kShaker, 3.0f, 0.0f, 0.0f, 8.0f},
    {"Sleigh bells|Percussion|1|60|STK", kShaker, 4.0f, 0.0f, 0.0f, 5.0f},
    {"Bamboo chimes|Percussion|1|60|STK", kShaker, 5.0f, 0.0f, 0.0f, 10.0f},
    {"Sticks|Percussion|1|60|STK", kShaker, 8.0f, 0.0f, 0.0f, 8.0f},
};
constexpr int kPresetCount = sizeof(kPresets) / sizeof(kPresets[0]);

constexpr float kStkLowest = 30.0f;

float midiToHz(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }

}  // namespace

// Runs before the STK members below are built: quiet warnings and default to 48 kHz.
struct StkSetup {
    StkSetup() {
        stk::Stk::showWarnings(false);
        if (stk::Stk::sampleRate() != 48000.0) stk::Stk::setSampleRate(48000.0);
    }
};

struct ModelVoice::Impl {
    StkSetup stkSetup;
    // DaisySP voices (no heap memory; Init only sets state).
    daisysp::AnalogBassDrum analogKick;
    daisysp::SyntheticBassDrum synthKick;
    daisysp::AnalogSnareDrum analogSnare;
    daisysp::SyntheticSnareDrum synthSnare;
    daisysp::HiHat<> hat;
    daisysp::StringVoice string;
    daisysp::ModalVoice modal;
    daisysp::Fm2 fm;
    daisysp::FormantOscillator formant;
    daisysp::VosimOscillator vosim;
    daisysp::Drip drip;
    daisysp::Particle particle;
    daisysp::Adsr env;

    // STK instruments allocate their delay lines up front, here on the UI thread.
    stk::Flute flute{kStkLowest};
    stk::Clarinet clarinet{kStkLowest};
    stk::Saxofony sax{kStkLowest};
    stk::Brass brass{kStkLowest};
    stk::Bowed bowed{kStkLowest};
    stk::BlowBotl bottle;
    stk::Sitar sitar{kStkLowest};
    stk::StifKarp stiff{kStkLowest};
    stk::Recorder recorder;
    stk::BandedWG banded;
    stk::Shakers shaker;

    int preset = 0;
    float velocity = 1.0f;
    float sampleRate = 48000.0f;
    bool gate = false;
    bool trig = false;
    int64_t gateLeft = 0;
    int quiet = 0;

    stk::Instrmnt* stkFor(int kind) {
        switch (kind) {
            case kFlute: return &flute;
            case kClarinet: return &clarinet;
            case kSax: return &sax;
            case kBrass: return &brass;
            case kBowed: return &bowed;
            case kBottle: return &bottle;
            case kSitar: return &sitar;
            case kStiff: return &stiff;
            case kRecorder: return &recorder;
            case kBanded: return &banded;
            case kShaker: return &shaker;
            default: return nullptr;
        }
    }

    void init(float sr) {
        sampleRate = sr;
        analogKick.Init(sr);
        synthKick.Init(sr);
        analogSnare.Init(sr);
        synthSnare.Init(sr);
        hat.Init(sr);
        string.Init(sr);
        modal.Init(sr);
        fm.Init(sr);
        formant.Init(sr);
        vosim.Init(sr);
        drip.Init(sr, 0.09f);
        particle.Init(sr);
        env.Init(sr);
    }
};

ModelVoice::ModelVoice() : impl_(new Impl()) { impl_->init(48000.0f); }
ModelVoice::~ModelVoice() = default;

int ModelVoice::presetCount() { return kPresetCount; }

const char* ModelVoice::presetInfo(int index) {
    return index >= 0 && index < kPresetCount ? kPresets[index].info : nullptr;
}

void ModelVoice::setSampleRate(int sampleRate) {
    const float sr = static_cast<float>(sampleRate > 0 ? sampleRate : 48000);
    if (stk::Stk::sampleRate() != sr) stk::Stk::setSampleRate(sr);
    if (impl_->sampleRate != sr) impl_->init(sr);
}

void ModelVoice::start(int slotIndex, int preset, int note, float velocity, int64_t gateSamples) {
    auto& m = *impl_;
    // Let the previous model of this voice fall silent if the preset changes kind.
    if (active && kPresets[m.preset].kind != kPresets[std::clamp(preset, 0, kPresetCount - 1)].kind) {
        if (auto* s = m.stkFor(kPresets[m.preset].kind)) s->noteOff(1.0);
    }
    active = true;
    slot = slotIndex;
    pitch = note;
    age = 0;
    m.preset = std::clamp(preset, 0, kPresetCount - 1);
    m.velocity = std::clamp(velocity, 0.05f, 1.0f);
    m.gate = true;
    m.trig = true;
    m.gateLeft = std::max<int64_t>(gateSamples, 1);
    m.quiet = 0;

    const Preset& p = kPresets[m.preset];
    const float hz = midiToHz(static_cast<float>(note));
    const float vel = m.velocity;
    switch (p.kind) {
        case kAnalogKick:
            m.analogKick.SetFreq(hz);
            m.analogKick.SetTone(p.a);
            m.analogKick.SetDecay(p.b);
            m.analogKick.SetAttackFmAmount(p.c);
            m.analogKick.SetSelfFmAmount(p.c * 0.6f);
            m.analogKick.SetAccent(vel);
            break;
        case kSynthKick:
            m.synthKick.SetFreq(hz);
            m.synthKick.SetTone(p.a);
            m.synthKick.SetDecay(p.b);
            m.synthKick.SetDirtiness(p.c);
            m.synthKick.SetFmEnvelopeAmount(0.6f);
            m.synthKick.SetFmEnvelopeDecay(0.3f);
            m.synthKick.SetAccent(vel);
            break;
        case kAnalogSnare:
            m.analogSnare.SetFreq(hz);
            m.analogSnare.SetTone(p.a);
            m.analogSnare.SetDecay(p.b);
            m.analogSnare.SetSnappy(p.c);
            m.analogSnare.SetAccent(vel);
            break;
        case kSynthSnare:
            m.synthSnare.SetFreq(hz);
            m.synthSnare.SetFmAmount(p.a);
            m.synthSnare.SetDecay(p.b);
            m.synthSnare.SetSnappy(p.c);
            m.synthSnare.SetAccent(vel);
            break;
        case kHat:
            m.hat.SetFreq(hz * 30.0f > 12000.0f ? 12000.0f : hz * 30.0f);
            m.hat.SetTone(p.a);
            m.hat.SetDecay(p.b);
            m.hat.SetNoisiness(p.c);
            m.hat.SetAccent(vel);
            break;
        case kString:
            m.string.SetFreq(hz);
            m.string.SetStructure(p.a);
            m.string.SetBrightness(p.b);
            m.string.SetDamping(p.c);
            m.string.SetAccent(vel);
            break;
        case kModal:
            m.modal.SetFreq(hz);
            m.modal.SetStructure(p.a);
            m.modal.SetBrightness(p.b);
            m.modal.SetDamping(p.c);
            m.modal.SetAccent(vel);
            break;
        case kFm:
            m.fm.SetFrequency(hz);
            m.fm.SetRatio(p.a);
            m.fm.SetIndex(p.b);
            m.env.SetAttackTime(0.003f);
            m.env.SetDecayTime(p.a > 2.0f ? 1.2f : 0.6f);
            m.env.SetSustainLevel(p.a > 2.0f ? 0.0f : 0.5f);
            m.env.SetReleaseTime(0.3f);
            m.env.Retrigger(false);
            break;
        case kFormant:
            m.formant.SetCarrierFreq(hz);
            m.formant.SetFormantFreq(p.a);
            m.env.SetAttackTime(0.02f);
            m.env.SetDecayTime(0.3f);
            m.env.SetSustainLevel(0.8f);
            m.env.SetReleaseTime(0.2f);
            m.env.Retrigger(false);
            break;
        case kVosim:
            m.vosim.SetFreq(hz);
            m.vosim.SetForm1Freq(p.a);
            m.vosim.SetForm2Freq(p.b);
            m.vosim.SetShape(p.c);
            m.env.SetAttackTime(0.01f);
            m.env.SetDecayTime(0.3f);
            m.env.SetSustainLevel(0.7f);
            m.env.SetReleaseTime(0.2f);
            m.env.Retrigger(false);
            break;
        case kParticle:
            m.particle.SetFreq(hz * 4.0f);
            m.particle.SetResonance(0.9f);
            m.particle.SetDensity(p.a);
            m.particle.SetGain(1.0f);
            m.particle.SetSpread(p.b);
            m.env.SetAttackTime(0.005f);
            m.env.SetDecayTime(0.4f);
            m.env.SetSustainLevel(0.6f);
            m.env.SetReleaseTime(0.3f);
            m.env.Retrigger(false);
            break;
        case kDrip:
            break;
        case kBanded:
            m.banded.setPreset(static_cast<int>(p.a));
            m.banded.noteOn(hz, vel);
            break;
        case kShaker: {
            // Shakers picks its instrument from the note frequency; aim at the wanted type.
            const float typeHz = 220.0f * std::pow(2.0f, (p.a - 57.0f) / 12.0f);
            m.shaker.noteOn(typeHz, vel);
            break;
        }
        default:
            if (auto* s = m.stkFor(p.kind)) s->noteOn(hz, vel);
            break;
    }
}

void ModelVoice::release() {
    auto& m = *impl_;
    if (!m.gate) return;
    m.gate = false;
    const int kind = kPresets[m.preset].kind;
    if (kind == kShaker) return;  // shakers ring out on their own
    if (auto* s = m.stkFor(kind)) s->noteOff(0.5);
}

void ModelVoice::render(float* out, int frames) {
    auto& m = *impl_;
    if (!active) return;
    const Preset& p = kPresets[m.preset];
    const int kind = p.kind;
    stk::Instrmnt* instrument = m.stkFor(kind);
    float peak = 0.0f;
    for (int i = 0; i < frames; ++i) {
        const bool t = m.trig;
        m.trig = false;
        float s = 0.0f;
        switch (kind) {
            case kAnalogKick: s = m.analogKick.Process(t); break;
            case kSynthKick: s = m.synthKick.Process(t); break;
            case kAnalogSnare: s = m.analogSnare.Process(t); break;
            case kSynthSnare: s = m.synthSnare.Process(t); break;
            case kHat: s = m.hat.Process(t); break;
            case kString: s = m.string.Process(t); break;
            case kModal: s = m.modal.Process(t); break;
            case kDrip: s = m.drip.Process(t); break;
            case kFm: s = m.fm.Process() * m.env.Process(m.gate) * 0.5f * m.velocity; break;
            case kFormant: s = m.formant.Process() * m.env.Process(m.gate) * 0.4f * m.velocity; break;
            case kVosim: s = m.vosim.Process() * m.env.Process(m.gate) * 0.4f * m.velocity; break;
            case kParticle: s = m.particle.Process() * m.env.Process(m.gate) * 0.5f * m.velocity; break;
            default: s = instrument ? static_cast<float>(instrument->tick()) : 0.0f; break;
        }
        if (!std::isfinite(s)) s = 0.0f;
        s = std::clamp(s, -2.0f, 2.0f);
        out[i] += s * p.gain * 0.8f;
        peak = std::max(peak, std::fabs(s));
        if (m.gate && --m.gateLeft <= 0) release();
    }
    age += frames;
    if (!m.gate && peak < 1e-4f) {
        if (++m.quiet > 80) active = false;
    } else {
        m.quiet = 0;
    }
    if (age > static_cast<int64_t>(m.sampleRate) * 30) active = false;
}

}  // namespace daw
