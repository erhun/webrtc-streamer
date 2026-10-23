package com.genymobile.scrcpy;

import com.genymobile.scrcpy.util.Ln;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class ServerService extends Service {

    private static final String CHANNEL_ID = "cloud-phone-streaming";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_RAPID_SESSION_REBUILDS = 3;
    private static final long STABLE_SESSION_MS = 60000;

    private Server.Session session;
    private int latestStartId;
    private String[] latestArgs = new String[0];
    private int rapidRebuilds;
    private long sessionStartedAtMs;
    private boolean userStopped;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ServerService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RuntimeContext.initialize(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (session != null) {
            // Resident service: a repeat start must not disturb the active capture session.
            return START_REDELIVER_INTENT;
        }
        String[] args = intent != null ? intent.getStringArrayExtra("args") : null;
        if (args == null) {
            args = new String[0];
        }
        latestArgs = args;
        try {
            // MediaProjection checks actual foreground state when its constructor starts the projection.
            startStreamingForeground();
            startSession(args);
        } catch (RuntimeException e) {
            Ln.e("Could not start streaming foreground service", e);
            stopStreamingForeground();
            stopSelfResult(startId);
        }
        return START_REDELIVER_INTENT;
    }

    private void startSession(String[] args) {
        sessionStartedAtMs = System.currentTimeMillis();
        userStopped = false;
        try {
            session = Server.run(this, args, () -> main.post(this::onSessionFinished));
        } catch (RuntimeException e) {
            Ln.e("Could not start streaming session", e);
            session = null;
        }
        if (session == null) {
            stopStreamingForeground();
            stopSelfResult(latestStartId);
        }
    }

    /**
     * A resident process distinguishes a deliberate stop from a failed session: only the latter is
     * rebuilt in place, and only a bounded number of rapid failures, so a wedge cannot spin.
     */
    private void onSessionFinished() {
        Server.Session ended = session;
        session = null;
        if (ended != null && !ended.wasStopRequested() && !userStopped) {
            if (System.currentTimeMillis() - sessionStartedAtMs >= STABLE_SESSION_MS) {
                rapidRebuilds = 0;
            }
            if (rapidRebuilds < MAX_RAPID_SESSION_REBUILDS) {
                rapidRebuilds++;
                Ln.w("Streaming session ended unexpectedly, rebuilding (" + rapidRebuilds + "/" + MAX_RAPID_SESSION_REBUILDS + ")");
                startSession(latestArgs);
                return;
            }
            Ln.e("Streaming session keeps failing, giving up; the platform must intervene");
        }
        stopStreamingForeground();
        stopSelfResult(latestStartId);
    }

    private void startStreamingForeground() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("Notification service is unavailable");
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "云手机串流", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("云手机串流")
                .setContentText("串流服务正在运行")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        Ln.i("Streaming foreground service started: type=mediaProjection");
    }

    @SuppressWarnings("deprecation")
    private void stopStreamingForeground() {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        userStopped = true;
        try {
            if (session != null) {
                session.stop();
            }
        } finally {
            stopStreamingForeground();
            super.onDestroy();
        }
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
