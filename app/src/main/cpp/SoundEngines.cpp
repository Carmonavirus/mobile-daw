#include "SoundEngines.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "MacroVoice.h"
#include "ModelVoice.h"

#define TSF_IMPLEMENTATION
#include "tsf.h"

namespace daw {

namespace {
constexpr int kMacroVoices = 16;
constexpr int kModelVoices = 20;
constexpr int kFontVoices = 48;
constexpr int kFontChannels = 8;
}  // namespace

SoundEngines::SoundEngines() {
    for (int i = 0; i < kMacroVoices; ++i) macro_.emplace_back(new MacroVoice());
    for (int i = 0; i < kModelVoices; ++i) model_.emplace_back(new ModelVoice());
}

SoundEngines::~SoundEngines() {
    for (auto& s : slots_) {
        tsf_close(s.font);
        tsf_close(s.pendingTsf.exchange(nullptr));
        tsf_close(s.retiredTsf.exchange(nullptr));
        delete s.ms;
        delete s.pendingMs.exchange(nullptr);
        delete s.retiredMs.exchange(nullptr);
    }
    for (tsf* b : banks_) tsf_close(b);
}

int SoundEngines::presetCount(int instrument) {
    if (instrument == kMacro) return MacroVoice::presetCount();
    if (instrument == kModeled) return ModelVoice::presetCount();
    return 0;
}

const char* SoundEngines::presetInfo(int instrument, int index) {
    if (instrument == kMacro) return MacroVoice::presetInfo(index);
    if (instrument == kModeled) return ModelVoice::presetInfo(index);
    return nullptr;
}

// ------------------------------------------------------------------ control thread

void SoundEngines::setSampleRate(int sampleRate) {
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    for (auto& v : macro_) v->setSampleRate(sampleRate_);
    for (auto& v : model_) v->setSampleRate(sampleRate_);
}

int SoundEngines::loadSoundFont(const void* data, int size) {
    tsf* f = tsf_load_memory(data, size);
    if (!f) return -1;
    tsf_set_output(f, TSF_MONO, sampleRate_, 0.0f);
    std::lock_guard<std::mutex> guard(banksLock_);
    banks_.push_back(f);
    return static_cast<int>(banks_.size()) - 1;
}

int SoundEngines::soundFontPresetCount(int bank) const {
    std::lock_guard<std::mutex> guard(banksLock_);
    if (bank < 0 || bank >= static_cast<int>(banks_.size())) return 0;
    return tsf_get_presetcount(banks_[bank]);
}

std::vector<int> SoundEngines::soundFontPresetIds(int bank, int index) const {
    std::lock_guard<std::mutex> guard(banksLock_);
    if (bank < 0 || bank >= static_cast<int>(banks_.size())) return {};
    const tsf* f = banks_[bank];
    if (index < 0 || index >= f->presetNum) return {};
    return {f->presets[index].bank, f->presets[index].preset};
}

const char* SoundEngines::soundFontPresetName(int bank, int index) const {
    std::lock_guard<std::mutex> guard(banksLock_);
    if (bank < 0 || bank >= static_cast<int>(banks_.size())) return nullptr;
    return tsf_get_presetname(banks_[bank], index);
}

void SoundEngines::collectGarbage() {
    for (auto& s : slots_) {
        tsf_close(s.retiredTsf.exchange(nullptr));
        delete s.retiredMs.exchange(nullptr);
    }
}

void SoundEngines::setChannel(int slot, int instrument, int variant, int bank) {
    if (slot < 0 || slot >= kEngineSlots) return;
    Slot& s = slots_[slot];
    s.variant.store(variant);
    s.instrument.store(instrument);
    if (instrument != kSoundFont || bank == s.uiBank) return;
    std::lock_guard<std::mutex> guard(banksLock_);
    if (bank < 0 || bank >= static_cast<int>(banks_.size())) return;
    collectGarbage();
    // Each slot gets its own player that shares the bank's samples; created here so the
    // audio thread never allocates.
    tsf* copy = tsf_copy(banks_[bank]);
    if (!copy) return;
    tsf_set_output(copy, TSF_MONO, sampleRate_, 3.0f);
    tsf_set_max_voices(copy, kFontVoices);
    // Notes go through tsf channels so each one can carry its own tuning. Creating them here
    // keeps the audio thread from allocating; one channel per note in turn keeps a retune from
    // bending hits that are still ringing.
    for (int ch = kFontChannels - 1; ch >= 0; --ch) tsf_channel_set_presetindex(copy, ch, 0);
    tsf_close(s.pendingTsf.exchange(copy));
    s.uiBank = bank;
}

std::vector<float> SoundEngines::renderHit(int bank, int variant, int key, float sampleRate) const {
    std::vector<float> out;
    tsf* f = nullptr;
    {
        std::lock_guard<std::mutex> guard(banksLock_);
        if (bank < 0 || bank >= static_cast<int>(banks_.size())) return out;
        f = tsf_copy(banks_[bank]);
    }
    if (!f || sampleRate <= 0.0f) { tsf_close(f); return out; }
    tsf_set_output(f, TSF_MONO, static_cast<int>(sampleRate), 3.0f);  // same level as a channel slot
    tsf_set_max_voices(f, 8);
    tsf_channel_set_presetindex(f, 0, variant);
    tsf_channel_note_on(f, 0, std::clamp(key, 0, 127), 1.0f);
    constexpr int kBlock = 64;
    const auto limit = static_cast<size_t>(4.0f * sampleRate);
    const auto noteOff = static_cast<size_t>(2.0f * sampleRate);  // hits ring out; long ones fade here
    float block[kBlock];
    bool released = false;
    while (out.size() < limit) {
        if (!released && out.size() >= noteOff) {
            tsf_channel_note_off_all(f, 0);
            released = true;
        }
        if (out.size() > 0 && tsf_active_voice_count(f) == 0) break;
        tsf_render_float(f, block, kBlock, 0);
        out.insert(out.end(), block, block + kBlock);
    }
    tsf_close(f);
    size_t end = out.size();
    while (end > 0 && std::fabs(out[end - 1]) < 1e-4f) --end;
    if (end == 0) return {};
    end = std::min(out.size(), end + static_cast<size_t>(0.002f * sampleRate));
    out.resize(std::max<size_t>(end, 2));
    return out;
}

void SoundEngines::setDrumKey(int slot, int key) {
    if (slot < 0 || slot >= kEngineSlots) return;
    slots_[slot].drumKey.store(key);
}

void SoundEngines::setMultiSample(int slot, MultiSampleSet* set) {
    if (slot < 0 || slot >= kEngineSlots) {
        delete set;
        return;
    }
    collectGarbage();
    if (!set) set = new MultiSampleSet();
    delete slots_[slot].pendingMs.exchange(set);
}

void SoundEngines::audition(int instrument, int variant, int bank, int pitch, float velocity) {
    setChannel(kEngineAuditionSlot, instrument, variant, bank);
    int w = queueWrite_.load(std::memory_order_relaxed);
    int next = (w + 1) % kQueue;
    if (next == queueRead_.load(std::memory_order_acquire)) return;
    queue_[w] = {pitch, velocity};
    queueWrite_.store(next, std::memory_order_release);
}

// ------------------------------------------------------------------ audio thread

void SoundEngines::adopt(Slot& s) {
    if (s.retiredTsf.load() == nullptr) {
        if (tsf* next = s.pendingTsf.exchange(nullptr)) {
            s.retiredTsf.store(s.font);
            s.font = next;
            s.offCount = 0;
        }
    }
    if (s.retiredMs.load() == nullptr) {
        if (MultiSampleSet* next = s.pendingMs.exchange(nullptr)) {
            for (auto& v : s.msVoices) v.active = false;
            s.retiredMs.store(s.ms);
            s.ms = next;
        }
    }
}

void SoundEngines::scheduleOff(Slot& s, int key, int64_t gate) {
    if (s.offCount >= static_cast<int>(s.offs.size())) {
        // Full: end the oldest pending note now.
        if (s.font) tsf_note_off(s.font, s.variant.load(), s.offs[0].key);
        std::copy(s.offs.begin() + 1, s.offs.begin() + s.offCount, s.offs.begin());
        --s.offCount;
    }
    s.offs[s.offCount++] = {key, std::max<int64_t>(gate, 1)};
}

void SoundEngines::noteOn(int slot, int pitch, float velocity, int64_t gateSamples) {
    if (slot < 0 || slot >= kEngineSlots) return;
    Slot& s = slots_[slot];
    adopt(s);
    const int instrument = s.instrument.load();
    const int variant = s.variant.load();
    const int64_t gate = std::max<int64_t>(gateSamples, sampleRate_ / 50);
    switch (instrument) {
        case kSoundFont:
        {
            if (!s.font) return;
            // A single drum hit always plays its own key, which is also where the step sequencer
            // puts its notes; a note's distance from that key repitches the hit.
            const int root = s.drumKey.load();
            const int key = root >= 0 ? root : pitch;
            const float shift = root >= 0 ? static_cast<float>(std::clamp(pitch - root, -48, 48)) : 0.0f;
            const int ch = s.nextFontChannel;
            s.nextFontChannel = (ch + 1) % kFontChannels;
            if (root < 0) tsf_note_off(s.font, variant, key);  // retrigger a held note
            tsf_channel_set_presetindex(s.font, ch, variant);
            tsf_channel_set_tuning(s.font, ch, shift);
            tsf_channel_note_on(s.font, ch, key, std::clamp(velocity, 0.01f, 1.0f));
            scheduleOff(s, key, gate);
            break;
        }
        case kMacro:
        case kModeled: {
            auto pick = [&](auto& pool) {
                // Retrigger the same note, else a free voice, else steal the oldest.
                decltype(pool[0].get()) target = nullptr;
                for (auto& v : pool) if (v->active && v->slot == slot && v->pitch == pitch) { target = v.get(); break; }
                if (!target) for (auto& v : pool) if (!v->active) { target = v.get(); break; }
                if (!target) {
                    target = pool[0].get();
                    for (auto& v : pool) if (v->age > target->age) target = v.get();
                }
                target->start(slot, variant, pitch, velocity, gate);
            };
            if (instrument == kMacro) pick(macro_); else pick(model_);
            break;
        }
        case kMultiSample: {
            if (!s.ms) return;
            const int vel = static_cast<int>(velocity * 127.0f);
            int zone = -1;
            for (size_t z = 0; z < s.ms->zones.size(); ++z) {
                const Zone& zz = s.ms->zones[z];
                if (pitch >= zz.loKey && pitch <= zz.hiKey && vel >= zz.loVel && vel <= zz.hiVel) { zone = static_cast<int>(z); break; }
            }
            if (zone < 0) {
                // Nothing mapped at this velocity: take the nearest key range.
                int best = 1 << 30;
                for (size_t z = 0; z < s.ms->zones.size(); ++z) {
                    const Zone& zz = s.ms->zones[z];
                    int d = pitch < zz.loKey ? zz.loKey - pitch : (pitch > zz.hiKey ? pitch - zz.hiKey : 0);
                    if (d < best) { best = d; zone = static_cast<int>(z); }
                }
            }
            if (zone < 0) return;
            MsVoice* target = nullptr;
            for (auto& v : s.msVoices) if (!v.active) { target = &v; break; }
            if (!target) {
                target = &s.msVoices[0];
                for (auto& v : s.msVoices) if (v.age > target->age) target = &v;
            }
            const Zone& z = s.ms->zones[static_cast<size_t>(zone)];
            target->active = true;
            target->zone = zone;
            target->pitch = pitch;
            target->pos = 0.0;
            target->rate = z.sampleRate / sampleRate_ * std::pow(2.0, (pitch - z.root + z.tuneCents / 100.0) / 12.0);
            target->gain = z.gain * velocity;
            target->env = 1.0f;
            target->releasing = false;
            target->gate = gate;
            target->age = 0;
            break;
        }
        default:
            break;
    }
}

void SoundEngines::releaseAll() {
    for (auto& s : slots_) {
        if (s.font) tsf_note_off_all(s.font);
        s.offCount = 0;
        for (auto& v : s.msVoices) if (v.active) v.releasing = true;
    }
    for (auto& v : macro_) if (v->active) v->release();
    for (auto& v : model_) if (v->active) v->release();
}

void SoundEngines::renderMultiSample(Slot& s, float* out) {
    if (!s.ms) return;
    const float releaseStep = 1.0f / std::max(1.0f, s.ms->release * static_cast<float>(sampleRate_));
    for (auto& v : s.msVoices) {
        if (!v.active) continue;
        const Zone& z = s.ms->zones[static_cast<size_t>(v.zone)];
        const auto& f = z.frames;
        for (int i = 0; i < kEngineBlock; ++i) {
            size_t idx = static_cast<size_t>(v.pos);
            if (z.loop && z.loopEnd > z.loopStart + 1 && v.pos >= z.loopEnd) {
                v.pos -= (z.loopEnd - z.loopStart);
                idx = static_cast<size_t>(v.pos);
            }
            if (idx + 1 >= f.size()) { v.active = false; break; }
            const float frac = static_cast<float>(v.pos - static_cast<double>(idx));
            const float smp = f[idx] + (f[idx + 1] - f[idx]) * frac;
            out[i] += smp * v.gain * v.env;
            v.pos += v.rate;
            if (!v.releasing && --v.gate <= 0) v.releasing = true;
            if (v.releasing) {
                v.env -= releaseStep;
                if (v.env <= 0.0f) { v.active = false; break; }
            }
        }
        v.age += kEngineBlock;
    }
}

void SoundEngines::renderBlock(int slotIndex) {
    Slot& s = slots_[slotIndex];
    adopt(s);
    float* out = s.buf;
    std::fill(out, out + kEngineBlock, 0.0f);

    if (s.font) {
        // Ends notes whose length has passed. The font keeps rendering after the slot switches
        // instrument, so released notes finish their tails.
        int keep = 0;
        for (int i = 0; i < s.offCount; ++i) {
            NoteOff o = s.offs[i];
            o.remaining -= kEngineBlock;
            if (o.remaining <= 0) tsf_note_off(s.font, s.variant.load(), o.key);
            else s.offs[keep++] = o;
        }
        s.offCount = keep;
        if (s.font->maxVoiceNum && tsf_active_voice_count(s.font) > 0) {
            tsf_render_float(s.font, out, kEngineBlock, 1);
        }
    }
    for (auto& v : macro_) if (v->active && v->slot == slotIndex) v->render(out, kEngineBlock);
    for (auto& v : model_) if (v->active && v->slot == slotIndex) v->render(out, kEngineBlock);
    renderMultiSample(s, out);
    s.pos = 0;
}

float SoundEngines::tick(int slot) {
    if (slot < 0 || slot >= kEngineSlots) return 0.0f;
    Slot& s = slots_[slot];
    if (s.pos >= kEngineBlock) renderBlock(slot);
    return s.buf[s.pos++];
}

float SoundEngines::tickAudition() {
    Slot& s = slots_[kEngineAuditionSlot];
    if (s.pos >= kEngineBlock) {
        int r = queueRead_.load(std::memory_order_relaxed);
        while (r != queueWrite_.load(std::memory_order_acquire)) {
            const Audition a = queue_[r];
            noteOn(kEngineAuditionSlot, a.pitch, a.velocity, sampleRate_ / 2);
            r = (r + 1) % kQueue;
            queueRead_.store(r, std::memory_order_release);
        }
    }
    return tick(kEngineAuditionSlot);
}

}  // namespace daw
