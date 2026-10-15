#!/usr/bin/env python3
"""Check JNI exports against javac-generated declarations; not a substitute for full libwebrtc compilation."""
from pathlib import Path
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='jni-abi-', dir=root / 'tests') as directory:
    work = Path(directory)
    logger = work / 'Ln.java'
    logger.write_text('package com.genymobile.scrcpy.util; public final class Ln {'
                      'public static void d(String s) {} public static void w(String s) {} }')
    subprocess.run([os.environ.get('JAVA', 'java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                    '-h', directory, '-d', directory, str(logger),
                    str(root / 'server/src/main/java/com/genymobile/scrcpy/device/NativeEncoderBridge.java')], check=True)
    header = (work / 'com_genymobile_scrcpy_device_NativeEncoderBridge.h').read_text()
    implementation = (root / 'server/src/main/cpp/jni_bridge.cc').read_text()
    pattern = r'JNIEXPORT\s+(\w+)\s+JNICALL\s+(Java_\w+)\s*\((.*?)\)'
    def declarations(text):
        result = {}
        for returns, name, args in re.findall(pattern, text, re.S):
            types = []
            for arg in args.split(','):
                match = re.match(r'\s*(\w+)\s*(\*)?', arg)
                assert match, arg
                types.append(match.group(1) + (match.group(2) or ''))
            result[name] = returns, types
        return result
    expected, actual = declarations(header), declarations(implementation)
    assert expected == actual, f'JNI declarations differ: expected {expected}, actual {actual}'
    print(f'PASS JNI signatures: {len(expected)} exports match javac headers (no native link validation)')
