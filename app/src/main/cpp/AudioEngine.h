// Owns the Oboe output stream and feeds it from the platform-independent Core.
#pragma once

#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "DawCore.h"

namespace daw {

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool start();
    void stop();
    Core& core() { return core_; }

    /**
     * How long it takes from writing a frame until it is heard, in ms, measured from the
     * stream's timestamps (includes Bluetooth delay on most phones). Negative when unknown.
     */
    double outputLatencyMillis();
    /** Android AudioDeviceInfo id the stream plays to, 0 when unknown. */
    int outputDeviceId();
    /** Latency the buffer alone adds (buffer size / rate), in ms; a floor when timestamps are missing. */
    double bufferLatencyMillis();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream();

    Core core_;
    std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
};

}  // namespace daw
