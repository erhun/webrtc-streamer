import com.genymobile.scrcpy.device.DataChannelInputStream;
import com.genymobile.scrcpy.video.CaptureRefreshGate;
import java.util.concurrent.atomic.AtomicBoolean;
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
        AtomicBoolean overflow = new AtomicBoolean();
        DataChannelInputStream stream = new DataChannelInputStream(() -> overflow.set(true));
        stream.onData(new byte[0]); stream.onData(new byte[]{1, 2}); stream.onData(new byte[]{3});
        check(stream.read() == 1 && stream.read() == 2 && stream.read() == 3);
        AtomicBoolean ended = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try { ended.set(stream.read() == -1); } catch (Exception e) { throw new RuntimeException(e); }
        });
        reader.start(); stream.close(); reader.join(1000); check(!reader.isAlive() && ended.get());
        DataChannelInputStream full = new DataChannelInputStream(() -> overflow.set(true));
        full.onData(new byte[1048576]); full.onData(new byte[]{1}); check(overflow.get() && full.read() == -1);
        System.out.println("Java streaming regressions passed");
    }
}
