#ifndef SCRCPY_PCM_FRAMER_H_
#define SCRCPY_PCM_FRAMER_H_
#include <array>
#include <cstddef>
#include <cstdint>
namespace scrcpy {
// Signed 16-bit LE stereo, 48kHz. Retain at most a partial 10ms frame.
class PcmFramer {
public:
    static constexpr size_t kSamples = 960;
    template <typename Emit>
    void Push(const uint8_t* bytes, size_t len, int64_t pts_us, Emit emit) {
        if (len % 4 != 0) { return; }
        if (used_ == 0) { next_pts_us_ = pts_us; }
        else if (pts_us > next_pts_us_ + 100000 || pts_us < next_pts_us_ - 100000) {
            used_ = 0; next_pts_us_ = pts_us;
        }
        for (size_t i = 0; i < len; i += 2) {
            samples_[used_++] = static_cast<int16_t>(static_cast<uint16_t>(bytes[i])
                    | (static_cast<uint16_t>(bytes[i + 1]) << 8));
            if (used_ == kSamples) {
                emit(samples_.data(), next_pts_us_);
                next_pts_us_ += 10000; used_ = 0;
            }
        }
    }
    void Reset() { used_ = 0; }
private:
    std::array<int16_t, kSamples> samples_{};
    size_t used_ = 0;
    int64_t next_pts_us_ = 0;
};
}
#endif
