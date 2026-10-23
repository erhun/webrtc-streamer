import com.genymobile.scrcpy.Server;
import com.genymobile.scrcpy.ServerService;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;

public final class ForegroundServiceTest {
    private ForegroundServiceTest() {
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        Build.VERSION.SDK_INT = Integer.parseInt(args[0]);
        ServerService service = new ServerService();
        service.onCreate();
        Intent intent = new Intent();
        intent.args = new String[]{"4.1", "audio=true", "audio_source=playback"};
        check(service.onStartCommand(intent, 0, 1) == Service.START_REDELIVER_INTENT,
                "A resident service is restarted by the system after a crash, args redelivered");
        check(service.foreground, "Capture must run in foreground");
        check(Server.runCalls == 1 && Server.receivedArgs == intent.args, "Forward the original session options");
        check(service.notification != null && service.notification.icon != 0 && service.notification.ongoing,
                "Foreground service requires an ongoing notification with a valid icon");
        check(service.foregroundType == (Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0),
                "Use explicit mediaProjection type on Android 10+");
        check(NotificationManager.createdChannels == (Build.VERSION.SDK_INT >= 26 ? 1 : 0), "Android 8+ channel compatibility");
        service.onStartCommand(intent, 0, 2);
        check(Server.runCalls == 1, "Repeated service start must not create a duplicate capture");

        // Resident semantics: an unexpected session end is rebuilt in place, bounded.
        Server.finished.run();
        Handler.drain();
        check(Server.runCalls == 2 && service.foreground && service.stoppedId == 0,
                "An unexpected session end is rebuilt without stopping the foreground service");
        check(Server.receivedArgs == intent.args, "The rebuild forwards the original session options");
        Server.finished.run();
        Handler.drain();
        Server.finished.run();
        Handler.drain();
        check(Server.runCalls == 4, "Each further unexpected end is rebuilt up to the rapid-failure limit");
        Server.finished.run();
        Handler.drain();
        check(Server.runCalls == 4 && !service.foreground && service.stoppedId == 2,
                "Persistent rapid failures give up and stop the service");

        ServerService deliberate = new ServerService();
        deliberate.onCreate();
        int before = Server.runCalls;
        deliberate.onStartCommand(intent, 0, 3);
        Server.lastSession.stop();
        Server.finished.run();
        Handler.drain();
        check(Server.runCalls == before + 1 && !deliberate.foreground && deliberate.stoppedId == 3,
                "A deliberate session stop is not rebuilt and removes the notification");

        ServerService destroyed = new ServerService();
        destroyed.onCreate();
        destroyed.onStartCommand(intent, 0, 4);
        Server.Session session = Server.lastSession;
        destroyed.onDestroy();
        check(session.stopped && session.wasStopRequested() && !destroyed.foreground,
                "Service destruction stops capture and removes notification");

        ServerService denied = new ServerService();
        denied.onCreate();
        denied.rejectForeground = true;
        int calls = Server.runCalls;
        denied.onStartCommand(intent, 0, 5);
        check(Server.runCalls == calls && denied.stoppedId == 5 && !denied.foreground,
                "Foreground permission failure must prevent capture startup");

        ServerService unavailable = new ServerService();
        unavailable.onCreate();
        Server.unavailable = true;
        unavailable.onStartCommand(intent, 0, 6);
        check(!unavailable.foreground && unavailable.stoppedId == 6, "Rejected session must not leave a foreground notification");
        System.out.println("PASS foreground lifecycle API " + Build.VERSION.SDK_INT);
    }
}
