// One polyphonic voice of the "Modeled" instrument: drum and voice models from DaisySP (MIT)
// and physical models from the Synthesis ToolKit, STK (MIT-style licence).
#pragma once

#include <cstdint>
#include <memory>

namespace daw {

class ModelVoice {
public:
    ModelVoice();
    ~ModelVoice();

    // Presets: "Name|Group|drum(0/1)|suggested pitch|library".
    static int presetCount();
    static const char* presetInfo(int index);

    // Call before audio starts; STK's sample rate is global.
    void setSampleRate(int sampleRate);
    void start(int slot, int preset, int pitch, float velocity, int64_t gateSamples);
    void release();
    // Adds `frames` samples to out.
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
