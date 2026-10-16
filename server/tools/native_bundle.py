#!/usr/bin/env python3
"""Transfer matching JNI artifacts between the native build server and APK service."""
import argparse
import os
from pathlib import Path
import sys
import tempfile
import zipfile

from prepare_native import LIBRARY, artifact_reasons, stale_reasons

MEMBERS = {'libscrcpy_native.so': 512 * 1024 * 1024, 'native-build.json': 1024 * 1024}


def validate(server, manifest, library):
    reasons = artifact_reasons(server, manifest, library)
    if reasons:
        raise ValueError('Native bundle does not match this checkout:\n  ' + '\n  '.join(reasons))


def export_bundle(server, output):
    # Capture the pair once; exporting cannot certify an arbitrary old library.
    manifest = (server / 'native-build.json').read_bytes()
    library = (server / LIBRARY).read_bytes()
    validate(server, manifest, library)
    if output.resolve() in {(server / 'native-build.json').resolve(), (server / LIBRARY).resolve()}:
        raise ValueError('Bundle output must not overwrite a native artifact')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-export-', dir=output.parent) as temporary:
        staged = Path(temporary) / 'bundle.zip'
        with zipfile.ZipFile(staged, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('libscrcpy_native.so', library)
            archive.writestr('native-build.json', manifest)
        staged.replace(output)
    print('Exported verified arm64 JNI bundle: ' + str(output))


def import_bundle(server, bundle):
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.infolist()
        # No extraction to member paths: reject duplicates, extras and traversal names.
        if len(entries) != len(MEMBERS) or {entry.filename for entry in entries} != set(MEMBERS):
            raise ValueError('Bundle must contain exactly libscrcpy_native.so and native-build.json')
        for entry in entries:
            if entry.file_size > MEMBERS[entry.filename]:
                raise ValueError('Oversized bundle member: ' + entry.filename)
        library = archive.read('libscrcpy_native.so')
        manifest = archive.read('native-build.json')
    validate(server, manifest, library)
    output = server / LIBRARY
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-import-', dir=server) as temporary:
        staged = Path(temporary)
        (staged / output.name).write_bytes(library)
        (staged / 'native-build.json').write_bytes(manifest)
        os.chmod(staged / output.name, 0o644)
        # The pair is not a filesystem transaction. Gradle independently verifies it
        # and refuses any intermediate/mismatched pair after interruption or edits.
        (staged / output.name).replace(output)
        (staged / 'native-build.json').replace(server / 'native-build.json')
    reasons = stale_reasons(server)
    if reasons:
        raise ValueError('Native inputs changed during import; retry with a matching bundle:\n  ' + '\n  '.join(reasons))
    print('Imported verified arm64 JNI artifacts; APK packaging may continue.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    exporter = commands.add_parser('export', help='On the native build server, export a verified pair')
    exporter.add_argument('--output', type=Path, required=True)
    importer = commands.add_parser('import', help='On the APK service, verify and import a bundle without compiling')
    importer.add_argument('bundle', type=Path)
    args = parser.parse_args()
    server = Path(__file__).resolve().parents[1]
    try:
        if args.command == 'export':
            export_bundle(server, args.output)
        else:
            import_bundle(server, args.bundle)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
