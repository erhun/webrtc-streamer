#!/usr/bin/env python3
"""Run the real video adaptation classes without Android or native dependencies."""
from pathlib import Path
import os
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    java = os.environ.get("JAVA", "java")
    source = root / "server/src/main/java/com/genymobile/scrcpy/video"
    with tempfile.TemporaryDirectory(prefix="video-adaptation-", dir=root / "tests") as temp:
        # Identity-only peer stub: budget aggregation is independent of JNI and Android.
        stub = Path(temp) / "NativeEncoderBridge.java"
        stub.write_text("package com.genymobile.scrcpy.device; public final class NativeEncoderBridge {}\n")
        subprocess.run([
            java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-Xlint:all", "-Werror", "-d", temp,
            str(source / "BitrateLadder.java"),
            str(source / "VideoAdaptationPolicy.java"),
            str(source / "FrameRateLimiter.java"), str(source / "BitrateAggregator.java"),
            str(root / "server/src/main/java/com/genymobile/scrcpy/audio/ShellAudioWorkaround.java"), str(stub),
            str(root / "tests/FramePipelineTest.java"),
            str(root / "tests/VideoAdaptationTest.java"), str(root / "tests/BudgetRecoveryTest.java"),
            str(root / "tests/ParseJava.java"),
        ], check=True, cwd=root)
        subprocess.run([java, "-cp", temp, "VideoAdaptationTest"], check=True, cwd=root)
        subprocess.run([java, "-cp", temp, "FramePipelineTest"], check=True, cwd=root)
        subprocess.run([java, "-cp", temp, "BudgetRecoveryTest",
                        "tests/fixtures/resolution-budget-window.txt"], check=True, cwd=root)
        subprocess.run([java, "-cp", temp, "ParseJava", "server/src/main/java"], check=True, cwd=root)


if __name__ == "__main__":
    main()
