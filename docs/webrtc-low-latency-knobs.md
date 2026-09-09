# libwebrtc low-latency knobs for cloud-phone game streaming (m150+)

Research date: 2026-09-02
Primary source used: full m150+ snapshot mirror `webrtc-sdk/webrtc` at
commit `73cb8180f7258ee292878d6edd05177f41883962` (branch `m150_release`), which
mirrors `webrtc.googlesource.com/src` (Google's `main`). All permalinks below
are of the form:

    https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/<path>#L<start>-L<end>

The upstream tree is authoritative; the mirror only gives us stable line
numbers. Note: in m150+ the receive pipeline was refactored. The concrete
`VideoReceiveStream2` implementation now lives in `video/video_receive_stream2.*`
(the old `call/video_receive_stream2.*` and `modules/rtp_rtcp/source/rtp_video_stream_receiver2.*`
no longer exist), and the delay math lives in `modules/video_coding/timing/`.

TL;DR budget for a 60 fps shooter on a healthy LAN/edge link (<5 ms jitter, ~0
loss), host = cloud phone (MediaCodec H.264 passthrough encoder), client =
Chromium or native libwebrtc:

| component | floor | how to get there |
|---|---|---|
| video jitter buffer (pre-decode) | ~0 ms | sender attaches playout-delay ext `0:0` (or `0:≤500`), or force via field trial |
| video decode scheduling | ~0–8 ms | low-latency rendering path paces decodes 8 ms apart by default |
| render delay term | 0–10 ms | `Config::render_delay_ms` default 10 ms; set 0 |
| NetEQ (audio, if any) | ~20–40 ms | `min_delay_ms=0`, fast-accelerate on; initial target 80 ms converges to 20 ms buckets |
| NACK/PLI round trip | ≥ RTT + ~20 ms | NACK scan 20 ms; PLI throttle 200 ms |
| decode (client HW) + present | ~2–16 ms | outside libwebrtc (MediaCodec/VAAPI + vsync) |

---

## 1. Video receive jitter buffer / playout delay

### Where the number is computed: VCMTiming

`modules/video_coding/timing/timing.h` defines the delay model
(`VideoDelayTimings`, lines 34–65):

* `minimum_delay` – pre-decode jitter smoothing term, "no smaller than this"
  (timing.h:46–48).
* `estimated_max_decode_time` – 95th percentile decode time over a recent
  window (`DecodeTimePercentileFilter`, `decode_time_percentile_filter.h:21–23`).
* `render_delay` – post-decode constant, default `kDefaultRenderDelay = 10 ms`
  (timing.h:35, 54).
* `min_playout_delay` – default 0; from API, `playout-delay` RTP header ext or
  A/V sync (timing.h:57).
* `max_playout_delay` – default **10 seconds** (timing.h:60) — *this is why the
  default is NOT low-latency*.
* `TargetDelay() = max(min_playout_delay, minimum_delay + estimated_max_decode_time + render_delay)`
  (timing.cc:63–66).

The low-latency trigger (`timing.cc:33–35, 73–76`):

```cpp
// Maximum `max_playout_delay` for a stream to use low-latency rendering.
constexpr TimeDelta kLowLatencyStreamMaxPlayoutDelayThreshold = TimeDelta::Millis(500);

bool VCMTiming::VideoDelayTimings::UseLowLatencyRendering() const {
  return min_playout_delay.IsZero() &&
         max_playout_delay <= kLowLatencyStreamMaxPlayoutDelayThreshold;
}
```

When true, `RenderTime()` returns `Timestamp::Zero()` = "decode/render as soon
as possible" (`timing.cc:162–167`). Otherwise frames are delayed to
`local_time + clamp(current_delay, min_playout_delay, max_playout_delay)`.

**So the hard rule is:** jitter-buffer delay floor 0 ms is reachable only when
`min_playout_delay == 0` **and** `max_playout_delay ≤ 500 ms`. Both values come
from the negotiated playout-delay RTP header extension carried on the RTP
packets (see §6), or are forced by field trials (§1.3). Because the default max
is 10 s, an unmodified PeerConnection never takes the low-latency path.

### 1.1 The three minimum-delay sources (VideoReceiveStream2)

`video/video_receive_stream2.h:333–349` documents three requesters of
`min_playout_delay`, plus one maximum:

* `frame_minimum_playout_delay_` – from the RTP playout-delay extension on each frame;
* `base_minimum_playout_delay_` – from `SetBaseMinimumPlayoutDelayMs()` (the
  API behind Chromium's `RTCRtpReceiver.playoutDelayHint` /
  `jitterBufferTarget`);
* `syncable_minimum_playout_delay_` – from A/V sync;
* `frame_maximum_playout_delay_` – from the extension.

`UpdatePlayoutDelays()` (`video/video_receive_stream2.cc:1090–1156`) takes the
**max** of the three minima, lets the frame maximum override if smaller, then
writes `timing_->set_playout_delay({min,max})` or `set_min_playout_delay(min)`.
`SetBaseMinimumPlayoutDelayMs` is range-limited to `[0 ms, 10 s]`
(`video_receive_stream2.cc:106–108, 684–694`).

A/V sync can silently raise the video floor: `SetMinimumPlayoutDelay()` is
"only called by A/V sync" (`video_receive_stream2.h:201–202`; impl
`video_receive_stream2.cc:828–833`). For pure game streaming consider
separating audio from video (no sync group), or dropping audio entirely;
otherwise NetEQ delay is pushed onto the video stream.

### 1.2 What actually buffers frames: VideoStreamBufferController

`video/video_stream_buffer_controller.cc`:

* FrameBuffer cap `kMaxFramesBuffered = 800` frames (line 48).
* When a frame is released to the decoder, jitter estimate is applied:
  `timing_->SetMinimumDelay(jitter_estimator_.GetEstimate())` (line 255).
  The jitter estimator is configurable via `WebRTC-JitterEstimatorConfig`
  (`modules/video_coding/timing/jitter_estimator.h:40`).
* Keyframes are decoded "right away"; others are scheduled by
  `FrameDecodeTiming` (`video/video_stream_buffer_controller.cc:365–419`).

`video/frame_decode_timing.cc`:

* Low-latency path: when `RenderTime()==0`, decode pacing default
  **`min_pacing = 8 ms`** between decode starts (const at line 30, parsed from
  `WebRTC-ZeroPlayoutDelay` lines 39–44, logic lines 83–104), unless the decode
  queue is backed up (`max_decode_queue_size`, default **8 frames**,
  `video_stream_buffer_controller.cc:54, 121–131`).
* Normal path: decode is scheduled at `render_time - estimated_max_decode_time - render_delay`
  (frame_decode_timing.cc:105–106).

Legacy knob: `VideoReceiveStreamInterface::Config::render_delay_ms = 10`
(default) and `enable_prerenderer_smoothing = true` (default)
(`call/video_receive_stream.h:274–280`). For "deliver to renderer as soon as
available" set `enable_prerenderer_smoothing = false` and
`render_delay_ms = 0` if you control the Config.

### 1.3 Forcing playout delay without sender cooperation (field trials)

Receiver: `WebRTC-ForcePlayoutDelay/min_ms:0/max_ms:0` is parsed by
`RtpVideoStreamReceiver2` and stamped into every received frame that lacks the
extension (`video/rtp_video_stream_receiver2.cc:301–302, 360–362, 599–606`).
Sender: `WebRTC-ForceSendPlayoutDelay/min_ms:0/max_ms:0` makes `RTPSenderVideo`
write the playout-delay header extension on every packet
(`modules/rtp_rtcp/source/rtp_sender_video.cc:135–145, 409–410, 922–933`).

Practical warning (from selkies-project/selkies#157 and the discuss-webrtc
thread): forcing `0:0` removes all jitter tolerance and causes stutter as soon
as the network jitters; use `0:100`…`0:500` (still low-latency, still
≤500 ms) when there is any real network in the path.

### 1.4 Waiting / keyframe timeouts

`video/video_receive_stream2.h:72–73`:
`kMaxWaitForKeyFrame = 200 ms`, `kMaxWaitForFrame = 3 s`.
`DetermineMaxWaitForFrame()` (`video_receive_stream2.cc:206–217`): with NACK
`rtp_history_ms = h`, the receiver waits `h` for a keyframe and `min(3h, 3 s)`
for a delta frame; without NACK, defaults 200 ms / 3 s. These feed the
`VideoReceiveStreamTimeoutTracker` and gate keyframe re-requests
(`keyframe_request_is_due` check, `video_receive_stream2.cc:838–840`).

---

## 2. Audio: NetEQ

API (`api/neteq/neteq.h:124–142`):

```cpp
struct Config {
  int sample_rate_hz = 48000;
  size_t max_packets_in_buffer = 200;
  int max_delay_ms = 0;      // 0 => default/unset
  int min_delay_ms = 0;      // base minimum delay (can't be lowered below this)
  bool enable_fast_accelerate = false;
  ...
};
```

Runtime knobs: `NetEq::SetMinimumDelay()`, `SetMaximumDelay()`,
`SetBaseMinimumDelayMs()` (neteq.h:265–281). `NetEqImpl` maps
`Config::min_delay_ms` → controller `base_min_delay_ms` and forwards the three
setters to the NetEqController (`modules/audio_coding/neteq/neteq_impl.cc:115–123,
491–510`).

The jitter-buffer level controller (`modules/audio_coding/neteq/delay_manager.cc`):

* Initial target **`kStartDelayMs = 80 ms`** (line 27, applied lines 72, 83).
* Target = max(underrun optimizer's optimal delay, reorder optimizer's margin)
  (lines 82–88).
* Underrun optimizer: 95th-percentile quantile over arrival-delay buckets of
  **20 ms** (`modules/audio_coding/neteq/underrun_optimizer.cc:22–23, 63–64`) →
  floor ≈ **20 ms** target on a perfectly steady stream.
* Configurable through `WebRTC-Audio-NetEqDelayManagerConfig` (quantile,
  forget_factor, resample_interval_ms, reorder params)
  (`delay_manager.cc:41–51`).

Practical floor: one Opus/RTP frame (typically 20 ms) in the packet buffer +
10 ms output block; with `min_delay_ms = 0` and `enable_fast_accelerate`
(a.k.a. jitter buffer fast/slow accelerate) the 80 ms bootstrap is shed quickly.
Stream-level config in the receive path: `AudioReceiveStreamInterface::Config`
(`call/audio_receive_stream.h:144–147`) – `jitter_buffer_max_packets = 200`,
`jitter_buffer_fast_accelerate = false`, `jitter_buffer_min_delay_ms = 0`;
plus `SetBaseMinimumPlayoutDelayMs` (line 215). On the PeerConnection/channel
level, Chromium's `playoutDelayHint` routes to the same
`media_channel_->SetBaseMinimumPlayoutDelayMs` call
(`pc/audio_rtp_receiver.cc:332–338`, `pc/video_rtp_receiver.cc:291–297`).

A/V sync caveat: do not put game audio in the same sync group as video if you
are optimizing for <50 ms video latency; sync will raise
`syncable_minimum_playout_delay_` (§1.1).

---

## 3. Congestion control

There is **no `low_latency` mode / FieldTrialParameter on
`GoogCcNetworkController` in m150+**. The class parses these trials only
(`modules/congestion_controller/goog_cc/goog_cc_network_control.cc:98–141`):

* `WebRTC-Bwe-MinAllocAsLowerBound`
* `WebRTC-Bwe-IgnoreProbesLowerThanNetworkStateEstimate`
* `WebRTC-Bwe-LimitProbesLowerThanThroughputEstimate`
* `WebRTC-Bwe-LimitPacingFactorByUpperLinkCapacityEstimate`
* `WebRTC-Bwe-SafeResetOnRouteChange`

Latency-relevant behavior/knobs that DO exist:

* **Pacing multiplier**: pacing rate = target bitrate × multiplier; defaults
  `kDefaultPaceMultiplier = 2.5f` (no send-side BWE yet) and
  `kDefaultPaceMultiplierWithSendSideBwe = 1.1f`
  (`goog_cc_network_control.cc:55–58, 654–677`). Higher pacing rate drains the
  pacer queue faster; `WebRTC-VideoRateControl/pacing_factor:` overrides it
  (`rtc_base/experiments/rate_control_settings.h:35–38`, `.cc:116–118`).
* **Congestion window / queueing target**: `WebRTC-CongestionWindow` with
  default `QueueSize:350,MinBitrate:30000,DropFrame:true` — the accepted
  queueing delay budget (350 ms) before pushback
  (`rate_control_settings.cc:28–33, 40–52, 87–109`).
* **Delay-based BWE (GoogCc) tolerates standing queue** until it detects
  overuse; there is no "don't buffer" mode. For game streaming the practical
  lever is to cap bitrate below the link so the delay-based controller never
  sees sustained overuse: `b=AS:` / `x-google-max-bitrate` /
  `RtpParameters.encodings[].maxBitrate` (engine reads x-google-max-bitrate,
  `media/engine/webrtc_video_engine.cc:2360`).
* Pacer trials (`modules/pacing/pacing_controller.cc:63–73`):
  `WebRTC-Pacer-DrainQueue`, `WebRTC-Pacer-PadInSilence`,
  `WebRTC-Pacer-BlockAudio`, `WebRTC-Pacer-IgnoreTransportOverhead`,
  `WebRTC-Pacer-FastRetransmissions`, `WebRTC-Pacer-KeyframeFlushing`
  (enable the last two for game traffic). Pacer granularity floor:
  `kMinSleepTime = 1 ms` (line 49).
* Google's own Stadia did **not** use GoogCc — they shipped a custom BBR-style
  controller plus receiver-side "disable buffering" extensions (see refs).

---

## 4. Encoder feedback: NACK / PLI / FEC

Receive-side constants (`modules/video_coding/nack_requester.*`,
`video/rtp_video_stream_receiver2.cc`):

* NACK background scan period `NackPeriodicProcessor::kUpdateInterval = 20 ms`
  (`nack_requester.h:44`).
* Initial NACK delay `kDefaultSendNackDelay = 0 ms`, tunable with
  `WebRTC-SendNackDelayMs` (`nack_requester.cc:42–46`); retries gated by RTT
  (`sent_at_time + rtt_`, lines 269–275), up to `kMaxNackRetries = 100`
  (line 39). First NACK for a hole waits for the reordering histogram
  (`WaitNumberOfPackets(0.5)`, line 253) to avoid NACKing reordered packets.
* Packet buffer: start size 512, max 2048, `kMaxPacketAgeToNack = 450`
  (configurable via `WebRTC-PacketBufferMaxSize`)
  (`rtp_video_stream_receiver2.cc:99–108`).
* PLI/FIR selection: `Config::Rtp::keyframe_method`
  (`KeyFrameReqMethod::kPliRtcp` default, `call/video_receive_stream.h:238`);
  request path `RtpVideoStreamReceiver2::RequestKeyFrame` →
  `rtp_rtcp_->SendPictureLossIndication()` / `SendFullIntraRequest()`
  (`rtp_video_stream_receiver2.cc:790–800`).
* Keyframe re-request throttle = `max_wait_for_keyframe_` = 200 ms default
  (`video_receive_stream2.cc:838–840`, plus §1.4). On a clean link a single PLI
  is sent immediately once the stream needs a keyframe.
* NACK depth: `Config::Rtp::nack.rtp_history_ms` (`NackConfig`, default 0 =
  off, `call/rtp_config.h:41–48`); RTX payload/ssrc config lives in the same
  `Config::Rtp` (`call/video_receive_stream.h:247–259`). Bigger
  `rtp_history_ms` = longer retransmission window but also longer frame-buffer
  waits (see §1.4).
* LNTF: `LntfConfig`/`Config::Rtp::lntf` +
  `SetLossNotificationEnabled()` (`call/video_receive_stream.h:241, 334`);
  faster than NACK for keyframe recovery when the sender honors it.
* FEC: ULPFEC/RED (`Config::Rtp::ulpfec_payload_type`, `red_payload_type`) and
  FlexFEC (`protected_by_flexfec`) — generally adds delay; prefer NACK + PLI for
  interactive game traffic.
* Sender-side RTX retransmission pacing: enable `WebRTC-Pacer-FastRetransmissions`.

---

## 5. Decoder side / "low latency renderer"

The old `low_latency_renderer` config field is gone in m150+. It has been
replaced by the timing flag described in §1:

* `VCMTiming::RenderParameters()` exposes
  `use_low_latency_rendering` and `max_composition_delay_in_frames`
  (`timing.cc:190–194`, `timing.h:115`).
* `VideoReceiveStream2::UpdatePlayoutDelays()` computes
  `max_composition_delay_in_frames = round(max_playout_delay × 60 fps) - frames
  already buffered` and pushes it down (so decoders/H.264 composition are told
  not to hold frames) (`video_receive_stream2.cc:1144–1155`).
* Decode is paced by `FrameDecodeTiming` (LL: 8 ms min spacing; normal:
  target minus 95th-percentile decode time minus 10 ms render delay)
  (`frame_decode_timing.cc:83–107`). Decode time percentile: 95th
  (`decode_time_percentile_filter.h:21–34`).
* Actual decoder latency (MediaCodec/NVENC-dec/VAAPI ≈ 2–8 ms/frame, plus
  output queue) is outside libwebrtc; the code only *schedules* decode to hit
  the render time. On Android native receive, enable low-latency on the
  MediaCodec decoder itself if you vendor one.

---

## 6. SDP / H.264 / header-extension settings

* Chromium/libwebrtc advertises by default: abs-send-time,
  transport-cc, video-rotation, **playout-delay**, video-content-type,
  **video-timing**, color-space, mid, rid
  (`media/engine/webrtc_video_engine.cc:875–884`).
* `playout-delay` extension is `urn:ietf:params:rtp-hdrext:playout-delay`
  (values min/max in ms, each 0–10000). The upstream doc:
  https://webrtc.googlesource.com/src/+/refs/heads/main/docs/native-code/rtp-hdrext/playout-delay/
  (the URI is also echoed in the code comment at `timing.cc:37–48`).
  Cloud-gaming guidance in that doc: set both to 0. Parser:
  `modules/rtp_rtcp/source/rtp_header_extensions.cc:394–406`.
* H.264 fmtp you control on the passthrough sender:
  `packetization-mode=1` (NonInterleaved; STAP-A/FU-A allowed) vs mode 0
  (SingleNalUnit). In code: `H264PacketizationMode`
  (`modules/video_coding/codecs/h264/include/h264_globals.h:41–44`).
  `profile-level-id` is just a parameter of the `SdpVideoFormat` your encoder
  factory reports — receiver matches payload types on it.
* `sps-pps-idr-in-keyframe=1` fmtp (or field trial
  `WebRTC-SpsPpsIdrIsH264Keyframe`) tells the receiver a keyframe is fully
  decodable without waiting for a separate SPS/PPS — avoids extra keyframe
  requests on join (`video/rtp_video_stream_receiver2.cc:402–405`).
* Forcing the extension without SDP dance: sender `WebRTC-ForceSendPlayoutDelay`,
  receiver `WebRTC-ForcePlayoutDelay` (§1.3).
* RTCP: prefer `RtcpMode::kReducedSize` (`Config::Rtp::rtcp_mode`,
  `call/video_receive_stream.h:227`) and `rtcp-mux`; enable
  `rtcp_xr.receiver_reference_time_report` for RTT measurement.
  Receiver reports + NACK/PLI travel on the same muxed channel.
* Keep the generic frame descriptor / dependency descriptor extensions only if
  you actually use them (they are what lets the jitter buffer recover frames
  without keyframes for VP9/AV1; H.264 with full-frame keyframes doesn't need
  them).

---

## 7. Field-trial cheat sheet (append to PeerConnectionFactory field trials)

```
WebRTC-ForceSendPlayoutDelay/min_ms:0/max_ms:0      // host: write playout-delay ext 0:0
WebRTC-ForcePlayoutDelay/min_ms:0/max_ms:0          // native client: force receive LL path
WebRTC-SendNackDelayMs/0                            // NACK immediately (default 0 already)
WebRTC-Pacer-FastRetransmissions/Enabled            // retransmits leave pacer ASAP
WebRTC-Pacer-KeyframeFlushing/Enabled               // keyframes bypass pacer queue
WebRTC-ZeroPlayoutDelay/min_pacing:8/max_decode_queue_size:8  // LL decode pacing
WebRTC-Audio-NetEqDelayManagerConfig/quantile:0.95/...        // audio JB aggressiveness
```

(Exact parameter spelling for each trial is defined where each file calls
`ParseFieldTrial` — see line refs above.)

---

## 8. Production references

* Stadia (Google I/O 2019, engineers Rob McCool / Guru Somadder): custom
  BBR-style congestion control on the send side + "WebRTC extensions provided
  by our team in Sweden to disable buffering and display things as soon as they
  arrive" on the receiver.
  https://www.pcgamesn.com/stadia/google-game-streaming-technology-latency-quality
* GeForce NOW / Stadia network-trace study (UCLouvain M.Sc. thesis, 2021):
  both platforms are WebRTC; **GeForce NOW always sends playout-delay 0:0**;
  Stadia sends 0:0 as well; Stadia becomes unplayable above ~75 ms added delay.
  https://thesis.dial.uclouvain.be/server/api/core/bitstreams/52fe7b4b-6202-423a-84c4-9d1acb3b9c9c/content
* BlogGeek.me "Cloud gaming, virtual desktops and WebRTC" — playout-delay as the
  key Google cloud-gaming extension.
  https://bloggeek.me/cloud-gaming-virtual-desktops-and-webrtc/
* NVIDIA CloudXR (WebRTC-based web client): recommended pose-to-frame latency
  20–30 ms.
  https://docs.nvidia.com/cloudxr-sdk/latest/requirement/network_setup.html
* selkies-project/selkies#157 "Optimize the WebRTC stack to the maximum" —
  practical low-latency SDP + playout-delay config; warns against forcing
  `jitterBufferTarget/playoutDelayHint = 0` in browsers.
  https://github.com/selkies-project/selkies/issues/157
* Shyamalp16/CloudGaming — MediaCodec/NVENC-style H.264 host over WebRTC with
  concrete low-latency encoder/PLI settings (PLI throttle, encoder lookahead=0).
  https://github.com/Shyamalp16/CloudGaming
* LizardByte/Sunshine experimental WebRTC path — libwebrtc encoded-video bridge
  with "latency" pacing mode (drop-oldest, 2-frame queue).
  https://github.com/Nonary/vibeshine/blob/vibe/architecture.md
* discuss-webrtc "Jitter and Packet Loss" (cloud gaming operators): playout
  delay 0 + sender pacer/queue control discussion.
  https://groups.google.com/g/discuss-webrtc/c/aLVkzRh9_cc
* Pandi-127/Frame-Jack — LAN game streaming <50 ms with `playoutDelayHint = 0`
  and H.264 `profile-level-id=42e02a` forced on the client.
  https://github.com/pandi-127/frame-jack
