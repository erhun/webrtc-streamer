package com.genymobile.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class ServerService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String[] args = intent != null ? intent.getStringArrayExtra("args") : null;
        if (args == null) {
            args = new String[0];
        }

        Server.run(this, args);

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
