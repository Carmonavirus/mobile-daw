// Mixer tracks ("inserts"): channels can be routed to one, it runs an effects chain and
// feeds the master. Portable C++; the UI thread writes parameters through atomics and the
// audio thread snapshots them once per block.
#pragma once

#include <array>
#include <atomic>
#include <memory>

#include "KeyDetector.h"
#include "ParametricEq.h"
#include "PitchCorrector.h"
#include "SoftClipper.h"

namespace daw::fx {

constexpr int kMaxInserts = 8;
// The master's own effects chain lives at this insert index (after the mixer tracks).
constexpr int kMasterInsert = kMaxInserts;
// Every channel (sound) also has its own effects chain: channel c uses insert kChannelInsertBase + c.
constexpr int kChannelInsertBase = kMasterInsert + 1;
constexpr int kMaxChannelInserts = 32;
constexpr int kInsertTotal = kChannelInsertBase + kMaxChannelInserts;
constexpr int kMaxSlots = 8;
constexpr int kSlotParams = 64;
constexpr int kSpectrumSize = 2048;

enum EffectType : int { kFxNone = 0, kFxEq = 1, kFxPitch = 2, kFxClip = 3, kFxKey = 4 };

static_assert(kEqParamCount <= kSlotParams, "EQ parameters must fit a slot");
static_assert(kPitchParamCount <= kSlotParams, "pitch parameters must fit a slot");
static_assert(kClipParamCount <= kSlotParams, "clipper parameters must fit a slot");
static_assert(kKeyParamCount <= kSlotParams, "key detector parameters must fit a slot");

struct EffectSlot {
    std::atomic<int> type{kFxNone};
    std::atomic<bool> bypass{false};
    std::array<std::atomic<float>, kSlotParams> params{};

    // Audio thread only.
    int activeType = kFxNone;
    std::array<float, kSlotParams> snapshot{};
    ParametricEq eq;
    SoftClipper clip;
    // The two big effects are only made when a slot first uses them, so the many channel
    // chains stay small. Made by ensure() on the control thread before the type is stored,
    // and kept until the slot is destroyed, so the audio thread never sees one go away.
    std::atomic<PitchCorrector*> pitch{nullptr};
    std::atomic<KeyDetector*> key{nullptr};

    EffectSlot() = default;
    EffectSlot(const EffectSlot&) = delete;
    EffectSlot& operator=(const EffectSlot&) = delete;
    ~EffectSlot() {
        delete pitch.load();
        delete key.load();
    }

    // Control thread: call before storing `t` in `type`.
    void ensure(int t) {
        if (t == kFxPitch && pitch.load() == nullptr) pitch.store(new PitchCorrector());
        if (t == kFxKey && key.load() == nullptr) key.store(new KeyDetector());
    }

    void process(float* left, float* right, int frames, float sampleRate) {
        const int t = type.load();
        PitchCorrector* const pc = pitch.load();
        KeyDetector* const kd = key.load();
        if (t != activeType) {
            activeType = t;
            eq.reset();
            if (pc) pc->reset();
            clip.reset();
            if (kd) kd->reset();
        }
        if (t == kFxNone || bypass.load(std::memory_order_relaxed)) return;
        for (int i = 0; i < kSlotParams; ++i)
            snapshot[static_cast<size_t>(i)] = params[static_cast<size_t>(i)].load(std::memory_order_relaxed);
        if (t == kFxEq) {
            eq.prepare(sampleRate);
            eq.process(left, right, frames, snapshot.data());
        } else if (t == kFxPitch && pc) {
            pc->prepare(sampleRate);
            pc->process(left, right, frames, snapshot.data());
        } else if (t == kFxClip) {
            clip.prepare(sampleRate);
            clip.process(left, right, frames, snapshot.data());
        } else if (t == kFxKey && kd) {
            kd->prepare(sampleRate);
            kd->process(left, right, frames, snapshot.data());
        }
    }
};

struct Insert {
    std::atomic<float> volume{1.0f};
    std::atomic<float> pan{0.0f};
    std::atomic<bool> mute{false};
    std::array<EffectSlot, kMaxSlots> slots{};

    // Audio thread: true while any slot holds an effect (or still has to notice it was removed).
    bool anyEffect() const {
        for (const auto& s : slots)
            if (s.type.load(std::memory_order_relaxed) != kFxNone || s.activeType != kFxNone) return true;
        return false;
    }

    // Post-chain mono signal for the EQ's spectrum view.
    std::array<float, kSpectrumSize> spectrum{};
    std::atomic<int> spectrumWrite{0};
    std::atomic<float> level{0.0f};
};

}  // namespace daw::fx
