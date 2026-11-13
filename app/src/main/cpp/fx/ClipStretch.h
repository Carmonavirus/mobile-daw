// Offline pitch shifting for audio clips that keeps their length (and so their tempo).
//
// Audio clips normally change pitch by playing faster or slower (resampling), like a turntable.
// These modes render a new copy of the clip at the new pitch but the original length, which
// the engine then plays at normal speed:
//   Stretch: WSOLA time-stretch by the pitch ratio, then resample back to the original length.
//            Works on anything (loops, chords, drums, voices); the tone moves with the pitch.
//   Vocal:   PSOLA from PitchCorrector (pitch correction off, transpose only). Keeps a single
//            voice's own tone (formants), so a lowered voice doesn't turn into a "monster".
// Portable C++, no platform headers.
#pragma once

#include <cstddef>
#include <vector>

namespace daw::fx {

enum ClipStretchMode : int {
    kClipResample = 0,  // nothing to render: the engine resamples while it plays
    kClipStretch = 1,
    kClipVocal = 2,
};

// Returns `n` samples: `in` shifted by `semis` (-24..24) at the same length. Mono.
std::vector<float> shiftKeepLength(const float* in, size_t n, float sampleRate, float semis, int mode);

// Building blocks, exposed for tests.
std::vector<float> wsolaStretch(const float* in, size_t n, float sampleRate, double factor);
std::vector<float> resampleTo(const std::vector<float>& in, double step, size_t outLen);

}  // namespace daw::fx
