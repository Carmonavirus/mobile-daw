// Delay: repeats the sound in time with the song. Each echo comes back through a tone filter,
// so repeats get darker as they fade. Normal keeps left and right apart; Ping-pong bounces the
// echoes from side to side. The time glides to a new value instead of jumping, so turning it
// while playing bends the pitch a little (like tape) rather than clicking. Portable C++.
#pragma once

#include <vector>

namespace daw::fx {

// Parameter slots: 0 time (s), 1 feedback (0..0.95), 2 mode (DelayMode), 3 tone (low-pass Hz,
// on the echoes), 4 dry level (0..1), 5 wet level (0..1), 6 stereo offset (s, added to the right side).
enum DelayParam : int {
    kDelayTime = 0,
    kDelayFeedback = 1,
    kDelayMode = 2,
    kDelayTone = 3,
    kDelayDry = 4,
    kDelayWet = 5,
    kDelayOffset = 6,
    kDelayParamCount = 7,
};

enum DelayMode : int { kDelayNormal = 0, kDelayPingPong = 1 };

class Delay {
public:
    static constexpr float kMaxSeconds = 4.0f;
    static constexpr float kMaxRate = 96000.0f;

    Delay();
    void prepare(float sampleRate);
    void reset();
    void process(float* left, float* right, int frames, const float* params);

private:
    float read(const std::vector<float>& buf, float delaySamples) const;

    std::vector<float> bufL_, bufR_;
    int size_ = 0;
    int write_ = 0;
    float timeL_ = 0.0f, timeR_ = 0.0f;  // smoothed, in samples
    float toneL_ = 0.0f, toneR_ = 0.0f;  // one-pole low-pass state
    float fb_ = 0.0f, dry_ = 1.0f, wet_ = 0.0f;
    bool primed_ = false;
    float sampleRate_ = 48000.0f;
};

}  // namespace daw::fx
