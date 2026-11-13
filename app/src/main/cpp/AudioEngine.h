// Owns the Oboe output stream and feeds it from the platform-independent Core.
#pragma once

#include <atomic>
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

    /**
     * Export: takes the Core away from the live stream (which plays silence meanwhile) so another
     * thread can render it offline with Core::renderMix. Returns once no callback is inside the Core.
     */
    void beginOffline();
    void endOffline();
    bool offline() const { return offline_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream();

    Core core_;
    std::mutex lock_;
    std::atomic<bool> offline_{false};
    std::atomic<bool> inCallback_{false};
    std::shared_ptr<oboe::AudioStream> stream_;
};

}  // namespace daw
