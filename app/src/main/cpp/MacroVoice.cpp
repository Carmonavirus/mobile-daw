// Macro instrument voice built from Plaits' synthesis engines (MIT licence, Emilie Gillet).
// The voice wrapper, presets and gate handling are ours, modelled on plaits/dsp/voice.cc.
//
// Left out on purpose:
//  - the six-op FM engines: their patch banks are DX7 cartridge dumps (incl. Yamaha factory ROMs);
//  - the speech engine: its word bank is LPC data extracted from Texas Instruments ROMs.
// Both would bring in third-party data whose licence we can't confirm.
#include "MacroVoice.h"

#include <algorithm>
#include <cmath>
#include <iterator>

#include "plaits/dsp/dsp.h"
#include "plaits/dsp/envelope.h"
#include "plaits/dsp/fx/low_pass_gate.h"
#include "plaits/dsp/engine/additive_engine.h"
#include "plaits/dsp/engine/bass_drum_engine.h"
#include "plaits/dsp/engine/chord_engine.h"
#include "plaits/dsp/engine/fm_engine.h"
#include "plaits/dsp/engine/grain_engine.h"
#include "plaits/dsp/engine/hi_hat_engine.h"
#include "plaits/dsp/engine/modal_engine.h"
#include "plaits/dsp/engine/noise_engine.h"
#include "plaits/dsp/engine/particle_engine.h"
#include "plaits/dsp/engine/snare_drum_engine.h"
#include "plaits/dsp/engine/string_engine.h"
#include "plaits/dsp/engine/swarm_engine.h"
#include "plaits/dsp/engine/virtual_analog_engine.h"
#include "plaits/dsp/engine/waveshaping_engine.h"
#include "plaits/dsp/engine/wavetable_engine.h"
#include "plaits/dsp/engine2/chiptune_engine.h"
#include "plaits/dsp/engine2/phase_distortion_engine.h"
#include "plaits/dsp/engine2/string_machine_engine.h"
#include "plaits/dsp/engine2/virtual_analog_vcf_engine.h"
#include "plaits/dsp/engine2/wave_terrain_engine.h"
#include "stmlib/dsp/limiter.h"
#include "stmlib/dsp/units.h"
#include "stmlib/utils/buffer_allocator.h"

namespace daw {

namespace {

enum Model {
    kVaVcf, kPhaseDistortion, kWaveTerrain, kStringMachine, kChiptune,
    kVirtualAnalog, kWaveshaping, kFm, kGrain, kAdditive, kWavetable, kChord,
    kSwarm, kNoise, kParticle, kString, kModal, kBassDrum, kSnareDrum, kHiHat,
    kModelCount,
};

struct Preset {
    const char* info;
    int model;
    float harmonics, timbre, morph, decay, colour;
    bool aux;
    float gain = 1.0f;
};

// Names are ours; the sound comes from the model and its three macro controls.
const Preset kPresets[] = {
    {"Analog lead|Leads|0|72", kVirtualAnalog, 0.55f, 0.55f, 0.45f, 0.5f, 0.5f, false},
    {"Filter synth|Leads|0|72", kVaVcf, 0.6f, 0.5f, 0.4f, 0.5f, 0.4f, false},
    {"Phase lead|Leads|0|72", kPhaseDistortion, 0.4f, 0.55f, 0.5f, 0.5f, 0.5f, false},
    {"Folded lead|Leads|0|72", kWaveshaping, 0.3f, 0.45f, 0.5f, 0.5f, 0.5f, false},
    {"Chiptune|Leads|0|72", kChiptune, 0.0f, 0.4f, 0.5f, 0.4f, 0.5f, false, 1.0f},
    {"FM keys|Keys|0|60", kFm, 0.3f, 0.35f, 0.25f, 0.45f, 0.5f, false},
    {"Organ|Keys|0|60", kAdditive, 0.35f, 0.5f, 0.6f, 0.6f, 0.6f, false},
    {"Wave bank|Keys|0|60", kWavetable, 0.25f, 0.5f, 0.5f, 0.5f, 0.5f, false},
    {"Chords|Pads|0|60", kChord, 0.3f, 0.45f, 0.4f, 0.7f, 0.5f, false},
    {"String machine|Pads|0|60", kStringMachine, 0.3f, 0.5f, 0.5f, 0.75f, 0.6f, false},
    {"Swarm|Pads|0|60", kSwarm, 0.4f, 0.5f, 0.5f, 0.75f, 0.6f, false},
    {"Wave terrain|Textures|0|60", kWaveTerrain, 0.5f, 0.5f, 0.5f, 0.6f, 0.5f, false},
    {"Formant|Textures|0|60", kGrain, 0.4f, 0.5f, 0.5f, 0.5f, 0.5f, false},
    {"Filtered noise|Textures|0|72", kNoise, 0.5f, 0.6f, 0.6f, 0.4f, 0.5f, false},
    {"Particles|Textures|0|72", kParticle, 0.5f, 0.5f, 0.6f, 0.6f, 0.5f, false, 0.6f},
    {"Plucked string|Plucks|0|60", kString, 0.3f, 0.5f, 0.5f, 0.5f, 0.5f, false, 4.0f},
    {"Mallet|Plucks|0|72", kModal, 0.35f, 0.4f, 0.55f, 0.5f, 0.5f, false, 0.6f},
    {"Bell|Plucks|0|72", kModal, 0.85f, 0.75f, 0.7f, 0.5f, 0.5f, false, 0.7f},
    {"Sub bass|Bass|0|36", kVirtualAnalog, 0.0f, 0.2f, 0.15f, 0.45f, 0.3f, false},
    {"FM bass|Bass|0|36", kFm, 0.15f, 0.35f, 0.2f, 0.4f, 0.3f, false},
    {"Analog kick|Drums|1|36", kBassDrum, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, false},
    {"808 kick|Drums|1|33", kBassDrum, 0.3f, 0.3f, 0.75f, 0.5f, 0.5f, true},
    {"Analog snare|Drums|1|50", kSnareDrum, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, false},
    {"Synth snare|Drums|1|50", kSnareDrum, 0.5f, 0.6f, 0.4f, 0.5f, 0.5f, true},
    {"Metal hat|Drums|1|72", kHiHat, 0.5f, 0.6f, 0.2f, 0.5f, 0.5f, false},
    {"Ring hat|Drums|1|72", kHiHat, 0.5f, 0.6f, 0.25f, 0.5f, 0.5f, true},
    {"Open hat|Drums|1|72", kHiHat, 0.5f, 0.55f, 0.7f, 0.5f, 0.5f, false},
};
constexpr int kPresetCount = sizeof(kPresets) / sizeof(kPresets[0]);

}  // namespace

struct MacroVoice::Impl {
    plaits::VirtualAnalogVCFEngine vaVcf;
    plaits::PhaseDistortionEngine phaseDistortion;
    plaits::WaveTerrainEngine waveTerrain;
    plaits::StringMachineEngine stringMachine;
    plaits::ChiptuneEngine chiptune;
    plaits::VirtualAnalogEngine virtualAnalog;
    plaits::WaveshapingEngine waveshaping;
    plaits::FMEngine fm;
    plaits::GrainEngine grain;
    plaits::AdditiveEngine additive;
    plaits::WavetableEngine wavetable;
    plaits::ChordEngine chord;
    plaits::SwarmEngine swarm;
    plaits::NoiseEngine noise;
    plaits::ParticleEngine particle;
    plaits::StringEngine string;
    plaits::ModalEngine modal;
    plaits::BassDrumEngine bassDrum;
    plaits::SnareDrumEngine snareDrum;
    plaits::HiHatEngine hiHat;

    plaits::EngineRegistry<kModelCount> engines;
    char ram[16384];

    plaits::DecayEnvelope decayEnvelope;
    plaits::LPGEnvelope lpgEnvelope;
    plaits::LowPassGate lpg;
    stmlib::Limiter limiter;

    float out[plaits::kMaxBlockSize];
    float aux[plaits::kMaxBlockSize];

    int current = -1;
    int preset = 0;
    float velocity = 1.0f;
    bool gate = false;
    bool rising = false;
    int64_t gateLeft = 0;
    int quietBlocks = 0;
    float noteOffset = 0.0f;

    Impl() {
        engines.Init();
        engines.RegisterInstance(&vaVcf, false, 1.0f, 1.0f);
        engines.RegisterInstance(&phaseDistortion, false, 0.7f, 0.7f);
        engines.RegisterInstance(&waveTerrain, false, 0.7f, 0.7f);
        engines.RegisterInstance(&stringMachine, false, 0.8f, 0.8f);
        engines.RegisterInstance(&chiptune, false, 0.5f, 0.5f);
        engines.RegisterInstance(&virtualAnalog, false, 0.8f, 0.8f);
        engines.RegisterInstance(&waveshaping, false, 0.7f, 0.6f);
        engines.RegisterInstance(&fm, false, 0.6f, 0.6f);
        engines.RegisterInstance(&grain, false, 0.7f, 0.6f);
        engines.RegisterInstance(&additive, false, 0.8f, 0.8f);
        engines.RegisterInstance(&wavetable, false, 0.6f, 0.6f);
        engines.RegisterInstance(&chord, false, 0.8f, 0.8f);
        engines.RegisterInstance(&swarm, false, -3.0f, 1.0f);
        engines.RegisterInstance(&noise, false, -1.0f, -1.0f);
        engines.RegisterInstance(&particle, false, -2.0f, 1.0f);
        engines.RegisterInstance(&string, true, -1.0f, 0.8f);
        engines.RegisterInstance(&modal, true, -1.0f, 0.8f);
        engines.RegisterInstance(&bassDrum, true, 0.8f, 0.8f);
        engines.RegisterInstance(&snareDrum, true, 0.8f, 0.8f);
        engines.RegisterInstance(&hiHat, true, 0.8f, 0.8f);
        stmlib::BufferAllocator allocator;
        for (int i = 0; i < engines.size(); ++i) {
            // All engines share the same RAM: only one runs at a time in a voice.
            allocator.Init(ram, sizeof(ram));
            engines.get(i)->Init(&allocator);
        }
        // No user data (that's for the hardware's flash); engines fall back to their built-in tables.
        for (int i = 0; i < engines.size(); ++i) engines.get(i)->LoadUserData(nullptr);
        chiptune.set_envelope_shape(plaits::ChiptuneEngine::NO_ENVELOPE);
        decayEnvelope.Init();
        lpgEnvelope.Init();
        lpg.Init();
        limiter.Init();
        std::fill(std::begin(out), std::end(out), 0.0f);
        std::fill(std::begin(aux), std::end(aux), 0.0f);
    }
};

MacroVoice::MacroVoice() : impl_(new Impl()) { setSampleRate(48000); }
MacroVoice::~MacroVoice() = default;

int MacroVoice::presetCount() { return kPresetCount; }

const char* MacroVoice::presetInfo(int index) {
    return index >= 0 && index < kPresetCount ? kPresets[index].info : nullptr;
}

void MacroVoice::setSampleRate(int sampleRate) {
    // Plaits computes pitches for its hardware's 47.87 kHz clock; correct for ours.
    impl_->noteOffset = 12.0f * std::log2(plaits::kCorrectedSampleRate / static_cast<float>(sampleRate > 0 ? sampleRate : 48000));
}

void MacroVoice::start(int slotIndex, int preset, int note, float velocity, int64_t gateSamples) {
    auto& m = *impl_;
    active = true;
    slot = slotIndex;
    pitch = note;
    age = 0;
    m.preset = std::clamp(preset, 0, kPresetCount - 1);
    m.velocity = std::clamp(velocity, 0.0f, 1.0f);
    m.gate = true;
    m.rising = true;
    m.gateLeft = std::max<int64_t>(gateSamples, 1);
    m.quietBlocks = 0;
    const int model = kPresets[m.preset].model;
    if (model != m.current) {
        m.engines.get(model)->LoadUserData(nullptr);
        m.engines.get(model)->Reset();
        m.limiter.Init();
        m.lpgEnvelope.Init();
        m.current = model;
    }
}

void MacroVoice::release() { impl_->gate = false; }

void MacroVoice::render(float* dst, int frames) {
    auto& m = *impl_;
    if (!active) return;
    const size_t size = static_cast<size_t>(std::clamp(frames, 1, static_cast<int>(plaits::kMaxBlockSize)));
    const Preset& pr = kPresets[m.preset];
    plaits::Engine* e = m.engines.get(pr.model);

    if (m.rising) {
        m.decayEnvelope.Trigger();
    }
    plaits::EngineParameters p;
    p.trigger = m.gate ? (plaits::TRIGGER_HIGH | (m.rising ? plaits::TRIGGER_RISING_EDGE : 0)) : plaits::TRIGGER_LOW;
    m.rising = false;

    const float shortDecay = (200.0f * plaits::kBlockSize) / plaits::kSampleRate * stmlib::SemitonesToRatio(-96.0f * pr.decay);
    m.decayEnvelope.Process(shortDecay * 2.0f);
    p.accent = 0.4f + 0.6f * m.velocity;
    p.note = static_cast<float>(pitch) + m.noteOffset;
    p.harmonics = pr.harmonics;
    p.timbre = pr.timbre;
    p.morph = pr.morph;

    bool alreadyEnveloped = e->post_processing_settings.already_enveloped;
    e->Render(p, m.out, m.aux, size, &alreadyEnveloped);
    // Strings, modal and drums decay by themselves; everything else follows our gate.
    alreadyEnveloped = pr.model >= kString;
    float* src = pr.aux ? m.aux : m.out;
    float gain = pr.aux ? e->post_processing_settings.aux_gain : e->post_processing_settings.out_gain;
    if (gain < 0.0f) {
        m.limiter.Process(-gain, src, size);
        gain = 1.0f;
    }
    if (!alreadyEnveloped) {
        // A low-pass gate that follows the held note, then decays with its tail.
        const float hf = pr.colour;
        const float tail = (20.0f * plaits::kBlockSize) / plaits::kSampleRate *
                               stmlib::SemitonesToRatio(-72.0f * pr.decay + 12.0f * hf) - shortDecay;
        m.lpgEnvelope.ProcessLP(m.gate ? m.velocity : 0.0f, shortDecay, tail, hf);
        m.lpg.Process(gain * m.lpgEnvelope.gain(), m.lpgEnvelope.frequency(), m.lpgEnvelope.hf_bleed(), src, size);
    } else {
        const float g = gain * (0.5f + 0.5f * m.velocity);
        for (size_t i = 0; i < size; ++i) src[i] *= g;
    }

    float peak = 0.0f;
    for (size_t i = 0; i < size; ++i) {
        const float s = std::isfinite(src[i]) ? src[i] : 0.0f;
        dst[i] += s * pr.gain * 1.4f;
        peak = std::max(peak, std::fabs(s));
    }

    age += static_cast<int64_t>(size);
    if (m.gate) {
        m.gateLeft -= static_cast<int64_t>(size);
        if (m.gateLeft <= 0) m.gate = false;
    }
    // Free the voice once the note is over and it has gone quiet.
    if (!m.gate && peak < 1e-4f) {
        if (++m.quietBlocks > 40) active = false;
    } else {
        m.quietBlocks = 0;
    }
    if (age > 48000 * 30) active = false;  // safety: nothing rings for 30 s
}

}  // namespace daw
