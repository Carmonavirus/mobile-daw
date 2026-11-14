// JNI entry points for com.mobiledaw.audio.SynthEngine (the wavetable synth).
#include <jni.h>

#include <vector>

#include "AudioEngine.h"
#include "wavesynth/WaveSynth.h"

daw::AudioEngine& dawSharedEngine();

extern "C" {

JNIEXPORT jobjectArray JNICALL Java_com_mobiledaw_audio_SynthEngine_paramNames(JNIEnv* env, jobject) {
    jobjectArray out = env->NewObjectArray(wavesynth::kParamCount, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < wavesynth::kParamCount; ++i)
        env->SetObjectArrayElement(out, i, env->NewStringUTF(wavesynth::paramInfo(i).name));
    return out;
}

// Flat [min, max, default, stepped(0/1)] per parameter.
JNIEXPORT jfloatArray JNICALL Java_com_mobiledaw_audio_SynthEngine_paramRanges(JNIEnv* env, jobject) {
    std::vector<float> data;
    for (int i = 0; i < wavesynth::kParamCount; ++i) {
        const auto& p = wavesynth::paramInfo(i);
        data.insert(data.end(), {p.min, p.max, p.def, p.stepped ? 1.0f : 0.0f});
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(data.size()));
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(data.size()), data.data());
    return out;
}

JNIEXPORT jobjectArray JNICALL Java_com_mobiledaw_audio_SynthEngine_tableNames(JNIEnv* env, jobject) {
    const auto& bank = wavesynth::Bank::get();
    jobjectArray out = env->NewObjectArray(bank.count(), env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < bank.count(); ++i) env->SetObjectArrayElement(out, i, env->NewStringUTF(bank.table(i).name));
    return out;
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_SynthEngine_tableShape(
    JNIEnv* env, jobject, jint table, jfloat position, jfloatArray out) {
    const jsize n = env->GetArrayLength(out);
    std::vector<float> tmp(static_cast<size_t>(n));
    wavesynth::Bank::get().shape(table, position, tmp.data(), n);
    env->SetFloatArrayRegion(out, 0, n, tmp.data());
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_SynthEngine_setParam(
    JNIEnv*, jobject, jint channel, jint param, jfloat value) {
    dawSharedEngine().core().setSynthParam(channel, param, value);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_SynthEngine_setParams(JNIEnv* env, jobject, jint channel, jfloatArray values) {
    const jsize n = env->GetArrayLength(values);
    std::vector<float> tmp(static_cast<size_t>(n));
    env->GetFloatArrayRegion(values, 0, n, tmp.data());
    for (jsize i = 0; i < n && i < wavesynth::kParamCount; ++i) dawSharedEngine().core().setSynthParam(channel, i, tmp[static_cast<size_t>(i)]);
}

JNIEXPORT void JNICALL Java_com_mobiledaw_audio_SynthEngine_noteHold(
    JNIEnv*, jobject, jint channel, jint pitch, jint velocity, jboolean on) {
    dawSharedEngine().core().previewHold(channel, pitch, velocity / 127.0f, on == JNI_TRUE);
}

}  // extern "C"
