# Weak-network session recovery

Based on `dev-1.1.0` at `5ef675878e0103621ca15478df20c0d3f882dc47`.

## Behavior

- The H5 client enters `reconnecting` on media disconnection/failure. It retains the
  video element and PeerConnection and retries ICE negotiation every three seconds
  within a fixed 30-second recovery window, with at least five seconds between
  offers. An unanswered offer is not replaced on the same socket: a delayed answer
  must not be applied to different ICE credentials. Socket replacement abandons
  the old negotiation; local offer creation is serialized.
- Signaling has a three-second application heartbeat and a five-second response
  timeout. A lost or half-open socket is replaced without closing the media peer.
- Initial authentication is still single-use and expires after five minutes.
  `ready` additionally returns a cryptographically random 256-bit `resumeToken`.
  Only that secret can resume the session or replace its half-open signaling socket.
  It is stored in tab-scoped `sessionStorage` with a 45-second rolling expiry so a
  reload can restore it. The initial login token is not persisted. Neither secret
  is put in a URL or logged. If storage is disabled, in-memory recovery still works.
- The server retains a disconnected signaling session for 45 seconds. Native
  media disconnection/failure also has a fixed 45-second grace period. Recovery
  cancels the native timeout and requests a fresh keyframe. A later outage gets
  its own timer; stale timers cannot close a recovered connection.
- Page reload uses the cached endpoint/secret automatically, without replaying
  the consumed login token. `pagehide` suspends the client without sending `bye`;
  explicit close removes the cached credential. A new page has a new random
  `peerId`. After validating the resume secret, the server recreates DTLS/SCTP and
  media tracks when this identity changes, preserving the capture and audio sources.
  Old peer callbacks and timers are invalidated before teardown. A repeated resume
  with the same peer identity only replaces signaling and never resets the peer.
- Peer replacement queues an input-release marker behind old control messages,
  rather than clearing a partially consumed stream. Held keys and touches are
  released on the controller thread before new input is processed.
- Subsequent authenticated offers are accepted for ICE restart. An offer already
  being processed is still protected against overlapping negotiations.
- Under control-channel backpressure, move/scroll updates are dropped; other
  messages, including key/touch releases, are queued in order (64 KiB bound) and
  drained on `bufferedamountlow`. Sustained critical-input overflow remains a
  terminal failure so Android can release held inputs.
- User-initiated/terminal close sends `bye`, rather than waiting for the grace
  period. Error text is shown after the failed state so the UI retains the cause.
- Display producers are detached before the old codec surface is destroyed.
  Stopping the encoder no longer unconditionally resets/reallocates the codec.

## Protocol additions

All messages use the existing authenticated signaling connection. The upstream
WS support is preserved; use WSS when deploying outside a trusted network. Deploy the H5 and Android changes together.

| Direction | Message | Purpose |
| --- | --- | --- |
| Server → H5 | `ready` with `resumeToken`, `resumed`, `peerId` | Issue recovery secret / acknowledge resume |
| H5 → server | `resume` with `token`, `peerId` | Resume using the recovery secret, not the initial token |
| H5 → server | `ping` | Check signaling liveness |
| Server → H5 | `pong` | Acknowledge heartbeat |
| H5 → server | `bye` | Explicitly terminate the session |

Recovery is bounded; it does not promise to survive arbitrary outages. A closed
SCTP data channel during an active page, rejected authentication, expired session,
or destroyed server session still requires a new session. Refresh within the
recovery window can now replace the old SCTP peer after authenticated resume. This change does not add a session-creation
API or reuse consumed login credentials.

## Validation and deployment

Run `python3 tests/run_regressions.py` for Java admission/expiry checks, Java syntax
parsing, native media utility checks, TypeScript checks, and browser mocks covering
ICE recovery, signaling resumption, silent socket loss, stale callbacks, recovery
timeout, authentication rejection, ordered input draining, page reload, new-peer
identity, expired tab credentials, storage-policy failures, and old-server detection.

Build H5 with `npm run build` in `p4/web`. Rebuild JNI with
`python3 server/tools/build_native.py` using the required WebRTC/Android toolchain,
then build the Android application. The checked-in native binary is NOT rebuilt
by editing C++ or by running the lightweight regression suite; shipping it would
retain its old behavior and cannot provide the new native peer-reset entry point.

Before deployment, run Android tests/lint/checkstyle and exercise an actual device:

1. Stream, then interrupt network traffic for 5–15 seconds and restore it.
   Expect `reconnecting`, recovery on the same session, a fresh keyframe, and input
   working again without black-screen persistence.
2. Drop signaling alone, including silently dropping packets without closing TCP.
   Expect authenticated socket replacement and continued/recovered media.
3. Repeat outages and restore connectivity before the recovery deadline. Verify
   an old timeout/socket callback cannot shut down the recovered session.
4. Keep the network unavailable beyond the deadline. Expect a specific timeout
   message and eventual server cleanup, not an infinite reconnect loop.
5. Drag/type under constrained bandwidth. Verify move updates are shed, ordered
   release events drain, and the video connection survives transient backpressure.
6. Stop/reconfigure capture and check that the display no longer targets a released
   encoder surface. Hardware integration is required to verify this ordering.

7. Refresh repeatedly while streaming, including while holding a key/touch. Expect
   automatic resume, a new native peer and control channel, and released old input.
8. Restore a page from the back-forward cache within 45 seconds. Verify it reconnects
   without replaying the initial token. After expiry, verify it requires a new session.

## Updating an existing installation

Deploy the H5 bundle and rebuild JNI/APK together. Older JNI binaries lack
`nativeResetPeer`; this is reported as a server-update error instead of crashing
with `UnsatisfiedLinkError`. A session already refreshed under the old H5 lost its
in-memory secret and cannot recover it retroactively: create one new session after
updating, then reload recovery is available. A new login token explicitly entered
for a restarted server gets one fallback authentication attempt if cached resume
is rejected; consumed tokens are never made reusable.

## Verification in this workspace

- Dependency-light regression suite and TypeScript checks passed.
- H5 production build passed (Linux build dependencies supplied outside the repo).
- Android `test lint checkstyle` could not run: the Gradle distribution download
  failed with `Network is unreachable`.
- JNI build could not run: `WEBRTC_ROOT`, `CLANG`, `ANDROID_SYSROOT`, and
  `WEBRTC_REVISION` are not supplied. No updated native binary or APK is produced.
- Real-device weak-network recovery remains to be verified.

## Diagnosing a repeated rejection after reload

The H5 diagnostic area starts with `H5 recovery build: reload-diagnostics-v1`.
It records whether a resume cache matched, whether `auth` or `resume` was sent,
the server protocol marker, and an allowlisted WebSocket close reason. No token
values or SDP are logged by these diagnostics. Server admission logs use the same
reason codes. `LOGIN_TOKEN_USED`, `RESUME_TOKEN_INVALID`, and `RESUME_EXPIRED`
distinguish a replayed login token, a mismatched server session, and an expired
recovery window. Existing protocol rejection reasons are also displayed.

An initial-token fallback is permitted only for a resume authentication mismatch,
not for every policy/protocol rejection. A protocol rejection must not trigger a
misleading retry using an already-consumed login token. Lightweight tests cover
this distinction. The repeated real-device rejection still requires the complete
H5 diagnostic sequence to identify its actual cause; passing mocked regressions
is not proof that the deployed H5/APK/JNI combination supports refresh recovery.

## Static-screen first frame after repeated reloads

Each successful peer reset now rearms a capture refresh on the encoder thread.
The refresh waits for a positive send bitrate and merges with bitrate/size
reconfiguration. A sync-frame request alone may not produce fresh input when
the display is static; the old one-time startup flag skipped this refresh for
later peers.

H5 also watches for the first rendered video frame. While the peer and control
channel are connected and playback is active, it sends the existing RESET_VIDEO
control message at most three times, at least two seconds apart. It stops on
the first frame, page exit, connection termination, or the bounded timeout.

Deploy the updated H5 bundle and Android Java application together. This
incremental fix adds no JNI entry point and works with the already rebuilt
native library supporting nativeResetPeer. It does not repair an older library
that lacks that method.

Regression coverage includes ten peer-reset cycles, positive-bitrate gating,
first-frame cancellation, delayed readiness, and retry exhaustion. On a device,
leave the display completely static and reload at least ten times without
touching the emulator; verify a first frame appears on every connection.

### Bootstrap correction

Waiting for positive bitrate must not block all source frames: native encoder
initialization can depend on input before SetRates is delivered. Each peer now
gets a bounded bootstrap IDR allowance, plus one capture refresh on its first
keyframe request (transport readiness). A positive rate still triggers the
send-ready refresh. A later zero-rate pause does not reopen the allowance.
This removes the Java-side circular wait without disabling native congestion
control. Device logs distinguish bootstrap refresh, bootstrap IDR forwarding,
and positive-rate refresh; receiver retries remain a bounded fallback.
