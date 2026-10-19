#ifndef SCRCPY_ENCODED_FRAME_GATE_H_
#define SCRCPY_ENCODED_FRAME_GATE_H_

#include <cstdint>

namespace scrcpy {

// Already-encoded H.264 cannot tolerate the raw-frame drops in VideoStreamEncoder.
// Sequence numbers detect those drops without assuming a constant source cadence.
// The owner serializes access. Only successful IDR delivery satisfies a refresh.
class EncodedFrameGate {
public:
    struct Decision {
        bool send;
        bool request_keyframe;
    };

    bool RequestRefresh(int64_t now_ms) {
        needs_keyframe_ = true;
        if (requested_ && now_ms >= last_request_ms_ && now_ms - last_request_ms_ < 1000) {
            return false;
        }
        requested_ = true;
        last_request_ms_ = now_ms;
        return true;
    }

    Decision Inspect(uint64_t sequence, bool keyframe, bool refresh, int64_t now_ms) {
        if (have_sequence_ && sequence != last_sequence_ + 1) {
            needs_keyframe_ = true;
        }
        have_sequence_ = true;
        last_sequence_ = sequence;
        needs_keyframe_ |= refresh;
        // The IDR currently being delivered can satisfy the request; do not ask for another.
        if (keyframe || !needs_keyframe_) {
            return {true, false};
        }
        return {false, RequestRefresh(now_ms)};
    }

    void OnDelivery(bool keyframe, bool success, bool drop_next_frame) {
        if (!success || drop_next_frame) {
            needs_keyframe_ = true;
        } else if (keyframe) {
            needs_keyframe_ = false;
            requested_ = false;
        }
    }

private:
    bool needs_keyframe_ = true;
    bool have_sequence_ = false;
    bool requested_ = false;
    uint64_t last_sequence_ = 0;
    int64_t last_request_ms_ = 0;
};

}
#endif
