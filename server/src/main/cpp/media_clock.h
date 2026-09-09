#ifndef SCRCPY_MEDIA_CLOCK_H_
#define SCRCPY_MEDIA_CLOCK_H_
#include <cstdint>
namespace scrcpy {
class MediaClock {
public:
    int64_t Normalize(int64_t us) {
        int64_t next = us + offset_;
        if (last_ >= 0 && next <= last_) { offset_ += last_ + 1 - next; next = last_ + 1; }
        last_ = next;
        return next;
    }
    static uint32_t Rtp90k(int64_t us) {
        return static_cast<uint32_t>((us / 100) * 9 + (us % 100) * 9 / 100);
    }
private:
    int64_t last_ = -1;
    int64_t offset_ = 0;
};
}
#endif
