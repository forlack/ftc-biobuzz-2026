"""Exercise match-log with a fake ADB; never connects to the robot."""
import json
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'match-log'
FAKE_ADB = '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

root = Path(os.environ['MATCH_LOG_TEST_ROOT'])
args = sys.argv[1:]
with (root / 'commands.jsonl').open('a') as output:
    output.write(json.dumps(args) + '\\n')
if args[:1] == ['connect']:
    sys.exit(1)   # USB serial: connect must never be called
assert args[:2] == ['-s', 'usb-test'], args
args = args[2:]
if args == ['get-state']:
    print('device')
elif args[:2] == ['shell', 'dmesg']:
    print('[  10.0] usb 1-1: new high-speed USB device number 2 using xhci-hcd')
    print('[ 900.5] usb 1-1: USB disconnect, device number 2')
    print('[ 900.6] something unrelated')
elif args[:1] == ['logcat']:
    assert '-d' in args, args   # must dump and exit, never stream
    print('11-14 12:00:00.000 1 2 F libc    : Fatal signal 11 (SIGSEGV)')
elif args[:2] == ['shell', 'date; cat /proc/uptime /proc/sys/kernel/random/boot_id']:
    print('Fri Nov 14 12:30:00 PST 2026')
    print('1234.56 4000.00')
    print('abcd-boot')
elif args[:1] == ['shell']:
    assert args[1] in ('dumpsys', 'ps'), args
    print('diagnostic: ' + ' '.join(args))
elif args[:1] == ['pull']:
    if args[1].endswith('.txt.1'):
        print('adb: error: remote object does not exist')
        sys.exit(1)
    target = Path(args[2])
    if args[1].endswith('.txt'):
        target.write_text('log')
    else:
        target.mkdir()
        (target / 'file.jsonl').write_text('x')
    print('pulled')
else:
    print('unexpected ' + ' '.join(args))
    sys.exit(2)
'''


class MatchLogTest(unittest.TestCase):
    def test_pulls_everything_read_only(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            adb = root / 'adb'
            adb.write_text(FAKE_ADB)
            adb.chmod(0o755)
            output = root / 'out'
            result = subprocess.run(
                [sys.executable, str(SCRIPT), '--adb', str(adb), '--serial', 'usb-test',
                 '--output', str(output), 'camera', 'white'],
                env={**os.environ, 'MATCH_LOG_TEST_ROOT': str(root)},
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stdout)
            for name in ('kernel.txt', 'logcat-all.txt', 'usb.txt', 'memory.txt', 'processes.txt',
                         'robot-time.txt', 'robotControllerLog.txt', 'summary.txt', 'README.txt'):
                self.assertTrue((output / name).is_file(), name)
            self.assertTrue((output / 'app-logs' / 'file.jsonl').is_file())
            self.assertTrue((output / 'matchlogs' / 'file.jsonl').is_file())
            self.assertIn('camera white', (output / 'README.txt').read_text())
            summary = (output / 'summary.txt').read_text()
            self.assertIn('up 20.6 min', summary)
            self.assertIn('1 USB events', summary)
            self.assertIn('USB disconnect', summary)
            self.assertIn('1 crash lines', summary)
            self.assertIn('not found or failed', result.stdout)   # missing .txt.1 is not fatal
            commands = [json.loads(line) for line in (root / 'commands.jsonl').read_text().splitlines()]
            self.assertFalse(any(c[:1] == ['connect'] for c in commands))
            # Read-only: nothing that clears, writes or restarts on the robot.
            flat = [' '.join(c) for c in commands]
            for forbidden in ('logcat -c', ' rm ', 'push', 'reboot', 'am ', 'settings put'):
                self.assertFalse(any(forbidden in f for f in flat), forbidden)


if __name__ == '__main__':
    unittest.main()
