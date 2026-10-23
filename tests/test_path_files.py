import contextlib
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
UPLOADER = runpy.run_path(str(ROOT / 'update-paths'))


class PathFilesTest(unittest.TestCase):
    def test_upload_uses_temporary_file_without_restart(self):
        with patch.object(sys, 'argv', ['update-paths', '-s', 'USB_TEST']), \
                patch('subprocess.run') as run, contextlib.redirect_stdout(io.StringIO()):
            UPLOADER['main']()
        commands = [call.args[0][1:] for call in run.call_args_list]
        self.assertEqual(commands[0], ['kill-server'])
        self.assertTrue(any('push' in c and c[-1] == '/sdcard/FIRST/paths/auto.java.txt.tmp' for c in commands))
        self.assertTrue(any('push' in c and c[-1] == '/sdcard/FIRST/paths/auto.json.tmp' for c in commands))
        self.assertEqual(commands[-1], ['-s', 'USB_TEST', 'shell', 'mv',
                                      '/sdcard/FIRST/paths/auto.json.tmp', '/sdcard/FIRST/paths/auto.json'])
        self.assertFalse(any('reboot' in c or 'install' in c or 'am' in c for c in commands))

    def test_invalid_json_is_not_uploaded(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'bad.json'
            file.write_text('{"x": NaN}')
            with patch.object(sys, 'argv', ['update-paths', str(file)]), \
                    patch('subprocess.run') as run, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    UPLOADER['main']()
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
