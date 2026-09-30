package com.genymobile.scrcpy.client;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

public class MainActivity extends Activity
        implements SignalingClientListener, WebRtcManager.Listener, DataChannelManager.Listener {

    private static final String TAG = "MainActivity";
    private static final long RECONNECT_INITIAL_DELAY_MS = 1000;
    private static final long RECONNECT_MAX_DELAY_MS = 30000;
    private static final int RECONNECT_MAX_ATTEMPTS = 10;

    private EditText editServerUrl;
    private EditText editToken;
    private Button btnConnect;
    private TextView textStatus;
    private SurfaceViewRenderer surfaceView;
    private View controlPanel;
    private View floatingBar;
    private Button btnDisconnect;
    private Button btnFullscreen;
    private View reconnectingOverlay;
    private TextView textReconnecting;

    private SignalingClient signalingClient;
    private WebRtcManager webRtcManager;
    private DataChannelManager dataChannelManager;
    private TouchInputHandler touchInputHandler;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private EglBase eglBase;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean connected;
    private boolean paused;
    private boolean isFullscreen;
    private int reconnectAttempts;
    private long reconnectDelayMs;
    private String lastServerUrl;
    private String lastToken;

    private final Runnable reconnectRunnable = this::attemptReconnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        editServerUrl = findViewById(R.id.edit_server_url);
        editToken = findViewById(R.id.edit_token);
        btnConnect = findViewById(R.id.btn_connect);
        textStatus = findViewById(R.id.text_status);
        surfaceView = findViewById(R.id.surface_view);
        controlPanel = findViewById(R.id.control_panel);
        floatingBar = findViewById(R.id.floating_bar);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        reconnectingOverlay = findViewById(R.id.reconnecting_overlay);
        textReconnecting = findViewById(R.id.text_reconnecting);

        eglBase = EglBase.create();
        surfaceView.init(eglBase.getEglBaseContext(), null);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        btnConnect.setOnClickListener(v -> {
            if (connected) {
                disconnect();
            } else {
                connect();
            }
        });

        btnDisconnect.setOnClickListener(v -> disconnect());

        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        if (savedInstanceState != null) {
            editServerUrl.setText(savedInstanceState.getString("server_url"));
            editToken.setText(savedInstanceState.getString("token"));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("server_url", editServerUrl.getText().toString());
        outState.putString("token", editToken.getText().toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(reconnectRunnable);
        disconnect();
        if (surfaceView != null) {
            surfaceView.release();
        }
        if (eglBase != null) {
            eglBase.release();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    private void connect() {
        String url = editServerUrl.getText().toString().trim();
        String token = editToken.getText().toString().trim();
        if (url.isEmpty() || token.isEmpty()) {
            setStatus("Please enter server URL and token");
            return;
        }

        lastServerUrl = url;
        lastToken = token;
        reconnectAttempts = 0;
        reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS;

        startConnection(url, token);
    }

    private void startConnection(String url, String token) {
        setStatus("Connecting...");
        showConnectingUI();
        connected = true;

        dataChannelManager = new DataChannelManager(this);

        touchInputHandler = new TouchInputHandler(payload -> {
            if (dataChannelManager != null) {
                return dataChannelManager.sendControlMessage(payload);
            }
            return false;
        });
        touchInputHandler.setRemoteScreenSize(1080, 1920);

        requestAudioFocus();

        if (webRtcManager == null || !webRtcManager.isInitialized()) {
            webRtcManager = new WebRtcManager(this, this);
            webRtcManager.initialize();
        }
        webRtcManager.createPeerConnection();

        signalingClient = new SignalingClient(this);
        signalingClient.connect(url, token);
    }

    private void disconnect() {
        mainHandler.removeCallbacks(reconnectRunnable);
        abandonAudioFocus();
        if (signalingClient != null) {
            signalingClient.sendBye();
            signalingClient.disconnect();
            signalingClient = null;
        }
        if (dataChannelManager != null) {
            dataChannelManager.dispose();
            dataChannelManager = null;
        }
        touchInputHandler = null;
        if (webRtcManager != null) {
            webRtcManager.shutdown();
            webRtcManager = null;
        }
        connected = false;
        reconnectAttempts = 0;
        btnConnect.setText("Connect");
        setStatus("Disconnected");
        surfaceView.setOnTouchListener(null);
        surfaceView.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        floatingBar.setVisibility(View.GONE);
        reconnectingOverlay.setVisibility(View.GONE);
    }

    private void attemptReconnect() {
        if (!connected || paused) {
            return;
        }
        reconnectAttempts++;
        if (reconnectAttempts > RECONNECT_MAX_ATTEMPTS) {
            setStatus("Reconnect failed after " + RECONNECT_MAX_ATTEMPTS + " attempts");
            showDisconnectedUI();
            return;
        }

        Log.i(TAG, "Reconnect attempt " + reconnectAttempts + " in " + reconnectDelayMs + "ms");
        setStatus("Reconnecting... (attempt " + reconnectAttempts + ")");
        textReconnecting.setText("Reconnecting... (" + reconnectAttempts + "/" + RECONNECT_MAX_ATTEMPTS + ")");

        if (dataChannelManager != null) {
            dataChannelManager.dispose();
            dataChannelManager = new DataChannelManager(this);
        }

        if (webRtcManager != null) {
            webRtcManager.closePeerConnection();
            webRtcManager.createPeerConnection();
        }

        if (signalingClient != null) {
            signalingClient.reconnect();
        }

        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_DELAY_MS);
    }

    private void setStatus(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            textStatus.setText(text);
        } else {
            mainHandler.post(() -> textStatus.setText(text));
        }
    }

    private void showVideo() {
        mainHandler.post(() -> {
            controlPanel.setVisibility(View.GONE);
            surfaceView.setVisibility(View.VISIBLE);
            floatingBar.setVisibility(View.VISIBLE);
            reconnectingOverlay.setVisibility(View.GONE);
            surfaceView.setOnTouchListener(touchInputHandler);
        });
    }

    private void showConnectingUI() {
        mainHandler.post(() -> {
            btnConnect.setText("Cancel");
            controlPanel.setVisibility(View.VISIBLE);
            floatingBar.setVisibility(View.GONE);
            reconnectingOverlay.setVisibility(View.GONE);
        });
    }

    private void showReconnecting() {
        mainHandler.post(() -> {
            reconnectingOverlay.setVisibility(View.VISIBLE);
            textReconnecting.setText("Reconnecting...");
        });
    }

    private void showDisconnectedUI() {
        mainHandler.post(() -> {
            connected = false;
            btnConnect.setText("Connect");
            surfaceView.setVisibility(View.GONE);
            floatingBar.setVisibility(View.GONE);
            reconnectingOverlay.setVisibility(View.GONE);
            controlPanel.setVisibility(View.VISIBLE);
        });
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        if (isFullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            floatingBar.setVisibility(View.GONE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
            floatingBar.setVisibility(View.VISIBLE);
        }
    }

    // --- SignalingClientListener ---

    @Override
    public void onReady(String resumeToken, boolean resumed, String peerId, int protocolVersion) {
        Log.i(TAG, "onReady: peerId=" + peerId + " protocol=" + protocolVersion + " resumed=" + resumed);
        setStatus("Authenticated, creating offer...");
        reconnectAttempts = 0;
        reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS;
        webRtcManager.createOffer();

        mainHandler.postDelayed(() -> {
            if (webRtcManager != null && webRtcManager.isOfferCreated()) {
                String sdp = webRtcManager.getOfferSdp();
                if (sdp != null) {
                    signalingClient.sendOffer(sdp);
                    setStatus("Offer sent, waiting for answer...");
                } else {
                    setStatus("Error: offer SDP is null");
                }
            }
        }, 500);
    }

    @Override
    public void onAnswer(String sdp) {
        Log.i(TAG, "onAnswer: sdp length=" + sdp.length());
        webRtcManager.setRemoteAnswer(sdp);
        setStatus("Answer received, connecting...");
    }

    @Override
    public void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        Log.d(TAG, "remoteIceCandidate: mid=" + sdpMid);
        if (webRtcManager != null) {
            webRtcManager.addIceCandidate(sdpMid, sdpMLineIndex, candidate);
        }
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "onError: " + message);
        setStatus("Error: " + message);
        if (connected && !signalingClient.isIntentionalClose()) {
            scheduleReconnect();
        } else {
            showDisconnectedUI();
        }
    }

    @Override
    public void onClosed(int code, String reason) {
        Log.i(TAG, "onClosed: code=" + code + " reason=" + reason);
        setStatus("Closed: " + reason);
        if (connected && !signalingClient.isIntentionalClose()) {
            scheduleReconnect();
        } else {
            showDisconnectedUI();
        }
    }

    @Override
    public void onConnectionDropped() {
        Log.w(TAG, "Connection dropped unexpectedly");
        if (connected) {
            showReconnecting();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, reconnectDelayMs);
    }

    // --- WebRtcManager.Listener ---

    @Override
    public void onIceConnectionState(WebRtcManager.IceConnectionState state) {
        Log.i(TAG, "iceConnectionState: " + state.name());
        switch (state) {
            case CONNECTING:
                setStatus("ICE connecting...");
                break;
            case CONNECTED:
                setStatus("Connected");
                reconnectAttempts = 0;
                reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS;
                break;
            case DISCONNECTED:
                setStatus("ICE disconnected");
                if (connected) {
                    showReconnecting();
                    scheduleReconnect();
                }
                break;
            case FAILED:
                setStatus("ICE failed");
                if (connected) {
                    showReconnecting();
                    scheduleReconnect();
                }
                break;
            case CLOSED:
                setStatus("Connection closed");
                break;
        }
    }

    @Override
    public void onRemoteVideo(VideoTrack videoTrack) {
        Log.i(TAG, "onRemoteVideo");
        videoTrack.addSink(surfaceView);
        showVideo();
        setStatus("Streaming");
    }

    @Override
    public void onLocalIceCandidate(IceCandidate candidate) {
        if (signalingClient != null) {
            signalingClient.sendIceCandidate(
                    candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
        }
    }

    @Override
    public void onLocalDataChannel(org.webrtc.DataChannel dataChannel) {
        Log.i(TAG, "onLocalDataChannel");
        if (dataChannelManager != null) {
            dataChannelManager.setDataChannel(dataChannel);
        }
    }

    // --- DataChannelManager.Listener ---

    @Override
    public void onStateChange(DataChannelManager.State state) {
        Log.i(TAG, "DataChannel state: " + state.name());
        if (state == DataChannelManager.State.OPEN) {
            setStatus("DataChannel open");
        }
    }

    @Override
    public void onDeviceMessage(DeviceMessageParser.DeviceMessage message) {
        Log.i(TAG, "DeviceMessage: type=" + message.getType());
        switch (message.getType()) {
            case DeviceMessageParser.TYPE_CLIPBOARD:
                Log.i(TAG, "Clipboard: " + message.getText());
                break;
            case DeviceMessageParser.TYPE_ACK_CLIPBOARD:
                Log.d(TAG, "AckClipboard: sequence=" + message.getSequence());
                break;
            case DeviceMessageParser.TYPE_UHID_OUTPUT:
                Log.d(TAG, "UhidOutput: id=" + message.getId() + " dataLen=" + message.getData().length);
                break;
        }
    }

    private void requestAudioFocus() {
        if (audioManager == null) {
            return;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .build();
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        Log.i(TAG, "Audio focus request: " + result);
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        }
    }
}
