// MP3 encoding for song export, on top of LAME (third_party/lame, LGPL).
// Portable: no Android code, so engine-test can check it on a desktop.
#pragma once

#include <cstdint>
#include <vector>

struct lame_global_struct;

namespace daw {

class Mp3Encoder {
public:
    // Stereo constant-bitrate MP3 at `kbps` (128, 192, 320...) from audio at `sampleRate`
    // (LAME resamples if MP3 can't store that rate). ok() is false when the encoder could not start.
    Mp3Encoder(int sampleRate, int kbps);
    ~Mp3Encoder();
    Mp3Encoder(const Mp3Encoder&) = delete;
    Mp3Encoder& operator=(const Mp3Encoder&) = delete;

    bool ok() const { return lame_ != nullptr; }
    int outputRate() const;

    // Adds interleaved stereo float frames (-1..1) and appends whatever MP3 bytes are ready to `out`.
    void encode(const float* interleaved, int frames, std::vector<uint8_t>& out);
    // Encodes what is still buffered and appends the encoder's last bytes.
    void finish(std::vector<uint8_t>& out);

private:
    lame_global_struct* lame_ = nullptr;
    std::vector<unsigned char> buf_;
};

}  // namespace daw
