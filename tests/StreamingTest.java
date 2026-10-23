import com.genymobile.scrcpy.device.DataChannelInputStream;
import com.genymobile.scrcpy.video.CaptureRefreshGate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
public final class StreamingTest {
    private static void check(boolean value) { if (!value) { throw new AssertionError(); } }
    public static void main(String[] args) throws Exception {
        CaptureRefreshGate refresh = new CaptureRefreshGate();
        for (int reload = 0; reload < 11; ++reload) {
            if (reload > 0) { refresh.newPeer(); }
            check(!refresh.consumeRefresh());
            refresh.onBitrate(0); check(!refresh.consumeRefresh());
            check(!refresh.consumeBootstrapFrame(false)); // No undecodable delta bootstrap.
            check(refresh.consumeBootstrapFrame(true)); // Initial IDR may precede negotiation.
            check(!refresh.consumeBootstrapFrame(true)); // No unbounded zero-budget stream.
            check(!refresh.consumeBootstrapRefresh(false));
            check(refresh.consumeBootstrapRefresh(true)); // Transport-ready request refreshes static input.
            check(!refresh.consumeBootstrapRefresh(true));
            check(refresh.consumeBootstrapFrame(true)); // Input can now initialize native SetRates.
            check(!refresh.consumeBootstrapFrame(true));
            refresh.onBitrate(30000); check(refresh.consumeRefresh());
            check(!refresh.consumeRefresh());
            check(!refresh.consumeBootstrapFrame(true));
            refresh.onBitrate(0); // A later network pause must not reopen bootstrap.
            check(!refresh.consumeBootstrapFrame(true));
            check(!refresh.consumeBootstrapRefresh(true));
            refresh.onBitrate(50000); check(!refresh.consumeRefresh());
        }
        CaptureRefreshGate earlyRate = new CaptureRefreshGate();
        earlyRate.onBitrate(200000);
        check(!earlyRate.consumeBootstrapRefresh(true)); // Coalesce with send-ready refresh.
        check(earlyRate.consumeRefresh());
        // Tier stability and sparse rate feedback are covered by VideoAdaptationTest.
        DataChannelInputStream stream = new DataChannelInputStream();
        stream.onData(new byte[0]); stream.onData(new byte[]{1, 2}); stream.onData(new byte[]{3});
        check(stream.read() == 1 && stream.read() == 2 && stream.read() == 3);
        AtomicBoolean ended = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try { ended.set(stream.read() == -1); } catch (Exception e) { throw new RuntimeException(e); }
        });
        reader.start(); stream.close(); reader.join(1000); check(!reader.isAlive() && ended.get());
        DataChannelInputStream mixed = new DataChannelInputStream(sender -> { });
        mixed.onData(new byte[]{1}, "room/a"); mixed.onData(new byte[]{2}, "room/b"); mixed.onData(new byte[]{3}, "room/a");
        check(mixed.read() == 1 && mixed.read() == 2 && mixed.read() == 3); // Interleaved senders keep FIFO order.
        AtomicReference<String> flooder = new AtomicReference<>();
        DataChannelInputStream full = new DataChannelInputStream(flooder::set);
        full.onData(new byte[1048576], "room/attacker");
        full.onData(new byte[1048576], "room/attacker"); // The second megabyte overflows the backlog.
        check("room/attacker".equals(flooder.get())); // The flooding sender is reported, not the whole session.
        full.onData(new byte[]{7}, "room/spectator");
        check(full.read() == 7); // The flooding backlog is drained and the input stays alive.
        System.out.println("Java streaming regressions passed");
    }
}
