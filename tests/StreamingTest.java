import com.genymobile.scrcpy.device.DataChannelInputStream;
import com.genymobile.scrcpy.signal.SessionAdmission;
import com.genymobile.scrcpy.video.BitrateLadder;
import java.util.concurrent.atomic.AtomicBoolean;
public final class StreamingTest {
    private static void check(boolean value) { if (!value) { throw new AssertionError(); } }
    public static void main(String[] args) throws Exception {
        String token = "a".repeat(32);
        Object a = new Object(), b = new Object();
        SessionAdmission admission = new SessionAdmission(token, 0);
        check(!admission.claim(a, "bad", 1)); check(admission.claim(a, token, 2));
        check(!admission.claim(b, token, 3)); check(!admission.release(b)); check(admission.owns(a));
        check(admission.release(a)); check(!admission.claim(b, token, 4));
        check(!new SessionAdmission(token, 0).claim(a, token, 300001));
        BitrateLadder startup = new BitrateLadder();
        check(startup.update(200000, 0));
        check(startup.current().getMaxSize() == 854 && startup.current().getFps() == 24);
        check(!startup.update(199167, 100));
        check(!startup.update(198333, 200));
        check(!startup.update(13000000, 1000));
        check(startup.update(13000000, 6000));
        check(startup.current().getMaxSize() == 1920);
        BitrateLadder ladder = new BitrateLadder();
        check(ladder.update(1500000, 0)); check(ladder.current().getMaxSize() == 960);
        check(!ladder.update(13000000, 1000)); check(!ladder.update(13000000, 5999));
        check(ladder.update(13000000, 6000)); check(ladder.current().getFps() == 60);
        check(ladder.update(500000, 7000)); check(ladder.current().getMaxSize() == 854);
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
