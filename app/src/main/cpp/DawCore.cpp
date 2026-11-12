#include "DawCore.h"

#include <algorithm>
#include <cmath>

namespace daw {

Core::Core() {
    for (auto& l : levels_) l.store(0.0f);
    current_ = new Sequence();
}

Core::~Core() {
    delete current_;
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
}

void Core::setSampleRate(int sampleRate) { sampleRate_ = sampleRate > 0 ? sampleRate : 48000; }

void Core::collectGarbage() {
    delete retired_.exchange(nullptr);
}

void Core::setSequence(std::vector<NoteEvent> events, int64_t loopTicks) {
    collectGarbage();
    auto* seq = new Sequence();
    std::stable_sort(events.begin(), events.end(),
                     [](const NoteEvent& a, const NoteEvent& b) { return a.start < b.start; });
    seq->events = std::move(events);
    seq->loopTicks = std::max<int64_t>(loopTicks, kTicksPerStep);
    // If the audio thread has not adopted the previous pending sequence yet, drop it.
    delete pending_.exchange(seq);
}

void Core::setPlaying(bool playing) {
    if (playing && !playing_.load()) restart_.store(true);
    playing_.store(playing);
}

void Core::setChannelCount(int count) {
    channelCount_.store(std::clamp(count, 0, kMaxChannels));
}

void Core::setChannel(int index, int instrument, float volume, float pan, bool mute, bool solo) {
    if (index < 0 || index >= kMaxChannels) return;
    auto& p = params_[index];
    p.instrument.store(instrument);
    p.volume.store(volume);
    p.pan.store(pan);
    p.mute.store(mute);
    p.solo.store(solo);
}

void Core::previewNote(int channel, int pitch, float velocity) {
    int w = previewWrite_.load(std::memory_order_relaxed);
    int next = (w + 1) % kPreviewQueue;
    if (next == previewRead_.load(std::memory_order_acquire)) return;  // full, drop
    previews_[w] = {channel, pitch, velocity};
    previewWrite_.store(next, std::memory_order_release);
}

void Core::readLevels(float* out, int count) const {
    for (int i = 0; i < count && i < kMaxChannels; ++i) out[i] = levels_[i].load();
}

void Core::readScope(float* out, int count) const {
    if (count <= 0) return;
    const int end = scopeWrite_.load();
    const int span = kScopeSize / count;
    for (int b = 0; b < count; ++b) {
        float peak = 0.0f;
        for (int i = 0; i < span; ++i) {
            int idx = (end + b * span + i) % kScopeSize;
            peak = std::max(peak, std::fabs(scope_[idx]));
        }
        out[b] = std::min(peak, 1.0f);
    }
}

void Core::trigger(int channel, int pitch, float velocity, int64_t gateSamples) {
    if (channel < 0 || channel >= kMaxChannels) return;
    auto& pool = voices_[channel];
    const int instrument = params_[channel].instrument.load();
    Voice* target = nullptr;
    if (isDrum(instrument)) {
        // Drums are monophonic per pitch: retrigger the same voice for a tight sound.
        for (auto& v : pool) if (v.active && v.pitch == pitch) { target = &v; break; }
    }
    if (!target) for (auto& v : pool) if (!v.active) { target = &v; break; }
    if (!target) {
        // Steal the oldest voice.
        target = &pool[0];
        for (auto& v : pool) if (v.age > target->age) target = &v;
    }
    // Drums ignore note length, they ring out naturally.
    Synth::start(*target, pitch, velocity, isDrum(instrument) ? 0 : std::max<int64_t>(gateSamples, 1));
}

void Core::releaseAll() {
    for (auto& pool : voices_)
        for (auto& v : pool) Synth::release(v);
}

void Core::advanceTick(const Sequence* seq) {
    const auto& ev = seq->events;
    const double samplesPerTick = sampleRate_ * 60.0 / (std::max(bpm_.load(), 20.0f) * kTicksPerBeat);
    while (eventIndex_ < ev.size() && ev[eventIndex_].start <= tick_) {
        const auto& e = ev[eventIndex_];
        if (e.start == tick_) {
            trigger(e.channel, e.pitch, e.velocity, static_cast<int64_t>(e.length * samplesPerTick));
        }
        ++eventIndex_;
    }
}

void Core::render(float* out, int frames) {
    // Adopt a newly published sequence, but only once the UI has freed the last retired one,
    // so the audio thread never frees memory.
    if (retired_.load() == nullptr) {
        if (Sequence* next = pending_.exchange(nullptr)) {
            retired_.store(current_);
            current_ = next;
            if (tick_ >= current_->loopTicks) tick_ = 0;
            // Events on the current tick already fired from the previous sequence.
            eventIndex_ = static_cast<size_t>(
                std::upper_bound(current_->events.begin(), current_->events.end(), tick_,
                                 [](int64_t t, const NoteEvent& e) { return t < e.start; }) -
                current_->events.begin());
        }
    }

    // Preview notes from the UI.
    int r = previewRead_.load(std::memory_order_relaxed);
    while (r != previewWrite_.load(std::memory_order_acquire)) {
        const auto& p = previews_[r];
        trigger(p.channel, p.pitch, p.velocity, sampleRate_ / 4);
        r = (r + 1) % kPreviewQueue;
        previewRead_.store(r, std::memory_order_release);
    }

    const bool playing = playing_.load();
    if (restart_.exchange(false)) {
        tick_ = 0;
        tickFraction_ = 0.0;
        eventIndex_ = 0;
        if (playing) advanceTick(current_);
    }
    if (wasPlaying_ && !playing) releaseAll();
    wasPlaying_ = playing;

    const double ticksPerSample = std::max(bpm_.load(), 20.0f) * kTicksPerBeat / (60.0 * sampleRate_);
    const int channelCount = channelCount_.load();
    bool anySolo = false;
    for (int c = 0; c < channelCount; ++c) anySolo |= params_[c].solo.load();

    float gains[kMaxChannels][2];
    int instruments[kMaxChannels];
    bool audible[kMaxChannels];
    float peaks[kMaxChannels] = {};
    for (int c = 0; c < channelCount; ++c) {
        auto& p = params_[c];
        float pan = std::clamp(p.pan.load(), -1.0f, 1.0f);
        float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
        float vol = p.volume.load();
        gains[c][0] = std::cos(angle) * vol * 1.414f;
        gains[c][1] = std::sin(angle) * vol * 1.414f;
        instruments[c] = p.instrument.load();
        audible[c] = !p.mute.load() && (!anySolo || p.solo.load());
    }
    const float master = master_.load();
    const float sr = static_cast<float>(sampleRate_);
    float masterPeak = 0.0f;
    int scopeW = scopeWrite_.load();

    for (int i = 0; i < frames; ++i) {
        if (playing) {
            tickFraction_ += ticksPerSample;
            while (tickFraction_ >= 1.0) {
                tickFraction_ -= 1.0;
                ++tick_;
                if (tick_ >= current_->loopTicks) {
                    tick_ = 0;
                    eventIndex_ = 0;
                }
                advanceTick(current_);
            }
        }
        float left = 0.0f, right = 0.0f;
        for (int c = 0; c < channelCount; ++c) {
            float sum = 0.0f;
            for (auto& v : voices_[c]) {
                if (v.active) sum += Synth::tick(v, instruments[c], sr);
            }
            if (!audible[c]) continue;
            float l = sum * gains[c][0];
            float rr = sum * gains[c][1];
            peaks[c] = std::max(peaks[c], std::max(std::fabs(l), std::fabs(rr)));
            left += l;
            right += rr;
        }
        left = std::tanh(left * master);
        right = std::tanh(right * master);
        out[2 * i] = left;
        out[2 * i + 1] = right;
        float mono = 0.5f * (left + right);
        masterPeak = std::max(masterPeak, std::fabs(mono));
        scope_[scopeW] = mono;
        scopeW = (scopeW + 1) % kScopeSize;
    }
    scopeWrite_.store(scopeW);
    position_.store(tick_);

    // Simple peak-hold meters with a smooth fall-off.
    for (int c = 0; c < kMaxChannels; ++c) {
        float prev = levels_[c].load();
        float now = c < channelCount ? std::min(peaks[c], 1.0f) : 0.0f;
        levels_[c].store(std::max(now, prev * 0.85f));
    }
    masterLevel_.store(std::max(masterPeak, masterLevel_.load() * 0.85f));
}

}  // namespace daw
