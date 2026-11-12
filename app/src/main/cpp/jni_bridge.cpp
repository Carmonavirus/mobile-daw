// JNI entry points for com.mobiledaw.audio.NativeEngine.
#include <jni.h>

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

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setBpm(JNIEnv*, jobject, jfloat bpm) {
    engine()->core().setBpm(bpm);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setMasterVolume(JNIEnv*, jobject, jfloat v) {
    engine()->core().setMasterVolume(v);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_setChannelSampler(
    JNIEnv*, jobject, jint index, jboolean cutItself, jfloat trimStart, jfloat trimEnd, jfloat attack, jfloat hold,
    jfloat decay, jfloat sustain, jfloat release, jint pitchSemis, jboolean reverse) {
    engine()->core().setChannelSampler(index, cutItself == JNI_TRUE, trimStart, trimEnd, attack, hold, decay, sustain,
                                       release, pitchSemis, reverse == JNI_TRUE);
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

JNIEXPORT jlong JNICALL Java_com_mobiledaw_audio_NativeEngine_positionTicks(JNIEnv*, jobject) {
    engine()->core().collectGarbage();
    return static_cast<jlong>(engine()->core().positionTicks());
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

}  // extern "C"
