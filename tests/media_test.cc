#include "media_clock.h"
#include "pcm_framer.h"
#include "telemetry_gate.h"
#include <cassert>
#include <vector>
int main() {
    scrcpy::TelemetryGate telemetry;
    assert(telemetry.Allow(0, 35000, 0));
    assert(!telemetry.Allow(1000000, 35000, 0));
    assert(!telemetry.Allow(4999999, 35000, 0));
    assert(!telemetry.Allow(5000000, 35000, 1025)); // Input backlog takes precedence.
    assert(telemetry.Allow(5000000, 35000, 0));
    assert(telemetry.Allow(6000000, 8000000, 0)); // Healthy links regain the normal interval.
    assert(!telemetry.Allow(6500000, 8000000, 0));
    assert(telemetry.Allow(1, 8000000, 0)); // Monotonic clock epoch change.
    telemetry.Reset();
    assert(telemetry.Allow(1, 35000, 0));

    assert(scrcpy::MediaClock::Rtp90k(1000000) == 90000);
    assert(scrcpy::MediaClock::Rtp90k(33334) == 3000);
    assert(scrcpy::MediaClock::Rtp90k(16667) == 1500);
    scrcpy::MediaClock clock;
    assert(clock.Normalize(1000000) == 1000000);
    assert(clock.Normalize(1033333) == 1033333);
    assert(clock.Normalize(0) == 1033334);
    assert(clock.Normalize(33333) == 1066667);
    scrcpy::PcmFramer framer;
    std::vector<uint8_t> bytes(4096);
    for (size_t i = 0; i < bytes.size(); i += 4) {
        bytes[i] = 0xff; bytes[i + 1] = 0x7f; bytes[i + 2] = 0; bytes[i + 3] = 0x80;
    }
    int frames = 0;
    auto emit = [&](const int16_t* data, int64_t pts) {
        assert(pts == 1000000 + frames * 10000);
        for (size_t i = 0; i < 960; i += 2) { assert(data[i] == 32767 && data[i + 1] == -32768); }
        ++frames;
    };
    framer.Push(bytes.data(), 4096, 1000000, emit); assert(frames == 2);
    framer.Push(bytes.data(), 4096, 1021333, emit); assert(frames == 4);
    framer.Push(bytes.data(), 1, 0, emit); assert(frames == 4);
    framer.Reset();
    int restarted = 0;
    framer.Push(bytes.data(), 1920, 9000000, [&](const int16_t*, int64_t pts) { assert(pts == 9000000); ++restarted; });
    assert(restarted == 1);
}
