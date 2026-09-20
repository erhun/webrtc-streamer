import com.genymobile.scrcpy.device.DataChannelInputStream;
import com.genymobile.scrcpy.signal.SessionAdmission;
import com.genymobile.scrcpy.video.BitrateLadder;
import com.genymobile.scrcpy.video.CaptureRefreshGate;
import java.util.concurrent.atomic.AtomicBoolean;
public final class StreamingTest {
    private static void check(boolean value) { if (!value) { throw new AssertionError(); } }
    public static void main(String[] args) throws Exception {
        String token = "a".repeat(32);
        Object a = new Object(), b = new Object();
        SessionAdmission admission = new SessionAdmission(token, 0);
        check(!admission.claim(a, "bad", 1)); check(admission.claim(a, token, 2));
        check("LOGIN_TOKEN_USED".equals(admission.claimRejection(token, 3)));
        check("LOGIN_TOKEN_INVALID".equals(admission.claimRejection("bad", 3)));
        check(!admission.claim(b, token, 3)); check(!admission.release(b, 3)); check(admission.owns(a));
        check(admission.release(a, 4)); check(!admission.claim(b, token, 4));
        check(admission.updatePeerId("page-a"));
        check(!admission.updatePeerId("page-a")); // Same page / new signaling socket: retain peer.
        check(admission.updatePeerId("page-b")); // Reload: new DTLS/SCTP peer required.
        check(!admission.updatePeerId("page-b")); // Retried resume is idempotent.
        String resume = admission.getResumeToken();
        check(!resume.equals(token) && resume.length() == 64);
        check(!admission.resume(b, token, 5)); check(!admission.resume(b, "bad", 5));
        check(admission.resume(b, resume, 5)); check(admission.owns(b));
        check(!admission.release(a, 6)); // Delayed close from the replaced socket.
        check(admission.resume(a, resume, 7)); // Authenticated takeover of a half-open socket.
        check(!admission.release(b, 8)); check(admission.owns(a));
        check(admission.release(a, 10));
        check(!admission.recoveryExpired(45010)); check(admission.recoveryExpired(45011));
        check("RESUME_EXPIRED".equals(admission.resumeRejection(resume, 45011)));
        check("RESUME_TOKEN_INVALID".equals(admission.resumeRejection("bad", 45011)));
        check(!admission.resume(b, resume, 45011));
        SessionAdmission fresh = new SessionAdmission(token, 0);
        check(!fresh.resume(a, fresh.getResumeToken(), 1));
        check(!new SessionAdmission(token, 0).claim(a, token, 300001));
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
