#!/usr/bin/env python3
"""Compile the real context selector against minimal Android identity bindings."""
from pathlib import Path
import os
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    java = os.environ.get("JAVA", "java")
    bindings = {
        "android/os/Process.java": """package android.os;
public final class Process {
    public static final int SHELL_UID = 2000;
    public static int uid;
    public static int myUid() { return uid; }
}
""",
        "android/content/pm/ApplicationInfo.java": """package android.content.pm;
public final class ApplicationInfo { public int uid; }
""",
        "android/content/Context.java": """package android.content;
import android.content.pm.ApplicationInfo;
public class Context {
    public Context application = this;
    private final ApplicationInfo info = new ApplicationInfo();
    private final String name;
    public Context(int uid, String name) { info.uid = uid; this.name = name; }
    public Context getApplicationContext() { return application; }
    public ApplicationInfo getApplicationInfo() { return info; }
    public String getPackageName() { return name; }
}
""",
        "com/genymobile/scrcpy/FakeContext.java": """package com.genymobile.scrcpy;
import android.content.Context;
public final class FakeContext extends Context {
    public static int requests;
    public FakeContext() { super(2000, "com.android.shell"); }
    public static FakeContext get() { requests++; return new FakeContext(); }
}
""",
    }
    with tempfile.TemporaryDirectory(prefix="runtime-context-", dir=root / "tests") as temp:
        sources = []
        for relative, content in bindings.items():
            path = Path(temp) / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(str(path))
        subprocess.run([
            java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-Xlint:all", "-Werror", "-d", temp,
            *sources, str(root / "server/src/main/java/com/genymobile/scrcpy/RuntimeContext.java"),
            str(root / "tests/RuntimeContextTest.java"),
        ], check=True, cwd=root)
        for scenario in ("system-missing", "app-missing", "shell", "system-app", "invalid-context", "multiuser"):
            subprocess.run([java, "-cp", temp, "RuntimeContextTest", scenario], check=True, cwd=root)


if __name__ == "__main__":
    main()
