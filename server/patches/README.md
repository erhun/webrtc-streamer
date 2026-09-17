# WebRTC frame-drop diagnostics

webrtc-frame-drop-trace.patch targets the exact video_stream_encoder.cc supplied
in this debugging session. It changes logging only, not the drop decisions.
At most 600 trace records are emitted per process with Android tag scrcpy_drop.

From the libwebrtc source checkout (replace the patch path with this repository's
absolute path):

```bash
git apply --check /path/to/webrtc-streamer/server/patches/webrtc-frame-drop-trace.patch
git apply /path/to/webrtc-streamer/server/patches/webrtc-frame-drop-trace.patch
```

Rebuild libwebrtc using the same GN args, clang and ABI as the current materials.
Export the rebuilt static libraries and their referenced object files into the
WEBRTC_ROOT used by server/tools/build_native.py, then rebuild JNI/APK with
p4/deploy.sh --build. Rebuilding only JNI does not compile video_stream_encoder.cc.
The revision identifier alone does not record this local diagnostic modification.

Before connecting, capture logcat using:

```bash
adb -s emulator-5554 logcat -v threadtime 'scrcpy_drop:I' 'scrcpy_native:V' 'scrcpy:V' '*:S'
```

Use the new process PID to exclude previous sessions. Match pts_us across source,
incoming, branch diagnostics and Encode. RTP values may be rewritten internally.
Stages: incoming, drop_size, paused, queue_overload, cwnd_drop,
drop_media_optimization, encoder_not_ready, dropped (numeric enum), encode_dispatch.
Missing incoming with Source sinks=1 warrants investigation before OnFrame (e.g.
cadence adapter); it is not proof of a specific drop reason.

After diagnosis, reverse this patch with git apply -R, rebuild/export libwebrtc,
and rebuild JNI/APK. Validation here is patch application against supplied source;
full libwebrtc build and runtime validation are still required.
