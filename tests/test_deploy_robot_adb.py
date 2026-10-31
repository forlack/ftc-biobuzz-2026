"""Robot ADB recovery uses HTTP before the verified laptop ADB handshake."""
import contextlib
import importlib.util
from importlib.machinery import SourceFileLoader
import io
import subprocess
from types import SimpleNamespace
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class RobotAdbTests(unittest.TestCase):
    def modules(self):
        for name in ('deploy', 'deploy-linux'):
            spec = importlib.util.spec_from_loader(name, SourceFileLoader(name, str(ROOT / name)))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            yield module

    def test_restart_posts_to_robot_with_bounded_timeout(self):
        for module in self.modules():
            with self.subTest(module=module.__name__), patch.object(module.urllib.request, 'urlopen') as open_url:
                open_url.return_value.__enter__.return_value = io.StringIO('{"canRestart":true,"listening":true}')
                with contextlib.redirect_stdout(io.StringIO()):
                    module.restart_robot_adb('192.168.43.1')
                request = open_url.call_args.args[0]
                self.assertEqual(request.full_url, 'http://192.168.43.1:8686/api/adb/restart')
                self.assertEqual(request.method, 'POST')
                self.assertEqual(request.data, b'{}')
                self.assertEqual(open_url.call_args.kwargs['timeout'], 20)
                self.assertEqual(open_url.call_count, 1)

    def test_unavailable_or_denied_restart_allows_normal_connection(self):
        for module in self.modules():
            for failure in (TimeoutError('offline'), module.urllib.error.HTTPError('url', 423, 'Locked', {}, None)):
                with self.subTest(module=module.__name__, failure=failure), \
                     patch.object(module.urllib.request, 'urlopen', side_effect=failure), \
                     contextlib.redirect_stdout(io.StringIO()) as output:
                    module.restart_robot_adb('192.168.43.1')
                    self.assertIn('trying the normal ADB connection', output.getvalue())

    def test_permission_error_is_reported(self):
        for module in self.modules():
            with patch.object(module.urllib.request, 'urlopen') as open_url, \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                open_url.return_value.__enter__.return_value = io.StringIO('{"error":"Not allowed yet: grant permission"}')
                module.restart_robot_adb('192.168.43.1')
                self.assertIn('grant permission', output.getvalue())

    def test_install_failure_disconnects_and_retries_once(self):
        for module in self.modules():
            events = []
            attempts = []
            def run(command, **kwargs):
                events.append(command)
                if ':TeamCode:installDebug' in command:
                    attempts.append(command)
                    if len(attempts) == 1:
                        raise subprocess.CalledProcessError(1, command)
            with patch.object(module, 'run', side_effect=run), \
                 patch.object(module, 'connect_adb', side_effect=lambda *a: events.append(['verified'])), \
                 contextlib.redirect_stdout(io.StringIO()):
                module.install_debug(SimpleNamespace(), 'adb', '192.168.43.1:5555', {})
            self.assertEqual(len(attempts), 2)
            self.assertEqual(events[1], ['adb', 'disconnect', '192.168.43.1:5555'])
            self.assertEqual(events[2], ['verified'])
            self.assertIn('--no-daemon', attempts[1])

    def test_second_install_failure_propagates_without_more_retries(self):
        for module in self.modules():
            failure = subprocess.CalledProcessError(1, 'gradlew')
            with patch.object(module, 'run', side_effect=failure) as run, \
                 patch.object(module, 'connect_adb'), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(subprocess.CalledProcessError):
                    module.install_debug(SimpleNamespace(), 'adb', '192.168.43.1:5555', {})
            self.assertEqual(sum(':TeamCode:installDebug' in c.args[0] for c in run.call_args_list), 2)
