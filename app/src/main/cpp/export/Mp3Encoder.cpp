#include "Mp3Encoder.h"

#include <algorithm>

#include "lame.h"

namespace daw {

Mp3Encoder::Mp3Encoder(int sampleRate, int kbps) {
    lame_ = lame_init();
    if (!lame_) return;
    lame_set_in_samplerate(lame_, sampleRate);
    lame_set_num_channels(lame_, 2);
    lame_set_mode(lame_, JOINT_STEREO);
    lame_set_VBR(lame_, vbr_off);
    lame_set_brate(lame_, kbps);
    lame_set_quality(lame_, 2);  // 0 best .. 9 fastest; 2 is LAME's "high quality"
    // The Xing/LAME tag frame needs a seek back to the file start; we stream the bytes out instead.
    lame_set_bWriteVbrTag(lame_, 0);
    if (lame_init_params(lame_) < 0) {
        lame_close(lame_);
        lame_ = nullptr;
    }
}

Mp3Encoder::~Mp3Encoder() {
    if (lame_) lame_close(lame_);
}

int Mp3Encoder::outputRate() const { return lame_ ? lame_get_out_samplerate(lame_) : 0; }

void Mp3Encoder::encode(const float* interleaved, int frames, std::vector<uint8_t>& out) {
    if (!lame_ || frames <= 0) return;
    // LAME's worst case: 1.25 x samples + 7200 bytes.
    buf_.resize(static_cast<size_t>(frames) * 5 / 4 + 7200);
    const int n = lame_encode_buffer_interleaved_ieee_float(lame_, interleaved, frames, buf_.data(),
                                                            static_cast<int>(buf_.size()));
    if (n > 0) out.insert(out.end(), buf_.begin(), buf_.begin() + n);
}

void Mp3Encoder::finish(std::vector<uint8_t>& out) {
    if (!lame_) return;
    buf_.resize(7200);
    const int n = lame_encode_flush(lame_, buf_.data(), static_cast<int>(buf_.size()));
    if (n > 0) out.insert(out.end(), buf_.begin(), buf_.begin() + n);
}

}  // namespace daw
