#!/usr/bin/env python3
"""Run dependency-free native media logic with UBSan; this does not link libwebrtc/JNI."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='native-media-', dir=root / 'tests') as temporary:
    for name in ('media_test', 'encoded_frame_gate_test'):
        binary = str(Path(temporary) / name)
        subprocess.run([os.environ.get('CXX', 'g++'), '-std=c++20', '-Wall', '-Wextra', '-Werror',
                        '-fsanitize=undefined', '-fno-sanitize-recover=all',
                        '-I' + str(root / 'server/src/main/cpp'), str(root / 'tests' / (name + '.cc')),
                        '-o', binary], check=True, cwd=root)
        subprocess.run([binary], check=True, cwd=root)
print('Native media logic passed (host only; full JNI build/device tests still required)')
