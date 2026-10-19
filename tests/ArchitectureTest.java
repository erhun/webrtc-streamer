import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.device.BroadcastVideoSink;
import com.genymobile.scrcpy.device.NativeEncoderBridge;
import com.genymobile.scrcpy.signal.PeerLease;
import com.genymobile.scrcpy.signal.PortMapConfig;
import com.genymobile.scrcpy.signal.SdpRewriter;
import com.genymobile.scrcpy.stream.PeerKey;
import com.genymobile.scrcpy.stream.StreamingConfig;
import com.genymobile.scrcpy.stream.StreamingSession;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.KeyFrameRequestGate;
import com.genymobile.scrcpy.video.VideoAdaptationPolicy;

import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class ArchitectureTest {
    private static final String TOKEN = "a".repeat(32);
    private static final String A = "1".repeat(32), B = "2".repeat(32), PAGE = "3".repeat(32);
    private ArchitectureTest() { }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 2000000000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) { throw new AssertionError("Executor did not complete"); }
            Thread.sleep(2);
        }
    }
    private static WebSocket open(WebSocketServer boundary) {
        WebSocket socket = new WebSocket(); boundary.onOpen(socket, null); return socket;
    }
    private static void send(WebSocketServer boundary, WebSocket socket, JSONObject message) {
        boundary.onMessage(socket, message.toString());
    }
    private static JSONObject join(String id) throws Exception {
        return new JSONObject().put("type", "join").put("token", TOKEN).put("room", "default")
                .put("clientId", id).put("peerId", PAGE);
    }
    private static JSONObject resume(String id, String secret, String page) throws Exception {
        return new JSONObject().put("type", "resume").put("token", secret).put("room", "default")
                .put("clientId", id).put("peerId", page);
    }
    private static void leaseAndIdentity() {
        Object old = new Object(), next = new Object();
        PeerLease<Object> lease = new PeerLease<>(old, PAGE, "secret");
        check(!lease.authorize("wrong", 0), "A client id is not recovery authority");
        check(lease.detach(old, 100), "Owner detach starts a deadline");
        check(!lease.expired(45099) && lease.expired(45100), "Recovery deadline is exact and bounded");
        check(!lease.authorize("secret", 45100), "Expired credentials cannot revive a peer");
        lease = new PeerLease<>(old, PAGE, "secret");
        check(!lease.attach(next, PAGE), "Same page keeps media");
        check(!lease.detach(old, 0) && lease.owns(next), "Stale close cannot revoke replacement");
        check(lease.attach(old, A), "New page requires media reset");
        check(!new PeerKey("one", A).equals(new PeerKey("two", A)), "Routing identity includes room");
    }
    private static void policyAndMapping() {
        KeyFrameRequestGate keyframes = new KeyFrameRequestGate();
        check(keyframes.allow(0) && !keyframes.allow(299) && keyframes.allow(300), "PLI bursts are coalesced across ticks");
        VideoAdaptationPolicy policy = new VideoAdaptationPolicy(2000000);
        policy.observe(100000, 0);
        check(policy.current().getMaxSize() == 640, "A single-tier drop is debounced");
        check(policy.tick(8000) && policy.current().getMaxSize() == 480, "Weak links reach a real low-resolution tier");
        policy.observe(9000000, 9000);
        check(policy.budget() <= 2000000, "The ramp probe never exceeds the operator bitrate ceiling");
        policy.observe(0, 10000); policy.reset();
        check(policy.budget() == -1, "Peer replacement invalidates old observations");
        VideoAdaptationPolicy capped = new VideoAdaptationPolicy(2000000);
        for (long time = 0; time <= 60000; time += 1000) {
            capped.observe(9000000, time);
            check(capped.budget() <= 2000000, "Encoding stays within the operator ceiling");
            check(capped.current().getBitRate() <= 2000000, "Never select a tier beyond the operator ceiling");
        }
        check(capped.budget() == 2000000, "Sustained headroom ramps the encoder up to the operator ceiling");
        check(capped.current().getMaxSize() == 960 && capped.current().getFps() == 30,
                "Network headroom must permit the highest tier supported by the operator ceiling");
        capped.observe(35000, 61000);
        capped.tick(66000);
        check(capped.budget() == 35000 && capped.current().getFps() == 10,
                "A 35 kbps allocation is preserved and reaches the weak-link tier, never forced upward");
        PortMapConfig mapping = new PortMapConfig("203.0.113.10", 10001, 10004, 31001, 31004);
        String candidate = "candidate:10001 1 udp 2100011001 10.0.0.1 10001 typ host generation 0";
        String rewritten = SdpRewriter.rewriteIceCandidate(candidate, mapping);
        check(rewritten.equals("candidate:10001 1 udp 2100011001 203.0.113.10 31001 typ host generation 0"),
                "Only candidate address and port are rewritten");
        Options options = new Options(); options.token = "";
        try { new StreamingConfig(options); throw new AssertionError("Empty production credential accepted"); }
        catch (IllegalArgumentException expected) { /* Explicit debug mode is required. */ }
        options.debug = true; new StreamingConfig(options);
        options.minPort = 10001;
        try { new StreamingConfig(options); throw new AssertionError("Incomplete media range accepted"); }
        catch (IllegalArgumentException expected) { /* Both local endpoints are required. */ }
    }
    private static void fanout() {
        BroadcastVideoSink sink = new BroadcastVideoSink();
        NativeEncoderBridge first = new NativeEncoderBridge(), second = new NativeEncoderBridge();
        sink.writeSessionMeta(360, 640, false);
        sink.writePacket(ByteBuffer.wrap(new byte[]{7}), 0, true, false);
        sink.addBridge(first);
        sink.writePacket(ByteBuffer.wrap(new byte[]{1}), 1, false, false);
        check(first.frames == 0, "New peer never starts on a dependent delta");
        sink.writePacket(ByteBuffer.wrap(new byte[]{2}), 2, false, true);
        check(first.frames == 1 && first.configs == 1, "Config precedes first IDR");
        sink.addBridge(second);
        sink.writePacket(ByteBuffer.wrap(new byte[]{3}), 3, false, false);
        check(first.frames == 2 && second.frames == 0, "Joining does not interrupt existing peer");
        sink.writePacket(ByteBuffer.wrap(new byte[]{4}), 4, false, true);
        check(second.frames == 1 && second.configs == 1, "New peer receives a decodable start");
        sink.restartBridge(second);
        sink.writePacket(ByteBuffer.wrap(new byte[]{5}), 5, false, false);
        check(second.frames == 1, "Reset peer waits for fresh IDR");
    }
    private static void session() throws Exception {
        NativeEncoderBridge.all.clear();
        AtomicInteger fatal = new AtomicInteger();
        Options settings = new Options(); settings.minPort = 10001; settings.maxPort = 10100;
        StreamingSession runtime = new StreamingSession(new StreamingConfig(settings), fatal::incrementAndGet);
        SurfaceEncoder encoder = new SurfaceEncoder(); runtime.setEncoder(encoder);
        WebSocketServer wire = WebSocketServer.last;
        runtime.start();
        ControlChannel control = runtime.controlChannel();
        try {
            check(NativeEncoderBridge.all.isEmpty(), "No native peer is allocated before admission");
            WebSocket a = open(wire); send(wire, a, join(A)); until(() -> !a.sent.isEmpty());
            JSONObject ready = new JSONObject(a.sent.get(0));
            check(ready.getInt("protocolVersion") == 3, "Versioned recovery protocol");
            String secret = ready.getString("resumeToken");
            NativeEncoderBridge mediaA = NativeEncoderBridge.all.get(0);
            check(mediaA.minPort == 10001 && mediaA.maxPort == 10100, "Deployment media range reaches transport adapter");
            WebSocket wrongRoom = open(wire);
            send(wire, wrongRoom, join(B).put("room", "another-phone")); until(() -> wrongRoom.code != 0);
            check("ROOM_NOT_AUTHORIZED".equals(wrongRoom.reason), "A token cannot select another room");
            WebSocket oversized = open(wire); wire.onMessage(oversized, "x".repeat(65537));
            check(oversized.code == 1008 && NativeEncoderBridge.all.size() == 1, "Oversized signaling is rejected before peer allocation");
            WebSocket b = open(wire); send(wire, b, join(B)); until(() -> !b.sent.isEmpty());
            NativeEncoderBridge mediaB = NativeEncoderBridge.all.get(1);
            WebSocket full = open(wire); send(wire, full, join("4".repeat(32))); until(() -> full.code != 0);
            check("ROOM_FULL".equals(full.reason) && NativeEncoderBridge.all.size() == 2, "Global peer capacity before native allocation");
            WebSocket collision = open(wire); send(wire, collision, join(A)); until(() -> collision.code != 0);
            check("CLIENT_ID_EXISTS".equals(collision.reason) && mediaA.closes == 0, "Join cannot hijack a live identity");
            mediaA.callback.onKeyFrameRequest(); mediaA.callback.onKeyFrameRequest();
            check(encoder.refreshes == 1, "Static-screen bootstrap refresh is bounded per peer");
            mediaA.callback.onBitrate(6000000, 60);
            mediaA.callback.onBitrate(6000000, 60);
            check(encoder.refreshes == 2, "First positive allocation refreshes once, not on every callback");
            mediaB.callback.onBitrate(200000, 15);
            mediaA.data.onData(new byte[]{0, 9});
            check(encoder.bps == 6000000, "Weak spectator does not cap the active controller");
            check(control.input.read() == 0 && control.input.read() == 9, "Controller input reaches device");
            int beforeA = mediaA.sends, beforeB = mediaB.sends;
            control.output.write(new byte[]{8});
            check(mediaA.sends == beforeA + 1 && mediaB.sends == beforeB, "Replies follow controller, not latest spectator");
            mediaB.data.onData("{\"type\":\"REQUEST_CONTROL\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            beforeB = mediaB.sends;
            final int sentBeforeSuspend = beforeB;
            wire.onClose(a, 1006, "network", true);
            until(() -> mediaB.sends > sentBeforeSuspend);
            check(control.input.read() == 23, "Old inputs released before ownership transfer");
            check(mediaA.closes == 0 && mediaB.closes == 0, "Signal loss retains media");
            mediaB.data.onData(new byte[]{0, 7});
            check(control.input.read() == 0 && control.input.read() == 7, "Queued controller receives authority");
            WebSocket bad = open(wire); send(wire, bad, resume(A, "bad", PAGE)); until(() -> bad.code != 0);
            check(mediaA.closes == 0, "Rejected resume cannot close legitimate peer");
            WebSocket resumed = open(wire); send(wire, resumed, resume(A, secret, PAGE)); until(() -> !resumed.sent.isEmpty());
            check(mediaA.resets == 0 && NativeEncoderBridge.all.size() == 2, "Same-page resume reuses media");
            NativeEncoderBridge.SignalCallback stale = mediaA.signal;
            WebSocket reload = open(wire); send(wire, reload, resume(A, secret, "5".repeat(32)));
            until(() -> !reload.sent.isEmpty());
            check(mediaA.resets == 1 && mediaA.closes == 0, "Reload resets one peer without recreating shared capture");
            stale.onAnswer("stale-answer");
            send(wire, reload, new JSONObject().put("type", "ping"));
            until(() -> reload.sent.stream().anyMatch(message -> message.contains("pong")));
            check(reload.sent.stream().noneMatch(message -> message.contains("stale-answer")), "Old-generation answer is discarded");
            mediaA.callback.onBitrate(6000000, 60); // New peer generation supplies its own valid budget.
            mediaB.closed.run(); until(() -> mediaB.closes == 1);
            check(mediaA.closes == 0 && fatal.get() == 0, "Spectator failure is isolated");
            mediaB.callback.onBitrate(1, 1);
            check(encoder.bps == 6000000, "Late callback cannot resurrect a removed peer budget");
        } finally { runtime.close(); runtime.close(); }
        check(NativeEncoderBridge.all.stream().allMatch(peer -> peer.closes == 1), "All native peers close exactly once");
    }
    public static void main(String[] args) throws Exception {
        leaseAndIdentity(); policyAndMapping(); fanout(); session();
        System.out.println("PASS architecture: lease, admission, weak-network policy, ICE mapping, fanout, recovery, ownership, fault isolation, shutdown");
    }
}
