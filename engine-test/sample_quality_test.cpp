// Imported-loop quality: stereo samples stay stereo, rate conversion keeps the treble, and the
// master output is clean below full scale.
#include <cmath>
#include <cstdio>
#include <vector>

#include "DawCore.h"

using namespace daw;

static int failures = 0;
static void check(bool ok, const char* what, double value) {
    std::printf("%s %-58s %.4f\n", ok ? "ok  " : "FAIL", what, value);
    if (!ok) ++failures;
}

static double rms(const std::vector<float>& x, size_t from, size_t to) {
    double s = 0;
    for (size_t i = from; i < to; ++i) s += double(x[i]) * x[i];
    return std::sqrt(s / double(to - from));
}

int main() {
    const double kPi = 3.14159265358979323846;
    // 1) 44.1 kHz -> 48 kHz keeps a 15 kHz tone at full level (linear interpolation lost ~4 dB).
    {
        std::vector<float> in(44100);
        for (size_t i = 0; i < in.size(); ++i) in[i] = std::sin(2 * kPi * 15000.0 * double(i) / 44100.0);
        const auto out = resampleSinc(in, 44100.0, 48000.0);
        check(std::abs(long(out.size()) - 48000) <= 1, "resample: length follows the rate", double(out.size()));
        const double db = 20 * std::log10(rms(out, 2000, out.size() - 2000) / std::sqrt(0.5));
        check(std::fabs(db) < 0.3, "resample: 15 kHz tone level change (dB)", db);
        // Compare with a straight-line resample of the same tone.
        std::vector<float> lin(out.size());
        for (size_t o = 0; o + 1 < lin.size(); ++o) {
            const double t = double(o) * 44100.0 / 48000.0;
            const size_t i = size_t(t);
            if (i + 1 >= in.size()) break;
            lin[o] = float(in[i] + (in[i + 1] - in[i]) * (t - double(i)));
        }
        const double linDb = 20 * std::log10(rms(lin, 2000, lin.size() - 2000) / std::sqrt(0.5));
        check(linDb < -2.0, "resample: old linear method was dull (dB)", linDb);
        // 48 -> 44.1 kHz: a 22 kHz tone above the new Nyquist is filtered out, not aliased.
        std::vector<float> hi(48000);
        for (size_t i = 0; i < hi.size(); ++i) hi[i] = std::sin(2 * kPi * 23000.0 * double(i) / 48000.0);
        const auto down = resampleSinc(hi, 48000.0, 44100.0);
        const double alias = 20 * std::log10(rms(down, 2000, down.size() - 2000) / std::sqrt(0.5) + 1e-9);
        check(alias < -60.0, "resample: 23 kHz tone removed going to 44.1 kHz (dB)", alias);
    }

    // 2) A stereo sample plays in stereo: left-only content stays on the left.
    {
        const int sr = 48000, block = 256;
        Core core;
        core.setSampleRate(sr);
        core.setBpm(120.0f);
        core.setChannelCount(1);
        core.setChannel(0, 8 /* sample */, 1.0f, 0.0f, false, false);
        std::vector<float> l(sr * 2), r(sr * 2, 0.0f);
        for (size_t i = 0; i < l.size(); ++i) l[i] = 0.5f * float(std::sin(2 * kPi * 440.0 * double(i) / 44100.0));
        core.setSample(0, l, r, 44100.0f);
        const int64_t len = kTicksPerBeat * 2;
        core.setSequence({NoteEvent{0, 0, static_cast<int32_t>(len), 60, 1.0f}}, len * 4);
        core.setPlaying(true);
        std::vector<float> buf(block * 2);
        double sl = 0, srr = 0;
        for (int b = 0; b < 40; ++b) {
            core.render(buf.data(), block);
            for (int i = 0; i < block; ++i) { sl += buf[2 * i] * buf[2 * i]; srr += buf[2 * i + 1] * buf[2 * i + 1]; }
        }
        check(sl > 1.0, "stereo: left channel carries the sound", sl);
        check(srr < 1e-6, "stereo: right channel stays silent", srr);
    }

    // 3) The master output is untouched at normal levels and never passes full scale.
    check(outputClip(0.5f) == 0.5f, "output: 0.5 passes unchanged", outputClip(0.5f));
    check(outputClip(-0.85f) == -0.85f, "output: -0.85 passes unchanged", outputClip(-0.85f));
    check(outputClip(3.0f) <= 1.0f && outputClip(3.0f) > 0.95f, "output: loud peaks are capped at full scale", outputClip(3.0f));

    std::printf(failures ? "\nsample quality: %d failure(s)\n" : "\nsample quality: all good\n", failures);
    return failures ? 1 : 0;
}
