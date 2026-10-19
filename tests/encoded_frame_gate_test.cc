#include "encoded_frame_gate.h"

#include <cassert>
#include <iostream>

int main() {
    scrcpy::EncodedFrameGate gate;
    // Startup SetRates and Encode(delta) share a single outstanding request.
    assert(gate.RequestRefresh(0));
    auto decision = gate.Inspect(1, false, true, 10);
    assert(!decision.send && !decision.request_keyframe);
    decision = gate.Inspect(2, true, true, 20);
    assert(decision.send && !decision.request_keyframe); // Current IDR satisfies the request.
    gate.OnDelivery(true, true, false);
    decision = gate.Inspect(3, false, false, 30);
    assert(decision.send && !decision.request_keyframe);

    // Raw source sequence 4 was dropped before Encode. Never forward dependent deltas.
    decision = gate.Inspect(5, false, false, 40);
    assert(!decision.send && decision.request_keyframe);
    decision = gate.Inspect(6, false, false, 500);
    assert(!decision.send && !decision.request_keyframe);
    decision = gate.Inspect(7, false, false, 1040);
    assert(!decision.send && decision.request_keyframe); // Retry a lost refresh, bounded to 1Hz.
    decision = gate.Inspect(8, true, false, 1050);
    assert(decision.send && !decision.request_keyframe);
    gate.OnDelivery(true, false, false); // Failed delivery must not open the reference chain.
    assert(!gate.Inspect(9, false, false, 1060).send);
    assert(gate.Inspect(10, true, false, 1070).send);
    gate.OnDelivery(true, true, false);
    assert(gate.Inspect(11, false, false, 1080).send);

    // A downstream send error also invalidates the chain.
    gate.OnDelivery(false, false, false);
    assert(!gate.Inspect(12, false, false, 1090).send);
    assert(gate.Inspect(13, true, false, 1100).send);
    gate.OnDelivery(true, true, false);
    gate.OnDelivery(false, true, true); // Honor downstream drop-next requests conservatively.
    assert(!gate.Inspect(14, false, false, 1110).send);

    scrcpy::EncodedFrameGate idle;
    assert(idle.Inspect(100, true, true, 0).send);
    idle.OnDelivery(true, true, false);
    assert(idle.Inspect(101, false, false, 60000).send); // Static content is not a sequence gap.
    assert(idle.Inspect(102, true, true, 60001).send);
    idle.OnDelivery(true, true, false);
    assert(idle.Inspect(103, false, false, 60002).send);
    assert(idle.RequestRefresh(60003));
    assert(!idle.RequestRefresh(60004));
    assert(idle.RequestRefresh(1)); // Clock epoch changes cannot suppress recovery forever.

    std::cout << "Encoded frame recovery passed (startup, IDR coalescing, sequence gaps, send errors, retries, idle)\n";
}
