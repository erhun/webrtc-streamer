"""Mock adb to verify startup ordering, rejection handling and remote quoting."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='streaming-tests-', dir=root / 'tests') as temp:
    work = Path(temp)
    (work / 'p4').mkdir()
    shutil.copy(root / 'p4/start-server.sh', work / 'p4/start-server.sh')
    adb = work / 'adb'
    adb.write_text("""#!/usr/bin/env python3
import os, sys, shlex
from pathlib import Path
args=sys.argv[3:]
if args == ['root']:
    Path(os.environ['ROOT_MARK']).touch()
elif args == ['wait-for-device']:
    assert Path(os.environ['ROOT_MARK']).exists()
elif args == ['shell', 'id', '-u']:
    print('2000' if os.environ['CASE']=='nonroot' else '0')
elif args[0]=='shell' and args[1].startswith('am startservice'):
    assert Path(os.environ['ROOT_MARK']).exists()
    words=shlex.split(args[1])
    assert len(words)==7
    assert 'signal_token=' + os.environ['SIGNAL_TOKEN'] in words[-1]
    assert 'turn_password=' + os.environ['TURN_PASSWORD'] in words[-1]
    print('Error: Requires permission' if os.environ['CASE']=='denied' else 'Starting service')
elif args == ['shell', 'ss -ltn']:
    print('LISTEN 0 50 127.0.0.1:8080 0.0.0.0:*')
elif args[0]=='forward':
    Path(os.environ['FORWARD_MARK']).touch()
else:
    raise AssertionError(args)
""")
    adb.chmod(0o755)
    for case in ('nonroot', 'denied', 'ok'):
        for name in ('root-mark', 'forward-mark'):
            (work / name).unlink(missing_ok=True)
        env = dict(os.environ, PATH=str(work) + ':' + os.environ['PATH'],
                   CASE=case, ROOT_MARK=str(work / 'root-mark'), FORWARD_MARK=str(work / 'forward-mark'),
                   SIGNAL_TOKEN='a' * 64, TURN_URL='turn:10.0.2.2:3478',
                   TURN_PASSWORD="quote' dollar$ space", TURN_USER='test')
        result = subprocess.run(['bash', str(work / 'p4/start-server.sh')], env=env, text=True, capture_output=True)
        assert (result.returncode == 0) == (case == 'ok'), result.stderr
        assert (work / 'forward-mark').exists() == (case == 'ok')
        assert env['SIGNAL_TOKEN'] not in result.stdout + result.stderr
    token = work / 'tmp/session-token.txt'
    assert token.read_text().strip() == 'a' * 64
    assert token.stat().st_mode & 0o777 == 0o600
print('Startup mock tests passed; emulator validation still required')
