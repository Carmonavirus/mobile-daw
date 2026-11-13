// Platform-independent audio core: synth voices, sequencer and mixer.
// Kept free of Android/Oboe so it can be unit-tested on a desktop (see engine-test/).
#pragma once

#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include "SoundEngines.h"
#include "Synth.h"
#include "fx/Mixer.h"
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

// An audio sample, e.g. one hit from a drum kit or an imported loop. Mono samples leave `right`
// empty; stereo samples keep the left channel in `frames` and the right one in `right`.
struct SampleData {
    std::vector<float> frames;
    std::vector<float> right;
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
    // Amplitude envelope (FL sampler-style): delay is silence before the attack, attack, hold and
    // decay are times in seconds, sustain is the 0..1 level the decay falls to, release fades the
    // tail before the end edge, tension (-1..1) curves the attack/decay/release segments.
    std::atomic<float> envDelay{0.0f};
    std::atomic<float> attack{0.0f};
    std::atomic<float> hold{0.0f};
    std::atomic<float> decay{0.0f};
    std::atomic<float> sustain{1.0f};
    std::atomic<float> release{0.0f};
    std::atomic<float> tension{0.0f};
    std::atomic<int> pitchSemis{0};     // -12..12 transpose for sample playback
    std::atomic<bool> reverse{false};   // play the sample backwards
    // Audio clips on the playlist: when playback starts or seeks into the middle of one of
    // this channel's notes, the sample starts from that point instead of staying silent.
    std::atomic<bool> resumeMidway{false};
    // Mixer track (fx::Insert) this channel plays through, or -1 for straight to the master.
    std::atomic<int> insert{-1};
};

// Per-block snapshot of a sample channel's trim and envelope, in sample frames (times) / 0..1 (level).
struct SampleShape {
    double start = 0.0;
    double end = 0.0;
    double delay = 0.0;
    double attack = 0.0;
    double hold = 0.0;
    double decay = 0.0;
    double sustain = 1.0;
    double release = 0.0;
    double tension = 0.0;
};

// Converts a sample from srcRate to dstRate with a Kaiser-windowed sinc filter (no audible
// treble loss or aliasing, unlike linear interpolation).
std::vector<float> resampleSinc(const std::vector<float>& in, double srcRate, double dstRate);

// Master output stage: untouched (unity gain, no colouring) up to 0.9, then a smooth knee that
// never goes past full scale. Replaces an always-on tanh that squashed and coloured every mix.
inline float outputClip(float x) {
    constexpr float kKnee = 0.9f;
    const float a = std::fabs(x);
    if (a <= kKnee) return x;
    const float y = kKnee + (1.0f - kKnee) * std::tanh((a - kKnee) / (1.0f - kKnee));
    return x < 0.0f ? -y : y;
}

class Core {
public:
    Core();
    ~Core();

    // ---- control thread (UI) ----
    void setSampleRate(int sampleRate);
    void setSequence(std::vector<NoteEvent> events, int64_t loopTicks);
    void setPlaying(bool playing);
    // "Stop all sound": stops playback and silences every voice, synth tail, audio clip and
    // audition within a few milliseconds.
    void panic() {
        setPlaying(false);
        panic_.store(true);
    }
    // Moves the playhead. While stopped, the next play starts there instead of at 0.
    void seek(int64_t tick);
    void setBpm(float bpm) { bpm_.store(bpm); }
    void setChannelCount(int count);
    void setChannel(int index, int instrument, float volume, float pan, bool mute, bool solo);
    void setChannelSampler(int index, bool cutItself, float trimStart, float trimEnd, float envDelay, float attack,
                           float hold, float decay, float sustain, float release, float tension, int pitchSemis,
                           bool reverse);
    void setMasterVolume(float v) { master_.store(v); }

    // ---- metronome (control thread)
    // The click is generated here, on the exact sample of each beat, accented on beat 1 of the bar.
    // It is mixed after the master, so it never reaches the meters, the scope or an offline bounce
    // (renderMix), only what you hear from render().
    void setMetronome(bool on) { metronome_.store(on); }
    bool metronome() const { return metronome_.load(); }
    void setBeatsPerBar(int beats) { beatsPerBar_.store(beats < 1 ? 1 : (beats > 16 ? 16 : beats)); }
    // Counts in `beats` clicks at the current tempo (first one accented, whether or not the metronome
    // is on), then starts playback from the start tick on the sample the next beat would fall on.
    // setPlaying(false) or panic() cancels it.
    void startCountIn(int beats) { countInRequest_.store(beats < 1 ? 1 : (beats > 16 ? 16 : beats)); }
    // 1..beats while counting in (the beat just clicked), 0 otherwise.
    int countInBeat() const { return countInBeat_.load(); }
    // Marks a channel as an audio-clip lane (see ChannelParams::resumeMidway).
    void setChannelClip(int index, bool resumeMidway);

    // ---- mixer tracks (control thread)
    // Routes a channel to mixer track `insert` (0..fx::kMaxInserts-1), or -1 for the master.
    void setChannelInsert(int channel, int insert);
    void setInsertCount(int count) { insertCount_.store(count < 0 ? 0 : (count > fx::kMaxInserts ? fx::kMaxInserts : count)); }
    void setInsert(int index, float volume, float pan, bool mute);
    // Puts an effect (fx::EffectType) in a slot of a mixer track's chain.
    void setInsertEffect(int index, int slot, int type, bool bypass);
    void setEffectParam(int index, int slot, int param, float value);
    // Mixer track meters (0..1).
    void readInsertLevels(float* out, int count) const;
    // The newest `count` (<= fx::kSpectrumSize) post-effects samples of a mixer track, oldest first.
    void readInsertSignal(int index, float* out, int count) const;
    // Pitch corrector readout: out[0] = detected Hz, out[1] = corrected Hz (0 when silent).
    void readPitch(int index, int slot, float* out) const;
    void previewNote(int channel, int pitch, float velocity);
    // Press (on = true) and release a held preview note, e.g. from the synth editor's keyboard.
    void previewHold(int channel, int pitch, float velocity, bool on);
    // Sets one wavetable-synth parameter (see wavesynth::Param) for a channel.
    void setSynthParam(int channel, int param, float value);
    // Replaces the sample a channel plays (slot 0..kMaxChannels-1) or the audition sample
    // (kAuditionSlot). Pass empty frames to clear it.
    // Samples whose rate differs from the output rate are converted once here with a high-quality
    // windowed-sinc resampler, so plain playback is sample-exact instead of interpolated.
    void setSample(int slot, std::vector<float> frames, float sampleRate) { setSample(slot, std::move(frames), {}, sampleRate); }
    void setSample(int slot, std::vector<float> left, std::vector<float> right, float sampleRate);
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

    // SoundFont / Macro / Modeled / Multisample instruments (see SoundEngines.h).
    SoundEngines& engines() { return engines_; }

    // ---- audio thread ----
    // Live output: the mix plus the metronome / count-in click.
    void render(float* interleavedStereo, int frames);
    // The mix alone, without any click: what a bounce or export must use.
    void renderMix(float* interleavedStereo, int frames);

    // Largest block rendered in one go; render() splits bigger requests.
    static constexpr int kBlock = 256;

private:
    void renderBlock(float* interleavedStereo, int frames, bool withClick);
    void startClick(bool accent);
    float clickTick();
    void advanceTick(const Sequence* seq);
    // Returns the voice it started, or nullptr (wavetable channels, bad index).
    Voice* trigger(int channel, int pitch, float velocity, int64_t gateSamples);
    void releaseAll();
    // Fades every voice out over `seconds` (audio thread).
    void silenceAll(float seconds);
    void adoptSamples();
    void jumpTo(int64_t tick);
    void resumeLongSamples();
    // Returns the left (or mono) output; writes the right one to *right when given.
    float sampleTick(Voice& v, const SampleData* data, const SampleShape* shape = nullptr, float* right = nullptr);
    SampleShape shapeFor(int channel) const;

    int sampleRate_ = 48000;
    std::atomic<float> bpm_{120.0f};
    std::atomic<float> master_{0.7f};
    std::atomic<bool> playing_{false};
    std::atomic<bool> restart_{false};
    std::atomic<bool> panic_{false};
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

    SoundEngines engines_;
    // Mixer tracks and their per-block buses (audio thread).
    std::unique_ptr<std::array<fx::Insert, fx::kMaxInserts>> inserts_;
    std::atomic<int> insertCount_{0};
    std::array<std::array<float, kBlock>, fx::kMaxInserts> busL_{};
    std::array<std::array<float, kBlock>, fx::kMaxInserts> busR_{};

    // Metronome and count-in (audio thread, except the atomics).
    std::atomic<bool> metronome_{false};
    std::atomic<int> beatsPerBar_{4};
    std::atomic<int> countInRequest_{0};  // > 0: start a count-in of that many beats, -1: cancel
    std::atomic<int> countInBeat_{0};
    int countInBeats_ = 0;       // beats in the running count-in, 0 when not counting
    int countInDone_ = 0;        // beats already clicked
    double countInSamples_ = 0;  // samples since the count-in started
    bool clickPending_ = false;  // playback (re)started exactly on a beat
    double clickPhase_ = 0.0;
    double clickFreq_ = 0.0;
    float clickEnv_ = 0.0f;
    float clickGain_ = 0.0f;
    float clickDecay_ = 0.0f;
    std::array<float, kBlock> clickBuf_{};

    // Metering
    std::array<std::atomic<float>, kMaxChannels> levels_{};
    std::atomic<float> masterLevel_{0.0f};
    std::array<float, kScopeSize> scope_{};
    std::atomic<int> scopeWrite_{0};
};

}  // namespace daw
