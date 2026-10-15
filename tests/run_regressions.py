#!/usr/bin/env python3
"""Dependency-light checks; Android/native integration validation remains required."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
root = Path(__file__).resolve().parents[1]
os.chdir(root)
java = os.environ.get('JAVA', 'java')
node = os.environ.get('NODE', 'node')
source = 'server/src/main/java/com/genymobile/scrcpy/'
def run(*args):
    subprocess.run(list(args), check=True)
run(sys.executable, 'tests/run_video_adaptation.py')
run(sys.executable, 'tests/run_architecture.py')
run(sys.executable, 'tests/native_abi_test.py')
run(sys.executable, 'tests/native_build_test.py')
run(sys.executable, 'tests/run_foreground_service.py')
run(sys.executable, 'tests/deploy_system_app_test.py')
with tempfile.TemporaryDirectory(prefix='streaming-tests-', dir=root / 'tests') as temp:
    logger = Path(temp) / 'Ln.java'
    logger.write_text('package com.genymobile.scrcpy.util; public final class Ln {'
                      'public static void d(String s) {} public static void w(String s) {} }\n')
    run(java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', temp,
        str(logger), source + 'device/NativeEncoderBridge.java', source + 'device/DataChannelInputStream.java',
        source + 'video/CaptureRefreshGate.java', source + 'video/BitrateLadder.java', 'tests/StreamingTest.java', 'tests/ParseJava.java')
    run(java, '-cp', temp, 'StreamingTest')
    run(java, '-cp', temp, 'ParseJava', 'server/src/main/java')
    binary = str(Path(temp) / 'media-test')
    run(os.environ.get('CXX', 'g++'), '-std=c++20', '-Wall', '-Wextra', '-Werror', '-fsanitize=undefined',
        '-fno-sanitize-recover=all', '-Iserver/src/main/cpp', 'tests/media_test.cc', '-o', binary)
    run(binary)
    tsc = 'p4/web/node_modules/typescript/bin/tsc'
    run(node, tsc, '-p', 'p4/web/tsconfig.json')
    run(node, tsc, 'p4/web/src/scrcpy-client.ts', '--target', 'ES2020', '--module', 'commonjs', '--outDir', temp, '--strict', '--skipLibCheck')
    run(node, 'tests/client_test.cjs', str(Path(temp) / 'scrcpy-client.js'))
    run(node, tsc, 'p4/web/src/first-frame-recovery.ts', '--target', 'ES2020', '--module', 'commonjs', '--outDir', temp, '--strict')
    run(node, 'tests/first_frame_recovery_test.cjs', str(Path(temp) / 'first-frame-recovery.js'))
    run(node, tsc, 'p4/web/src/latency-metrics.ts', '--target', 'ES2020', '--module', 'commonjs', '--outDir', temp, '--strict', '--skipLibCheck')
    run(node, 'tests/latency_metrics_test.cjs', str(Path(temp) / 'latency-metrics.js'))
    run(node, tsc, 'p4/web/src/stream-metrics.ts', '--target', 'ES2020', '--module', 'commonjs', '--outDir', temp, '--strict')
    run(node, 'tests/presentation_metrics_test.cjs', str(Path(temp) / 'stream-metrics.js'))
print('All dependency-light regressions passed')
