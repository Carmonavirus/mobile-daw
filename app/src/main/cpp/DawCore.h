// Platform-independent audio core: synth voices, sequencer and mixer.
// Kept free of Android/Oboe so it can be unit-tested on a desktop (see engine-test/).
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <vector>

#include "Synth.h"
#include "wavesynth/WaveSynth.h"

namespace daw {

constexpr int kTicksPerBeat = 96;
constexpr int kTicksPerStep = kTicksPerBeat / 4;  // a 16th note
constexpr int kMaxChannels = 32;
constexpr int kVoicesPerChannel = 8;
constexpr int kScopeSize = 4096;

struct NoteEvent {
    int channel;
    int64_t start;   // ticks
    int32_t length;  // ticks
    int32_t pitch;   // MIDI note number
    float velocity;  // 0..1
};

// A mono audio sample, e.g. one hit from a drum kit.
struct SampleData {
    std::vector<float> frames;
    float sampleRate = 48000.0f;
};

// Sample slot used to audition sounds in the kit browser without touching any channel.
constexpr int kAuditionSlot = kMaxChannels;

// An immutable, start-sorted list of note events that loops every loopTicks.
struct Sequence {
    std::vector<NoteEvent> events;
    int64_t loopTicks = kTicksPerBeat * 4;
};

struct ChannelParams {
    std::atomic<int> instrument{0};
    std::atomic<float> volume{0.8f};
    std::atomic<float> pan{0.0f};  // -1..1
    std::atomic<bool> mute{false};
    std::atomic<bool> solo{false};
    // Sampler-style options (FL's channel settings): "cut itself" chokes the previous hit,
    // trim is a 0..1 fraction of the sample, attack/release are fade-in/out times in seconds.
    std::atomic<bool> cutItself{false};
    std::atomic<float> trimStart{0.0f};
    std::atomic<float> trimEnd{1.0f};
    // Amplitude envelope (FL sampler-style): attack, hold and decay are times in seconds,
    // sustain is the 0..1 level the decay falls to, release fades the tail before the end edge.
    std::atomic<float> attack{0.0f};
    std::atomic<float> hold{0.0f};
    std::atomic<float> decay{0.0f};
    std::atomic<float> sustain{1.0f};
    std::atomic<float> release{0.0f};
    std::atomic<int> pitchSemis{0};     // -12..12 transpose for sample playback
    std::atomic<bool> reverse{false};   // play the sample backwards
};

// Per-block snapshot of a sample channel's trim and envelope, in sample frames (times) / 0..1 (level).
struct SampleShape {
    double start = 0.0;
    double end = 0.0;
    double attack = 0.0;
    double hold = 0.0;
    double decay = 0.0;
    double sustain = 1.0;
    double release = 0.0;
};

class Core {
public:
    Core();
    ~Core();

    // ---- control thread (UI) ----
    void setSampleRate(int sampleRate);
    void setSequence(std::vector<NoteEvent> events, int64_t loopTicks);
    void setPlaying(bool playing);
    // Moves the playhead. While stopped, the next play starts there instead of at 0.
    void seek(int64_t tick);
    void setBpm(float bpm) { bpm_.store(bpm); }
    void setChannelCount(int count);
    void setChannel(int index, int instrument, float volume, float pan, bool mute, bool solo);
    void setChannelSampler(int index, bool cutItself, float trimStart, float trimEnd, float attack, float hold,
                           float decay, float sustain, float release, int pitchSemis, bool reverse);
    void setMasterVolume(float v) { master_.store(v); }
    void previewNote(int channel, int pitch, float velocity);
    // Press (on = true) and release a held preview note, e.g. from the synth editor's keyboard.
    void previewHold(int channel, int pitch, float velocity, bool on);
    // Sets one wavetable-synth parameter (see wavesynth::Param) for a channel.
    void setSynthParam(int channel, int param, float value);
    // Replaces the sample a channel plays (slot 0..kMaxChannels-1) or the audition sample
    // (kAuditionSlot). Pass empty frames to clear it.
    void setSample(int slot, std::vector<float> frames, float sampleRate);
    // Plays the audition sample once.
    void audition() { auditionTrigger_.store(true); }
    int64_t positionTicks() const { return position_.load(); }
    bool isPlaying() const { return playing_.load(); }
    void readLevels(float* out, int count) const;
    float masterLevel() const { return masterLevel_.load(); }
    // Fills `count` peak values (0..1) summarising the most recent output.
    void readScope(float* out, int count) const;
    // Frees a sequence the audio thread has finished with. Call regularly from the UI thread.
    void collectGarbage();

    // ---- audio thread ----
    void render(float* interleavedStereo, int frames);

private:
    void advanceTick(const Sequence* seq);
    void trigger(int channel, int pitch, float velocity, int64_t gateSamples);
    void releaseAll();
    void adoptSamples();
    void jumpTo(int64_t tick);
    float sampleTick(Voice& v, const SampleData* data, const SampleShape* shape = nullptr);
    SampleShape shapeFor(int channel) const;

    int sampleRate_ = 48000;
    std::atomic<float> bpm_{120.0f};
    std::atomic<float> master_{0.7f};
    std::atomic<bool> playing_{false};
    std::atomic<bool> restart_{false};
    std::atomic<int64_t> seek_{-1};       // pending seek while playing
    std::atomic<int64_t> startTick_{0};   // where the next play starts
    std::atomic<int> channelCount_{0};
    std::array<ChannelParams, kMaxChannels> params_;

    // Sequence handoff: UI publishes into pending_, audio thread adopts it and
    // hands the previous one back through retired_ for the UI to delete.
    std::atomic<Sequence*> pending_{nullptr};
    std::atomic<Sequence*> retired_{nullptr};
    Sequence* current_ = nullptr;

    // Transport state (audio thread only, except position_)
    bool wasPlaying_ = false;
    double tickFraction_ = 0.0;
    int64_t tick_ = 0;
    size_t eventIndex_ = 0;
    std::atomic<int64_t> position_{0};

    // Voices
    std::array<std::array<Voice, kVoicesPerChannel>, kMaxChannels> voices_{};

    // Wavetable synths, one per channel, and their parameters (written by the UI thread).
    std::array<wavesynth::Instrument, kMaxChannels> synths_{};
    std::array<std::array<std::atomic<float>, wavesynth::kParamCount>, kMaxChannels> synthParams_;

    // Preview notes: single-producer single-consumer ring.
    struct Preview { int channel; int pitch; float velocity; int hold; };  // hold: 0 tap, 1 press, -1 release
    void pushPreview(const Preview& p);
    static constexpr int kPreviewQueue = 64;
    std::array<Preview, kPreviewQueue> previews_{};
    std::atomic<int> previewWrite_{0};
    std::atomic<int> previewRead_{0};

    // Samples: same hand-off scheme as sequences, one slot per channel plus audition.
    static constexpr int kSampleSlots = kMaxChannels + 1;
    std::array<std::atomic<SampleData*>, kSampleSlots> pendingSample_{};
    std::array<std::atomic<SampleData*>, kSampleSlots> retiredSample_{};
    std::array<SampleData*, kSampleSlots> sample_{};
    Voice auditionVoice_{};
    std::atomic<bool> auditionTrigger_{false};

    // Metering
    std::array<std::atomic<float>, kMaxChannels> levels_{};
    std::atomic<float> masterLevel_{0.0f};
    std::array<float, kScopeSize> scope_{};
    std::atomic<int> scopeWrite_{0};
};

}  // namespace daw
