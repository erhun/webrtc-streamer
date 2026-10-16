#ifndef SCRCPY_TELEMETRY_GATE_H_
#define SCRCPY_TELEMETRY_GATE_H_

#include <cstddef>
#include <cstdint>

namespace scrcpy {
// Diagnostics share the control channel and path with media. Never queue them
// behind input, or let rapid requests consume a weak link's remaining bandwidth.
class TelemetryGate {
public:
    bool Allow(int64_t now_us, int video_bps, size_t buffered_bytes) {
        if (buffered_bytes > 1024) { return false; }
        const int64_t period_us = video_bps >= 0 && video_bps <= 128000 ? 5000000
                : video_bps >= 0 && video_bps <= 512000 ? 2000000 : 1000000;
        if (last_us_ >= 0 && now_us >= last_us_ && now_us - last_us_ < period_us) { return false; }
        last_us_ = now_us;
        return true;
    }
    void Reset() { last_us_ = -1; }
private:
    int64_t last_us_ = -1;
};
}
#endif
