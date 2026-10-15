#!/usr/bin/env python3
"""Verify packaged JNI inputs and rebuild stale artifacts before Gradle packaging."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REQUIRED_BUILD_INPUTS = ('WEBRTC_ROOT', 'CLANG', 'ANDROID_SYSROOT', 'WEBRTC_REVISION')


def artifact_files(server):
    cpp = server / 'src/main/cpp'
    return sorted([*cpp.glob('*.cc'), *cpp.glob('*.h'),
                   server / 'src/main/java/com/genymobile/scrcpy/device/NativeEncoderBridge.java',
                   server / 'tools/build_native.py',
                   server / 'src/main/jniLibs/arm64-v8a/libscrcpy_native.so'])


def stale_reasons(server):
    root = server.parent
    manifest_path = server / 'native-build.json'
    if not manifest_path.is_file():
        return ['server/native-build.json is missing']
    try:
        manifest = json.loads(manifest_path.read_text())
        recorded = manifest['files']
        if (not isinstance(recorded, dict)
                or not isinstance(manifest.get('webrtc_revision'), str) or not manifest['webrtc_revision']
                or not isinstance(manifest.get('compiler'), str) or not manifest['compiler']):
            return ['server/native-build.json has invalid build metadata']
    except (OSError, ValueError, KeyError, TypeError):
        return ['server/native-build.json is invalid; rebuild JNI rather than editing the manifest']
    files = artifact_files(server)
    reasons = []
    expected_names = {str(path.relative_to(root)) for path in files}
    if set(recorded) - expected_names:
        reasons.append('native source file set changed (removed or renamed files)')
    for path in files:
        relative = str(path.relative_to(root))
        digest = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        if digest is None or recorded.get(relative) != digest:
            reasons.append(relative)
    revision = os.environ.get('WEBRTC_REVISION')
    if revision and revision != manifest['webrtc_revision']:
        reasons.append('WEBRTC_REVISION differs from the packaged library')
    return reasons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify only; never invoke the compiler')
    args = parser.parse_args()
    server = Path(__file__).resolve().parents[1]
    reasons = stale_reasons(server)
    if not reasons:
        print('JNI library matches current sources; rebuild not needed.')
        return 0
    print('JNI library needs rebuilding:\n  ' + '\n  '.join(reasons), file=sys.stderr)
    if args.check:
        return 1
    missing = [name for name in REQUIRED_BUILD_INPUTS if not os.environ.get(name)]
    if missing:
        print('Cannot rebuild JNI automatically. Missing environment variables: ' + ', '.join(missing)
              + '\nConfigure the matching libwebrtc toolchain described in docs/native-build.md, then rerun Gradle.'
              + '\nOr run: python3 server/tools/build_native.py'
              + '\nWEBRTC_REVISION must identify the library source, not this application commit.'
              + '\nThe old library and manifest have not been modified.', file=sys.stderr)
        return 1
    try:
        subprocess.run([sys.executable, str(server / 'tools/build_native.py')], cwd=server.parent, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        print('JNI rebuild failed; APK packaging must stop. ' + str(error), file=sys.stderr)
        return 1
    remaining = stale_reasons(server)
    if remaining:
        print('JNI rebuild did not produce matching artifacts:\n  ' + '\n  '.join(remaining), file=sys.stderr)
        return 1
    print('JNI rebuilt and verified; APK packaging may continue.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
