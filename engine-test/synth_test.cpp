// Desktop test for the wavetable synth: renders every factory preset to out/synth/<name>.wav
// and fails if one is silent, clips hard, or produces NaNs. Also checks that the parameter
// names in the preset file exist, and times a heavy patch.
// Build: g++ -std=c++17 -O2 -I../app/src/main/cpp synth_test.cpp ../app/src/main/cpp/wavesynth/*.cpp -o out/synthtest
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "wavesynth/WaveSynth.h"

using namespace wavesynth;

struct Preset {
    std::string name, category;
    std::vector<std::pair<std::string, float>> params;
};

// Just enough JSON for {"presets":[{"name":..,"category":..,"params":{k:v,..}},..]}.
struct Reader {
    std::string s;
    size_t i = 0;
    void ws() { while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) ++i; }
    bool eat(char c) { ws(); if (i < s.size() && s[i] == c) { ++i; return true; } return false; }
    std::string str() {
        ws(); std::string out; ++i;
        while (s[i] != '"') { if (s[i] == '\\') ++i; out += s[i++]; }
        ++i; return out;
    }
    double num() { ws(); size_t n = 0; double v = std::stod(s.substr(i), &n); i += n; return v; }
    void skipValue() {
        ws();
        if (s[i] == '"') { str(); return; }
        if (s[i] == '{' || s[i] == '[') {
            char open = s[i], close = open == '{' ? '}' : ']'; int depth = 0;
            do { if (s[i] == open) ++depth; else if (s[i] == close) --depth; else if (s[i] == '"') { str(); continue; } ++i; } while (depth > 0);
            return;
        }
        num();
    }
};

static std::vector<Preset> loadPresets(const char* path) {
    std::ifstream f(path);
    std::stringstream ss; ss << f.rdbuf();
    Reader r{ss.str()};
    std::vector<Preset> out;
    r.eat('{');
    while (!r.eat('}')) {
        std::string key = r.str(); r.eat(':');
        if (key != "presets") { r.skipValue(); r.eat(','); continue; }
        r.eat('[');
        while (!r.eat(']')) {
            Preset p; r.eat('{');
            while (!r.eat('}')) {
                std::string k = r.str(); r.eat(':');
                if (k == "name") p.name = r.str();
                else if (k == "category") p.category = r.str();
                else if (k == "params") {
                    r.eat('{');
                    while (!r.eat('}')) { std::string pk = r.str(); r.eat(':'); p.params.push_back({pk, static_cast<float>(r.num())}); r.eat(','); }
                } else r.skipValue();
                r.eat(',');
            }
            out.push_back(p); r.eat(',');
        }
        r.eat(',');
    }
    return out;
}

static void writeWav(const std::string& path, const std::vector<float>& data, int sr) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    uint32_t bytes = static_cast<uint32_t>(data.size() * 2);
    std::fwrite("RIFF", 1, 4, f); w32(36 + bytes); std::fwrite("WAVEfmt ", 1, 8, f);
    w32(16); w16(1); w16(2); w32(sr); w32(sr * 4); w16(4); w16(16);
    std::fwrite("data", 1, 4, f); w32(bytes);
    for (float s : data) { int16_t v = static_cast<int16_t>(std::fmax(-1.f, std::fmin(1.f, s)) * 32767); w16(v); }
    std::fclose(f);
}

struct Note { double at, len; int pitch; float vel; };

int main(int argc, char** argv) {
    const char* presetPath = argc > 1 ? argv[1] : "../app/src/main/assets/wavesynth/factory_presets.json";
    const int sr = 48000;
    auto presets = loadPresets(presetPath);
    if (presets.empty()) { std::printf("FAIL: no presets read from %s\n", presetPath); return 1; }
    if (std::system("mkdir -p out/synth") != 0) return 1;

    bool ok = true;
    for (const auto& pr : presets) {
        Patch patch;
        for (const auto& [k, v] : pr.params) {
            int idx = paramIndex(k.c_str());
            if (idx < 0) { std::printf("FAIL: %s uses unknown parameter %s\n", pr.name.c_str(), k.c_str()); ok = false; continue; }
            const auto& info = paramInfo(idx);
            if (v < info.min || v > info.max) { std::printf("FAIL: %s: %s=%g outside %g..%g\n", pr.name.c_str(), k.c_str(), v, info.min, info.max); ok = false; }
            patch.v[static_cast<size_t>(idx)] = v;
        }
        const bool low = pr.category == "808" || pr.category == "Bass";
        const int root = low ? 36 : 60;
        std::vector<Note> notes;
        if (pr.category == "Pad" || pr.category == "Keys") {
            for (int n : {0, 4, 7, 11}) notes.push_back({0.0, 1.6, root + n, 0.8f});
            for (int n : {-3, 0, 4, 7}) notes.push_back({2.0, 1.6, root + n, 0.8f});
        } else {
            // A short phrase, with overlapping notes so mono presets glide.
            const int seq[] = {0, 0, 7, 3, 12, 10, 7, 5};
            for (int k = 0; k < 8; ++k) notes.push_back({k * 0.4, k == 2 ? 0.55 : 0.3, root + seq[k], k % 2 ? 0.7f : 1.0f});
        }
        Instrument inst;
        const int total = sr * 5;
        std::vector<float> out(static_cast<size_t>(total) * 2);
        double peak = 0, sum = 0;
        bool nan = false;
        size_t next = 0;
        for (int i = 0; i < total; ++i) {
            if (i % 256 == 0) inst.prepare(patch, static_cast<float>(sr), 140.0f);
            while (next < notes.size() && notes[next].at * sr <= i) {
                inst.noteOn(notes[next].pitch, notes[next].vel, static_cast<int64_t>(notes[next].len * sr));
                ++next;
            }
            float l = 0, r = 0;
            inst.tick(l, r);
            if (!std::isfinite(l) || !std::isfinite(r)) nan = true;
            out[static_cast<size_t>(i) * 2] = l;
            out[static_cast<size_t>(i) * 2 + 1] = r;
            peak = std::fmax(peak, std::fmax(std::fabs(l), std::fabs(r)));
            sum += 0.5 * (l * l + r * r);
        }
        const double rms = std::sqrt(sum / total);
        std::string file = "out/synth/" + pr.category + " - " + pr.name + ".wav";
        writeWav(file, out, sr);
        const bool bad = nan || peak < 0.02 || peak > 4.0;
        std::printf("%-6s %-18s peak %.2f  rms %.3f%s\n", pr.category.c_str(), pr.name.c_str(), peak, rms, bad ? "  <-- FAIL" : "");
        ok = ok && !bad;
    }

    // Wavetable display helper.
    float shape[128];
    for (int t = 0; t < Bank::get().count(); ++t) {
        Bank::get().shape(t, 0.5f, shape, 128);
        for (float s : shape) if (!std::isfinite(s) || std::fabs(s) > 1.01f) { std::printf("FAIL: table %d shape\n", t); ok = false; break; }
    }

    // Heavy patch: 8 voices x 2 oscillators x 7 unison, 24-dB filter.
    Patch heavy;
    heavy.v[kAUnison] = 7; heavy.v[kBOn] = 1; heavy.v[kBUnison] = 7; heavy.v[kFltType] = 1; heavy.v[kAmpS] = 1;
    Instrument inst;
    inst.prepare(heavy, sr, 120);
    for (int n = 0; n < 8; ++n) inst.noteOn(48 + n * 3, 1.0f, 0);
    const auto t0 = std::chrono::steady_clock::now();
    float l = 0, r = 0;
    for (int i = 0; i < sr * 10; ++i) { if (i % 256 == 0) inst.prepare(heavy, sr, 120); inst.tick(l, r); }
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("heavy patch: 10 s of audio rendered in %.2f s (%.0fx real time) %f\n", secs, 10.0 / secs, l + r);

    std::printf(ok ? "OK: %zu presets\n" : "FAILED\n", presets.size());
    return ok ? 0 : 1;
}
