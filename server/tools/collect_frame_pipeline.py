#!/usr/bin/env python3
"""Read-only, same-window capture of app rendering, system display state and streaming logs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--app', default='mark.via', help='Package producing the source picture, not the H5 receiver')
    parser.add_argument('--seconds', type=int, default=10)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 60:
        parser.error('--seconds must be between 1 and 60')
    adb = shutil.which('adb')
    if not adb:
        parser.error('adb is not installed; run this on the computer with the cloud-phone tunnel')
    args.out.mkdir(parents=True, exist_ok=False)
    prefix = [adb, '-s', args.serial]
    results = {}

    def capture(name, *command):
        with (args.out / name).open('wb') as output:
            try:
                result = subprocess.run(prefix + list(command), stdout=output, stderr=subprocess.STDOUT,
                                        timeout=args.seconds + 20, check=False)
                results[name] = result.returncode
            except subprocess.TimeoutExpired:
                results[name] = 'timeout'

    started = datetime.now(timezone.utc).isoformat()
    with (args.out / 'stream-log.txt').open('wb') as output:
        log = subprocess.Popen(prefix + ['logcat', '-v', 'threadtime', '-s', 'scrcpy:D', 'scrcpy_native:D'],
                               stdout=output, stderr=subprocess.STDOUT)
        try:
            capture('source-before.txt', 'shell', 'dumpsys', 'gfxinfo', args.app, 'framestats')
            with ThreadPoolExecutor(max_workers=4) as pool:
                futures = [
                    pool.submit(capture, 'display.txt', 'shell', 'dumpsys', 'display'),
                    pool.submit(capture, 'sf.txt', 'shell', 'dumpsys', 'SurfaceFlinger'),
                    pool.submit(capture, 'codec.txt', 'shell', 'dumpsys', 'media.resource_manager'),
                    pool.submit(capture, 'app-trace.atrace', 'shell',
                                f'atrace -z -b 16384 -t {args.seconds} -a "*" gfx view webview sched'),
                ]
                for future in futures:
                    future.result()
            capture('source-after.txt', 'shell', 'dumpsys', 'gfxinfo', args.app, 'framestats')
        finally:
            log.terminate()
            try:
                log.wait(timeout=5)
            except subprocess.TimeoutExpired:
                log.kill()
                log.wait()
    metadata = {'startedUtc': started, 'endedUtc': datetime.now(timezone.utc).isoformat(),
                'serial': args.serial, 'sourceApp': args.app, 'traceSeconds': args.seconds, 'results': results}
    (args.out / 'capture.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(args.out.resolve())


if __name__ == '__main__':
    main()
