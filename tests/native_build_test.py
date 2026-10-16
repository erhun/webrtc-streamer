#!/usr/bin/env python3
"""Exercise build orchestration with a fake compiler, not native ABI compilation."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import warnings
import zipfile

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
    # Move artifacts between independent checkouts, including paths with spaces.
    consumer = work / 'APK service' / 'server'
    for relative in ('tools', 'src/main/cpp', 'src/main/java'):
        shutil.copytree(server / relative, consumer / relative)
    library = consumer / 'src/main/jniLibs/arm64-v8a/libscrcpy_native.so'
    manifest_path = consumer / 'native-build.json'
    bundle = work / 'transfer' / 'scrcpy native.zip'
    no_toolchain = {key: value for key, value in env.items()
                    if key not in ('WEBRTC_ROOT', 'CLANG', 'CLANG_LD', 'ANDROID_SYSROOT', 'WEBRTC_REVISION')}

    def invoke(script, *arguments, environment=no_toolchain, success=True):
        result = subprocess.run(['python3', str(script), *map(str, arguments)], env=environment,
                                capture_output=True, text=True, check=False)
        assert (result.returncode == 0) == success, result.stdout + result.stderr
        return result

    def artifacts():
        return library.read_bytes(), manifest_path.read_bytes()

    exporter = server / 'tools/native_bundle.py'
    importer = consumer / 'tools/native_bundle.py'
    prepare = consumer / 'tools/prepare_native.py'
    builder = server / 'tools/build_native.py'
    calls_before = log.read_text()
    invoke(exporter, 'export', '--output', bundle)
    invoke(importer, 'import', bundle)
    before = artifacts()
    invoke(prepare)
    # Ambient toolchain settings/revisions do not affect consumer-side verification.
    invoke(prepare, '--check', environment=dict(env, WEBRTC_REVISION='unrelated-host-setting'))
    assert log.read_text() == calls_before
    header = next((consumer / 'src/main/cpp').glob('*.h'))
    original_header = header.read_bytes()
    header.write_bytes(original_header + b'// stale consumer header test\n')
    failed = invoke(prepare, environment=env, success=False)
    assert 'native build server' in failed.stderr
    invoke(importer, 'import', bundle, environment=env, success=False)
    assert artifacts() == before and log.read_text() == calls_before
    header.write_bytes(original_header)

    removed = next((consumer / 'src/main/cpp').glob('*.cc'))
    removed_contents = removed.read_bytes()
    removed.unlink()
    invoke(importer, 'import', bundle, success=False)
    assert artifacts() == before
    removed.write_bytes(removed_contents)
    recipe = consumer / 'tools/build_native.py'
    recipe.write_text(recipe.read_text() + '# changed recipe\n')
    invoke(importer, 'import', bundle, success=False)
    shutil.copyfile(builder, recipe)

    # Reject corrupt, mixed, malformed and unsafe bundles before changing installed artifacts.
    with zipfile.ZipFile(bundle) as archive:
        pair = {name: archive.read(name) for name in archive.namelist()}
    corrupt_manifest = json.loads(pair['native-build.json'])
    corrupt_manifest['files']['server/src/main/jniLibs/arm64-v8a/libscrcpy_native.so'] = '0' * 64
    cases = [
        {**pair, 'libscrcpy_native.so': b'wrong library'},
        {**pair, 'native-build.json': json.dumps(corrupt_manifest).encode()},
        {**pair, 'native-build.json': b'null'},
        {**pair, 'native-build.json': b'[]'},
        {**pair, 'native-build.json': b'{invalid'},
        {**pair, 'native-build.json': b' ' * (1024 * 1024 + 1)},
        {**pair, '../outside': b'traversal'},
        {'libscrcpy_native.so': pair['libscrcpy_native.so']},
    ]
    for index, entries in enumerate(cases):
        bad = work / ('bad-%d.zip' % index)
        with zipfile.ZipFile(bad, 'w') as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        invoke(importer, 'import', bad, success=False)
        assert artifacts() == before
    assert not (work / 'outside').exists()
    # Duplicate member names cannot bypass the exact pair requirement.
    duplicate = work / 'duplicate.zip'
    with zipfile.ZipFile(duplicate, 'w') as archive:
        for name, content in pair.items():
            archive.writestr(name, content)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr('libscrcpy_native.so', b'wrong library')
    invoke(importer, 'import', duplicate, success=False)
    assert artifacts() == before

    # Missing/corrupt installed inputs fail closed even when a compiler is configured.
    library.unlink()
    invoke(prepare, environment=env, success=False)
    invoke(importer, 'import', bundle)
    manifest_path.unlink()
    invoke(prepare, environment=env, success=False)
    invoke(importer, 'import', bundle)
    manifest_path.write_text('{invalid')
    invoke(prepare, environment=env, success=False)
    invoke(importer, 'import', bundle)
    assert log.read_text() == calls_before, 'Consumer/import/export must never invoke the compiler'

    # Export must not certify a stale build-server artifact either.
    build_header = next((server / 'src/main/cpp').glob('*.h'))
    build_header.write_text(build_header.read_text() + '// force failed compile\n')
    original_bundle = bundle.read_bytes()
    invoke(exporter, 'export', '--output', bundle, success=False)
    assert bundle.read_bytes() == original_bundle
    build_library = server / 'src/main/jniLibs/arm64-v8a/libscrcpy_native.so'
    build_manifest = server / 'native-build.json'
    build_before = build_library.read_bytes(), build_manifest.read_bytes()
    invoke(builder, environment=dict(env, FAIL_NATIVE_TEST_BUILD='1'), success=False)
    assert (build_library.read_bytes(), build_manifest.read_bytes()) == build_before
    raced = invoke(builder, environment=dict(env, MUTATE_NATIVE_TEST_SOURCE=str(build_header)), success=False)
    assert 'Native sources changed during compilation' in raced.stderr
    assert (build_library.read_bytes(), build_manifest.read_bytes()) == build_before
    invoke(builder, environment=env)
    invoke(exporter, 'export', '--output', bundle)
    # Consumers still need the matching checkout, even if the newer bundle itself is valid.
    invoke(importer, 'import', bundle, success=False)
print('Native delivery passed: separate checkouts, no consumer compilation, hashes, missing/corrupt artifacts, unsafe bundles, build failure and concurrent edits')
print('Fake compiler only; no native ABI or Gradle runtime validation')
