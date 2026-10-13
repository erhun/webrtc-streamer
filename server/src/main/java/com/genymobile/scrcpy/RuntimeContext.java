package com.genymobile.scrcpy;

import android.content.Context;
import android.os.Process;

/** Keeps application identity separate from the legacy app_process entry point. */
public final class RuntimeContext {
    private static volatile Context applicationContext;

    private RuntimeContext() {
        // Not instantiable.
    }

    public static void initialize(Context context) {
        if (context == null || context instanceof FakeContext) {
            throw new IllegalArgumentException("A real application Context is required");
        }
        Context app = context.getApplicationContext();
        if (app == null || app instanceof FakeContext || app.getApplicationInfo().uid != Process.myUid()) {
            throw new IllegalArgumentException("Application Context must belong to the current process UID");
        }
        applicationContext = app;
    }

    public static Context getApplicationContext() {
        return applicationContext;
    }

    public static boolean isShellRuntime() {
        int uid = Process.myUid();
        return applicationContext == null && (uid == 0 || uid == Process.SHELL_UID);
    }

    public static Context get() {
        Context context = applicationContext;
        if (context != null) {
            return context;
        }
        if (!isShellRuntime()) {
            throw new IllegalStateException("Application Context is missing: start the system application through ServerService");
        }
        return FakeContext.get();
    }

    public static int getUserId() {
        // Android UID = userId * 100000 + appId. This is a user ID, not a Linux UID.
        return Process.myUid() / 100000;
    }
}
