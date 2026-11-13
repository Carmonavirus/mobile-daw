// JNI entry points for com.mobiledaw.audio.NativeEngine.
#include <jni.h>

#include <string>
#include <vector>

#include "AudioEngine.h"

using daw::AudioEngine;
using daw::NoteEvent;

namespace {
AudioEngine* engine() {
    static AudioEngine instance;
    return &instance;
}
}  // namespace

// Shared with synth_jni.cpp.
AudioEngine& dawSharedEngine() { return *engine(); }

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_mobiledaw_audio_NativeEngine_start(JNIEnv*, jobject) {
    return engine()->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_stop(JNIEnv*, jobject) {
    engine()->stop();
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setPlaying(JNIEnv*, jobject, jboolean playing) {
    engine()->core().setPlaying(playing == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_panic(JNIEnv*, jobject) {
    engine()->core().panic();
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setBpm(JNIEnv*, jobject, jfloat bpm) {
    engine()->core().setBpm(bpm);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setMasterVolume(JNIEnv*, jobject, jfloat v) {
    engine()->core().setMasterVolume(v);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelSampler(
    JNIEnv*, jobject, jint index, jboolean cutItself, jfloat trimStart, jfloat trimEnd, jfloat envDelay, jfloat attack,
    jfloat hold, jfloat decay, jfloat sustain, jfloat release, jfloat tension, jint pitchSemis, jboolean reverse) {
    engine()->core().setChannelSampler(index, cutItself == JNI_TRUE, trimStart, trimEnd, envDelay, attack, hold, decay,
                                       sustain, release, tension, pitchSemis, reverse == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelBoost(JNIEnv*, jobject, jint index, jfloat gain) {
    engine()->core().setChannelBoost(index, gain);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelClip(
    JNIEnv*, jobject, jint index, jboolean resumeMidway) {
    engine()->core().setChannelClip(index, resumeMidway == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelCount(JNIEnv*, jobject, jint count) {
    engine()->core().setChannelCount(count);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannel(
    JNIEnv*, jobject, jint index, jint instrument, jfloat volume, jfloat pan, jboolean mute, jboolean solo) {
    engine()->core().setChannel(index, instrument, volume, pan, mute == JNI_TRUE, solo == JNI_TRUE);
}

// events: flat array of [channel, startTick, lengthTicks, pitch, velocity(0..127)] per note.
JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setSequence(
    JNIEnv* env, jobject, jintArray events, jint loopTicks) {
    const jsize len = env->GetArrayLength(events);
    std::vector<jint> data(static_cast<size_t>(len));
    env->GetIntArrayRegion(events, 0, len, data.data());
    std::vector<NoteEvent> notes;
    notes.reserve(static_cast<size_t>(len / 5));
    for (jsize i = 0; i + 4 < len; i += 5) {
        notes.push_back({data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4] / 127.0f});
    }
    engine()->core().setSequence(std::move(notes), loopTicks);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_previewNote(
    JNIEnv*, jobject, jint channel, jint pitch, jint velocity) {
    engine()->core().previewNote(channel, pitch, velocity / 127.0f);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setSample(
    JNIEnv* env, jobject, jint slot, jfloatArray frames, jfloat sampleRate) {
    std::vector<float> data;
    if (frames != nullptr) {
        const jsize len = env->GetArrayLength(frames);
        data.resize(static_cast<size_t>(len));
        env->GetFloatArrayRegion(frames, 0, len, data.data());
    }
    engine()->core().setSample(slot, std::move(data), sampleRate);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_seek(JNIEnv*, jobject, jlong tick) {
    engine()->core().seek(static_cast<int64_t>(tick));
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_audition(JNIEnv*, jobject) {
    engine()->core().audition();
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setMetronome(JNIEnv*, jobject, jboolean on, jint beatsPerBar) {
    engine()->core().setBeatsPerBar(beatsPerBar);
    engine()->core().setMetronome(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_startCountIn(JNIEnv*, jobject, jint beats) {
    engine()->core().startCountIn(beats);
}

JNIEXPORT jint JNICALL Java_com_mobiledaw_audio_NativeEngine_countInBeat(JNIEnv*, jobject) {
    return engine()->core().countInBeat();
}

JNIEXPORT jboolean JNICALL Java_com_mobiledaw_audio_NativeEngine_isPlaying(JNIEnv*, jobject) {
    return engine()->core().isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_mobiledaw_audio_NativeEngine_positionTicks(JNIEnv*, jobject) {
    engine()->core().collectGarbage();
    return static_cast<jlong>(engine()->core().positionTicks());
}

JNIEXPORT jdouble JNICALL Java_com_mobiledaw_audio_NativeEngine_outputLatencyMs(JNIEnv*, jobject) {
    return engine()->outputLatencyMillis();
}

JNIEXPORT jdouble JNICALL Java_com_mobiledaw_audio_NativeEngine_bufferLatencyMs(JNIEnv*, jobject) {
    return engine()->bufferLatencyMillis();
}

JNIEXPORT jint JNICALL Java_com_mobiledaw_audio_NativeEngine_outputDeviceId(JNIEnv*, jobject) {
    return engine()->outputDeviceId();
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_readLevels(JNIEnv* env, jobject, jfloatArray out) {
    const jsize len = env->GetArrayLength(out);
    std::vector<float> tmp(static_cast<size_t>(len));
    engine()->core().readLevels(tmp.data(), len);
    if (len > 0) tmp[static_cast<size_t>(len - 1)] = engine()->core().masterLevel();  // last slot = master
    env->SetFloatArrayRegion(out, 0, len, tmp.data());
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_readScope(JNIEnv* env, jobject, jfloatArray out) {
    const jsize len = env->GetArrayLength(out);
    std::vector<float> tmp(static_cast<size_t>(len));
    engine()->core().readScope(tmp.data(), len);
    env->SetFloatArrayRegion(out, 0, len, tmp.data());
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelInsert(JNIEnv*, jobject, jint channel, jint insert) {
    engine()->core().setChannelInsert(channel, insert);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setInsertCount(JNIEnv*, jobject, jint count) {
    engine()->core().setInsertCount(count);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setInsert(
    JNIEnv*, jobject, jint index, jfloat volume, jfloat pan, jboolean mute) {
    engine()->core().setInsert(index, volume, pan, mute == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setInsertEffect(
    JNIEnv*, jobject, jint index, jint slot, jint type, jboolean bypass) {
    engine()->core().setInsertEffect(index, slot, type, bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setEffectParams(
    JNIEnv* env, jobject, jint index, jint slot, jfloatArray values) {
    const jsize len = env->GetArrayLength(values);
    std::vector<float> data(static_cast<size_t>(len));
    env->GetFloatArrayRegion(values, 0, len, data.data());
    for (jsize i = 0; i < len; ++i) engine()->core().setEffectParam(index, slot, i, data[static_cast<size_t>(i)]);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_readInsertLevels(JNIEnv* env, jobject, jfloatArray out) {
    const jsize len = env->GetArrayLength(out);
    std::vector<float> tmp(static_cast<size_t>(len));
    engine()->core().readInsertLevels(tmp.data(), len);
    env->SetFloatArrayRegion(out, 0, len, tmp.data());
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_readInsertSignal(JNIEnv* env, jobject, jint index, jfloatArray out) {
    const jsize len = env->GetArrayLength(out);
    std::vector<float> tmp(static_cast<size_t>(len));
    engine()->core().readInsertSignal(index, tmp.data(), len);
    env->SetFloatArrayRegion(out, 0, len, tmp.data());
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_readPitch(JNIEnv* env, jobject, jint index, jint slot, jfloatArray out) {
    float tmp[2];
    engine()->core().readPitch(index, slot, tmp);
    if (env->GetArrayLength(out) >= 2) env->SetFloatArrayRegion(out, 0, 2, tmp);
}

}  // extern "C"

// ---------------------------------------------------------------------------- extra engines
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <cstdio>

using daw::MultiSampleSet;
using daw::SoundEngines;
using daw::Zone;

extern "C" {

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelEngine(
    JNIEnv*, jobject, jint slot, jint instrument, jint variant, jint bank) {
    engine()->core().engines().setChannel(slot, instrument, variant, bank);
}

JNIEXPORT jint JNICALL Java_com_mobiledaw_audio_NativeEngine_loadSoundFontAsset(
    JNIEnv* env, jobject, jobject assetManager, jstring name) {
    AAssetManager* am = AAssetManager_fromJava(env, assetManager);
    const char* path = env->GetStringUTFChars(name, nullptr);
    AAsset* asset = am ? AAssetManager_open(am, path, AASSET_MODE_BUFFER) : nullptr;
    env->ReleaseStringUTFChars(name, path);
    if (!asset) return -1;
    const void* data = AAsset_getBuffer(asset);
    const off_t size = AAsset_getLength(asset);
    const int bank = data ? engine()->core().engines().loadSoundFont(data, static_cast<int>(size)) : -1;
    AAsset_close(asset);
    return bank;
}

JNIEXPORT jint JNICALL Java_com_mobiledaw_audio_NativeEngine_loadSoundFontFile(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    FILE* f = std::fopen(p, "rb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return -1;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::vector<char> data(size > 0 ? static_cast<size_t>(size) : 0);
    const size_t read = data.empty() ? 0 : std::fread(data.data(), 1, data.size(), f);
    std::fclose(f);
    if (read != data.size() || data.empty()) return -1;
    return engine()->core().engines().loadSoundFont(data.data(), static_cast<int>(data.size()));
}

// One "bank|program|name" string per preset.
JNIEXPORT jobjectArray JNICALL Java_com_mobiledaw_audio_NativeEngine_soundFontPresets(JNIEnv* env, jobject, jint bank) {
    auto& e = engine()->core().engines();
    const int count = e.soundFontPresetCount(bank);
    jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; ++i) {
        const auto ids = e.soundFontPresetIds(bank, i);
        const char* name = e.soundFontPresetName(bank, i);
        std::string s = std::to_string(ids.size() > 0 ? ids[0] : 0) + "|" + std::to_string(ids.size() > 1 ? ids[1] : 0) +
                        "|" + (name ? name : "");
        jstring js = env->NewStringUTF(s.c_str());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL Java_com_mobiledaw_audio_NativeEngine_enginePresets(JNIEnv* env, jobject, jint instrument) {
    const int count = SoundEngines::presetCount(instrument);
    jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; ++i) {
        jstring js = env->NewStringUTF(SoundEngines::presetInfo(instrument, i));
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

// zones: one FloatArray of frames per zone; rates: sample rate per zone;
// meta: 8 ints per zone (loKey, hiKey, root, loVel, hiVel, loop, loopStart, loopEnd);
// tuning: 2 floats per zone (tune in cents, gain).
JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setMultiSample(
    JNIEnv* env, jobject, jint slot, jobjectArray zones, jfloatArray rates, jintArray meta, jfloatArray tuning, jfloat release) {
    if (zones == nullptr) {
        engine()->core().engines().setMultiSample(slot, nullptr);
        return;
    }
    const jsize n = env->GetArrayLength(zones);
    std::vector<float> r(static_cast<size_t>(n));
    std::vector<jint> m(static_cast<size_t>(n) * 8);
    std::vector<float> t(static_cast<size_t>(n) * 2);
    env->GetFloatArrayRegion(rates, 0, n, r.data());
    env->GetIntArrayRegion(meta, 0, n * 8, m.data());
    env->GetFloatArrayRegion(tuning, 0, n * 2, t.data());
    auto* set = new MultiSampleSet();
    set->release = release;
    for (jsize i = 0; i < n; ++i) {
        auto frames = static_cast<jfloatArray>(env->GetObjectArrayElement(zones, i));
        Zone z;
        const jsize len = env->GetArrayLength(frames);
        z.frames.resize(static_cast<size_t>(len));
        env->GetFloatArrayRegion(frames, 0, len, z.frames.data());
        env->DeleteLocalRef(frames);
        z.sampleRate = r[i];
        const jint* mm = &m[static_cast<size_t>(i) * 8];
        z.loKey = mm[0]; z.hiKey = mm[1]; z.root = mm[2]; z.loVel = mm[3]; z.hiVel = mm[4];
        z.loop = mm[5] != 0; z.loopStart = mm[6]; z.loopEnd = mm[7];
        z.tuneCents = t[static_cast<size_t>(i) * 2];
        z.gain = t[static_cast<size_t>(i) * 2 + 1];
        set->zones.push_back(std::move(z));
    }
    engine()->core().engines().setMultiSample(slot, set);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_auditionEngine(
    JNIEnv*, jobject, jint instrument, jint variant, jint bank, jint pitch, jint velocity) {
    engine()->core().engines().audition(instrument, variant, bank, pitch, velocity / 127.0f);
}

}  // extern "C"
