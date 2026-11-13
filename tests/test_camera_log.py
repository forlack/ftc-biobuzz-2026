"""Exercise the capture CLI with fake ADB; never connect to the robot."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'camera-log'
FAKE_ADB = '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
import time

root = Path(os.environ['CAMERA_LOG_TEST_ROOT'])
args = sys.argv[1:]
with (root / 'commands.jsonl').open('a') as output:
    output.write(json.dumps(args) + '\\n')
assert args[:2] == ['-s', 'usb-test'], args
args = args[2:]
if args == ['get-state']:
    print('device')
elif args[:1] == ['logcat']:
    print('11-12 14:07:00.000 123 456 D Other: hidden context', flush=True)
    print('11-12 14:07:01.000 123 456 D UvcStream: previous frame failed to send EOF', flush=True)
    print('11-12 14:07:02.000 123 456 E RobotCore: unable to start streaming', flush=True)
    print('11-12 14:07:02.001 123 456 W System.err: at camera.capture(Source.java:122)', flush=True)
    if os.environ.get('CAMERA_LOG_TEST_RECONNECT') and not (root / 'disconnected').exists():
        (root / 'disconnected').touch()
    else:
        (root / 'streaming').touch()
        time.sleep(60)
elif args[:2] == ['shell', 'dmesg']:
    print('dmesg: Permission denied')
    sys.exit(1)
elif args[:1] == ['shell']:
    assert args[1] in ('cat', 'dumpsys', 'ps'), args
    print('diagnostic: ' + ' '.join(args))
elif args[:2] == ['pull', '/sdcard/FIRST/logs']:
    folder = Path(args[2])
    folder.mkdir()
    (folder / 'events.jsonl').write_text('saved event\\n')
    print('pulled saved logs')
else:
    raise AssertionError(args)
'''


class CameraLogTests(unittest.TestCase):
    def run_capture(self, reconnect=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / 'adb'
            adb.write_text(FAKE_ADB)
            adb.chmod(0o755)
            env = dict(os.environ, CAMERA_LOG_TEST_ROOT=str(root))
            if reconnect:
                env['CAMERA_LOG_TEST_RECONNECT'] = '1'
            output = root / 'capture'
            process = subprocess.Popen(
                [sys.executable, str(SCRIPT), '--adb', str(adb), '--serial', 'usb-test',
                 '--output', str(output)], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
            try:
                deadline = time.monotonic() + 15
                while not (root / 'streaming').exists():
                    if process.poll() is not None or time.monotonic() > deadline:
                        self.fail('Fake logcat did not start: ' + process.communicate(timeout=2)[0])
                    time.sleep(0.05)
                console, _ = process.communicate('Impact at field wall\nq\n', timeout=15)
                self.assertEqual(process.returncode, 0, console)
                raw = (output / 'logcat-full.txt').read_text()
                self.assertIn('hidden context', raw)
                self.assertNotIn('hidden context', console)
                self.assertIn('previous frame failed to send EOF', console)
                self.assertIn('unable to start streaming', console)
                self.assertIn('at camera.capture', raw)
                self.assertIn('MARK: Impact at field wall', console)
                self.assertIn('unavailable (see file)', console)
                self.assertIn('Impact at field wall', (output / '02-failure/marker.txt').read_text())
                self.assertTrue((output / '01-baseline/app-logs/events.jsonl').is_file())
                self.assertTrue((output / '02-failure/app-logs/events.jsonl').is_file())
                if reconnect:
                    self.assertGreaterEqual(raw.count('--- logcat connection'), 2)
                    self.assertIn('reconnecting in 3 s', console)
                # The fake rejects any device mutation, clear-log call, or server reset.
                self.assertNotIn('kill-server', (root / 'commands.jsonl').read_text())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()

    def test_capture_and_failure_snapshot(self):
        self.run_capture()

    def test_reconnect_preserves_existing_capture(self):
        self.run_capture(reconnect=True)


if __name__ == '__main__':
    unittest.main()
