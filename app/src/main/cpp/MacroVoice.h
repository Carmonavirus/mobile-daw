// One polyphonic voice of the "Macro" instrument: synthesis models from Plaits (MIT, Emilie Gillet),
// driven by our own note/gate handling. See MacroVoice.cpp for which models are included.
#pragma once

#include <cstdint>
#include <memory>

namespace daw {

class MacroVoice {
public:
    MacroVoice();
    ~MacroVoice();

    // Presets: "Name|Group|drum(0/1)|suggested pitch".
    static int presetCount();
    static const char* presetInfo(int index);

    void setSampleRate(int sampleRate);
    void start(int slot, int preset, int pitch, float velocity, int64_t gateSamples);
    void release();
    // Adds `frames` (<= 24) samples to out.
    void render(float* out, int frames);

    bool active = false;
    int slot = -1;
    int pitch = 0;
    int64_t age = 0;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace daw
