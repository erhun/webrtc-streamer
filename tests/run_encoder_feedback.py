#!/usr/bin/env python3
"""Exercise actual SurfaceEncoder feedback/configuration with platform bindings, not device codec behavior."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
base = root / 'server/src/main/java/com/genymobile/scrcpy'
bindings = {
'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
'android/os/Build.java': 'package android.os; public final class Build { public static final class VERSION { public static int SDK_INT=31; } public static final class VERSION_CODES { public static final int LOLLIPOP=21,N=24; } }',
'android/os/Looper.java': 'package android.os; public final class Looper { public static void prepare() {} }',
'android/os/SystemClock.java': 'package android.os; public final class SystemClock { public static long now; public static long elapsedRealtime() { return now; } public static void sleep(long ms) {} }',
'android/os/Bundle.java': 'package android.os; public final class Bundle { public final java.util.Map<String,Integer> values=new java.util.HashMap<>(); public void putInt(String k,int v) { values.put(k,v); } }',
'android/view/Surface.java': 'package android.view; public final class Surface { public void release() {} }',
'android/util/Log.java': 'package android.util; public final class Log { public static String getStackTraceString(Throwable t) { return t.toString(); } }',
'android/util/Range.java': 'package android.util; public final class Range<T> { private final T upper; public Range(T value) { upper=value; } public T getUpper() { return upper; } }',
'android/media/MediaCodecInfo.java': '''package android.media;
public final class MediaCodecInfo {
 public static double maximumFps=60;
 public CodecCapabilities getCapabilitiesForType(String mime) { return new CodecCapabilities(); }
 public static final class VideoCapabilities {
  public int getWidthAlignment() { return 2; } public int getHeightAlignment() { return 2; }
  public boolean isSizeSupported(int w,int h) { return true; }
  public android.util.Range<Double> getSupportedFrameRatesFor(int w,int h) { return new android.util.Range<>(maximumFps); }
 }
 public static final class CodecCapabilities {
  public static final int COLOR_FormatSurface=1;
  public VideoCapabilities getVideoCapabilities() { return new VideoCapabilities(); }
 }
 public static final class EncoderCapabilities { public static final int BITRATE_MODE_CBR=2; }
}''',
'android/media/MediaFormat.java': '''package android.media;
public final class MediaFormat {
 public static final String KEY_MIME="mime", KEY_BIT_RATE="bitrate", KEY_FRAME_RATE="fps", KEY_COLOR_FORMAT="color",
 KEY_I_FRAME_INTERVAL="gop", KEY_INTRA_REFRESH_PERIOD="intra", KEY_BITRATE_MODE="mode", KEY_WIDTH="width", KEY_HEIGHT="height";
 public final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public void setInteger(String k,int v) { values.put(k,v); } public void setFloat(String k,float v) { values.put(k,v); }
 public void setString(String k,String v) { values.put(k,v); } public boolean containsKey(String k) { return values.containsKey(k); }
}''',
'android/media/MediaCodec.java': '''package android.media;
public final class MediaCodec {
 public static final int BUFFER_FLAG_END_OF_STREAM=4, BUFFER_FLAG_CODEC_CONFIG=2, BUFFER_FLAG_KEY_FRAME=1, CONFIGURE_FLAG_ENCODE=1;
 public static final String PARAMETER_KEY_VIDEO_BITRATE="bitrate", PARAMETER_KEY_REQUEST_SYNC_FRAME="sync";
 public static MediaFormat configuredFormat;
 public final java.util.List<android.os.Bundle> updates=new java.util.ArrayList<>();
 public static MediaCodec createByCodecName(String n) throws java.io.IOException { return new MediaCodec(); }
 public static MediaCodec createEncoderByType(String n) throws java.io.IOException { return new MediaCodec(); }
 public MediaCodecInfo getCodecInfo() { return new MediaCodecInfo(); } public String getName() { return "binding"; }
 public void configure(MediaFormat f,Object s,Object crypto,int flags) { configuredFormat=f; }
 public android.view.Surface createInputSurface() { return new android.view.Surface(); }
 public void start() {} public void stop() {} public void reset() {} public void release() {} public void signalEndOfInputStream() {}
 public int dequeueOutputBuffer(BufferInfo b,long timeout) { b.flags=BUFFER_FLAG_END_OF_STREAM; return 0; }
 public java.nio.ByteBuffer getOutputBuffer(int i) { return java.nio.ByteBuffer.allocate(0); }
 public void releaseOutputBuffer(int i,boolean render) {} public void setParameters(android.os.Bundle b) { updates.add(b); }
 public static final class BufferInfo { public int flags,size; public long presentationTimeUs; }
}''',
'com/genymobile/scrcpy/Options.java': '''package com.genymobile.scrcpy;
public final class Options {
 public boolean adaptive=true; public int maxSize=854; public float fps=60;
 public int getVideoBitRate() { return 8000000; } public boolean getVideoAdaptation() { return adaptive; }
 public int getSignalPort() { return 10000; } public int getMaxSize() { return maxSize; } public float getMaxFps() { return fps; }
 public java.util.List<com.genymobile.scrcpy.model.CodecOption> getVideoCodecOptions() { return null; }
 public String getVideoEncoder() { return null; } public boolean getDownsizeOnError() { return true; }
 public int getMinSizeAlignment() { return 1; } public boolean getIgnoreVideoEncoderConstraints() { return false; }
}''',
'com/genymobile/scrcpy/device/NativeEncoderBridge.java': 'package com.genymobile.scrcpy.device; public final class NativeEncoderBridge { public interface Callback { void onBitrate(int b,double fps); void onKeyFrameRequest(); void onPeerReset(); } }',
'com/genymobile/scrcpy/device/PacketSink.java': '''package com.genymobile.scrcpy.device;
public final class PacketSink {
 public int bitrate;
 public com.genymobile.scrcpy.model.Codec getCodec() { return new com.genymobile.scrcpy.model.Codec(); }
 public void writeVideoHeader() throws java.io.IOException {} public void writeSessionMeta(int w,int h,boolean r) throws java.io.IOException {}
 public void writePacket(java.nio.ByteBuffer b,android.media.MediaCodec.BufferInfo i) throws java.io.IOException {}
 public void reportVideoBitrate(int b) { bitrate=b; }
}''',
'com/genymobile/scrcpy/model/Codec.java': 'package com.genymobile.scrcpy.model; public final class Codec { public String getMimeType() { return "video/avc"; } public String getName() { return "H264"; } public static String getMimeType(android.media.MediaCodec c) { return "video/avc"; } }',
'com/genymobile/scrcpy/model/CodecOption.java': 'package com.genymobile.scrcpy.model; public final class CodecOption { public String getKey() { return ""; } public Object getValue() { return ""; } }',
'com/genymobile/scrcpy/model/Size.java': 'package com.genymobile.scrcpy.model; public final class Size { private final int w,h; public Size(int a,int b) { w=a; h=b; } public int getWidth() { return w; } public int getHeight() { return h; } }',
'com/genymobile/scrcpy/util/Ln.java': 'package com.genymobile.scrcpy.util; public final class Ln { public enum Level { DEBUG } public static boolean isEnabled(Level l) { return false; } public static void d(String s) {} public static void i(String s) {} public static void w(String s) {} public static void e(String s) {} public static void e(String s,Throwable t) {} }',
'com/genymobile/scrcpy/util/IO.java': 'package com.genymobile.scrcpy.util; public final class IO { public static boolean isBrokenPipe(Exception e) { return false; } }',
'com/genymobile/scrcpy/util/CodecUtils.java': 'package com.genymobile.scrcpy.util; public final class CodecUtils { public static void setCodecOption(android.media.MediaFormat f,String k,Object v) {} }',
'com/genymobile/scrcpy/util/LogUtils.java': 'package com.genymobile.scrcpy.util; public final class LogUtils { public static String buildVideoEncoderListMessage() { return ""; } }',
}
with tempfile.TemporaryDirectory(prefix='encoder-feedback-', dir=root / 'tests') as temporary:
    work = Path(temporary)
    paths = []
    for name, contents in bindings.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents + '\n')
        paths.append(str(path))
    actual = ['AsyncProcessor.java', 'model/ConfigurationException.java', 'video/SurfaceEncoder.java',
              'video/SurfaceCapture.java', 'video/VideoConstraints.java', 'video/CaptureControl.java',
              'video/CaptureRefreshGate.java', 'video/KeyFrameRequestGate.java', 'video/VideoPipelineStats.java',
              'video/BitrateLadder.java', 'video/VideoAdaptationPolicy.java']
    subprocess.run([os.environ.get('JAVA', 'java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', temporary,
                    *paths, *(str(base / name) for name in actual), str(root / 'tests/EncoderFeedbackTest.java')], check=True)
    subprocess.run([os.environ.get('JAVA', 'java'), '-cp', temporary, 'EncoderFeedbackTest'], check=True)
