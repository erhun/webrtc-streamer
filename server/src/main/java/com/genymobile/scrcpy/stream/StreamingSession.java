package com.genymobile.scrcpy.stream;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.control.ControlManager;
import com.genymobile.scrcpy.device.BroadcastAudioSink;
import com.genymobile.scrcpy.device.BroadcastVideoSink;
import com.genymobile.scrcpy.device.DataChannelInputStream;
import com.genymobile.scrcpy.device.NativeEncoderBridge;
import com.genymobile.scrcpy.device.PacketSink;
import com.genymobile.scrcpy.signal.SignalServer;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.BitrateAggregator;
import com.genymobile.scrcpy.video.SurfaceEncoder;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application owner: one capture pipeline, bounded peers, one serialized control authority. */
public final class StreamingSession implements SignalServer.Listener, AutoCloseable {
    private final StreamingConfig config;
    private final Runnable fatalError;
    private final BroadcastVideoSink video = new BroadcastVideoSink();
    private final BroadcastAudioSink audio = new BroadcastAudioSink();
    private final ControlManager controls = new ControlManager();
    private final DataChannelInputStream input;
    private final Map<PeerKey, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BitrateAggregator budgets;
    private final SignalServer signaling;
    private volatile SurfaceEncoder encoder;

    public StreamingSession(StreamingConfig config, Runnable fatalError) {
        this.config = config;
        this.fatalError = fatalError;
        input = new DataChannelInputStream(this::onControlBacklogOverflow);
        controls.setController(input::onData);
        budgets = new BitrateAggregator(new BitrateAggregator.Output() {
            @Override
            public void onAggregatedBitrate(int bps, double fps) {
                SurfaceEncoder target = encoder;
                if (target != null) { target.onBitrate(bps, fps); }
            }
            @Override
            public void onAggregatedKeyFrameRequest() {
                SurfaceEncoder target = encoder;
                if (target != null) { target.onKeyFrameRequest(); }
            }
            @Override
            public void onAggregatedPeerReset() {
                SurfaceEncoder target = encoder;
                if (target != null) { target.onPeerReset(); }
            }
        }, config.bandwidthPolicy);
        controls.setOwnerChanged(id -> {
            NativeEncoderBridge preferred = null;
            for (Peer peer : peers.values()) {
                if (peer.active && peer.key.toString().equals(id)) { preferred = peer.bridge; break; }
            }
            budgets.setPreferredBridge(preferred);
        });
        signaling = new SignalServer(config.bindAddress, config.port, config.token, config.room,
                config.maxPeers, this, config.portMap);
    }

    public PacketSink videoSink() { return video; }
    public PacketSink audioSink() { return audio; }
    public void setEncoder(SurfaceEncoder value) { encoder = value; }
    public void start() { signaling.start(); }

    public ControlChannel controlChannel() {
        // Responses follow the current controller, never the most recently joined spectator.
        return new ControlChannel(input, new OutputStream() {
            @Override
            public void write(int value) { write(new byte[]{(byte) value}, 0, 1); }
            @Override
            public void write(byte[] bytes, int offset, int length) {
                String controller = controls.getCurrentController();
                if (controller == null) { return; }
                byte[] message = new byte[length];
                System.arraycopy(bytes, offset, message, 0, length);
                for (Peer peer : peers.values()) {
                    if (peer.key.toString().equals(controller) && peer.active) { peer.bridge.sendData(message); return; }
                }
            }
        });
    }

    @Override
    public void onClientJoined(String roomId, String clientId) {
        PeerKey key = new PeerKey(roomId, clientId);
        Peer peer = new Peer(key);
        if (closed.get() || peers.putIfAbsent(key, peer) != null) { throw new IllegalStateException("Duplicate or closed peer"); }
        try {
            NativeEncoderBridge bridge = peer.bridge;
            bridge.setCallback(new NativeEncoderBridge.Callback() {
                @Override
                public void onBitrate(int bps, double fps) { if (peer.active) {
                        budgets.updateBitrate(bridge, bps, fps);
                        if (bps <= 0) { peer.positiveRefresh.set(true); }
                        if (bps > 0 && peer.positiveRefresh.compareAndSet(true, false)) { refreshCapture(); }
                    } }
                @Override
                public void onKeyFrameRequest() {
                    if (peer.active) {
                        if (peer.bootstrapRefresh.compareAndSet(true, false)) { refreshCapture(); }
                        budgets.requestKeyFrame();
                    }
                }
                @Override
                public void onPeerReset() { if (peer.active) { budgets.resetBridge(bridge); } }
            });
            bridge.setClosedCallback(() -> { if (peer.active) { signaling.disconnect(roomId, clientId); } });
            bindSignals(peer);
            bridge.setDataCallback(data -> {
                if (peer.active && config.control) { controls.handleControlData(key.toString(), data); }
            });
            bridge.open(config.audio, 0, config.turnUrl, config.turnUser, config.turnPassword,
                    config.minimumMediaPort, config.maximumMediaPort, config.videoBitRate);
            budgets.addBridge(bridge);
            controls.registerForwarder(key.toString(), (data, sender) -> { });
            controls.registerSender(key.toString(), message -> {
                if (peer.active) { bridge.sendData(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            });
            video.addBridge(bridge);
            if (config.audio) { audio.addBridge(bridge); }
            budgets.requestKeyFrame();
        } catch (RuntimeException | LinkageError error) {
            onClientLeft(roomId, clientId);
            throw error;
        }
    }

    private void refreshCapture() {
        SurfaceEncoder target = encoder;
        if (target != null) { target.onPeerAttached(); }
    }

    /**
     * A flooding viewer overflows the shared control backlog. It must be disconnected without
     * touching the capture session: the control authority is released first so the queued inputs
     * of the next controller cannot interleave with the flooder's backlog.
     */
    private void onControlBacklogOverflow(String sender) {
        if (sender == null) {
            Ln.w("Control backlog overflow without a known sender, dropped the whole backlog");
            return;
        }
        for (Peer peer : peers.values()) {
            if (sender.equals(peer.key.toString())) {
                Ln.w("Control backlog overflow, disconnecting the flooding viewer " + sender);
                controls.releaseControl(sender);
                signaling.disconnect(peer.key.getRoom(), peer.key.getClient());
                return;
            }
        }
        Ln.w("Control backlog overflow from an unknown viewer " + sender);
    }

    private void bindSignals(Peer peer) {
        long generation = peer.generation;
        peer.bridge.setSignalCallback(new NativeEncoderBridge.SignalCallback() {
            @Override
            public void onAnswer(String sdp) {
                if (peer.active) { signaling.sendAnswer(peer.key.getRoom(), peer.key.getClient(), generation, sdp); }
            }
            @Override
            public void onIceCandidate(String mid, int index, String sdp) {
                if (peer.active) {
                    signaling.sendIceCandidate(peer.key.getRoom(), peer.key.getClient(), generation, mid, index, sdp);
                }
            }
        });
    }

    @Override
    public void onClientLeft(String roomId, String clientId) {
        Peer peer = peers.remove(new PeerKey(roomId, clientId));
        if (peer != null) { peer.close(); }
    }

    @Override
    public boolean onPeerReplaced(String roomId, String clientId) {
        Peer peer = peers.get(new PeerKey(roomId, clientId));
        if (peer == null) { return false; }
        controls.releaseControl(peer.key.toString());
        video.restartBridge(peer.bridge);
        peer.generation++;
        peer.bootstrapRefresh.set(true);
        peer.positiveRefresh.set(true);
        bindSignals(peer);
        return peer.bridge.resetPeer();
    }

    @Override
    public void onClientSuspended(String roomId, String clientId) {
        controls.releaseControl(new PeerKey(roomId, clientId).toString());
    }

    @Override
    public void onClientResumed(String roomId, String clientId) { budgets.requestKeyFrame(); }
    @Override
    public void onRoomEmpty(String roomId) { /* Capture lifetime belongs to the outer session, not a spectator. */ }
    @Override
    public void onServerStartFailed(Exception error) { fatalError.run(); }
    @Override
    public void onOffer(String roomId, String clientId, String sdp) {
        Peer peer = peers.get(new PeerKey(roomId, clientId));
        if (peer != null && peer.active) { peer.bridge.onOffer(sdp); }
    }
    @Override
    public void onIceCandidate(String roomId, String clientId, String mid, int index, String sdp) {
        Peer peer = peers.get(new PeerKey(roomId, clientId));
        if (peer != null && peer.active) { peer.bridge.onIceCandidate(mid, index, sdp); }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        signaling.close();
        for (Peer peer : new ArrayList<>(peers.values())) {
            if (peers.remove(peer.key, peer)) { peer.close(); }
        }
        input.close();
        encoder = null;
    }

    private final class Peer implements AutoCloseable {
        final PeerKey key;
        final NativeEncoderBridge bridge = new NativeEncoderBridge();
        volatile boolean active = true;
        long generation;
        final AtomicBoolean bootstrapRefresh = new AtomicBoolean(true);
        final AtomicBoolean positiveRefresh = new AtomicBoolean(true);
        Peer(PeerKey key) { this.key = key; }
        @Override
        public void close() {
            active = false; // Late native callbacks cannot reinsert budgets or route to a replacement.
            video.removeBridge(bridge);
            audio.removeBridge(bridge);
            budgets.removeBridge(bridge);
            controls.unregisterForwarder(key.toString());
            bridge.close();
        }
    }
}
