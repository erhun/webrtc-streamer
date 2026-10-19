#!/usr/bin/env python3
"""Behavior tests of actual session/protocol/policy classes; platform and native transport are bindings, not media tests."""
from pathlib import Path
import os
import subprocess
import tempfile
from run_foreground_service import platform_bindings

root = Path(__file__).resolve().parents[1]
base = root / 'server/src/main/java/com/genymobile/scrcpy'
bindings = platform_bindings()
# Use the actual ICE rewriter in these tests.
del bindings['com/genymobile/scrcpy/signal/SdpRewriter.java']
bindings.update({
'org/json/JSONObject.java': r'''package org.json;
import java.util.*;
import java.util.regex.*;
public final class JSONObject {
    public static final Object NULL = new Object();
    private final Map<String, Object> values = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String text) throws JSONException {
        if (!text.startsWith("{") || !text.endsWith("}")) throw new JSONException();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"([^\"]*)\"|true|false|-?[0-9]+)").matcher(text);
        while (m.find()) values.put(m.group(1), m.group(3) != null ? m.group(3) : m.group(2));
    }
    public JSONObject put(String key, Object value) throws JSONException { values.put(key, value); return this; }
    public String getString(String key) throws JSONException { if (!values.containsKey(key)) throw new JSONException(); return String.valueOf(values.get(key)); }
    public int getInt(String key) throws JSONException { return Integer.parseInt(getString(key)); }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String fallback) { return values.containsKey(key) ? String.valueOf(values.get(key)) : fallback; }
    public String toString() {
        StringJoiner j = new StringJoiner(",", "{", "}");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            j.add("\"" + e.getKey() + "\":" + (v == NULL ? "null" : v instanceof String ? "\"" + v + "\"" : v));
        }
        return j.toString();
    }
}
''',
'org/java_websocket/WebSocket.java': '''package org.java_websocket;
import java.util.concurrent.CopyOnWriteArrayList;
public final class WebSocket {
    public final CopyOnWriteArrayList<String> sent = new CopyOnWriteArrayList<>();
    public volatile int code; public volatile String reason; public volatile boolean open = true;
    public void close(int value, String text) { code = value; reason = text; open = false; }
    public boolean isOpen() { return open; }
    public void send(String value) { sent.add(value); }
}
''',
'com/genymobile/scrcpy/util/Ln.java': '''package com.genymobile.scrcpy.util;
public final class Ln {
    public static void i(String s) {} public static void d(String s) {} public static void w(String s) {}
    public static void e(String s, Throwable t) {}
}
''',
'android/media/MediaCodec.java': '''package android.media;
public final class MediaCodec {
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2, BUFFER_FLAG_KEY_FRAME = 1;
    public static final class BufferInfo { public long presentationTimeUs; public int flags; }
}
''',
'com/genymobile/scrcpy/model/Codec.java': 'package com.genymobile.scrcpy.model; public interface Codec {}',
'com/genymobile/scrcpy/video/VideoCodec.java': 'package com.genymobile.scrcpy.video; public enum VideoCodec implements com.genymobile.scrcpy.model.Codec { H264 }',
'com/genymobile/scrcpy/audio/AudioCodec.java': 'package com.genymobile.scrcpy.audio; public enum AudioCodec implements com.genymobile.scrcpy.model.Codec { RAW, OPUS }',
'com/genymobile/scrcpy/control/ControlChannel.java': '''package com.genymobile.scrcpy.control;
public final class ControlChannel {
    public final java.io.InputStream input; public final java.io.OutputStream output;
    public ControlChannel(java.io.InputStream input, java.io.OutputStream output) { this.input = input; this.output = output; }
}
''',
'com/genymobile/scrcpy/control/ControlMessage.java': 'package com.genymobile.scrcpy.control; public final class ControlMessage { public static final int TYPE_RELEASE_INPUTS = 23; }',
'com/genymobile/scrcpy/Options.java': '''package com.genymobile.scrcpy;
import com.genymobile.scrcpy.audio.AudioScheme;
public final class Options {
    public boolean debug; public String token = "a".repeat(32); public int maxPeers = 2; public int minPort, maxPort;
    public boolean getSignalDebug() { return debug; } public String getSignalToken() { return token; }
    public String getBandwidthPolicy() { return "controller"; } public int getMaxPeers() { return maxPeers; } public int getSignalPort() { return 8080; }
    public String getSignalRoom() { return "default"; } public String getSignalBind() { return "127.0.0.1"; }
    public boolean getAudio() { return true; } public boolean getControl() { return true; } public AudioScheme getAudioScheme() { return AudioScheme.PCM; }
    public String getTurnUrl() { return ""; } public String getTurnUser() { return ""; } public String getTurnPassword() { return ""; }
    public String getPublicIp() { return ""; } public int getLocalMinPort() { return minPort; } public int getLocalMaxPort() { return maxPort; }
    public int getExternalMinPort() { return 0; } public int getExternalMaxPort() { return 0; }
}
''',
'com/genymobile/scrcpy/video/SurfaceEncoder.java': '''package com.genymobile.scrcpy.video;
public final class SurfaceEncoder { public int keys, resets, refreshes; public int bps;
    public void onBitrate(int rate, double fps) { bps = rate; }
    public void onPeerAttached() { refreshes++; } public void onKeyFrameRequest() { keys++; } public void onPeerReset() { resets++; }
}
''',
'com/genymobile/scrcpy/device/NativeEncoderBridge.java': '''package com.genymobile.scrcpy.device;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
public final class NativeEncoderBridge {
    public interface Callback { void onBitrate(int bps, double fps); void onKeyFrameRequest(); default void onPeerReset() {} }
    public interface SignalCallback { void onAnswer(String sdp); void onIceCandidate(String mid, int index, String sdp); }
    public interface DataCallback { void onData(byte[] bytes); }
    public static final CopyOnWriteArrayList<NativeEncoderBridge> all = new CopyOnWriteArrayList<>();
    public Callback callback; public SignalCallback signal; public DataCallback data; public Runnable closed;
    public volatile int closes, resets, frames, configs, sends; public int minPort, maxPort;
    public NativeEncoderBridge() { all.add(this); }
    public void setCallback(Callback c) { callback = c; } public void setSignalCallback(SignalCallback c) { signal = c; }
    public void setDataCallback(DataCallback c) { data = c; } public void setClosedCallback(Runnable c) { closed = c; }
    public void open(boolean audio, int scheme, String turn, String user, String password, int minPort, int maxPort) { this.minPort = minPort; this.maxPort = maxPort; }
    public void close() { closes++; } public boolean resetPeer() { resets++; callback.onPeerReset(); return true; }
    public void onOffer(String sdp) {} public void onIceCandidate(String mid, int index, String sdp) {}
    public boolean sendData(byte[] bytes) { sends++; return true; }
    public void reportVideoBitrate(int bps) {}
    public void pushEncodedVideoFrame(byte[] bytes, long pts, boolean config, boolean key, int width, int height) {
        if (config) configs++; else frames++;
    }
    public void pushPcm(ByteBuffer bytes, long pts) {} public void pushPcm(byte[] bytes, long pts) {}
}
''',
})
real = ['stream/PeerKey.java', 'stream/StreamingConfig.java', 'stream/StreamingSession.java',
        'signal/PeerLease.java', 'signal/SignalServer.java', 'signal/PortMapConfig.java', 'signal/SdpRewriter.java',
        'control/ControlManager.java', 'device/DataChannelInputStream.java', 'device/PacketSink.java',
        'device/BroadcastVideoSink.java', 'device/BroadcastAudioSink.java', 'audio/AudioScheme.java',
        'video/BitrateAggregator.java', 'video/BitrateLadder.java', 'video/BitrateRampProbe.java',
        'video/VideoAdaptationPolicy.java', 'video/KeyFrameRequestGate.java']
with tempfile.TemporaryDirectory(prefix='architecture-', dir=root / 'tests') as directory:
    temp = Path(directory)
    sources = []
    for relative, content in bindings.items():
        path = temp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
    java = os.environ.get('JAVA', 'java')
    subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-Xlint:all', '-Werror', '-d', directory,
                    *sources, *[str(base / name) for name in real], str(root / 'tests/ArchitectureTest.java')], check=True)
    subprocess.run([java, '-cp', directory, 'ArchitectureTest'], check=True)
