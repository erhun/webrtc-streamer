package com.genymobile.scrcpy.audio;

/** The Android 11 foreground workaround belongs only to the adb shell entry point. */
public final class ShellAudioWorkaround {
    public static final String PACKAGE_NAME = "com.android.shell";

    private ShellAudioWorkaround() {
    }

    public static boolean isRequired(int sdk, int uid, boolean applicationContextAvailable) {
        return sdk == 30 && uid == 2000 && !applicationContextAvailable;
    }
}
