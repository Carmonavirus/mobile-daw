#include "NoteDetect.h"

#include <algorithm>
#include <cmath>

namespace daw::fx {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr float kTargetRate = 12000.0f;
constexpr float kMinHz = 50.0f;
constexpr float kMaxHz = 1600.0f;

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    float run(float x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return static_cast<float>(y);
    }
};

float median(std::vector<float> v) {
    if (v.empty()) return 0.0f;
    const size_t m = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + static_cast<long>(m), v.end());
    return v[m];
}
}  // namespace

NoteAnalysis detectNotes(const float* mono, size_t frames, float sampleRate) {
    NoteAnalysis out;
    if (!mono || frames == 0 || sampleRate <= 0.0f) return out;

    // ---- low-pass and decimate
    const int decimate = std::max(1, static_cast<int>(std::lround(sampleRate / kTargetRate)));
    const float rate = sampleRate / static_cast<float>(decimate);
    std::vector<float> x;
    x.reserve(frames / static_cast<size_t>(decimate) + 1);
    {
        Biquad f[4];
        const double fc = std::min(0.4 * rate, 0.45 * sampleRate);
        const double w0 = 2.0 * kPi * fc / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * 0.7071);
        const double cw = std::cos(w0), a0 = 1.0 + alpha;
        for (auto& b : f) {
            b.b0 = (1.0 - cw) / 2.0 / a0;
            b.b1 = (1.0 - cw) / a0;
            b.b2 = b.b0;
            b.a1 = -2.0 * cw / a0;
            b.a2 = (1.0 - alpha) / a0;
        }
        for (size_t i = 0; i < frames; ++i) {
            float s = std::isfinite(mono[i]) ? mono[i] : 0.0f;
            if (decimate > 1)
                for (auto& b : f) s = b.run(s);
            if (i % static_cast<size_t>(decimate) == 0) x.push_back(s);
        }
    }

    const int hop = std::max(1, static_cast<int>(std::lround(rate * 0.01f)));
    const int window = static_cast<int>(std::lround(rate * 0.03f));
    const int tauMin = std::max(2, static_cast<int>(rate / kMaxHz));
    const int tauMax = static_cast<int>(rate / kMinHz);
    out.hopSeconds = hop / rate;
    const long n = static_cast<long>(x.size());
    const long count = n > window + tauMax + 1 ? (n - window - tauMax - 1) / hop + 1 : 0;
    // Frame i describes the sound around i * hop (its window starts half a window earlier).
    const size_t total = static_cast<size_t>(std::ceil(x.size() / static_cast<double>(hop)));
    out.pitch.assign(total, 0.0f);
    out.level.assign(total, 0.0f);
    if (count <= 0) return out;
    const int lead = window / 2;

    std::vector<double> d(static_cast<size_t>(tauMax + 2));
    std::vector<double> rms(total, 0.0);
    double loudest = 0.0;
    for (long fI = 0; fI < count; ++fI) {
        const float* s = x.data() + fI * hop;
        double e = 0.0;
        for (int j = 0; j < window; ++j) e += static_cast<double>(s[j]) * s[j];
        const size_t at = static_cast<size_t>(fI) + static_cast<size_t>((lead + hop / 2) / hop);
        if (at >= total) break;
        rms[at] = std::sqrt(e / window);
        loudest = std::max(loudest, rms[at]);
        if (rms[at] < 0.003) continue;  // under -50 dBFS: too quiet to have a pitch

        // YIN difference function and its cumulative mean normalised form.
        d[0] = 1.0;
        double running = 0.0;
        for (int tau = 1; tau <= tauMax; ++tau) {
            // Four float partial sums so the compiler can vectorise the inner loop.
            float acc[4] = {0, 0, 0, 0};
            const float* t = s + tau;
            int j = 0;
            for (; j + 4 <= window; j += 4)
                for (int k = 0; k < 4; ++k) {
                    const float diff = s[j + k] - t[j + k];
                    acc[k] += diff * diff;
                }
            for (; j < window; ++j) {
                const float diff = s[j] - t[j];
                acc[0] += diff * diff;
            }
            const double sum = static_cast<double>(acc[0]) + acc[1] + acc[2] + acc[3];
            running += sum;
            d[static_cast<size_t>(tau)] = running > 0.0 ? sum * tau / running : 1.0;
        }
        int best = -1;
        for (int tau = tauMin; tau < tauMax; ++tau) {
            if (d[static_cast<size_t>(tau)] < 0.15) {
                while (tau + 1 < tauMax && d[static_cast<size_t>(tau + 1)] < d[static_cast<size_t>(tau)]) ++tau;
                best = tau;
                break;
            }
        }
        if (best < 0) {
            double lowest = 1e9;
            for (int tau = tauMin; tau < tauMax; ++tau)
                if (d[static_cast<size_t>(tau)] < lowest) {
                    lowest = d[static_cast<size_t>(tau)];
                    best = tau;
                }
            if (lowest > 0.3) continue;  // no clear period: noise, a drum, a breath
        }
        double tauF = best;
        if (best > tauMin && best < tauMax - 1) {
            const double a = d[static_cast<size_t>(best - 1)], b = d[static_cast<size_t>(best)], c = d[static_cast<size_t>(best + 1)];
            const double den = a - 2.0 * b + c;
            if (std::fabs(den) > 1e-12) tauF += std::clamp(0.5 * (a - c) / den, -0.5, 0.5);
        }
        const double hz = rate / tauF;
        out.pitch[at] = static_cast<float>(69.0 + 12.0 * std::log2(hz / 440.0));
    }
    // Quiet compared with the loudest part of the sound (-40 dB): no pitch either.
    for (size_t i = 0; i < total; ++i) {
        out.level[i] = loudest > 0.0 ? static_cast<float>(rms[i] / loudest) : 0.0f;
        if (out.level[i] < 0.01f) out.pitch[i] = 0.0f;
    }

    // ---- clean up: fold lone octave jumps back, drop lone pitched frames, then median of 3.
    std::vector<float> p = out.pitch;
    for (size_t i = 0; i < total; ++i) {
        if (p[i] <= 0.0f) continue;
        std::vector<float> around;
        for (long k = static_cast<long>(i) - 4; k <= static_cast<long>(i) + 4; ++k)
            if (k >= 0 && k < static_cast<long>(total) && k != static_cast<long>(i) && out.pitch[static_cast<size_t>(k)] > 0.0f)
                around.push_back(out.pitch[static_cast<size_t>(k)]);
        if (around.size() < 2) {
            p[i] = 0.0f;
            continue;
        }
        const float m = median(around);
        for (float shift : {-24.0f, -12.0f, 12.0f, 24.0f})
            if (std::fabs(p[i] + shift - m) < 1.0f) p[i] += shift;
    }
    for (size_t i = 1; i + 1 < total; ++i) {
        if (p[i - 1] > 0.0f && p[i] > 0.0f && p[i + 1] > 0.0f) {
            const float a = p[i - 1], b = p[i], c = p[i + 1];
            out.pitch[i] = std::max(std::min(a, b), std::min(std::max(a, b), c));
        } else {
            out.pitch[i] = p[i];
        }
    }
    if (total > 0) {
        out.pitch[0] = p[0];
        out.pitch[total - 1] = p[total - 1];
    }

    // ---- group into notes
    const float hopS = out.hopSeconds;
    std::vector<float> cur;
    size_t curStart = 0;
    float curLevel = 0.0f;
    auto close = [&](size_t endFrame) {  // endFrame exclusive
        if (!cur.empty() && (endFrame - curStart) * hopS >= 0.06f) {
            DetectedNote note;
            note.start = curStart * hopS;
            note.end = endFrame * hopS;
            note.midi = median(cur);
            note.level = curLevel;
            out.notes.push_back(note);
        }
        cur.clear();
        curLevel = 0.0f;
    };
    int gap = 0, away = 0;
    for (size_t i = 0; i < total; ++i) {
        const float pi = out.pitch[i];
        if (pi <= 0.0f) {
            if (!cur.empty() && ++gap >= 3) {
                close(i - static_cast<size_t>(gap) + 1);
                gap = 0;
            }
            continue;
        }
        gap = 0;
        if (cur.empty()) {
            curStart = i;
            cur.push_back(pi);
            curLevel = out.level[i];
            away = 0;
            continue;
        }
        // Use the recent part of the note as the reference so slow glides don't split it.
        std::vector<float> recent(cur.end() - static_cast<long>(std::min<size_t>(cur.size(), 12)), cur.end());
        const float ref = median(recent);
        const bool attack = i >= 3 && out.level[i] > 2.8f * out.level[i - 3] && out.level[i - 3] > 0.0f && cur.size() >= 5;
        if (std::fabs(pi - ref) > 0.7f) {
            if (++away >= 3) {
                const size_t split = i + 1 - static_cast<size_t>(away);
                cur.resize(cur.size() - static_cast<size_t>(away - 1));
                close(split);
                curStart = split;
                for (size_t k = split; k <= i; ++k) {
                    cur.push_back(out.pitch[k]);
                    curLevel = std::max(curLevel, out.level[k]);
                }
                away = 0;
                continue;
            }
        } else {
            away = 0;
        }
        if (attack && away == 0) {
            close(i);
            curStart = i;
        }
        cur.push_back(pi);
        curLevel = std::max(curLevel, out.level[i]);
    }
    close(total);
    float top = 0.0f;
    for (const auto& note : out.notes) top = std::max(top, note.level);
    if (top > 0.0f)
        for (auto& note : out.notes) note.level /= top;
    return out;
}

std::vector<float> detectNotesFlat(const float* mono, size_t frames, float sampleRate) {
    const NoteAnalysis a = detectNotes(mono, frames, sampleRate);
    std::vector<float> flat;
    flat.reserve(3 + a.pitch.size() * 2 + a.notes.size() * 4);
    flat.push_back(a.hopSeconds);
    flat.push_back(static_cast<float>(a.pitch.size()));
    flat.push_back(static_cast<float>(a.notes.size()));
    flat.insert(flat.end(), a.pitch.begin(), a.pitch.end());
    flat.insert(flat.end(), a.level.begin(), a.level.end());
    for (const auto& n : a.notes) {
        flat.push_back(n.start);
        flat.push_back(n.end);
        flat.push_back(n.midi);
        flat.push_back(n.level);
    }
    return flat;
}

}  // namespace daw::fx
