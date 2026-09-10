#!/usr/bin/env python3
"""Rebuild JNI using the same Chromium clang/libc++ ABI as libwebrtc; no downloads."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

server = Path(__file__).resolve().parents[1]
root = server.parent
cpp = server / 'src/main/cpp'
required = ['WEBRTC_ROOT', 'CLANG', 'ANDROID_SYSROOT', 'WEBRTC_REVISION']
missing = [key for key in required if not os.environ.get(key)]
if missing:
    raise SystemExit('Missing build inputs: ' + ', '.join(missing))
materials = Path(os.environ['WEBRTC_ROOT']).resolve()
clang = os.environ['CLANG']
sysroot = Path(os.environ['ANDROID_SYSROOT']).resolve()
static = materials / 'static_libs/obj'
if not (static / 'libwebrtc.a').is_file():
    raise SystemExit('Missing WEBRTC_ROOT/static_libs/obj/libwebrtc.a')
compiler = subprocess.check_output([clang, '--version'], text=True)
common = [clang, '--target=aarch64-linux-android23', '--sysroot=' + str(sysroot),
          '-std=c++20', '-fno-rtti', '-fno-exceptions', '-fPIC', '-nostdinc++', '-DNDEBUG',
          '-D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_NONE',
          '-DWEBRTC_POSIX', '-DWEBRTC_ANDROID', '-DWEBRTC_LINUX', '-DWEBRTC_ARCH_ARM64',
          '-fexperimental-relative-c++-abi-vtables', '-Wall', '-Wextra', '-Wno-unused-parameter',
          '-I' + str(materials / 'third_party/libc++/src/include'),
          '-I' + str(materials / 'buildtools/third_party/libc++'),
          '-I' + str(materials / 'include'), '-I' + str(materials / 'include/third_party/abseil-cpp'), '-I' + str(cpp)]
output = server / 'src/main/jniLibs/arm64-v8a/libscrcpy_native.so'
output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='native-', dir=server) as temporary:
    temporary = Path(temporary)
    objects = []
    for source in sorted(cpp.glob('*.cc')):
        obj = temporary / (source.stem + '.o')
        subprocess.run(common + ['-c', str(source), '-o', str(obj)], check=True)
        objects.append(str(obj))
    archives = [static / 'libwebrtc.a']
    for name in ('third_party', 'buildtools'):
        archives.extend(sorted((static / name).rglob('*.a')))
    built = temporary / output.name
    subprocess.run([clang, '--target=aarch64-linux-android23', '--sysroot=' + str(sysroot),
                    '-shared', '-fuse-ld=lld', '-nostdlib++', '-Wl,--no-undefined',
                    '-Wl,--start-group', *objects, *map(str, archives), '-Wl,--end-group',
                    '-o', str(built), '-llog', '-ldl', '-lm', '-lz'], check=True)
    built.replace(output)
files = sorted([*cpp.glob('*.cc'), *cpp.glob('*.h'), output,
                server / 'src/main/java/com/genymobile/scrcpy/device/NativeEncoderBridge.java'])
manifest = {'webrtc_revision': os.environ['WEBRTC_REVISION'], 'compiler': compiler,
            'files': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
(server / 'native-build.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('Built JNI library and source manifest; build APK next.')
