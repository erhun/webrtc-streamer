#!/usr/bin/env python3
"""Exercise build orchestration with a fake compiler, not native ABI compilation."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='streaming-tests-', dir=root / 'tests') as directory:
    work = Path(directory)
    server = work / 'server'
    for relative in ('tools', 'src/main/cpp'):
        shutil.copytree(root / 'server' / relative, server / relative)
    bridge = Path('src/main/java/com/genymobile/scrcpy/device/NativeEncoderBridge.java')
    (server / bridge).parent.mkdir(parents=True)
    shutil.copyfile(root / 'server' / bridge, server / bridge)
    static = work / 'materials/static_libs/obj'
    static.mkdir(parents=True)
    (static / 'libwebrtc.a').touch()
    compiler = work / 'fake-clang'
    compiler.write_text("""#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
if args == ['--version']:
    print('fake compiler: orchestration test only')
    sys.exit(0)
with open(os.environ['BUILD_CALL_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
if '-c' in args:
    assert pathlib.Path(args[args.index('-c') + 1]).is_file()
pathlib.Path(args[args.index('-o') + 1]).write_bytes(b'fake build output')
""")
    compiler.chmod(0o755)
    log = work / 'calls.jsonl'
    env = dict(os.environ, WEBRTC_ROOT=str(work / 'materials'), CLANG=str(compiler),
               CLANG_LD='/test/custom-ld', ANDROID_SYSROOT=str(work / 'sysroot'),
               WEBRTC_REVISION='test-revision', BUILD_CALL_LOG=str(log))
    subprocess.run(['python3', str(server / 'tools/build_native.py')], env=env, check=True)
    calls = [json.loads(line) for line in log.read_text().splitlines()]
    compiled = {Path(args[args.index('-c') + 1]).name for args in calls if '-c' in args}
    assert compiled == {path.name for path in (root / 'server/src/main/cpp').glob('*.cc')}
    assert 'scrcpy_opus_audio_encoder.cc' not in compiled
    assert '-fuse-ld=/test/custom-ld' in calls[-1]
    assert '-Wl,--no-undefined' in calls[-1]
    manifest = json.loads((server / 'native-build.json').read_text())
    assert manifest['webrtc_revision'] == 'test-revision'
    for name, digest in manifest['files'].items():
        assert hashlib.sha256((work / name).read_bytes()).hexdigest() == digest
    assert 'server/src/main/jniLibs/arm64-v8a/libscrcpy_native.so' in manifest['files']
print('Native build orchestration passed (fake compiler; no ABI validation)')
