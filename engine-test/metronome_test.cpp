// Metronome and count-in test: clicks land on the exact sample of each beat, the downbeat is
// accented, the count-in starts playback on time, and the click never reaches renderMix (bounces),
// the meters or the scope.
// Build: g++ -std=c++17 -O2 -I../app/src/main/cpp metronome_test.cpp ../app/src/main/cpp/DawCore.cpp ...
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "DawCore.h"

using namespace daw;

static const int sr = 48000;
static const int block = 192;  // not a divisor of a beat, so clicks fall inside blocks
static int failures = 0;

static void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

struct Onset { long at; float peak; };

// Clicks: first non-silent sample after at least 2000 silent ones, with the loudest sample of the click.
static std::vector<Onset> onsets(const std::vector<float>& mono) {
    std::vector<Onset> out;
    long quiet = 1 << 20;
    for (size_t i = 0; i < mono.size(); ++i) {
        const float a = std::fabs(mono[i]);
        if (a > 1e-5f) {
            if (quiet >= 2000) out.push_back({static_cast<long>(i), 0.0f});
            if (!out.empty()) out.back().peak = std::fmax(out.back().peak, a);
            quiet = 0;
        } else {
            ++quiet;
        }
    }
    return out;
}

static std::vector<float> run(Core& core, int samples, bool live, std::vector<int>* beats = nullptr) {
    std::vector<float> mono;
    std::vector<float> buf(block * 2);
    for (int done = 0; done < samples; done += block) {
        if (live) core.render(buf.data(), block); else core.renderMix(buf.data(), block);
        for (int i = 0; i < block; ++i) mono.push_back(buf[i * 2]);
        if (beats) beats->push_back(core.countInBeat());
    }
    return mono;
}

static bool near(long a, long b) { return std::labs(a - b) <= 2; }

static void setup(Core& core) {
    core.setSampleRate(sr);
    core.setBpm(120);  // one beat = 24000 samples
    core.setChannelCount(1);
    core.setChannel(0, 0, 0.8f, 0.0f, false, false);
    core.setSequence({}, kTicksPerBeat * 4 * 4);
}

int main() {
    {
        Core core;
        setup(core);
        core.setMetronome(true);
        core.setPlaying(true);
        auto mono = run(core, sr * 3, true);
        auto on = onsets(mono);
        std::printf("playing: %zu clicks:", on.size());
        for (auto& o : on) std::printf(" %ld(%.2f)", o.at, o.peak);
        std::printf("\n");
        check(on.size() == 6, "six beats in three seconds at 120 BPM");
        bool timed = on.size() == 6;
        for (size_t i = 0; timed && i < on.size(); ++i) timed = near(on[i].at, static_cast<long>(i) * 24000);
        check(timed, "each click on the exact sample of its beat");
        check(on.size() == 6 && on[0].peak > on[1].peak * 1.3f && on[4].peak > on[3].peak * 1.3f,
              "beat 1 of each bar is accented");
        float level[1];
        core.readLevels(level, 1);
        float scope[16];
        core.readScope(scope, 16);
        float scopePeak = 0;
        for (float s : scope) scopePeak = std::fmax(scopePeak, s);
        check(core.masterLevel() == 0.0f && scopePeak == 0.0f, "click stays off the meters and scope");
    }
    {
        Core core;
        setup(core);
        core.setMetronome(true);
        core.setPlaying(true);
        auto mono = run(core, sr * 3, false);
        float peak = 0;
        for (float s : mono) peak = std::fmax(peak, std::fabs(s));
        check(peak == 0.0f, "renderMix (bounce) has no click");
    }
    {
        Core core;
        setup(core);
        core.setBeatsPerBar(3);
        core.setMetronome(true);
        core.setPlaying(true);
        auto on = onsets(run(core, sr * 3 + 100, true));
        check(on.size() == 7 && on[3].peak > on[2].peak * 1.3f && on[1].peak < on[0].peak,
              "3/4 accents every third beat");
    }
    {
        Core core;
        setup(core);
        core.setMetronome(true);
        core.startCountIn(4);
        std::vector<int> beats;
        auto mono = run(core, sr * 3, true, &beats);
        auto on = onsets(mono);
        std::printf("count-in: %zu clicks:", on.size());
        for (auto& o : on) std::printf(" %ld(%.2f)", o.at, o.peak);
        std::printf(" playing=%d pos=%lld\n", core.isPlaying() ? 1 : 0, (long long)core.positionTicks());
        bool timed = on.size() == 6;
        for (size_t i = 0; timed && i < on.size(); ++i) timed = near(on[i].at, static_cast<long>(i) * 24000);
        check(timed, "count-in clicks, then the song's beats, all on time");
        check(on.size() == 6 && on[0].peak > on[1].peak * 1.3f && on[4].peak > on[3].peak * 1.3f,
              "count-in and the song both accent beat 1");
        // Playback started exactly at sample 96000: at 144000 the playhead is 2 beats in.
        check(core.isPlaying() && std::llabs(core.positionTicks() - 2 * kTicksPerBeat) <= 2,
              "playback starts right after the count-in");
        const size_t at = static_cast<size_t>(30000 / block);
        check(beats[0] == 1 && beats[at] == 2 && beats.back() == 0, "countInBeat follows the count");
    }
    {
        Core core;
        setup(core);
        core.startCountIn(4);  // metronome off: count-in still clicks, the song doesn't
        auto on = onsets(run(core, sr * 3, true));
        check(on.size() == 4 && core.isPlaying(), "metronome off: only the count-in clicks");
    }
    {
        Core core;
        setup(core);
        core.setMetronome(true);
        core.startCountIn(4);
        auto first = run(core, 30000, true);
        core.setPlaying(false);
        auto rest = onsets(run(core, sr * 3, true));
        check(rest.empty() && !core.isPlaying() && core.countInBeat() == 0, "stopping cancels the count-in");
    }
    {
        Core core;
        setup(core);
        core.startCountIn(4);
        auto mono = run(core, sr * 3, false);
        float peak = 0;
        for (float s : mono) peak = std::fmax(peak, std::fabs(s));
        check(peak == 0.0f, "renderMix has no count-in click either");
    }
    if (failures) {
        std::printf("%d metronome check(s) failed\n", failures);
        return 1;
    }
    std::printf("metronome: all good\n");
    return 0;
}
