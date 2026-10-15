"""Exercise cleanup without switching networks or deploying to hardware."""
import importlib.util
from importlib.machinery import SourceFileLoader
from pathlib import Path
import subprocess
import tempfile
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

deploy_path = Path(__file__).resolve().parents[1] / 'deploy'
spec = importlib.util.spec_from_loader(
    'deploy_wifi', SourceFileLoader('deploy_wifi', str(deploy_path)))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HardwareMapTests(unittest.TestCase):
    def commands(self, hardware_map, map_only=False):
        args = SimpleNamespace(ssid='Robot Wi-Fi', robot_ip='192.168.43.1',
                               hardware_map=hardware_map or map_only, map_only=map_only)
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            return SimpleNamespace(stdout='device\n' if command[-1] == 'get-state' else '', stderr='')
        with patch.object(module, 'run', side_effect=run), patch.object(module, 'join'):
            module.deploy(args, 'en0', 'Home', '10.0.0.1', '/sdk/adb', {},
                          {'Robot Wi-Fi': 'r', 'Home': 'h'})
        return commands

    def test_hardware_map_pushed_before_install(self):
        commands = self.commands(True)
        pushes = [i for i, c in enumerate(commands) if 'push' in c]
        install = next(i for i, c in enumerate(commands) if ':TeamCode:installDebug' in c)
        self.assertTrue(pushes)
        self.assertTrue(all(i < install for i in pushes))

    def test_map_only_restarts_app_without_install(self):
        commands = self.commands(False, map_only=True)
        self.assertFalse([c for c in commands if ':TeamCode:installDebug' in c])
        self.assertTrue([c for c in commands if 'push' in c])
        self.assertTrue([c for c in commands if 'force-stop' in c])

    def test_no_hardware_map_without_flag(self):
        self.assertFalse([c for c in self.commands(False) if 'push' in c])


class RestoreTests(unittest.TestCase):
    def exercise(self, failure=None, join_failure=False, restore_failure=False):
        args = SimpleNamespace(ssid='Robot Wi-Fi', robot_ip='192.168.43.1')
        def run(command, **kwargs):
            if command[-1] == 'get-state':
                return SimpleNamespace(stdout='device\n', stderr='')
            if ':TeamCode:installDebug' in command:
                self.assertEqual(kwargs['env']['ANDROID_SERIAL'], '192.168.43.1:5555')
                self.assertIn('--offline', command)
                if failure:
                    raise failure
            return SimpleNamespace(stdout='', stderr='')
        with patch.object(module, 'run', side_effect=run), \
             patch.object(module, 'join') as join:
            if join_failure:
                join.side_effect = [RuntimeError('join failed'), None]
            elif restore_failure:
                join.side_effect = [None, RuntimeError('restore failed')]
            expected = failure or (RuntimeError() if join_failure or restore_failure else None)
            if expected:
                with self.assertRaises(type(expected)):
                    module.deploy(args, 'en0', 'Home Wi-Fi', '10.0.0.1', '/sdk/adb', {}, {'Robot Wi-Fi': 'robot-secret', 'Home Wi-Fi': 'home-secret'})
            else:
                module.deploy(args, 'en0', 'Home Wi-Fi', '10.0.0.1', '/sdk/adb', {}, {'Robot Wi-Fi': 'robot-secret', 'Home Wi-Fi': 'home-secret'})
            self.assertEqual(join.call_args_list[-1].args, ('en0', 'Home Wi-Fi', '10.0.0.1', 'home-secret'))

    def test_private_config_loads_without_keychain(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'wifi.json'
            path.write_text(json.dumps({'passwords': {'Robot': 'test-only'}}))
            path.chmod(0o600)
            with patch.object(module.subprocess, 'run') as command:
                self.assertEqual(module.load_config(path)['passwords']['Robot'], 'test-only')
                command.assert_not_called()

    def test_missing_config_starts_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'wifi.json'
            self.assertEqual(module.load_config(path), {'passwords': {}})

    def test_missing_password_is_prompted_and_saved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'wifi.json'
            config = {'passwords': {}}
            with patch.object(module.sys.stdin, 'isatty', return_value=True), \
                 patch.object(module.getpass, 'getpass', return_value='test-only'):
                self.assertEqual(module.password_for(config, path, 'Test Wi-Fi'), 'test-only')
            saved = json.loads(path.read_text())
            self.assertEqual(saved['passwords']['Test Wi-Fi'], 'test-only')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_saved_password_does_not_prompt(self):
        config = {'passwords': {'Test Wi-Fi': 'test-only'}}
        with patch.object(module.getpass, 'getpass') as prompt:
            self.assertEqual(
                module.password_for(config, Path('/not/used'), 'Test Wi-Fi'),
                'test-only')
            prompt.assert_not_called()

    def test_open_network_omits_password_argument(self):
        result = SimpleNamespace(stdout='', stderr='')
        with patch.object(module, 'run', return_value=result) as command, \
             patch.object(module, 'current_ssid', return_value='Open Wi-Fi'), \
             patch.object(module, 'router', return_value='10.0.0.1'):
            module.join('en0', 'Open Wi-Fi', '10.0.0.1', '')
        command.assert_called_once_with(
            [module.NETWORKSETUP, '-setairportnetwork', 'en0', 'Open Wi-Fi'],
            timeout=45)

    def test_wifi_name_ignores_case_and_punctuation(self):
        config = {
            'robot_ssid': 'Robot-24620-RC',
            'return_wifi': 'Home Network',
            'passwords': {'Robot-24620-RC': 'one', 'Home Network': 'two'},
        }
        self.assertEqual(module.wifi_name('robot24620rc', config), 'Robot-24620-RC')
        self.assertEqual(module.wifi_name('home network', config), 'Home Network')

    def test_new_wifi_name_is_preserved(self):
        config = {'passwords': {}}
        self.assertEqual(module.wifi_name('New Wi-Fi', config), 'New Wi-Fi')

    def test_public_config_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'wifi.json'
            path.write_text('{}')
            path.chmod(0o644)
            with self.assertRaises(RuntimeError):
                module.load_config(path)

    def test_join_failure_does_not_expose_password(self):
        secret = 'never-print-this-password'
        error = subprocess.TimeoutExpired(['networksetup', secret], 45)
        with patch.object(module, 'run', side_effect=error):
            with self.assertRaises(RuntimeError) as caught:
                module.join('en0', 'Robot', password=secret)
            self.assertNotIn(secret, str(caught.exception))

    def test_get_state_timeout_reaches_adb_restart(self):
        calls = []
        state_attempts = 0

        def run(command, **kwargs):
            nonlocal state_attempts
            calls.append(command)
            if command[-1] == 'get-state':
                state_attempts += 1
                if state_attempts <= 3:
                    raise subprocess.TimeoutExpired(command, 10)
                return SimpleNamespace(stdout='device\n', stderr='')
            return SimpleNamespace(stdout='', stderr='')

        with patch.object(module, 'run', side_effect=run), \
             patch.object(module.time, 'sleep'):
            module.connect_adb('/sdk/adb', '192.168.43.1:5555')
        self.assertIn(['/sdk/adb', 'kill-server'], calls)

    def test_connect_timeout_reaches_adb_restart(self):
        calls = []
        connect_attempts = 0

        def run(command, **kwargs):
            nonlocal connect_attempts
            calls.append(command)
            if command[1] == 'connect':
                connect_attempts += 1
                if connect_attempts <= 3:
                    raise subprocess.TimeoutExpired(command, 10)
            if command[-1] == 'get-state':
                return SimpleNamespace(stdout='device\n', stderr='')
            return SimpleNamespace(stdout='', stderr='')

        with patch.object(module, 'run', side_effect=run), \
             patch.object(module.time, 'sleep'):
            module.connect_adb('/sdk/adb', '192.168.43.1:5555')
        self.assertIn(['/sdk/adb', 'kill-server'], calls)

    def test_stay_skips_restore_and_keeps_adb(self):
        args = SimpleNamespace(ssid='Robot Wi-Fi', robot_ip='192.168.43.1', stay=True)
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            return SimpleNamespace(stdout='device\n' if 'get-state' in command else '', stderr='')
        with patch.object(module, 'run', side_effect=run), \
             patch.object(module.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='device\n')), \
             patch.object(module, 'join') as join:
            module.deploy(args, 'en0', 'Home Wi-Fi', '10.0.0.1', '/sdk/adb', {},
                          {'Robot Wi-Fi': 'robot-secret', 'Home Wi-Fi': 'home-secret'})
        self.assertEqual(join.call_count, 1)   # joined the robot, never went back
        disconnects = [c for c in commands if c[1:2] == ['disconnect']]
        self.assertEqual(len(disconnects), 1)   # only the pre-connect cleanup, none afterwards

    def test_stay_still_restores_when_robot_join_fails(self):
        args = SimpleNamespace(ssid='Robot Wi-Fi', robot_ip='192.168.43.1', stay=True)
        with patch.object(module, 'run', return_value=SimpleNamespace(stdout='', stderr='')), \
             patch.object(module, 'join') as join:
            join.side_effect = [RuntimeError('join failed'), None]
            with self.assertRaises(RuntimeError):
                module.deploy(args, 'en0', 'Home Wi-Fi', '10.0.0.1', '/sdk/adb', {},
                              {'Robot Wi-Fi': 'robot-secret', 'Home Wi-Fi': 'home-secret'})
        self.assertEqual(join.call_args_list[-1].args, ('en0', 'Home Wi-Fi', '10.0.0.1', 'home-secret'))

    def test_success_restores(self):
        self.exercise()

    def test_failed_install_restores(self):
        self.exercise(subprocess.CalledProcessError(1, 'gradlew'))

    def test_ctrl_c_restores(self):
        self.exercise(KeyboardInterrupt())

    def test_timeout_restores(self):
        self.exercise(subprocess.TimeoutExpired('gradlew', 300))

    def test_failed_join_restores(self):
        self.exercise(join_failure=True)

    def test_failed_restore_reports_failure(self):
        self.exercise(restore_failure=True)


if __name__ == '__main__':
    unittest.main()
