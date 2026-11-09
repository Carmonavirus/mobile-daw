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
