import com.genymobile.scrcpy.signal.SignalServer;

import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;

import java.net.BindException;

public final class SignalStartupTest {
    private SignalStartupTest() {
    }

    private static final class Listener implements SignalServer.Listener {
        private int failures;
        private Exception error;

        @Override
        public void onServerStartFailed(Exception value) { failures++; error = value; }
        @Override
        public void onClientJoined(String room, String client) { }
        @Override
        public void onClientLeft(String room, String client) { }
        @Override
        public void onOffer(String room, String client, String sdp) { }
        @Override
        public void onIceCandidate(String room, String client, String mid, int index, String sdp) { }
        @Override
        public void onRoomEmpty(String room) { }
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        Listener failed = new Listener();
        SignalServer server = new SignalServer(10000, "", 4, failed);
        WebSocketServer boundary = WebSocketServer.last;
        BindException error = new BindException("Address already in use");
        boundary.onError(new WebSocket(), error);
        check(failed.failures == 0, "Client errors do not stop the whole streaming session");
        boundary.onError(null, error);
        check(failed.failures == 1 && failed.error == error, "Global bind failure reaches the session owner");
        server.close();
        boundary.onError(null, error);
        check(failed.failures == 1, "Late callbacks after close are ignored");

        Listener live = new Listener();
        SignalServer running = new SignalServer(10001, "", 4, live);
        boundary = WebSocketServer.last;
        boundary.onStart();
        boundary.onError(null, new IllegalStateException("Runtime transport error"));
        check(live.failures == 0, "Errors after listening must not be misclassified as startup failures");
        running.close();
        System.out.println("PASS signal startup error lifecycle");
    }
}
