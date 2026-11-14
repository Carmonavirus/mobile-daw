// Tests for the note viewer's note detection (fx/NoteDetect): melodies of rich tones, legato
// changes, vibrato, repeated notes, silence, and the time a five-minute take takes.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <vector>

#include "fx/NoteDetect.h"

using namespace daw::fx;

static int failures = 0;
static void check(bool ok, const char* what, double value) {
    std::printf("%s %-58s %.3f\n", ok ? "ok  " : "FAIL", what, value);
    if (!ok) ++failures;
}

constexpr float kSr = 48000.0f;
constexpr double kPi = 3.14159265358979;

struct Tone { double start, len, midi, vibrato; };

// Continuous phase so legato changes don't click; each tone has its own attack and release.
static std::vector<float> render(const std::vector<Tone>& tones, double seconds, int harmonics = 6) {
    std::vector<float> out(static_cast<size_t>(seconds * kSr), 0.0f);
    for (const auto& t : tones) {
        double phase = 0;
        const size_t a = static_cast<size_t>(t.start * kSr), len = static_cast<size_t>(t.len * kSr);
        for (size_t i = 0; i < len && a + i < out.size(); ++i) {
            const double time = i / double(kSr);
            const double midi = t.midi + t.vibrato * std::sin(2 * kPi * 5.5 * time);
            phase += 440.0 * std::pow(2.0, (midi - 69) / 12.0) / kSr;
            const double env = std::min(1.0, time * 100.0) * std::min(1.0, (t.len - time) * 50.0);
            double v = 0;
            for (int h = 1; h <= harmonics; ++h) v += std::sin(2 * kPi * phase * h) / h;
            out[a + i] += static_cast<float>(0.3 * env * v);
        }
    }
    return out;
}

static void expectNotes(const char* what, const NoteAnalysis& a, const std::vector<std::pair<int, double>>& want) {
    bool ok = a.notes.size() == want.size();
    for (size_t i = 0; ok && i < want.size(); ++i)
        ok = std::lround(a.notes[i].midi) == want[i].first && std::fabs(a.notes[i].start - want[i].second) < 0.05;
    std::printf("     %zu notes:", a.notes.size());
    for (const auto& n : a.notes) std::printf(" %.2f@%.2f-%.2f", n.midi, n.start, n.end);
    std::printf("\n");
    check(ok, what, double(a.notes.size()));
}

int main() {
    // A minor melody with gaps: A3 C4 E4 A4 G4 (bass-ish to vocal range).
    {
        std::vector<Tone> t{{0.1, 0.4, 57, 0}, {0.6, 0.4, 60, 0}, {1.1, 0.4, 64, 0}, {1.6, 0.4, 69, 0}, {2.1, 0.6, 67, 0}};
        auto a = detectNotes(render(t, 3.0).data(), size_t(3.0 * kSr), kSr);
        expectNotes("notes: A3 C4 E4 A4 G4 with gaps", a, {{57, 0.1}, {60, 0.6}, {64, 1.1}, {69, 1.6}, {67, 2.1}});
        double err = 0;
        for (const auto& n : a.notes) err = std::max(err, double(std::fabs(n.midi - std::round(n.midi))));
        check(err < 0.1, "notes: pitch within 10 cents", err * 100);
    }
    // Legato (no gap) steps on one continuous tone: D4 -> F4 -> A4.
    {
        std::vector<float> s(size_t(2.0 * kSr));
        double phase = 0;
        for (size_t i = 0; i < s.size(); ++i) {
            const double t = i / double(kSr);
            const double midi = t < 0.6 ? 62 : t < 1.2 ? 65 : 69;
            phase += 440.0 * std::pow(2.0, (midi - 69) / 12.0) / kSr;
            s[i] = static_cast<float>(0.3 * std::min(1.0, t * 100) * (std::sin(2 * kPi * phase) + 0.5 * std::sin(4 * kPi * phase)));
        }
        auto a = detectNotes(s.data(), s.size(), kSr);
        expectNotes("notes: legato D4 F4 A4 splits at pitch changes", a, {{62, 0.0}, {65, 0.6}, {69, 1.2}});
    }
    // Vibrato (+-0.4 st) stays one note.
    {
        auto a = detectNotes(render({{0.2, 1.5, 64, 0.4}}, 2.0).data(), size_t(2.0 * kSr), kSr);
        expectNotes("notes: vibrato E4 stays one note", a, {{64, 0.2}});
    }
    // Low 808-style sine bass: C2 (65 Hz), G1 (49 Hz is under the range, so use A1 55 Hz).
    {
        auto a = detectNotes(render({{0.1, 0.5, 36, 0}, {0.8, 0.5, 33, 0}}, 1.5, 1).data(), size_t(1.5 * kSr), kSr);
        expectNotes("notes: low sine bass C2 A1", a, {{36, 0.1}, {33, 0.8}});
    }
    // High voice: C6 (1047 Hz).
    {
        auto a = detectNotes(render({{0.1, 0.5, 84, 0}}, 1.0).data(), size_t(1.0 * kSr), kSr);
        expectNotes("notes: high C6", a, {{84, 0.1}});
    }
    // Same note twice with a quick re-attack (dip to near silence for 15 ms).
    {
        auto s = render({{0.1, 0.4, 67, 0}, {0.515, 0.4, 67, 0}}, 1.2);
        auto a = detectNotes(s.data(), s.size(), kSr);
        expectNotes("notes: repeated G4 is two notes", a, {{67, 0.1}, {67, 0.515}});
    }
    // Silence and noise give no notes.
    {
        std::vector<float> z(48000, 0.0f);
        check(detectNotes(z.data(), z.size(), kSr).notes.empty(), "notes: silence has no notes", 0);
        uint32_t seed = 7;
        for (auto& v : z) { seed = seed * 1664525u + 1013904223u; v = 0.3f * ((seed >> 9) / float(1u << 23) - 0.5f); }
        auto a = detectNotes(z.data(), z.size(), kSr);
        check(a.notes.size() <= 1, "notes: white noise has (almost) no notes", double(a.notes.size()));
    }
    // 44.1 kHz works too.
    {
        std::vector<float> s(44100);
        for (size_t i = 0; i < s.size(); ++i) s[i] = 0.3f * std::sin(2 * kPi * 440.0 * i / 44100.0) * std::min(1.0, i / 441.0);
        auto a = detectNotes(s.data(), s.size(), 44100.0f);
        check(a.notes.size() == 1 && std::lround(a.notes[0].midi) == 69, "notes: A4 at 44.1 kHz", a.notes.empty() ? 0 : a.notes[0].midi);
    }
    // Speed: five minutes of melody.
    {
        std::vector<Tone> t;
        for (int i = 0; i < 600; ++i) t.push_back({i * 0.5, 0.45, 55.0 + (i * 7) % 15, 0.2});
        auto s = render(t, 300.0);
        const auto t0 = std::chrono::steady_clock::now();
        auto flat = detectNotesFlat(s.data(), s.size(), kSr);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        check(ms < 4000, "notes: five minutes analysed in under 4 s (ms)", ms);
        check(int(flat[2]) >= 590 && int(flat[2]) <= 610, "notes: five minutes -> about 600 notes", flat[2]);
    }
    std::printf(failures ? "\n%d check(s) failed\n" : "\nall note checks passed\n", failures);
    return failures ? 1 : 0;
}
