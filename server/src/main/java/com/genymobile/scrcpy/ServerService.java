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

    private Server.Session session;
    private int latestStartId;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override
    public void onCreate() {
        super.onCreate();
        RuntimeContext.initialize(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (session != null) { return START_NOT_STICKY; }
        String[] args = intent != null ? intent.getStringArrayExtra("args") : null;
        if (args == null) { args = new String[0]; }
        try {
            // MediaProjection checks actual foreground state when its constructor starts the projection.
            startStreamingForeground();
            session = Server.run(this, args, () -> main.post(() -> {
                session = null;
                stopStreamingForeground();
                stopSelfResult(latestStartId);
            }));
            if (session == null) {
                stopStreamingForeground();
                stopSelfResult(startId);
            }
        } catch (RuntimeException e) {
            Ln.e("Could not start streaming foreground service", e);
            stopStreamingForeground();
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
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
        try {
            if (session != null) { session.stop(); }
        } finally {
            stopStreamingForeground();
            super.onDestroy();
        }
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
