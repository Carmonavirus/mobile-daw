// Extra instrument engines built on free, permissively licensed libraries:
//   - SoundFont (.sf2/.sf3) player: TinySoundFont (MIT)
//   - Macro oscillator: synthesis models from Plaits (MIT, Emilie Gillet)
//   - Modeled: drum and voice models from DaisySP (MIT) and physical models from STK (MIT-style)
//   - Multisample: key/velocity-mapped samples, used for SFZ instruments
// See THIRD_PARTY_NOTICES.md. Everything here is platform independent so engine-test can run it.
//
// Core asks for one sample per channel slot at a time (tick). Internally each slot renders in small
// blocks of kEngineBlock frames, so block-based engines (TinySoundFont, Plaits) run efficiently;
// a note starts at the next block boundary, at most kEngineBlock samples (0.25 ms) late.
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

struct tsf;

namespace daw {

enum ExtraInstrument : int {
    kSoundFont = 9,
    kMacro = 10,
    kModeled = 11,
    kMultiSample = 12,
};

constexpr int kEngineChannels = 32;                  // matches kMaxChannels
constexpr int kEngineAuditionSlot = kEngineChannels; // library preview slot
constexpr int kEngineSlots = kEngineChannels + 1;
constexpr int kEngineBlock = 12;

// One sample zone of a multisampled instrument (an SFZ <region>).
struct Zone {
    std::vector<float> frames;
    float sampleRate = 44100.0f;
    int loKey = 0, hiKey = 127, root = 60;
    int loVel = 0, hiVel = 127;
    float tuneCents = 0.0f;
    float gain = 1.0f;
    bool loop = false;
    int loopStart = 0, loopEnd = 0;
};

struct MultiSampleSet {
    std::vector<Zone> zones;
    float release = 0.25f;  // seconds
};

class MacroVoice;
class ModelVoice;

class SoundEngines {
public:
    SoundEngines();
    ~SoundEngines();

    static bool handles(int instrument) { return instrument >= kSoundFont && instrument <= kMultiSample; }

    // Built-in preset tables for kMacro and kModeled ("Name|group|drum" strings, drum is 0/1).
    static int presetCount(int instrument);
    static const char* presetInfo(int instrument, int index);

    // ---- control thread ----
    void setSampleRate(int sampleRate);
    // Loads a SoundFont from memory. Returns a bank id (>= 0) or -1 if it could not be read.
    int loadSoundFont(const void* data, int size);
    int soundFontPresetCount(int bank) const;
    // "bank|program|name" for a preset index.
    std::vector<int> soundFontPresetIds(int bank, int index) const;
    const char* soundFontPresetName(int bank, int index) const;
    // Instrument, preset/model index and (for SoundFonts) the bank a slot plays.
    void setChannel(int slot, int instrument, int variant, int bank);
    // A SoundFont drum hit picked from a kit: every note plays [key], repitched by the note's
    // distance from [key] in semitones, like a sample. -1 plays notes as they are (whole kit).
    void setDrumKey(int slot, int key);
    // UI thread: one hit of [key] from SoundFont [bank] preset [variant], recorded at [sampleRate]
    // until it dies away (at most a few seconds), silent tail removed. Empty if it can't play.
    std::vector<float> renderHit(int bank, int variant, int key, float sampleRate) const;
    // Takes ownership. Pass nullptr to clear.
    void setMultiSample(int slot, MultiSampleSet* set);
    // Plays a note on the audition slot with the given instrument (library preview).
    void audition(int instrument, int variant, int bank, int pitch, float velocity);
    // Silences the audition slot and drops queued previews (the library's stop button).
    void stopAudition() { stopAudition_.store(true, std::memory_order_release); }
    void collectGarbage();

    // ---- audio thread ----
    void noteOn(int slot, int pitch, float velocity, int64_t gateSamples);
    void releaseAll();
    // Next output sample of a slot; call once per frame for every slot in use.
    float tick(int slot);
    // Next audition sample; call once per frame.
    float tickAudition();

private:
    struct NoteOff { int key; int64_t remaining; };
    struct MsVoice {
        bool active = false;
        int zone = 0;
        int pitch = 0;
        double pos = 0.0, rate = 1.0;
        float gain = 1.0f, env = 1.0f;
        bool releasing = false;
        int64_t gate = 0;
        int64_t age = 0;
    };
    struct Slot {
        std::atomic<int> instrument{-1};
        std::atomic<int> variant{0};
        std::atomic<int> drumKey{-1};
        int nextFontChannel = 0;  // tsf channel for the next note (audio thread)
        int uiBank = -1;  // bank behind the published tsf copy (UI thread only)
        std::atomic<tsf*> pendingTsf{nullptr};
        std::atomic<tsf*> retiredTsf{nullptr};
        tsf* font = nullptr;
        std::atomic<MultiSampleSet*> pendingMs{nullptr};
        std::atomic<MultiSampleSet*> retiredMs{nullptr};
        MultiSampleSet* ms = nullptr;
        std::array<NoteOff, 64> offs{};
        int offCount = 0;
        std::array<MsVoice, 12> msVoices{};
        float buf[kEngineBlock] = {};
        int pos = kEngineBlock;
    };

    void adopt(Slot& s);
    void renderBlock(int slotIndex);
    void scheduleOff(Slot& s, int key, int64_t gate);
    void renderMultiSample(Slot& s, float* out);

    int sampleRate_ = 48000;
    std::array<Slot, kEngineSlots> slots_;
    std::vector<tsf*> banks_;  // control threads only, guarded by banksLock_
    mutable std::mutex banksLock_;
    std::vector<std::unique_ptr<MacroVoice>> macro_;
    std::vector<std::unique_ptr<ModelVoice>> model_;

    // Audition requests: single-producer single-consumer ring.
    struct Audition { int pitch; float velocity; };
    static constexpr int kQueue = 32;
    std::array<Audition, kQueue> queue_{};
    std::atomic<int> queueWrite_{0};
    std::atomic<int> queueRead_{0};
    std::atomic<bool> stopAudition_{false};
};

}  // namespace daw
