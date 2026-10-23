"""Exercise robot-settings without a robot: every adb call is mocked."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = runpy.run_path(str(ROOT / 'robot-settings'))


def adb_commands(argv, local, returncode=0):
    with patch.dict(SCRIPT['main'].__globals__, LOCAL=local), \
         patch.object(sys, 'argv', ['robot-settings', *argv, '-s', 'USB_TEST']), \
         patch('subprocess.run', return_value=SimpleNamespace(returncode=returncode)) as run, \
         contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        try:
            SCRIPT['main']()
        except SystemExit:
            pass
    return [call.args[0][1:] for call in run.call_args_list]


class RobotSettingsScriptTest(unittest.TestCase):
    def test_push_stages_then_backs_up_and_swaps(self):
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / 'robot.json'
            local.write_text(json.dumps({'version': 1, 'FieldCentricJava': {'INTAKE_RPM': 700}}))
            commands = adb_commands(['push'], local)
        self.assertIn(['-s', 'USB_TEST', 'push', str(local), '/sdcard/FIRST/settings/robot.json.tmp'], commands)
        swap = commands[-1][-1]
        self.assertLess(swap.index('robot.json.bak'), swap.index('mv '))
        self.assertFalse(any('install' in c or 'force-stop' in c for c in commands))

    def test_bad_json_is_never_pushed(self):
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / 'robot.json'
            local.write_text('{"version": 1, "x": NaN}')
            self.assertEqual(adb_commands(['push'], local), [])

    def test_pull_copies_robot_file_into_repo(self):
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / 'settings' / 'robot.json'
            commands = adb_commands(['pull'], local)
        self.assertIn(['-s', 'USB_TEST', 'pull', '/sdcard/FIRST/settings/robot.json', str(local)], commands)
        self.assertEqual(commands[0], ['kill-server'])

    def test_pull_without_robot_file_copies_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / 'robot.json'
            commands = adb_commands(['pull'], local, returncode=1)
        self.assertFalse(any('pull' in c for c in commands))


if __name__ == '__main__':
    unittest.main()
