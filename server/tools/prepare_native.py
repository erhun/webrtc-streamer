#!/usr/bin/env python3
"""Verify prebuilt JNI artifacts; APK packaging never invokes a native compiler."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

LIBRARY = Path('src/main/jniLibs/arm64-v8a/libscrcpy_native.so')


def artifact_files(server):
    cpp = server / 'src/main/cpp'
    return sorted([*cpp.glob('*.cc'), *cpp.glob('*.h'),
                   server / 'src/main/java/com/genymobile/scrcpy/device/NativeEncoderBridge.java',
                   server / 'tools/build_native.py', server / LIBRARY])


def artifact_reasons(server, manifest_bytes, library_bytes):
    """Check captured artifact bytes against this checkout before import/export."""
    try:
        manifest = json.loads(manifest_bytes)
        if not isinstance(manifest, dict):
            return ['server/native-build.json must be an object']
        recorded = manifest['files']
        if (not isinstance(recorded, dict)
                or not isinstance(manifest.get('webrtc_revision'), str) or not manifest['webrtc_revision']
                or not isinstance(manifest.get('compiler'), str) or not manifest['compiler']):
            return ['server/native-build.json has invalid build metadata']
    except (ValueError, KeyError, TypeError, UnicodeError):
        return ['server/native-build.json is invalid; obtain the original manifest from the build server']
    files = artifact_files(server)
    reasons = []
    expected_names = {str(path.relative_to(server.parent)) for path in files}
    if set(recorded) != expected_names:
        reasons.append('native source/artifact file set differs from this checkout')
    for path in files:
        relative = str(path.relative_to(server.parent))
        data = library_bytes if path == server / LIBRARY else path.read_bytes() if path.is_file() else None
        digest = hashlib.sha256(data).hexdigest() if data is not None else None
        if digest is None or recorded.get(relative) != digest:
            reasons.append(relative)
    return reasons


def stale_reasons(server):
    manifest = server / 'native-build.json'
    if not manifest.is_file():
        return ['server/native-build.json is missing']
    library = server / LIBRARY
    return artifact_reasons(server, manifest.read_bytes(), library.read_bytes() if library.is_file() else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    # Retained for callers of the previous verifier; verification is now the only mode.
    parser.add_argument('--check', action='store_true', help='Verify only (also the default)')
    parser.parse_args()
    try:
        reasons = stale_reasons(Path(__file__).resolve().parents[1])
    except OSError as error:
        reasons = [str(error)]
    if not reasons:
        print('Prebuilt JNI library matches current sources; APK packaging may continue.')
        return 0
    print('Prebuilt JNI artifacts do not match this checkout:\n  ' + '\n  '.join(reasons)
          + '\nObtain a matching arm64 bundle (.so + native-build.json) from the native build server.'
          + '\nImport: python3 server/tools/native_bundle.py import /path/to/scrcpy-native-arm64.zip'
          + '\nThis service never compiles JNI or rewrites the manifest. See docs/native-build.md.', file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
