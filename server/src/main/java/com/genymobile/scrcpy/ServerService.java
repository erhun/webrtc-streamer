package com.genymobile.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class ServerService extends Service {
    private Server.Session session;
    private int latestStartId;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (session != null) { return START_NOT_STICKY; }
        String[] args = intent != null ? intent.getStringArrayExtra("args") : null;
        if (args == null) { args = new String[0]; }
        session = Server.run(this, args, () -> main.post(() -> {
            session = null;
            stopSelfResult(latestStartId);
        }));
        if (session == null) { stopSelfResult(startId); }
        return START_NOT_STICKY;
    }
    @Override
    public void onDestroy() {
        if (session != null) { session.stop(); }
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
