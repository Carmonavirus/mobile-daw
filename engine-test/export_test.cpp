// Export test: a bounce stops at the song's end without looping back, lets the last note ring
// out, renders only the masked channels for stems, and the MP3 encoder writes valid frames.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "DawCore.h"
#include "export/Mp3Encoder.h"

using namespace daw;

static const int sr = 48000;
static int failures = 0;

static void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

// A 1 s decaying 220 Hz tone as a sample.
static std::vector<float> tone(float freq) {
    std::vector<float> s(sr);
    for (int i = 0; i < sr; ++i) s[i] = 0.5f * std::sin(2.0f * 3.14159265f * freq * i / sr) * std::exp(-2.0f * i / sr);
    return s;
}

static void setup(Core& core) {
    core.setSampleRate(sr);
    core.setBpm(120);  // one beat = 24000 samples, a bar = 96000
    core.setChannelCount(2);
    for (int c = 0; c < 2; ++c) {
        core.setChannel(c, kSample, 0.8f, 0.0f, false, false);
        core.setSample(c, tone(c == 0 ? 220.0f : 330.0f), static_cast<float>(sr));
    }
    // Channel 0 hits beat 1, channel 1 hits the last beat; the song is one bar long.
    core.setSequence({{0, 0, kTicksPerStep, 60, 1.0f}, {1, kTicksPerBeat * 3, kTicksPerStep, 60, 1.0f}}, kTicksPerBeat * 4);
    core.setStopAtEnd(true);
    core.seek(0);
    core.setPlaying(true);
}

// Renders until the song stops, then `tail` more frames. Returns stereo frames; `songFrames` gets
// how many frames were rendered while still playing (to block precision).
static std::vector<float> bounce(Core& core, int tail, long* songFrames) {
    std::vector<float> out;
    std::vector<float> buf(512 * 2);
    long frames = 0;
    while (core.isPlaying() && frames < sr * 10) {
        core.renderMix(buf.data(), 512);
        out.insert(out.end(), buf.begin(), buf.end());
        frames += 512;
    }
    *songFrames = frames;
    for (int done = 0; done < tail; done += 512) {
        core.renderMix(buf.data(), 512);
        out.insert(out.end(), buf.begin(), buf.end());
    }
    return out;
}

static float peak(const std::vector<float>& s, long from, long to) {
    float p = 0;
    for (long i = from * 2; i < to * 2 && i < static_cast<long>(s.size()); ++i) p = std::fmax(p, std::fabs(s[i]));
    return p;
}

int main() {
    {
        Core core;
        setup(core);
        long songFrames = 0;
        auto out = bounce(core, sr, &songFrames);
        std::printf("song frames %ld\n", songFrames);
        check(std::labs(songFrames - 96000) <= 512, "playback stops at the end of the song (one bar)");
        check(!core.isPlaying(), "transport reports stopped after the end");
        // The last hit (at 72000) is a 1 s tone: it must keep ringing well past the bar line.
        check(peak(out, 100000, 120000) > 0.05f, "the last note rings out past the end");
        // Nothing may restart at the top: just after the end there is only the decaying 330 Hz tail,
        // so the level keeps falling instead of jumping back up to a fresh hit.
        check(peak(out, 96000 + 2000, 96000 + 6000) <= peak(out, 90000, 94000) + 1e-3f, "no loop back to the start");
    }
    {
        Core core;
        setup(core);
        core.setRenderMask(1 << 1);  // stem: channel 1 only
        long songFrames = 0;
        auto out = bounce(core, 0, &songFrames);
        check(peak(out, 0, 70000) == 0.0f, "stem: the other channel is silent");
        check(peak(out, 72000, 90000) > 0.05f, "stem: its own channel plays");
    }
    {
        Core core;
        setup(core);
        core.setChannel(1, kSample, 0.8f, 0.0f, true, false);  // muted
        core.setRenderMask(1 << 1);
        long songFrames = 0;
        auto out = bounce(core, 0, &songFrames);
        check(peak(out, 0, songFrames) == 0.0f, "stem: a muted channel stays silent");
    }
    for (int rate : {48000, 44100, 96000}) {
        Mp3Encoder enc(rate, 192);
        check(enc.ok(), "mp3 encoder starts");
        std::vector<uint8_t> mp3;
        std::vector<float> pcm(static_cast<size_t>(rate) * 2 * 2);
        for (int i = 0; i < rate * 2; ++i) pcm[i * 2] = pcm[i * 2 + 1] = 0.5f * std::sin(2.0f * 3.14159265f * 440.0f * i / rate);
        for (int at = 0; at < rate * 2; at += 1000) enc.encode(pcm.data() + at * 2, std::min(1000, rate * 2 - at), mp3);
        enc.finish(mp3);
        const double expected = 192000.0 / 8.0 * 2.0;  // two seconds
        std::printf("mp3 @%d -> %d Hz: %zu bytes (expected ~%.0f)\n", rate, enc.outputRate(), mp3.size(), expected);
        check(mp3.size() > 2 && mp3[0] == 0xFF && (mp3[1] & 0xE0) == 0xE0, "mp3 starts with a frame sync");
        check(enc.outputRate() == (rate == 96000 ? 48000 : rate), "mp3 keeps 44.1/48 kHz, resamples 96 kHz");
        check(std::fabs(mp3.size() - expected) < expected * 0.1, "mp3 size matches the bitrate");
        if (rate == 44100 && std::getenv("MP3_OUT")) {
            FILE* f = std::fopen(std::getenv("MP3_OUT"), "wb");
            if (f) { std::fwrite(mp3.data(), 1, mp3.size(), f); std::fclose(f); }
        }
    }
    std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}
