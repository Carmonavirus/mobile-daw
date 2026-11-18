// Note detection for the note viewer: finds the pitch of a sound over time (one voice or
// instrument at a time, like a singer, a bass line, a lead or an 808) and groups it into notes.
//
// The sound is mixed down to ~12 kHz and every ~10 ms a YIN pitch estimate is made (cumulative
// normalised difference, parabolic refinement). Frames that are quiet or have no clear period
// count as unpitched. The pitch line is cleaned of single-frame octave jumps and medians, then
// cut into notes wherever the pitch moves by more than ~0.7 semitones and stays there, where the
// sound stops, or where a new attack starts on the same pitch. Portable C++, runs offline.
#pragma once

#include <cstddef>
#include <vector>

namespace daw::fx {

struct DetectedNote {
    float start = 0;  // seconds
    float end = 0;    // seconds
    float midi = 0;   // median pitch, fractional (69 = A4)
    float level = 0;  // 0..1, loudest note = 1
};

struct NoteAnalysis {
    float hopSeconds = 0;
    std::vector<float> pitch;  // per frame: MIDI pitch, 0 = unpitched
    std::vector<float> level;  // per frame: 0..1, loudest frame = 1
    std::vector<DetectedNote> notes;
};

NoteAnalysis detectNotes(const float* mono, size_t frames, float sampleRate);

// Flat layout for the app: [hopSeconds, frameCount F, noteCount N, F pitches, F levels,
// N x (start, end, midi, level)].
std::vector<float> detectNotesFlat(const float* mono, size_t frames, float sampleRate);

}  // namespace daw::fx
