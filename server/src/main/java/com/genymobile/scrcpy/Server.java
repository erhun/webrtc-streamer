package com.genymobile.scrcpy;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
import com.genymobile.scrcpy.audio.AudioPlaybackCapture;
import com.genymobile.scrcpy.audio.AudioRawRecorder;
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.DataChannelInputStream;
import com.genymobile.scrcpy.device.DataChannelOutputStream;
import com.genymobile.scrcpy.device.DesktopConnection;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.NativeEncoderBridge;
import com.genymobile.scrcpy.device.PacketSink;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.device.WebRtcPacketSink;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.signal.SignalServer;
import com.genymobile.scrcpy.model.NewDisplay;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.video.CameraCapture;
import com.genymobile.scrcpy.video.NewDisplayCapture;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoSource;
import com.genymobile.scrcpy.video.VideoCodec;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class Server {

    public static final String SERVER_PATH;

    static {
        String[] classPaths = System.getProperty("java.class.path").split(File.pathSeparator);
        // By convention, scrcpy is always executed with the absolute path of scrcpy-server.jar as the first item in the classpath
        SERVER_PATH = classPaths[0];
    }

    private static class Completion {
        private int running;
        private boolean fatalError;
        private final Looper looper;

        Completion(int running) {
            this.running = running;
            this.looper = Looper.myLooper();
        }

        synchronized void addCompleted(boolean fatalError) {
            --running;
            if (fatalError) {
                this.fatalError = true;
            }
            if (running == 0 || this.fatalError) {
                looper.quitSafely();
            }
        }
    }

    private Server() {
        // not instantiable
    }

    private static void scrcpy(Context context, Options options, Session session) throws IOException, ConfigurationException {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_31_ANDROID_12 && options.getVideoSource() == VideoSource.CAMERA) {
            Ln.e("Camera mirroring is not supported before Android 12");
            throw new ConfigurationException("Camera mirroring is not supported");
        }

        if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
            if (options.getNewDisplay() != null) {
                Ln.e("New virtual display is not supported before Android 10");
                throw new ConfigurationException("New virtual display is not supported");
            }
            if (options.getDisplayImePolicy() != -1) {
                Ln.e("Display IME policy is not supported before Android 10");
                throw new ConfigurationException("Display IME policy is not supported");
            }
        }

        CleanUp cleanUp = null;

        if (options.getCleanup() && context == null) {
            cleanUp = CleanUp.start(options);
        }

        int scid = options.getScid();
        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean video = options.getVideo();
        boolean audio = options.getAudio();
        boolean sendDummyByte = options.getSendDummyByte();

        Workarounds.apply();

        List<AsyncProcessor> asyncProcessors = new ArrayList<>();

        NativeEncoderBridge bridge = null;
        SignalServer signalServer = null;
        DataChannelInputStream dataIn = null;
        DesktopConnection connection = null;
        int signalPort = options.getSignalPort();
        if (signalPort == 0) {
            connection = DesktopConnection.open(scid, tunnelForward, video, audio, control, sendDummyByte);
        }
        try {
            if (connection != null && options.getSendDeviceMeta()) {
                connection.sendDeviceMeta(Device.getDeviceName());
            }
            if (signalPort > 0) {
                if (!video || options.getVideoCodec() != VideoCodec.H264) {
                    throw new ConfigurationException("WebRTC requires H.264 video");
                }
                if (options.getSignalToken().length() < 32) {
                    throw new ConfigurationException("WebRTC requires signal_token with at least 32 characters");
                }
                bridge = new NativeEncoderBridge();
                bridge.setClosedCallback(session::stop);
                bridge.open(audio, options.getTurnUrl(), options.getTurnUser(), options.getTurnPassword());
            }

            Controller controller = null;

            if (control) {
                ControlChannel controlChannel = null;
                if (connection != null) {
                    controlChannel = connection.getControlChannel();
                } else if (bridge != null) {
                    dataIn = new DataChannelInputStream(session::stop);
                    DataChannelOutputStream dataOut = new DataChannelOutputStream(bridge);
                    bridge.setDataCallback(dataIn);
                    controlChannel = new ControlChannel(dataIn, dataOut);
                }
                if (controlChannel != null) {
                    controller = new Controller(controlChannel, cleanUp, options);
                    asyncProcessors.add(controller);
                }
            }

            if (audio) {
                AudioCodec audioCodec = bridge != null ? AudioCodec.RAW : options.getAudioCodec();
                AudioSource audioSource = options.getAudioSource();
                AudioCapture audioCapture;
                if (audioSource.isDirect()) {
                    audioCapture = new AudioDirectCapture(audioSource);
                } else {
                    audioCapture = new AudioPlaybackCapture(options.getAudioDup());
                }

                PacketSink audioSink = createAudioSink(bridge, connection, audioCodec, options);
                AsyncProcessor audioRecorder;
                if (audioCodec == AudioCodec.RAW) {
                    audioRecorder = new AudioRawRecorder(audioCapture, audioSink);
                } else {
                    audioRecorder = new AudioEncoder(audioCapture, audioSink, options);
                }
                asyncProcessors.add(audioRecorder);
            }

            if (video) {
                PacketSink videoSink = createVideoSink(bridge, connection, options);
                SurfaceCapture surfaceCapture;
                if (options.getVideoSource() == VideoSource.DISPLAY) {
                    NewDisplay newDisplay = options.getNewDisplay();
                    if (newDisplay != null) {
                        surfaceCapture = new NewDisplayCapture(controller, options);
                    } else {
                        assert options.getDisplayId() != Device.DISPLAY_ID_NONE;
                        surfaceCapture = new ScreenCapture(controller, options);
                    }
                } else {
                    surfaceCapture = new CameraCapture(options);
                }
                SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoSink, options);
                if (bridge != null) {
                    bridge.setCallback(surfaceEncoder);
                }
                asyncProcessors.add(surfaceEncoder);

                if (controller != null) {
                    controller.setSurfaceCapture(surfaceCapture);
                }
            }

            if (bridge != null) { signalServer = startSignalServer(bridge, options, session); }
            Completion completion = new Completion(asyncProcessors.size());
            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.start((fatalError) -> {
                    completion.addCompleted(fatalError);
                });
            }

            Looper.loop(); // interrupted by the Completion implementation
        } finally {
            if (signalServer != null) { signalServer.close(); }
            if (dataIn != null) { dataIn.close(); }
            if (cleanUp != null) {
                cleanUp.interrupt();
            }
            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.stop();
            }

            if (connection != null) {
                connection.shutdown();
            }

            try {
                if (cleanUp != null) {
                    cleanUp.join();
                }
                for (AsyncProcessor asyncProcessor : asyncProcessors) {
                    asyncProcessor.join();
                }

                OpenGLRunner.shutdown();
            } catch (InterruptedException e) {
                // ignore
            }

            if (connection != null) {
                connection.close();
            }
            if (bridge != null) {
                bridge.close();
            }
        }
    }

    private static PacketSink createAudioSink(NativeEncoderBridge bridge, DesktopConnection connection, AudioCodec audioCodec, Options options) {
        if (bridge != null) {
            return new WebRtcPacketSink(audioCodec, bridge, false);
        }
        return new Streamer(connection.getAudioFd(), audioCodec, options.getSendStreamMeta(), options.getSendFrameMeta());
    }

    private static PacketSink createVideoSink(NativeEncoderBridge bridge, DesktopConnection connection, Options options) {
        if (bridge != null) {
            return new WebRtcPacketSink(options.getVideoCodec(), bridge, true);
        }
        return new Streamer(connection.getVideoFd(), options.getVideoCodec(), options.getSendStreamMeta(), options.getSendFrameMeta());
    }

    private static SignalServer startSignalServer(NativeEncoderBridge bridge, Options options, Session session) {
        SignalServer signalServer = new SignalServer(options.getSignalPort(), options.getSignalToken(), new SignalServer.Listener() {
            @Override
            public void onClosed() { session.stop(); }

            @Override
            public void onOffer(String sdp) {
                bridge.onOffer(sdp);
            }

            @Override
            public void onIceCandidate(String sdpMid, int sdpMlineIndex, String sdp) {
                bridge.onIceCandidate(sdpMid, sdpMlineIndex, sdp);
            }
        });
        bridge.setSignalCallback(new NativeEncoderBridge.SignalCallback() {
            @Override
            public void onAnswer(String sdp) {
                signalServer.sendAnswer(sdp);
            }

            @Override
            public void onIceCandidate(String sdpMid, int sdpMlineIndex, String sdp) {
                signalServer.sendIceCandidate(sdpMid, sdpMlineIndex, sdp);
            }
        });
        signalServer.start();
        return signalServer;
    }

    private static void prepareMainLooper() {
        // Like Looper.prepareMainLooper(), but with quitAllowed set to true
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                @SuppressLint("DiscouragedPrivateApi")
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    public static final class Session {
        public enum State { STARTING, RUNNING, STOPPING, STOPPED }
        private State state = State.STARTING;
        private Looper looper;
        public synchronized State getState() { return state; }
        private synchronized void attach(Looper value) {
            looper = value;
            if (state == State.STOPPING) { looper.quitSafely(); }
            else { state = State.RUNNING; }
        }
        public synchronized void stop() {
            if (state == State.STOPPED) { return; }
            state = State.STOPPING;
            if (looper != null) { looper.quitSafely(); }
        }
        private synchronized void finished() { state = State.STOPPED; looper = null; }
    }
    private static Session activeServiceSession;
    public static synchronized Session run(Context context, String[] args, Runnable finished) {
        if (activeServiceSession != null) { return null; }
        Session session = new Session();
        activeServiceSession = session;
        new Thread(() -> {
            Looper.prepare();
            session.attach(Looper.myLooper());
            try {
                Options options = Options.parse(args);
                Ln.disableSystemStreams();
                Ln.initLogLevel(options.getLogLevel());
                if (session.getState() != Session.State.STOPPING) { scrcpy(context, options, session); }
            } catch (Exception e) { Ln.e("Server error", e); }
            finally {
                session.finished();
                synchronized (Server.class) { activeServiceSession = null; }
                finished.run();
            }
        }, "scrcpy-server").start();
        return session;
    }

    public static void main(String... args) {
        int status = 0;
        try {
            internalMain(args);
        } catch (Throwable t) {
            Ln.e(t.getMessage(), t);
            status = 1;
        } finally {
            // By default, the Java process exits when all non-daemon threads are terminated.
            // The Android SDK might start some non-daemon threads internally, preventing the scrcpy server to exit.
            // So force the process to exit explicitly.
            System.exit(status);
        }
    }

    private static void internalMain(String... args) throws Exception {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Ln.e("Exception on thread " + t, e);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });

        dropRootPrivileges();

        prepareMainLooper();

        Options options = Options.parse(args);

        Ln.disableSystemStreams();
        Ln.initLogLevel(options.getLogLevel());

        Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");

        if (options.getList()) {
            if (options.getCleanup()) {
                CleanUp.unlinkSelf();
            }

            if (options.getListEncoders()) {
                Ln.i(LogUtils.buildVideoEncoderListMessage());
                Ln.i(LogUtils.buildAudioEncoderListMessage());
            }
            if (options.getListDisplays()) {
                Ln.i(LogUtils.buildDisplayListMessage());
            }
            if (options.getListCameras() || options.getListCameraSizes()) {
                Workarounds.apply();
                Ln.i(LogUtils.buildCameraListMessage(options.getListCameraSizes()));
            }
            if (options.getListApps()) {
                Workarounds.apply();
                Ln.i("Processing Android apps... (this may take some time)");
                Ln.i(LogUtils.buildAppListMessage());
            }
            // Just print the requested data, do not mirror
            return;
        }

        try {
            Session session = new Session();
            session.attach(Looper.myLooper());
            scrcpy(null, options, session);
        } catch (ConfigurationException e) {
            // Do not print stack trace, a user-friendly error-message has already been logged
        }
    }

    @SuppressWarnings("deprecation")
    private static void dropRootPrivileges() {
        try {
            if (Os.getuid() == 0) {
                // Copy-paste does not work with root user
                // <https://github.com/Genymobile/scrcpy/issues/6224>
                Os.setuid(2000);
            }
        } catch (Exception e) {
            Ln.w("Cannot set UID", e);
        }
    }
}
