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
with tempfile.TemporaryDirectory(prefix='streaming-tests-', suffix=' with spaces', dir=root / 'tests') as directory:
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
    if os.environ.get('FAIL_NATIVE_TEST_BUILD'):
        sys.exit(37)
    mutate = os.environ.get('MUTATE_NATIVE_TEST_SOURCE')
    if mutate:
        path = pathlib.Path(mutate)
        if not path.read_text().endswith('// concurrent test edit\\n'):
            path.write_text(path.read_text() + '// concurrent test edit\\n')
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
    assert '-fuse-ld=/test/custom-ld' in calls[-1]
    assert '-Wl,--no-undefined' in calls[-1]
    manifest = json.loads((server / 'native-build.json').read_text())
    assert manifest['webrtc_revision'] == 'test-revision'
    for name, digest in manifest['files'].items():
        assert hashlib.sha256((work / name).read_bytes()).hexdigest() == digest
    assert 'server/src/main/jniLibs/arm64-v8a/libscrcpy_native.so' in manifest['files']
    # Exercise the actual Gradle preparation entry without an Android SDK or native ABI toolchain.
    prepare = server / 'tools/prepare_native.py'
    library = server / 'src/main/jniLibs/arm64-v8a/libscrcpy_native.so'
    manifest_path = server / 'native-build.json'
    no_toolchain = {key: value for key, value in env.items()
                    if key not in ('WEBRTC_ROOT', 'CLANG', 'ANDROID_SYSROOT', 'WEBRTC_REVISION')}

    def invoke(environment, *arguments, success=True):
        result = subprocess.run(['python3', str(prepare), *arguments], env=environment,
                                capture_output=True, text=True, check=False)
        assert (result.returncode == 0) == success, result.stdout + result.stderr
        return result

    def artifacts():
        return library.read_bytes(), manifest_path.read_bytes()

    calls_before = log.read_text()
    invoke(no_toolchain)
    assert log.read_text() == calls_before, 'Matching artifacts must not invoke a compiler'
    header = next((server / 'src/main/cpp').glob('*.h'))
    header.write_text(header.read_text() + '// stale header test\n')
    before = artifacts()
    checked = invoke(env, '--check', success=False)
    assert str(header.relative_to(work)) in checked.stderr and artifacts() == before
    assert log.read_text() == calls_before, '--check must never rebuild'
    missing = invoke(no_toolchain, success=False)
    assert 'Missing environment variables: WEBRTC_ROOT, CLANG, ANDROID_SYSROOT, WEBRTC_REVISION' in missing.stderr
    assert artifacts() == before and log.read_text() == calls_before
    invoke(env)
    assert log.read_text() != calls_before
    invoke(no_toolchain, '--check')

    # Missing library and missing/malformed manifests each require a real rebuild.
    library.unlink()
    invoke(env)
    manifest_path.unlink()
    invoke(env)
    manifest_path.write_text('{invalid')
    invoke(env)
    invoke(no_toolchain, '--check')

    # A removed file or changed build recipe must invalidate the earlier source inventory.
    removed = next((server / 'src/main/cpp').glob('*.cc'))
    removed_name = str(removed.relative_to(work))
    removed.unlink()
    invoke(env)
    assert removed_name not in json.loads(manifest_path.read_text())['files']
    builder = server / 'tools/build_native.py'
    builder.write_text(builder.read_text() + '# changed build recipe test\n')
    invoke(env)
    revision_env = dict(env, WEBRTC_REVISION='test-revision-2')
    invoke(revision_env)
    assert json.loads(manifest_path.read_text())['webrtc_revision'] == 'test-revision-2'
    env = revision_env

    # Compile failure and edits during compilation cannot stamp a stale binary as current.
    header.write_text(header.read_text() + '// force failed compile\n')
    before = artifacts()
    failed = invoke(dict(env, FAIL_NATIVE_TEST_BUILD='1'), success=False)
    assert 'JNI rebuild failed' in failed.stderr and artifacts() == before
    raced = invoke(dict(env, MUTATE_NATIVE_TEST_SOURCE=str(header)), success=False)
    assert 'Native sources changed during compilation' in raced.stderr and artifacts() == before
    invoke(env)
    invoke(no_toolchain, '--check')

    # Compiler exit success alone is insufficient: verify the generated source/artifact manifest again.
    builder.write_text('print("no artifacts produced")\n')
    noop = invoke(env, success=False)
    assert 'did not produce matching artifacts' in noop.stderr
print('Native preparation passed: fresh, stale, missing inputs/artifacts, recipe, inventory, revision, failure and concurrent edit paths')
print('Fake compiler only; no native ABI or Gradle runtime validation')
