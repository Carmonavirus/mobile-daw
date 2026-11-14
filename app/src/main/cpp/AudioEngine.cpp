#include "AudioEngine.h"

#include <algorithm>
#include <chrono>
#include <thread>

#include <android/log.h>

#define LOG_TAG "MobileDawEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace daw {

bool AudioEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        return false;
    }
    // Two bursts of buffering is a good latency/glitch trade-off on most phones.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    core_.setSampleRate(stream_->getSampleRate());
    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        return false;
    }
    return true;
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> guard(lock_);
    if (stream_) return true;
    return openStream();
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    // inCallback_ is raised before offline_ is read, and beginOffline raises offline_ before it
    // reads inCallback_, so the two threads can never both be inside the Core.
    inCallback_.store(true);
    if (offline_.load()) {
        std::fill_n(static_cast<float*>(audioData), static_cast<size_t>(numFrames) * 2, 0.0f);
    } else {
        core_.render(static_cast<float*>(audioData), numFrames);
    }
    inCallback_.store(false);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::beginOffline() {
    offline_.store(true);
    while (inCallback_.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

void AudioEngine::endOffline() {
    offline_.store(false);
}

double AudioEngine::outputLatencyMillis() {
    std::lock_guard<std::mutex> guard(lock_);
    if (!stream_ || stream_->getState() != oboe::StreamState::Started) return -1.0;
    auto result = stream_->calculateLatencyMillis();
    return result ? result.value() : -1.0;
}

int AudioEngine::outputDeviceId() {
    std::lock_guard<std::mutex> guard(lock_);
    return stream_ ? stream_->getDeviceId() : 0;
}

double AudioEngine::bufferLatencyMillis() {
    std::lock_guard<std::mutex> guard(lock_);
    if (!stream_ || stream_->getSampleRate() <= 0) return -1.0;
    return 1000.0 * stream_->getBufferSizeInFrames() / stream_->getSampleRate();
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // A disconnect (headphone plug/unplug, or another app taking exclusive audio) closes the
    // stream. Reopen so playback resumes on the current device instead of going silent.
    LOGE("stream closed with error: %s; reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> guard(lock_);
    stream_.reset();
    openStream();
}

}  // namespace daw
