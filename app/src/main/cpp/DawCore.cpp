#include "DawCore.h"

#include <algorithm>
#include <cmath>

namespace daw {

Core::Core() {
    for (auto& l : levels_) l.store(0.0f);
    for (int i = 0; i < kSampleSlots; ++i) {
        pendingSample_[i].store(nullptr);
        retiredSample_[i].store(nullptr);
        sample_[i] = nullptr;
    }
    current_ = new Sequence();
}

Core::~Core() {
    for (int i = 0; i < kSampleSlots; ++i) {
        delete sample_[i];
        delete pendingSample_[i].exchange(nullptr);
        delete retiredSample_[i].exchange(nullptr);
    }
    delete current_;
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
}

void Core::setSampleRate(int sampleRate) { sampleRate_ = sampleRate > 0 ? sampleRate : 48000; }

void Core::collectGarbage() {
    delete retired_.exchange(nullptr);
    for (auto& r : retiredSample_) delete r.exchange(nullptr);
}

void Core::setSample(int slot, std::vector<float> frames, float sampleRate) {
    if (slot < 0 || slot >= kSampleSlots) return;
    collectGarbage();
    auto* data = new SampleData();
    data->frames = std::move(frames);
    data->sampleRate = sampleRate > 0 ? sampleRate : 44100.0f;
    delete pendingSample_[slot].exchange(data);
}

void Core::adoptSamples() {
    for (int i = 0; i < kSampleSlots; ++i) {
        if (retiredSample_[i].load() != nullptr) continue;
        SampleData* next = pendingSample_[i].exchange(nullptr);
        if (!next) continue;
        // Voices may point into the old buffer, so silence them before handing it back.
        if (i == kAuditionSlot) {
            auditionVoice_.active = false;
        } else {
            for (auto& v : voices_[i]) v.active = false;
        }
        retiredSample_[i].store(sample_[i]);
        sample_[i] = next;
    }
}

SampleShape Core::shapeFor(int channel) const {
    SampleShape shape;
    const SampleData* data = (channel >= 0 && channel < kSampleSlots) ? sample_[channel] : nullptr;
    if (!data || data->frames.empty()) return shape;
    const double n = static_cast<double>(data->frames.size());
    if (channel >= kMaxChannels) {
        shape.end = n;
        return shape;
    }
    const auto& p = params_[channel];
    float a = std::clamp(p.trimStart.load(), 0.0f, 1.0f);
    float b = std::clamp(p.trimEnd.load(), 0.0f, 1.0f);
    if (b - a < 0.001f) b = std::min(1.0f, a + 0.001f);
    shape.start = a * n;
    shape.end = b * n;
    shape.attack = std::max(0.0f, p.attack.load()) * data->sampleRate;
    shape.release = std::max(0.0f, p.release.load()) * data->sampleRate;
    return shape;
}

float Core::sampleTick(Voice& v, const SampleData* data, const SampleShape* shape) {
    if (!data || data->frames.empty()) {
        v.active = false;
        return 0.0f;
    }
    const auto& f = data->frames;
    const double end = shape ? std::min(shape->end, static_cast<double>(f.size())) : static_cast<double>(f.size());
    const double pos = v.phase1;
    const size_t i = static_cast<size_t>(pos);
    if (pos + 1.0 >= end || i + 1 >= f.size()) {
        v.active = false;
        return 0.0f;
    }
    const float frac = static_cast<float>(pos - static_cast<double>(i));
    float s = f[i] + (f[i + 1] - f[i]) * frac;
    if (shape) {
        // Attack fades in from the trim start, release fades out before the trim end.
        double env = 1.0;
        if (shape->attack > 1.0) env = std::min(env, (pos - shape->start) / shape->attack);
        if (shape->release > 1.0) env = std::min(env, (end - pos) / shape->release);
        s *= static_cast<float>(std::clamp(env, 0.0, 1.0));
    }
    v.phase1 += v.phase2;  // phase2 holds the playback rate for sample voices
    return s * v.velocity;
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
    if (!playing) startTick_.store(0);
    playing_.store(playing);
}

void Core::seek(int64_t tick) {
    tick = std::max<int64_t>(tick, 0);
    if (playing_.load()) {
        seek_.store(tick);
    } else {
        startTick_.store(tick);
        position_.store(tick);
    }
}

// Audio thread: puts the transport at `tick`; events on that tick fire on the next advanceTick.
void Core::jumpTo(int64_t tick) {
    tick_ = (current_ && tick < current_->loopTicks) ? tick : 0;
    tickFraction_ = 0.0;
    eventIndex_ = current_ ? static_cast<size_t>(
        std::lower_bound(current_->events.begin(), current_->events.end(), tick_,
                         [](const NoteEvent& e, int64_t t) { return e.start < t; }) -
        current_->events.begin()) : 0;
}

void Core::setChannelSampler(int index, bool cutItself, float trimStart, float trimEnd, float attack, float release) {
    if (index < 0 || index >= kMaxChannels) return;
    auto& p = params_[index];
    p.cutItself.store(cutItself);
    p.trimStart.store(trimStart);
    p.trimEnd.store(trimEnd);
    p.attack.store(attack);
    p.release.store(release);
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
    const bool cut = params_[channel].cutItself.load();
    Voice* target = nullptr;
    if (cut) {
        // "Cut itself": every hit chokes whatever this channel is still playing (4 ms fade).
        const float step = 1.0f / (0.004f * static_cast<float>(sampleRate_));
        for (auto& v : pool) {
            if (v.active && v.fadeStep == 0.0f) v.fadeStep = step;
        }
    } else if (isDrum(instrument) && instrument != kSample) {
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
    if (instrument == kSample) {
        const SampleData* data = sample_[channel];
        const float rate = data ? data->sampleRate : static_cast<float>(sampleRate_);
        target->phase2 = rate / sampleRate_ * std::pow(2.0, (pitch - 60) / 12.0);
        target->phase1 = shapeFor(channel).start;
    }
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

    adoptSamples();
    if (auditionTrigger_.exchange(false)) {
        const SampleData* data = sample_[kAuditionSlot];
        Synth::start(auditionVoice_, 60, 0.9f, 0);
        auditionVoice_.phase2 = (data ? data->sampleRate : static_cast<float>(sampleRate_)) / sampleRate_;
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
        seek_.store(-1);
        jumpTo(startTick_.exchange(0));
        if (playing) advanceTick(current_);
    } else if (const int64_t s = seek_.exchange(-1); s >= 0 && playing) {
        releaseAll();
        jumpTo(s);
        advanceTick(current_);
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
    SampleShape shapes[kMaxChannels];
    for (int c = 0; c < channelCount; ++c) {
        if (instruments[c] == kSample) shapes[c] = shapeFor(c);
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
                if (!v.active) continue;
                float x = instruments[c] == kSample ? sampleTick(v, sample_[c], &shapes[c]) : Synth::tick(v, instruments[c], sr);
                if (v.fadeStep > 0.0f) {
                    x *= v.fade;
                    v.fade -= v.fadeStep;
                    if (v.fade <= 0.0f) v.active = false;
                }
                sum += x;
            }
            if (!audible[c]) continue;
            float l = sum * gains[c][0];
            float rr = sum * gains[c][1];
            peaks[c] = std::max(peaks[c], std::max(std::fabs(l), std::fabs(rr)));
            left += l;
            right += rr;
        }
        if (auditionVoice_.active) {
            const float a = sampleTick(auditionVoice_, sample_[kAuditionSlot]) * 0.8f;
            left += a;
            right += a;
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
    // Stopped: show where the next play will start.
    position_.store(playing ? tick_ : startTick_.load());

    // Simple peak-hold meters with a smooth fall-off.
    for (int c = 0; c < kMaxChannels; ++c) {
        float prev = levels_[c].load();
        float now = c < channelCount ? std::min(peaks[c], 1.0f) : 0.0f;
        levels_[c].store(std::max(now, prev * 0.85f));
    }
    masterLevel_.store(std::max(masterPeak, masterLevel_.load() * 0.85f));
}

}  // namespace daw
