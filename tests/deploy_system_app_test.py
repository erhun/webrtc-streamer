#!/usr/bin/env python3
"""Mock adb to check deployment decisions, update preservation and failure handling."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "server/tools/deploy_system_app.sh"
MOCK = r'''#!/usr/bin/env python3
import hashlib, json, os, shlex, sys
from pathlib import Path
path = Path(os.environ['DEPLOY_STATE'])
state = json.loads(path.read_text())
args = sys.argv[1:]
assert args[:2] == ['-s', 'test-device']
args = args[2:]
state['calls'].append(args)
code, output = 0, ''
if args == ['get-state']: output = 'device'
elif args == ['root']: state['root'] = True
elif args == ['wait-for-device']: pass
elif args == ['remount']:
    if state.get('remount_error'): code, output = 1, 'remount failed'
elif args[:2] == ['install', '-r']:
    if state.get('install_error'): code, output = 1, 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'
    else:
        state['active'] = '/data/app/scrcpy/base.apk'
        state['files'][state['active']] = hashlib.sha256(Path(args[2]).read_bytes()).hexdigest()
        state['registered'] = True
        output = 'Success'
elif args[:1] == ['push']:
    state['files'][args[2]] = hashlib.sha256(Path(args[1]).read_bytes()).hexdigest()
elif args == ['reboot']:
    if not state.get('unchanged_server'): state['pid'] += 1
    if not state.get('unchanged_boot_id'): state['boot_id'] = 'new-boot'
    state['registered'] = True
    state['system'] = state['privileged'] = True
    state.setdefault('active', '/system/priv-app/scrcpy/scrcpy.apk')
elif args[:1] == ['shell']:
    shell = args[1:]
    if shell == ['pm', 'list', 'packages', 'com.genymobile.scrcpy']:
        if state.get('registered'): output = 'package:com.genymobile.scrcpy'
    elif shell == ['dumpsys', 'package', 'com.genymobile.scrcpy']:
        output = 'Packages:\n  Package [com.genymobile.scrcpy]:\n    userId=%s\n    pkgFlags=[ %s ]\n    privateFlags=[ %s ]\n' % (
            state['uid'], 'SYSTEM HAS_CODE' if state.get('system') else 'HAS_CODE',
            'PRIVILEGED' if state.get('privileged') else '')
        if state.get('hidden_base'):
            output += 'Hidden system packages:\n    userId=1000\n    pkgFlags=[ SYSTEM ]\n    privateFlags=[ PRIVILEGED ]\n'
    elif shell == ['pm', 'path', 'com.genymobile.scrcpy']: output = 'package:' + state['active']
    elif shell == ['id', '-u']: output = '0' if state.get('root') else '2000'
    elif shell == ['pidof', 'system_server']: output = str(state['pid'])
    elif shell == ['cat', '/proc/sys/kernel/random/boot_id']: output = state['boot_id']
    elif shell == ['pidof system_server; cat /proc/sys/kernel/random/boot_id; getprop sys.boot_completed']:
        output = str(state['pid']) + '\n' + state['boot_id'] + '\n1'
    elif shell[:2] == ['am', 'stopservice']: output = 'Service stopped'
    else:
        words = shlex.split(shell[0])
        if words[:1] == ['mkdir']: pass
        elif words[:1] == ['chmod']: pass
        elif words[:1] == ['sha256sum']:
            output = state['files'][words[1]] + '  ' + words[1]
            if state.get('bad_stage_hash') and '.new.' in words[1]: output = '0' * 64 + '  ' + words[1]
        elif words[:2] == ['mv', '-f']: state['files'][words[3]] = state['files'].pop(words[2])
        elif words[:2] == ['rm', '-f']: state['files'].pop(words[2], None)
        elif words[:2] == ['if', 'command']: pass
        else: raise AssertionError(shell)
else: raise AssertionError(args)
path.write_text(json.dumps(state))
print(output)
sys.exit(code)
'''


def main():
    with tempfile.TemporaryDirectory(prefix="deploy-check-", dir=ROOT / "tests") as directory:
        work = Path(directory)
        adb = work / "adb"
        adb.write_text(MOCK)
        adb.chmod(0o755)
        native = work / "libscrcpy_native.so"
        native.write_bytes(b"test native payload")
        apk = work / "scrcpy-platform.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"test manifest")
            archive.writestr("lib/arm64-v8a/libscrcpy_native.so", native.read_bytes())
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()

        def run(name, overrides, strategy="system", mode="cloud", expect=True, apk_path=apk):
            state = {"calls": [], "files": {}, "root": True, "uid": 1000, "pid": 427, "boot_id": "old-boot", **overrides}
            path = work / (name + ".json")
            path.write_text(json.dumps(state))
            result = subprocess.run(["bash", str(SCRIPT), "--serial", "test-device", "--apk", str(apk_path),
                "--native", str(native), "--strategy", strategy, "--mode", mode, "--boot-timeout", "1"],
                env={**os.environ, "PATH": str(work) + os.pathsep + os.environ["PATH"], "DEPLOY_STATE": str(path)},
                capture_output=True, text=True, timeout=15)
            assert (result.returncode == 0) == expect, (name, result.stdout, result.stderr)
            state = json.loads(path.read_text())
            assert not any('pkill' in str(c) or 'uninstall' in str(c) for c in state['calls']), name
            assert not any('.new.' in p for p in state['files']), (name, 'temporary files remain')
            print("PASS deploy:", name)
            return state

        first = run("first-system-deploy", {})
        assert ["reboot"] in first['calls'] and first['files'][first['active']] == digest
        updated = run("system-with-data-override", {"registered": True, "system": True, "privileged": True})
        assert updated['active'].startswith('/data/app/') and updated['files'][updated['active']] == digest
        assert ["reboot"] in updated['calls'] and updated['files']['/system/priv-app/scrcpy/scrcpy.apk'] == digest
        hot = run("signed-update", {"registered": True, "system": True, "privileged": True, "root": False}, strategy="update")
        assert not any(c[0] in ('push', 'root', 'remount', 'reboot') for c in hot['calls'])
        assert hot['files'][hot['active']] == digest
        emulator = run("emulator-root-remount", {"root": False}, mode="emulator")
        assert emulator['calls'].index(['root']) < emulator['calls'].index(['remount'])
        run("wrong-existing-uid", {"registered": True, "uid": 10123}, expect=False)
        run("hidden-base-is-not-active-privilege", {"registered": True, "hidden_base": True}, strategy="update", expect=False)
        failed = run("signature-rejection", {"registered": True, "system": True, "privileged": True, "install_error": True}, expect=False)
        assert not any(c[0] in ('push', 'reboot') for c in failed['calls'])
        run("remount-failure", {"remount_error": True}, mode="emulator", expect=False)
        bad = run("stage-checksum-failure", {"bad_stage_hash": True}, expect=False)
        assert ['reboot'] not in bad['calls'] and '/system/priv-app/scrcpy/scrcpy.apk' not in bad['files']
        run("old-boot-completed-is-not-new-boot", {"unchanged_server": True, "unchanged_boot_id": True}, expect=False)
        run("cloud-restart-keeps-host-boot-id", {"unchanged_boot_id": True})
        run("real-reboot-reuses-server-pid", {"unchanged_server": True})
        mismatch = work / "stale.apk"
        with zipfile.ZipFile(mismatch, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("lib/arm64-v8a/libscrcpy_native.so", b"stale native")
        stale = run("stale-apk-native", {}, apk_path=mismatch, expect=False)
        assert stale['calls'] == []


if __name__ == "__main__":
    main()
