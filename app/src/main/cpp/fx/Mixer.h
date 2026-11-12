// Mixer tracks ("inserts"): channels can be routed to one, it runs an effects chain and
// feeds the master. Portable C++; the UI thread writes parameters through atomics and the
// audio thread snapshots them once per block.
#pragma once

#include <array>
#include <atomic>
#include <memory>

#include "ParametricEq.h"
#include "PitchCorrector.h"

namespace daw::fx {

constexpr int kMaxInserts = 8;
constexpr int kMaxSlots = 8;
constexpr int kSlotParams = 64;
constexpr int kSpectrumSize = 2048;

enum EffectType : int { kFxNone = 0, kFxEq = 1, kFxPitch = 2 };

static_assert(kEqParamCount <= kSlotParams, "EQ parameters must fit a slot");
static_assert(kPitchParamCount <= kSlotParams, "pitch parameters must fit a slot");

struct EffectSlot {
    std::atomic<int> type{kFxNone};
    std::atomic<bool> bypass{false};
    std::array<std::atomic<float>, kSlotParams> params{};

    // Audio thread only.
    int activeType = kFxNone;
    std::array<float, kSlotParams> snapshot{};
    ParametricEq eq;
    PitchCorrector pitch;

    void process(float* left, float* right, int frames, float sampleRate) {
        const int t = type.load(std::memory_order_relaxed);
        if (t != activeType) {
            activeType = t;
            eq.reset();
            pitch.reset();
        }
        if (t == kFxNone || bypass.load(std::memory_order_relaxed)) return;
        for (int i = 0; i < kSlotParams; ++i)
            snapshot[static_cast<size_t>(i)] = params[static_cast<size_t>(i)].load(std::memory_order_relaxed);
        if (t == kFxEq) {
            eq.prepare(sampleRate);
            eq.process(left, right, frames, snapshot.data());
        } else if (t == kFxPitch) {
            pitch.prepare(sampleRate);
            pitch.process(left, right, frames, snapshot.data());
        }
    }
};

struct Insert {
    std::atomic<float> volume{1.0f};
    std::atomic<float> pan{0.0f};
    std::atomic<bool> mute{false};
    std::array<EffectSlot, kMaxSlots> slots{};

    // Post-chain mono signal for the EQ's spectrum view.
    std::array<float, kSpectrumSize> spectrum{};
    std::atomic<int> spectrumWrite{0};
    std::atomic<float> level{0.0f};
};

}  // namespace daw::fx
