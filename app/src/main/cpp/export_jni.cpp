// JNI entry points for song export (com.mobiledaw.audio.NativeEngine): offline rendering of the
// live Core, and MP3 encoding.
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "AudioEngine.h"
#include "export/Mp3Encoder.h"

daw::AudioEngine& dawSharedEngine();

namespace {

// Renders silence until every voice, release tail and effect has died down (at most 3 s), so an
// export starts from a clean engine whatever was playing before.
void settle(daw::Core& core) {
    const int block = 1024;
    std::vector<float> buf(block * 2);
    const int maxBlocks = 3 * core.sampleRate() / block;
    int quiet = 0;
    for (int b = 0; b < maxBlocks && quiet < 4; ++b) {
        core.renderMix(buf.data(), block);
        float peak = 0.0f;
        for (float s : buf) peak = std::fmax(peak, std::fabs(s));
        quiet = peak < 1e-5f ? quiet + 1 : 0;
    }
}

jbyteArray toBytes(JNIEnv* env, const std::vector<uint8_t>& data) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(data.size()));
    if (!data.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return out;
}

}  // namespace

extern "C" {

// Takes the engine off the live output (it plays silence meanwhile). Returns the sample rate.
JNIEXPORT jint JNICALL Java_com_mobiledaw_audio_NativeEngine_offlineBegin(JNIEnv*, jobject) {
    auto& engine = dawSharedEngine();
    engine.beginOffline();
    return engine.core().sampleRate();
}

// Starts one bounce from the top of the current sequence. `mask` picks the channel slots heard
// (bit n = slot n) for a stem; -1 renders the whole mix.
JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_offlineStart(JNIEnv*, jobject, jlong mask) {
    auto& core = dawSharedEngine().core();
    core.setStopAtEnd(true);
    core.setRenderMask(-1);
    core.panic();
    core.collectGarbage();
    settle(core);
    core.setRenderMask(mask);
    core.seek(0);
    core.setPlaying(true);
}

// Renders `frames` stereo frames into `out`. Returns false once the song has ended (its tail is
// still in the output; keep rendering for the tail).
JNIEXPORT jboolean JNICALL Java_com_mobiledaw_audio_NativeEngine_offlineRender(JNIEnv* env, jobject, jfloatArray out, jint frames) {
    auto& core = dawSharedEngine().core();
    std::vector<float> buf(static_cast<size_t>(frames) * 2);
    core.renderMix(buf.data(), frames);
    env->SetFloatArrayRegion(out, 0, frames * 2, buf.data());
    return core.isPlaying() ? JNI_TRUE : JNI_FALSE;
}

// Back to live playback: stops, silences, and gives the engine back to the output stream.
JNIEXPORT void JNICALL Java_com_mobiledaw_audio_NativeEngine_offlineEnd(JNIEnv*, jobject) {
    auto& engine = dawSharedEngine();
    auto& core = engine.core();
    core.setPlaying(false);
    core.panic();
    core.setRenderMask(-1);
    core.setStopAtEnd(false);
    core.seek(0);
    engine.endOffline();
}

JNIEXPORT jlong JNICALL Java_com_mobiledaw_audio_NativeEngine_mp3Open(JNIEnv*, jobject, jint sampleRate, jint kbps) {
    auto* enc = new daw::Mp3Encoder(sampleRate, kbps);
    if (!enc->ok()) {
        delete enc;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(enc));
}

JNIEXPORT jbyteArray JNICALL Java_com_mobiledaw_audio_NativeEngine_mp3Encode(JNIEnv* env, jobject, jlong handle, jfloatArray pcm, jint frames) {
    auto* enc = reinterpret_cast<daw::Mp3Encoder*>(static_cast<intptr_t>(handle));
    std::vector<uint8_t> out;
    if (enc && frames > 0) {
        std::vector<float> buf(static_cast<size_t>(frames) * 2);
        env->GetFloatArrayRegion(pcm, 0, frames * 2, buf.data());
        enc->encode(buf.data(), frames, out);
    }
    return toBytes(env, out);
}

// Flushes the encoder, frees it and returns the last bytes.
JNIEXPORT jbyteArray JNICALL Java_com_mobiledaw_audio_NativeEngine_mp3Finish(JNIEnv* env, jobject, jlong handle) {
    auto* enc = reinterpret_cast<daw::Mp3Encoder*>(static_cast<intptr_t>(handle));
    std::vector<uint8_t> out;
    if (enc) {
        enc->finish(out);
        delete enc;
    }
    return toBytes(env, out);
}

}  // extern "C"
