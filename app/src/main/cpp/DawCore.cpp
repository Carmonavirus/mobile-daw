#include "DawCore.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <thread>

namespace daw {

Core::Core() : inserts_(std::make_unique<std::array<fx::Insert, fx::kMaxInserts>>()) {
    for (auto& l : levels_) l.store(0.0f);
    for (int i = 0; i < kSampleSlots; ++i) {
        pendingSample_[i].store(nullptr);
        retiredSample_[i].store(nullptr);
        sample_[i] = nullptr;
    }
    current_ = new Sequence();
    const wavesynth::Patch defaults;
    for (auto& channel : synthParams_)
        for (int i = 0; i < wavesynth::kParamCount; ++i) channel[static_cast<size_t>(i)].store(defaults[i]);
    // Build the wavetables in the background; synth channels stay silent for the moment it takes.
    std::thread([] { wavesynth::Bank::get(); }).detach();
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

void Core::setSampleRate(int sampleRate) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    engines_.setSampleRate(sampleRate_);
}

void Core::collectGarbage() {
    delete retired_.exchange(nullptr);
    for (auto& r : retiredSample_) delete r.exchange(nullptr);
    engines_.collectGarbage();
}

void Core::setSample(int slot, std::vector<float> left, std::vector<float> right, float sampleRate) {
    if (slot < 0 || slot >= kSampleSlots) return;
    collectGarbage();
    auto* data = new SampleData();
    const float src = sampleRate > 0 ? sampleRate : 44100.0f;
    const float dst = static_cast<float>(sampleRate_);
    if (right.size() != left.size()) right.clear();
    if (std::fabs(src - dst) > 0.5f && !left.empty()) {
        data->frames = resampleSinc(left, src, dst);
        if (!right.empty()) data->right = resampleSinc(right, src, dst);
        data->sampleRate = dst;
    } else {
        data->frames = std::move(left);
        data->right = std::move(right);
        data->sampleRate = src;
    }
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
    shape.delay = std::max(0.0f, p.envDelay.load()) * data->sampleRate;
    shape.attack = std::max(0.0f, p.attack.load()) * data->sampleRate;
    shape.hold = std::max(0.0f, p.hold.load()) * data->sampleRate;
    shape.decay = std::max(0.0f, p.decay.load()) * data->sampleRate;
    shape.sustain = std::clamp(p.sustain.load(), 0.0f, 1.0f);
    shape.release = std::max(0.0f, p.release.load()) * data->sampleRate;
    shape.tension = std::clamp(p.tension.load(), -1.0f, 1.0f);
    return shape;
}

namespace {
// Bends a 0..1 ramp by a tension amount in [-1, 1]. 0 is linear; positive eases out (fast then
// slow), negative eases in (slow then fast). Same shape FL's envelope tension gives.
// 4-point cubic Hermite interpolation at fraction t between y1 and y2. Far flatter in the treble
// than straight-line interpolation (which audibly dulls highs), and exact at t == 0.
inline float hermite(float y0, float y1, float y2, float y3, float t) {
    const float c1 = 0.5f * (y2 - y0);
    const float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    const float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
    return ((c3 * t + c2) * t + c1) * t + y1;
}

inline float interpAt(const std::vector<float>& f, size_t i, float t) {
    const size_t n = f.size();
    const float y1 = f[i];
    if (t == 0.0f) return y1;
    const float y0 = i > 0 ? f[i - 1] : y1;
    const float y2 = i + 1 < n ? f[i + 1] : y1;
    const float y3 = i + 2 < n ? f[i + 2] : y2;
    return hermite(y0, y1, y2, y3, t);
}

constexpr double kPi = 3.14159265358979323846;

double besselI0(double x) {
    double sum = 1.0, term = 1.0;
    for (int k = 1; k < 32; ++k) {
        term *= (x / (2.0 * k)) * (x / (2.0 * k));
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

}  // namespace

// Kaiser-windowed sinc resampler (32 zero crossings each side, flat to ~90% of the
// lower Nyquist, >80 dB stopband). Runs once per sample load on the control thread.
std::vector<float> resampleSinc(const std::vector<float>& in, double srcRate, double dstRate) {
    if (in.empty() || srcRate <= 0.0 || dstRate <= 0.0) return in;
    const double ratio = srcRate / dstRate;  // input samples per output sample
    const double fc = std::min(1.0, 1.0 / ratio) * 0.92;  // cutoff, relative to the input Nyquist
    constexpr int kZeroCrossings = 32;
    const int half = static_cast<int>(std::ceil(kZeroCrossings / fc));  // half width in input samples
    const int taps = 2 * half;
    constexpr double kBeta = 8.6;
    const double i0b = besselI0(kBeta);
    const auto kernel = [&](double d) {
        d = std::fabs(d);
        if (d >= half) return 0.0;
        const double x = fc * d;
        const double sinc = x < 1e-9 ? 1.0 : std::sin(kPi * x) / (kPi * x);
        const double r = d / half;
        return fc * sinc * besselI0(kBeta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
    };
    const int64_t n = static_cast<int64_t>(in.size());
    const int64_t outLen = std::max(int64_t{1}, static_cast<int64_t>(std::floor(static_cast<double>(n) / ratio)));
    std::vector<float> out(static_cast<size_t>(outLen));

    // Whole-number rates (44100 -> 48000 is 147:160) use a precomputed polyphase table, so each
    // output sample is one straight dot product. Odd rates fall back to an interpolated kernel.
    const int64_t src = std::llround(srcRate), dst = std::llround(dstRate);
    int64_t g = src, h = dst;
    while (h != 0) { const int64_t t = g % h; g = h; h = t; }
    const bool integral = std::fabs(srcRate - src) < 1e-6 && std::fabs(dstRate - dst) < 1e-6 && g > 0;
    const int64_t phases = integral ? dst / g : 0;
    const int64_t step = integral ? src / g : 0;
    if (integral && phases <= 4096) {
        std::vector<float> table(static_cast<size_t>(phases * taps));
        for (int64_t p = 0; p < phases; ++p) {
            const double frac = static_cast<double>(p) / phases;
            for (int j = 0; j < taps; ++j)
                table[static_cast<size_t>(p * taps + j)] = static_cast<float>(kernel(frac + (half - 1 - j)));
        }
        for (int64_t o = 0; o < outLen; ++o) {
            const int64_t num = o * step;
            const int64_t c = num / phases, p = num % phases;
            const int64_t first = c - half + 1;
            const float* w = &table[static_cast<size_t>(p * taps)];
            float acc = 0.0f;
            if (first >= 0 && first + taps <= n) {
                const float* x = &in[static_cast<size_t>(first)];
                for (int j = 0; j < taps; ++j) acc += x[j] * w[j];
            } else {
                for (int j = 0; j < taps; ++j) {
                    const int64_t k = first + j;
                    if (k >= 0 && k < n) acc += in[static_cast<size_t>(k)] * w[j];
                }
            }
            out[static_cast<size_t>(o)] = acc;
        }
        return out;
    }

    constexpr int kRes = 512;  // kernel table points per input sample
    std::vector<float> table(static_cast<size_t>(half * kRes + 2), 0.0f);
    for (size_t j = 0; j < table.size(); ++j) table[j] = static_cast<float>(kernel(static_cast<double>(j) / kRes));
    const auto lookup = [&](double d) {
        const double q = std::fabs(d) * kRes;
        const size_t j = static_cast<size_t>(q);
        if (j + 1 >= table.size()) return 0.0f;
        const float t = static_cast<float>(q - static_cast<double>(j));
        return table[j] + (table[j + 1] - table[j]) * t;
    };
    for (int64_t o = 0; o < outLen; ++o) {
        const double t = static_cast<double>(o) * ratio;
        const int64_t c = static_cast<int64_t>(std::floor(t));
        const int64_t a = std::max(int64_t{0}, c - half + 1), b = std::min(n - 1, c + half);
        float acc = 0.0f;
        for (int64_t k = a; k <= b; ++k) acc += in[static_cast<size_t>(k)] * lookup(t - static_cast<double>(k));
        out[static_cast<size_t>(o)] = acc;
    }
    return out;
}

namespace {

double envWarp(double x, double tension) {
    if (std::fabs(tension) < 1e-4) return x;
    const double k = tension * 5.0;
    return (std::exp(k * x) - 1.0) / (std::exp(k) - 1.0);
}
}  // namespace

float Core::sampleTick(Voice& v, const SampleData* data, const SampleShape* shape, float* right) {
    if (!data || data->frames.empty()) {
        v.active = false;
        return 0.0f;
    }
    const auto& f = data->frames;
    const double lo = shape ? shape->start : 0.0;
    const double hi = shape ? std::min(shape->end, static_cast<double>(f.size())) : static_cast<double>(f.size());
    const double pos = v.phase1;
    const bool forward = v.phase2 >= 0.0;
    // Stop at whichever edge we are travelling towards (flips for reverse playback).
    const bool reachedEdge = forward ? (pos + 1.0 >= hi) : (pos <= lo);
    if (reachedEdge || pos < lo || pos >= hi) {
        v.active = false;
        return 0.0f;
    }
    const size_t i = static_cast<size_t>(pos);
    if (i + 1 >= f.size()) { v.active = false; return 0.0f; }
    const float frac = static_cast<float>(pos - static_cast<double>(i));
    float s = interpAt(f, i, frac);
    const bool stereo = !data->right.empty();
    float sr = stereo ? interpAt(data->right, i, frac) : s;
    if (shape) {
        // Time-based delay / attack / hold / decay / sustain from the trigger, plus a position-based
        // release that fades the tail before the end edge (edges flip for reverse playback). Tension
        // curves the attack, decay and release segments.
        const double t = static_cast<double>(v.age);
        const double dly = shape->delay;
        const double a = shape->attack;
        const double h = shape->hold;
        const double d = shape->decay;
        const double tn = shape->tension;
        double env;
        if (t < dly) {
            env = 0.0;  // delay: silent before the attack begins
        } else if (a > 1.0 && t < dly + a) {
            env = envWarp((t - dly) / a, tn);  // attack ramp 0 -> 1
        } else if (t < dly + a + h) {
            env = 1.0;  // hold at full
        } else if (d > 1.0 && t < dly + a + h + d) {
            env = 1.0 - (1.0 - shape->sustain) * envWarp((t - dly - a - h) / d, tn);  // decay 1 -> sustain
        } else {
            env = shape->sustain;  // held at the sustain level
        }
        const double toEnd = forward ? (hi - pos) : (pos - lo);
        if (shape->release > 1.0) env = std::min(env, envWarp(toEnd / shape->release, tn));
        const float e = static_cast<float>(std::clamp(env, 0.0, 1.0));
        s *= e;
        sr *= e;
    }
    v.phase1 += v.phase2;  // phase2 holds the signed playback rate for sample voices
    v.age++;               // envelope time advances even though samples bypass Synth::tick
    if (right) *right = sr * v.velocity;
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
    if (!playing) countInRequest_.store(-1);
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

void Core::setChannelSampler(int index, bool cutItself, float trimStart, float trimEnd, float envDelay, float attack,
                             float hold, float decay, float sustain, float release, float tension, int pitchSemis,
                             bool reverse) {
    if (index < 0 || index >= kMaxChannels) return;
    auto& p = params_[index];
    p.cutItself.store(cutItself);
    p.trimStart.store(trimStart);
    p.trimEnd.store(trimEnd);
    p.envDelay.store(envDelay);
    p.attack.store(attack);
    p.hold.store(hold);
    p.decay.store(decay);
    p.sustain.store(sustain);
    p.release.store(release);
    p.tension.store(tension);
    p.pitchSemis.store(pitchSemis);
    p.reverse.store(reverse);
}

void Core::setChannelClip(int index, bool resumeMidway) {
    if (index < 0 || index >= kMaxChannels) return;
    params_[index].resumeMidway.store(resumeMidway);
}

// Audio thread: after a jump, starts any audio-clip sample that should already be sounding
// at tick_, from the matching point inside it.
void Core::resumeLongSamples() {
    if (!current_) return;
    const double samplesPerTick = sampleRate_ * 60.0 / (std::max(bpm_.load(), 20.0f) * kTicksPerBeat);
    const auto& ev = current_->events;
    for (size_t i = 0; i < ev.size() && ev[i].start < tick_; ++i) {
        const auto& e = ev[i];
        if (e.channel < 0 || e.channel >= kMaxChannels) continue;
        if (!params_[e.channel].resumeMidway.load()) continue;
        if (tick_ >= e.start + e.length) continue;
        if (Voice* v = trigger(e.channel, e.pitch, e.velocity, 0)) {
            v->phase1 += static_cast<double>(tick_ - e.start) * samplesPerTick * v->phase2;
        }
    }
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

void Core::setChannelInsert(int channel, int insert) {
    if (channel < 0 || channel >= kMaxChannels) return;
    params_[channel].insert.store(insert >= 0 && insert < fx::kMaxInserts ? insert : -1);
}

void Core::setInsert(int index, float volume, float pan, bool mute) {
    if (index < 0 || index >= fx::kMaxInserts) return;
    auto& ins = (*inserts_)[static_cast<size_t>(index)];
    ins.volume.store(volume);
    ins.pan.store(pan);
    ins.mute.store(mute);
}

void Core::setInsertEffect(int index, int slot, int type, bool bypass) {
    if (index < 0 || index >= fx::kMaxInserts || slot < 0 || slot >= fx::kMaxSlots) return;
    auto& s = (*inserts_)[static_cast<size_t>(index)].slots[static_cast<size_t>(slot)];
    s.type.store(type);
    s.bypass.store(bypass);
}

void Core::setEffectParam(int index, int slot, int param, float value) {
    if (index < 0 || index >= fx::kMaxInserts || slot < 0 || slot >= fx::kMaxSlots || param < 0 || param >= fx::kSlotParams) return;
    (*inserts_)[static_cast<size_t>(index)].slots[static_cast<size_t>(slot)].params[static_cast<size_t>(param)].store(value);
}

void Core::readInsertLevels(float* out, int count) const {
    for (int i = 0; i < count && i < fx::kMaxInserts; ++i) out[i] = (*inserts_)[static_cast<size_t>(i)].level.load();
}

void Core::readInsertSignal(int index, float* out, int count) const {
    if (index < 0 || index >= fx::kMaxInserts) return;
    const auto& ins = (*inserts_)[static_cast<size_t>(index)];
    count = std::min(count, fx::kSpectrumSize);
    const int end = ins.spectrumWrite.load();
    for (int i = 0; i < count; ++i)
        out[i] = ins.spectrum[static_cast<size_t>((end - count + i + fx::kSpectrumSize) % fx::kSpectrumSize)];
}

void Core::readPitch(int index, int slot, float* out) const {
    out[0] = out[1] = 0.0f;
    if (index < 0 || index >= fx::kMaxInserts || slot < 0 || slot >= fx::kMaxSlots) return;
    const auto& s = (*inserts_)[static_cast<size_t>(index)].slots[static_cast<size_t>(slot)];
    out[0] = s.pitch.detectedHz();
    out[1] = s.pitch.targetHz();
}

void Core::previewNote(int channel, int pitch, float velocity) { pushPreview({channel, pitch, velocity, 0}); }

void Core::previewHold(int channel, int pitch, float velocity, bool on) {
    pushPreview({channel, pitch, velocity, on ? 1 : -1});
}

void Core::pushPreview(const Preview& p) {
    int w = previewWrite_.load(std::memory_order_relaxed);
    int next = (w + 1) % kPreviewQueue;
    if (next == previewRead_.load(std::memory_order_acquire)) return;  // full, drop
    previews_[w] = p;
    previewWrite_.store(next, std::memory_order_release);
}

void Core::setSynthParam(int channel, int param, float value) {
    if (channel < 0 || channel >= kMaxChannels || param < 0 || param >= wavesynth::kParamCount) return;
    synthParams_[static_cast<size_t>(channel)][static_cast<size_t>(param)].store(value);
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

Voice* Core::trigger(int channel, int pitch, float velocity, int64_t gateSamples) {
    if (channel < 0 || channel >= kMaxChannels) return nullptr;
    auto& pool = voices_[channel];
    const int instrument = params_[channel].instrument.load();
    if (SoundEngines::handles(instrument)) {
        engines_.noteOn(channel, pitch, velocity, gateSamples);
        return nullptr;
    }
    const bool cut = params_[channel].cutItself.load();
    if (instrument == kWavetable) {
        auto& synth = synths_[static_cast<size_t>(channel)];
        if (cut) synth.choke();
        synth.noteOn(pitch, velocity, gateSamples);
        return nullptr;
    }
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
        const int transpose = params_[channel].pitchSemis.load();
        const double step = rate / sampleRate_ * std::pow(2.0, (pitch - 60 + transpose) / 12.0);
        const SampleShape shape = shapeFor(channel);
        if (params_[channel].reverse.load()) {
            // Start just inside the trim end so interpolation still has a next sample.
            const double last = data ? static_cast<double>(data->frames.size()) - 2.0 : shape.end - 2.0;
            target->phase1 = std::max(shape.start, std::min(shape.end - 1.0, last));
            target->phase2 = -step;  // walk backwards from the trim end
        } else {
            target->phase1 = shape.start;
            target->phase2 = step;
        }
    }
    return target;
}

void Core::releaseAll() {
    // Synth voices go into their release; sample voices (one-shots and audio clips) don't use
    // that envelope, so they get a short fade instead, or a long clip would keep playing on stop.
    const float step = 1.0f / (0.01f * static_cast<float>(sampleRate_));
    for (int c = 0; c < kMaxChannels; ++c) {
        const bool sample = params_[c].instrument.load() == kSample;
        for (auto& v : voices_[c]) {
            if (!v.active) continue;
            if (sample) {
                if (v.fadeStep == 0.0f) v.fadeStep = step;
            } else {
                Synth::release(v);
            }
        }
    }
    for (auto& s : synths_) s.releaseAll();
    engines_.releaseAll();
}

void Core::silenceAll(float seconds) {
    const float step = 1.0f / (std::max(seconds, 0.0005f) * static_cast<float>(sampleRate_));
    for (auto& pool : voices_)
        for (auto& v : pool)
            if (v.active) v.fadeStep = std::max(v.fadeStep, step);
    for (auto& s : synths_) s.choke();
    auditionVoice_.active = false;
    clickEnv_ = 0.0f;
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
    while (frames > 0) {
        const int n = std::min(frames, kBlock);
        renderBlock(out, n, true);
        out += 2 * n;
        frames -= n;
    }
}

void Core::renderMix(float* out, int frames) {
    while (frames > 0) {
        const int n = std::min(frames, kBlock);
        renderBlock(out, n, false);
        out += 2 * n;
        frames -= n;
    }
}

// Audio thread: a short pitched blip, higher and louder on the downbeat.
void Core::startClick(bool accent) {
    clickPhase_ = 0.0;
    clickFreq_ = accent ? 1760.0 : 1175.0;
    clickGain_ = accent ? 0.55f : 0.35f;
    clickEnv_ = 1.0f;
    // About 40 ms to fade to -60 dB.
    clickDecay_ = std::exp(-6.9f / (0.04f * static_cast<float>(sampleRate_)));
}

float Core::clickTick() {
    if (clickEnv_ < 1e-4f) return 0.0f;
    // A 1 ms ramp-in keeps the attack from popping.
    const float ramp = std::min(1.0f, static_cast<float>(clickPhase_) * 1000.0f);
    const float s = std::sin(static_cast<float>(2.0 * 3.14159265358979 * clickFreq_ * clickPhase_)) * clickEnv_ * clickGain_ * ramp;
    clickPhase_ += 1.0 / sampleRate_;
    clickEnv_ *= clickDecay_;
    return s;
}

void Core::renderBlock(float* out, int frames, bool withClick) {
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
        if (p.hold < 0) {
            if (p.channel >= 0 && p.channel < kMaxChannels) synths_[static_cast<size_t>(p.channel)].noteOff(p.pitch);
        } else {
            // A held press only stays down on synth channels; everything else plays a short note.
            const bool held = p.hold > 0 && p.channel >= 0 && p.channel < kMaxChannels &&
                              params_[p.channel].instrument.load() == kWavetable;
            trigger(p.channel, p.pitch, p.velocity, held ? 0 : sampleRate_ / 4);
        }
        r = (r + 1) % kPreviewQueue;
        previewRead_.store(r, std::memory_order_release);
    }

    // Count-in requests: start a fresh one, or drop the running one.
    if (const int req = countInRequest_.exchange(0); req != 0) {
        countInBeats_ = req > 0 && !playing_.load() ? req : 0;
        countInDone_ = 0;
        countInSamples_ = 0.0;
        countInBeat_.store(0);
    }
    if (countInBeats_ > 0 && playing_.load()) {  // play pressed during the count-in
        countInBeats_ = 0;
        countInBeat_.store(0);
    }

    bool playing = playing_.load();
    if (restart_.exchange(false)) {
        seek_.store(-1);
        jumpTo(startTick_.exchange(0));
        if (playing) {
            resumeLongSamples();
            advanceTick(current_);
            clickPending_ = tick_ % kTicksPerBeat == 0;
        }
    } else if (const int64_t s = seek_.exchange(-1); s >= 0 && playing) {
        releaseAll();
        jumpTo(s);
        resumeLongSamples();
        advanceTick(current_);
        clickPending_ = tick_ % kTicksPerBeat == 0;
    }
    if (wasPlaying_ && !playing) releaseAll();
    wasPlaying_ = playing;
    if (panic_.exchange(false)) silenceAll(0.003f);

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
        auto& synth = synths_[static_cast<size_t>(c)];
        if (instruments[c] == kWavetable) {
            wavesynth::Patch patch;
            for (int i = 0; i < wavesynth::kParamCount; ++i)
                patch.v[static_cast<size_t>(i)] = synthParams_[static_cast<size_t>(c)][static_cast<size_t>(i)].load(std::memory_order_relaxed);
            synth.prepare(patch, static_cast<float>(sampleRate_), bpm_.load());
        } else if (synth.anyActive()) {
            synth.silence();  // the channel switched to another instrument
        }
    }
    const int insertCount = insertCount_.load();
    int routes[kMaxChannels];
    for (int c = 0; c < channelCount; ++c) {
        const int r = params_[c].insert.load();
        routes[c] = r < insertCount ? r : -1;
    }
    for (int b = 0; b < insertCount; ++b) {
        std::fill(busL_[static_cast<size_t>(b)].begin(), busL_[static_cast<size_t>(b)].begin() + frames, 0.0f);
        std::fill(busR_[static_cast<size_t>(b)].begin(), busR_[static_cast<size_t>(b)].begin() + frames, 0.0f);
    }
    SampleShape shapes[kMaxChannels];
    for (int c = 0; c < channelCount; ++c) {
        if (instruments[c] == kSample) shapes[c] = shapeFor(c);
    }
    const float master = master_.load();
    const float sr = static_cast<float>(sampleRate_);
    float masterPeak = 0.0f;
    int scopeW = scopeWrite_.load();

    const bool metronome = metronome_.load();
    const int beatsPerBar = beatsPerBar_.load();
    const double samplesPerBeat = 60.0 * sampleRate_ / std::max(bpm_.load(), 20.0f);
    // Playback that was already running clicks on beats it crosses; a fresh start clicks on its first tick.
    bool clickNow = playing && metronome && clickPending_;
    clickPending_ = false;

    for (int i = 0; i < frames; ++i) {
        if (countInBeats_ > 0) {
            if (countInSamples_ >= countInDone_ * samplesPerBeat) {
                if (countInDone_ < countInBeats_) {
                    startClick(countInDone_ == 0);
                    ++countInDone_;
                    countInBeat_.store(countInDone_);
                } else {
                    // The count-in is over: play from here, on this exact sample.
                    countInBeats_ = 0;
                    countInBeat_.store(0);
                    restart_.store(false);
                    playing_.store(true);
                    playing = true;
                    wasPlaying_ = true;
                    jumpTo(startTick_.exchange(0));
                    resumeLongSamples();
                    advanceTick(current_);
                    clickNow = metronome;
                }
            }
            countInSamples_ += 1.0;
        }
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
                if (metronome && tick_ % kTicksPerBeat == 0) clickNow = true;
            }
        }
        if (clickNow) {
            startClick((tick_ / kTicksPerBeat) % beatsPerBar == 0);
            clickNow = false;
        }
        clickBuf_[static_cast<size_t>(i)] = clickTick();
        float left = 0.0f, right = 0.0f;
        for (int c = 0; c < channelCount; ++c) {
            float sumL = 0.0f, sumR = 0.0f;
            if (instruments[c] == kWavetable) {
                synths_[static_cast<size_t>(c)].tick(sumL, sumR);
            } else {
                const float engine = SoundEngines::handles(instruments[c]) ? engines_.tick(c) : 0.0f;
                sumL = sumR = engine;
                for (auto& v : voices_[c]) {
                    if (!v.active) continue;
                    float xr = 0.0f;
                    float x;
                    if (instruments[c] == kSample) {
                        x = sampleTick(v, sample_[c], &shapes[c], &xr);
                    } else {
                        x = Synth::tick(v, instruments[c], sr);
                        xr = x;
                    }
                    if (v.fadeStep > 0.0f) {
                        x *= v.fade;
                        xr *= v.fade;
                        v.fade -= v.fadeStep;
                        if (v.fade <= 0.0f) v.active = false;
                    }
                    sumL += x;
                    sumR += xr;
                }
            }
            if (!audible[c]) continue;
            float l = sumL * gains[c][0];
            float rr = sumR * gains[c][1];
            peaks[c] = std::max(peaks[c], std::max(std::fabs(l), std::fabs(rr)));
            if (routes[c] >= 0) {
                busL_[static_cast<size_t>(routes[c])][static_cast<size_t>(i)] += l;
                busR_[static_cast<size_t>(routes[c])][static_cast<size_t>(i)] += rr;
            } else {
                left += l;
                right += rr;
            }
        }
        {
            const float a = engines_.tickAudition() * 0.8f;
            left += a;
            right += a;
        }
        if (auditionVoice_.active) {
            float ar = 0.0f;
            const float a = sampleTick(auditionVoice_, sample_[kAuditionSlot], nullptr, &ar) * 0.8f;
            left += a;
            right += ar * 0.8f;
        }
        out[2 * i] = left;
        out[2 * i + 1] = right;
    }

    // Mixer tracks: run each bus through its effects and add it to the master.
    for (int b = 0; b < insertCount; ++b) {
        auto& ins = (*inserts_)[static_cast<size_t>(b)];
        float* bl = busL_[static_cast<size_t>(b)].data();
        float* br = busR_[static_cast<size_t>(b)].data();
        for (auto& slot : ins.slots) slot.process(bl, br, frames, sr);
        const float pan = std::clamp(ins.pan.load(), -1.0f, 1.0f);
        const float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
        const float vol = ins.mute.load() ? 0.0f : ins.volume.load();
        const float gl = std::cos(angle) * vol * 1.414f, gr = std::sin(angle) * vol * 1.414f;
        int w = ins.spectrumWrite.load(std::memory_order_relaxed);
        float peak = 0.0f;
        for (int i = 0; i < frames; ++i) {
            ins.spectrum[static_cast<size_t>(w)] = 0.5f * (bl[i] + br[i]);
            w = (w + 1) % fx::kSpectrumSize;
            const float l = bl[i] * gl, r = br[i] * gr;
            peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
            out[2 * i] += l;
            out[2 * i + 1] += r;
        }
        ins.spectrumWrite.store(w, std::memory_order_relaxed);
        ins.level.store(std::max(std::min(peak, 1.0f), ins.level.load() * 0.85f));
    }
    for (int b = insertCount; b < fx::kMaxInserts; ++b) (*inserts_)[static_cast<size_t>(b)].level.store(0.0f);

    for (int i = 0; i < frames; ++i) {
        const float left = outputClip(out[2 * i] * master);
        const float right = outputClip(out[2 * i + 1] * master);
        out[2 * i] = left;
        out[2 * i + 1] = right;
        float mono = 0.5f * (left + right);
        masterPeak = std::max(masterPeak, std::fabs(mono));
        scope_[scopeW] = mono;
        scopeW = (scopeW + 1) % kScopeSize;
        // The click goes on top of the finished mix: not metered, not in the scope, not in a bounce.
        if (withClick) {
            const float c = clickBuf_[static_cast<size_t>(i)];
            out[2 * i] = left + c;
            out[2 * i + 1] = right + c;
        }
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
