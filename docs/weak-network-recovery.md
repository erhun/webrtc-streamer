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
  It remains in client memory, is never put in a URL, and is never logged.
- The server retains a disconnected signaling session for 45 seconds. Native
  media disconnection/failure also has a fixed 45-second grace period. Recovery
  cancels the native timeout and requests a fresh keyframe. A later outage gets
  its own timer; stale timers cannot close a recovered connection.
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
| Server → H5 | `ready` with `resumeToken`, `resumed` | Issue recovery secret / acknowledge resume |
| H5 → server | `resume` with `token` | Resume using the recovery secret, not the initial token |
| H5 → server | `ping` | Check signaling liveness |
| Server → H5 | `pong` | Acknowledge heartbeat |
| H5 → server | `bye` | Explicitly terminate the session |

Recovery is bounded; it does not promise to survive arbitrary outages. A closed
SCTP data channel, rejected authentication, expired session, or destroyed server
session still requires a new session. This change does not add a session-creation
API or reuse consumed login credentials.

## Validation and deployment

Run `python3 tests/run_regressions.py` for Java admission/expiry checks, Java syntax
parsing, native media utility checks, TypeScript checks, and browser mocks covering
ICE recovery, signaling resumption, silent socket loss, stale callbacks, recovery
timeout, authentication rejection, and ordered input draining.

Build H5 with `npm run build` in `p4/web`. Rebuild JNI with
`python3 server/tools/build_native.py` using the required WebRTC/Android toolchain,
then build the Android application. The checked-in native binary is NOT rebuilt
by editing C++ or by running the lightweight regression suite; shipping it would
retain immediate native session shutdown on failure.

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

## Verification in this workspace

- Dependency-light regression suite and TypeScript checks passed.
- H5 production build passed (Linux build dependencies supplied outside the repo).
- Android `test lint checkstyle` could not run: the Gradle distribution download
  failed with `Network is unreachable`.
- JNI build could not run: `WEBRTC_ROOT`, `CLANG`, `ANDROID_SYSROOT`, and
  `WEBRTC_REVISION` are not supplied. No updated native binary or APK is produced.
- Real-device weak-network recovery remains to be verified.
