// The built-in wavetables. Every table is generated here from simple maths (additive spectra,
// folding, sync, phase distortion...), so no third-party wave data is shipped.
#include <algorithm>
#include <atomic>
#include <cmath>
#include <complex>
#include <functional>

#include "WaveSynth.h"

namespace wavesynth {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kTwoPi = 2.0 * kPi;

using cplx = std::complex<double>;

// In-place iterative radix-2 FFT. sign = -1 forward, +1 inverse (unscaled).
void fft(std::vector<cplx>& a, int sign) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = sign * kTwoPi / static_cast<double>(len);
        const cplx wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            cplx w(1.0, 0.0);
            for (size_t k = 0; k < len / 2; ++k) {
                const cplx u = a[i + k];
                const cplx v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

// One frame as harmonic amplitudes: x(theta) = sum_h cosAmp[h] cos(h theta) + sinAmp[h] sin(h theta)
struct Spectrum {
    std::vector<double> cosAmp = std::vector<double>(kMaxHarmonics + 1, 0.0);
    std::vector<double> sinAmp = std::vector<double>(kMaxHarmonics + 1, 0.0);
};

using SpectrumFn = std::function<void(double t, Spectrum& s)>;  // t = frame position 0..1
using TimeFn = std::function<double(double t, double theta)>;    // theta = cycle phase 0..1

// Samples a single cycle finely and keeps its first kMaxHarmonics harmonics.
SpectrumFn fromTime(TimeFn fn) {
    return [fn](double t, Spectrum& s) {
        constexpr size_t n = 8192;
        std::vector<cplx> buf(n);
        for (size_t i = 0; i < n; ++i) buf[i] = fn(t, static_cast<double>(i) / n);
        fft(buf, -1);
        for (int h = 1; h <= kMaxHarmonics; ++h) {
            s.cosAmp[h] = 2.0 * buf[static_cast<size_t>(h)].real() / n;
            s.sinAmp[h] = -2.0 * buf[static_cast<size_t>(h)].imag() / n;
        }
    };
}

// Tiny deterministic hash, used for fixed "random" phases and amplitudes.
double hash01(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352dU; x ^= x >> 15; x *= 0x846ca68bU; x ^= x >> 16;
    return static_cast<double>(x & 0xFFFFFF) / 16777216.0;
}

double lerp(double a, double b, double t) { return a + (b - a) * t; }

// ---- generators -------------------------------------------------------------------------

// Sine -> triangle -> saw -> square.
void classicMorph(double t, Spectrum& s) {
    auto shape = [](int which, int h) -> double {
        switch (which) {
            case 0: return h == 1 ? 1.0 : 0.0;
            case 1: return (h % 2) ? 8.0 / (kPi * kPi) / (h * h) * (((h - 1) / 2) % 2 ? -1.0 : 1.0) : 0.0;
            case 2: return 2.0 / (kPi * h);
            default: return (h % 2) ? 4.0 / (kPi * h) : 0.0;
        }
    };
    const double x = std::min(t * 3.0, 2.9999);
    const int seg = static_cast<int>(x);
    const double f = x - seg;
    for (int h = 1; h <= kMaxHarmonics; ++h) s.sinAmp[h] = lerp(shape(seg, h), shape(seg + 1, h), f);
}

double pulse(double t, double th) {
    const double w = lerp(0.5, 0.04, t);
    return th < w ? 1.0 : -1.0;
}

// Saw with a resonant bump that climbs the harmonic series.
void harmonicSweep(double t, Spectrum& s) {
    const double centre = 1.0 + t * 46.0;
    const double width = 1.5 + t * 5.0;
    for (int h = 1; h <= 256; ++h) {
        const double d = (h - centre) / width;
        s.sinAmp[h] = (0.35 + 3.0 * std::exp(-d * d)) / std::pow(h, 0.9);
    }
}

// A buzzy source through vowel-like resonances (a, e, i, o, u).
void vowels(double t, Spectrum& s) {
    static const double f[5][3] = {{800, 1150, 2900}, {420, 1900, 2600}, {300, 2300, 3000}, {470, 830, 2800}, {330, 700, 2500}};
    static const double bw[3] = {90, 110, 160};
    static const double gain[3] = {1.0, 0.55, 0.3};
    const double x = std::min(t * 4.0, 3.9999);
    const int seg = static_cast<int>(x);
    const double fr = x - seg;
    const double f0 = 120.0;  // reference pitch the resonances are tuned for
    for (int h = 1; h <= kMaxHarmonics; ++h) {
        const double hz = h * f0;
        if (hz > 9000) break;
        double env = 0.04;
        for (int k = 0; k < 3; ++k) {
            const double fk = lerp(f[seg][k], f[seg + 1][k], fr);
            const double d = (hz - fk) / bw[k];
            env += gain[k] / (1.0 + d * d);
        }
        s.sinAmp[h] = env / std::sqrt(static_cast<double>(h));
    }
}

double sineFold(double t, double th) {
    const double g = 1.0 + t * 7.0;
    return std::sin(kPi * 0.5 * g * std::sin(kTwoPi * th));
}

double syncSaw(double t, double th) {
    const double ratio = 1.0 + t * 7.0;
    const double ph = th * ratio;
    return 2.0 * (ph - std::floor(ph)) - 1.0;
}

// Cosine read through a bent phase: smooth at the start, saw-like at the end.
double phaseBend(double t, double th) {
    const double knee = lerp(0.5, 0.02, t);
    const double p = th < knee ? th * 0.5 / knee : 0.5 + (th - knee) * 0.5 / (1.0 - knee);
    return -std::cos(kTwoPi * p);
}

void bell(double t, Spectrum& s) {
    static const int partial[] = {1, 2, 3, 4, 5, 7, 9, 11, 13, 17, 19, 23};
    static const double amp[] = {1.0, 0.6, 0.12, 0.5, 0.32, 0.3, 0.22, 0.18, 0.13, 0.09, 0.07, 0.05};
    for (size_t i = 0; i < sizeof(partial) / sizeof(int); ++i) {
        const int h = partial[i];
        const double bright = std::exp(-(h - 1) * (1.0 - t) * 0.35);
        const double ph = hash01(static_cast<uint32_t>(h * 977)) * kTwoPi;
        s.cosAmp[h] = amp[i] * bright * std::cos(ph);
        s.sinAmp[h] = amp[i] * bright * std::sin(ph);
    }
}

void drawbars(double t, Spectrum& s) {
    static const int harm[9] = {1, 2, 3, 4, 6, 8, 10, 12, 16};
    static const double reg[4][9] = {
        {1.0, 0.7, 0, 0, 0, 0, 0, 0, 0},
        {1.0, 1.0, 0.8, 0.5, 0, 0, 0, 0, 0},
        {0.8, 1.0, 0.6, 1.0, 0.6, 0.8, 0, 0, 0},
        {1.0, 1.0, 1.0, 1.0, 0.9, 0.9, 0.7, 0.7, 0.7},
    };
    const double x = std::min(t * 3.0, 2.9999);
    const int seg = static_cast<int>(x);
    const double f = x - seg;
    for (int i = 0; i < 9; ++i) s.cosAmp[harm[i]] = lerp(reg[seg][i], reg[seg + 1][i], f) / std::sqrt(harm[i]);
}

double bitSteps(double t, double th) {
    const double hold = std::round(lerp(64.0, 6.0, t));
    const double levels = std::round(lerp(24.0, 2.0, t));
    const double held = std::floor(th * hold) / hold;
    return std::round(std::sin(kTwoPi * held) * levels) / levels;
}

// Saw with moving comb notches: a talking, growly bass table.
void growl(double t, Spectrum& s) {
    for (int h = 1; h <= 256; ++h) {
        const double comb = 0.5 + 0.5 * std::cos(kTwoPi * h * (0.06 + t * 0.22) + t * kPi);
        s.sinAmp[h] = (0.12 + 0.88 * comb) / h;
    }
}

// Sine into an asymmetric soft clipper: clean sub to warm, gritty 808 body.
double warmSub(double t, double th) {
    const double g = 0.4 + t * 6.0;
    const double bias = 0.25 * t;
    return std::tanh(g * (std::sin(kTwoPi * th) + bias)) - std::tanh(g * bias);
}

void glass(double t, Spectrum& s) {
    s.sinAmp[1] = 1.0;
    const double centre = 1.5 + t * 4.5;  // in octaves
    for (int h = 2; h <= 160; ++h) {
        if (hash01(static_cast<uint32_t>(h * 131 + 7)) < 0.62) continue;
        const double oct = std::log2(static_cast<double>(h));
        const double d = oct - centre;
        const double a = 0.7 * std::exp(-d * d * 1.4);
        const double ph = hash01(static_cast<uint32_t>(h * 59 + 3)) * kTwoPi;
        s.cosAmp[h] = a * std::cos(ph);
        s.sinAmp[h] = a * std::sin(ph);
    }
}

struct TableDef {
    const char* name;
    SpectrumFn fn;
};

// Append only: presets refer to tables by index.
std::vector<TableDef> definitions() {
    return {
        {"Classic Morph", classicMorph},
        {"Pulse Width", fromTime(pulse)},
        {"Harmonic Sweep", harmonicSweep},
        {"Vowels", vowels},
        {"Sine Fold", fromTime(sineFold)},
        {"Sync Saw", fromTime(syncSaw)},
        {"Phase Bend", fromTime(phaseBend)},
        {"Bell", bell},
        {"Drawbars", drawbars},
        {"Bit Steps", fromTime(bitSteps)},
        {"Growl", growl},
        {"Warm Sub", fromTime(warmSub)},
        {"Glass", glass},
    };
}

Wavetable build(const TableDef& def) {
    Wavetable wt;
    wt.name = def.name;
    int offset = 0;
    for (int k = 0; k < kLevels; ++k) {
        const int size = std::max(2048 >> k, 64);
        wt.levels[static_cast<size_t>(k)] = {size, offset};
        offset += size + 1;
    }
    wt.frameStride = offset;
    wt.data.assign(static_cast<size_t>(offset) * kFrames, 0.0f);

    std::vector<cplx> buf;
    for (int f = 0; f < kFrames; ++f) {
        Spectrum s;
        def.fn(static_cast<double>(f) / (kFrames - 1), s);
        float scale = 1.0f;
        for (int k = 0; k < kLevels; ++k) {
            const auto& lv = wt.levels[static_cast<size_t>(k)];
            const int maxH = std::min(kMaxHarmonics >> k, lv.size / 2 - 1);
            buf.assign(static_cast<size_t>(lv.size), cplx(0.0, 0.0));
            for (int h = 1; h <= maxH; ++h) {
                const cplx c(s.cosAmp[h] * 0.5, -s.sinAmp[h] * 0.5);
                buf[static_cast<size_t>(h)] = c;
                buf[static_cast<size_t>(lv.size - h)] = std::conj(c);
            }
            fft(buf, +1);
            float* out = wt.data.data() + static_cast<size_t>(f) * wt.frameStride + lv.offset;
            if (k == 0) {
                double peak = 1e-9;
                for (const auto& v : buf) peak = std::max(peak, std::fabs(v.real()));
                scale = static_cast<float>(1.0 / peak);
            }
            for (int i = 0; i < lv.size; ++i) out[i] = static_cast<float>(buf[static_cast<size_t>(i)].real()) * scale;
            out[lv.size] = out[0];
        }
    }
    return wt;
}

}  // namespace

namespace {
std::atomic<bool> gReady{false};
}

Bank::Bank() {
    for (const auto& def : definitions()) tables_.push_back(build(def));
}

bool Bank::ready() { return gReady.load(std::memory_order_acquire); }

const Bank& Bank::get() {
    static const Bank bank;
    gReady.store(true, std::memory_order_release);
    return bank;
}

const Wavetable& Bank::table(int index) const {
    return tables_[static_cast<size_t>(std::clamp(index, 0, count() - 1))];
}

void Bank::shape(int index, float position, float* out, int n) const {
    const Wavetable& wt = table(index);
    const float fp = std::clamp(position, 0.0f, 1.0f) * (kFrames - 1);
    const int fa = std::min(static_cast<int>(fp), kFrames - 1);
    const int fb = std::min(fa + 1, kFrames - 1);
    const float mix = fp - fa;
    const int size = wt.levels[0].size;
    const float* a = wt.frame(fa, 0);
    const float* b = wt.frame(fb, 0);
    for (int i = 0; i < n; ++i) {
        const int j = static_cast<int>(static_cast<int64_t>(i) * size / n);
        out[i] = a[j] + (b[j] - a[j]) * mix;
    }
}

}  // namespace wavesynth
