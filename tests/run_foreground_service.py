#!/usr/bin/env python3
"""Exercise real service and signal startup ordering with platform bindings."""
from pathlib import Path
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    root = Path(__file__).resolve().parents[1]
    java = os.environ.get("JAVA", "java")
    bindings = {
        "android/content/Context.java": """package android.content;
public class Context { public static final String NOTIFICATION_SERVICE = "notification"; }
""",
        "android/content/Intent.java": """package android.content;
public final class Intent { public String[] args; public String[] getStringArrayExtra(String key) { return args; } }
""",
        "android/content/pm/ServiceInfo.java": """package android.content.pm;
public final class ServiceInfo { public static final int FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION = 32; }
""",
        "android/os/Build.java": """package android.os;
public final class Build { public static final class VERSION { public static int SDK_INT; } }
""",
        "android/os/IBinder.java": """package android.os; public interface IBinder {}\n""",
        "android/os/Looper.java": """package android.os;
public final class Looper { public static Looper getMainLooper() { return new Looper(); } }
""",
        "android/os/Handler.java": """package android.os;
import java.util.ArrayList;
public final class Handler {
    private static final ArrayList<Runnable> queue = new ArrayList<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { queue.add(action); return true; }
    public static void drain() { while (!queue.isEmpty()) { queue.remove(0).run(); } }
}
""",
        "android/R.java": """package android;
public final class R { public static final class drawable { public static final int ic_media_play = 1; } }
""",
        "android/app/Notification.java": """package android.app;
import android.content.Context;
public final class Notification {
    public static final String CATEGORY_SERVICE = "service";
    public int icon; public boolean ongoing;
    public static final class Builder {
        private final Notification notification = new Notification();
        public Builder(Context context) {}
        public Builder(Context context, String channel) {}
        public Builder setSmallIcon(int icon) { notification.icon = icon; return this; }
        public Builder setContentTitle(String text) { return this; }
        public Builder setContentText(String text) { return this; }
        public Builder setCategory(String category) { return this; }
        public Builder setOngoing(boolean value) { notification.ongoing = value; return this; }
        public Notification build() { return notification; }
    }
}
""",
        "android/app/NotificationChannel.java": """package android.app;
public final class NotificationChannel {
    public NotificationChannel(String id, String name, int importance) {}
}
""",
        "android/app/NotificationManager.java": """package android.app;
public final class NotificationManager {
    public static final int IMPORTANCE_LOW = 2;
    public static int createdChannels;
    public void createNotificationChannel(NotificationChannel channel) { createdChannels++; }
}
""",
        "android/app/Service.java": """package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
public class Service extends Context {
    public static final int START_NOT_STICKY = 2, STOP_FOREGROUND_REMOVE = 1;
    public boolean foreground, rejectForeground; public int foregroundType, stoppedId;
    public Notification notification;
    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int id) { return 0; }
    public void onDestroy() {}
    public IBinder onBind(Intent intent) { return null; }
    public Object getSystemService(String name) { return new NotificationManager(); }
    public void startForeground(int id, Notification value) { startForeground(id, value, 0); }
    public void startForeground(int id, Notification value, int type) {
        if (rejectForeground) { throw new SecurityException("Foreground permission denied"); }
        foreground = true; notification = value; foregroundType = type;
    }
    public void stopForeground(int flags) { foreground = false; }
    public void stopForeground(boolean remove) { foreground = false; }
    public boolean stopSelfResult(int id) { stoppedId = id; return true; }
}
""",
        "com/genymobile/scrcpy/AndroidVersions.java": """package com.genymobile.scrcpy;
public final class AndroidVersions {
    public static final int API_24_ANDROID_7_0 = 24, API_26_ANDROID_8_0 = 26, API_29_ANDROID_10 = 29;
}
""",
        "com/genymobile/scrcpy/RuntimeContext.java": """package com.genymobile.scrcpy;
import android.content.Context;
public final class RuntimeContext { public static void initialize(Context context) {} }
""",
        "com/genymobile/scrcpy/util/Ln.java": """package com.genymobile.scrcpy.util;
public final class Ln { public static void i(String s) {} public static void w(String s) {} public static void e(String s, Throwable t) {} }
""",
        "com/genymobile/scrcpy/Server.java": """package com.genymobile.scrcpy;
import android.content.Context;
import android.app.Service;
public final class Server {
    public static int runCalls; public static boolean unavailable;
    public static String[] receivedArgs; public static Runnable finished; public static Session lastSession;
    public static final class Session { public boolean stopped; public void stop() { stopped = true; } }
    public static Session run(Context context, String[] args, Runnable completion) {
        if (!((Service) context).foreground) { throw new AssertionError("Capture started before foreground promotion"); }
        runCalls++; receivedArgs = args; finished = completion;
        if (unavailable) { return null; }
        lastSession = new Session(); return lastSession;
    }
}
""",
        "org/java_websocket/WebSocket.java": """package org.java_websocket;
public final class WebSocket {
    public void close(int code, String reason) {}
    public boolean isOpen() { return true; }
    public void send(String value) {}
}
""",
        "org/java_websocket/handshake/ClientHandshake.java": """package org.java_websocket.handshake;
public interface ClientHandshake {}
""",
        "org/java_websocket/server/WebSocketServer.java": """package org.java_websocket.server;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import java.net.InetSocketAddress;
public abstract class WebSocketServer {
    public static WebSocketServer last;
    public WebSocketServer(InetSocketAddress address) { last = this; }
    public abstract void onOpen(WebSocket socket, ClientHandshake handshake);
    public abstract void onClose(WebSocket socket, int code, String reason, boolean remote);
    public abstract void onMessage(WebSocket socket, String text);
    public abstract void onError(WebSocket socket, Exception error);
    public abstract void onStart();
    public void setMaxPendingConnections(int count) {}
    public void start() { onStart(); }
    public void stop(int timeout) throws InterruptedException {}
}
""",
        "org/json/JSONException.java": """package org.json;
public final class JSONException extends Exception { private static final long serialVersionUID = 1L; }
""",
        "org/json/JSONObject.java": """package org.json;
public final class JSONObject {
    public JSONObject() {}
    public JSONObject(String value) throws JSONException {}
    public JSONObject put(String key, Object value) throws JSONException { return this; }
    public String getString(String key) throws JSONException { return ""; }
    public int getInt(String key) throws JSONException { return 0; }
    public String optString(String key) { return ""; }
    public String optString(String key, String value) { return value; }
}
""",
        "com/genymobile/scrcpy/signal/SdpRewriter.java": """package com.genymobile.scrcpy.signal;
public final class SdpRewriter { public static String rewriteIceCandidate(String text, PortMapConfig config) { return text; } }
""",
    }
    manifest = ET.parse(root / "server/src/main/AndroidManifest.xml").getroot()
    name = "{http://schemas.android.com/apk/res/android}name"
    permissions = {node.attrib[name] for node in manifest.findall("uses-permission")}
    for permission in ("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PROJECTION"):
        assert "android.permission." + permission in permissions
    service = manifest.find("application/service")
    assert service is not None and service.attrib["{http://schemas.android.com/apk/res/android}foregroundServiceType"] == "mediaProjection"
    with tempfile.TemporaryDirectory(prefix="foreground-service-", dir=root / "tests") as temp:
        sources = []
        for relative, content in bindings.items():
            path = Path(temp) / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(str(path))
        subprocess.run([
            java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-Xlint:all", "-Werror", "-d", temp,
            *sources, str(root / "server/src/main/java/com/genymobile/scrcpy/ServerService.java"),
            str(root / "server/src/main/java/com/genymobile/scrcpy/signal/SignalServer.java"),
            str(root / "server/src/main/java/com/genymobile/scrcpy/signal/Room.java"),
            str(root / "server/src/main/java/com/genymobile/scrcpy/signal/PortMapConfig.java"),
            str(root / "tests/SignalStartupTest.java"),
            str(root / "tests/ForegroundServiceTest.java"),
        ], check=True, cwd=root)
        for sdk in (21, 26, 29, 30, 36):
            subprocess.run([java, "-cp", temp, "ForegroundServiceTest", str(sdk)], check=True, cwd=root)
        subprocess.run([java, "-cp", temp, "SignalStartupTest"], check=True, cwd=root)


if __name__ == "__main__":
    main()
