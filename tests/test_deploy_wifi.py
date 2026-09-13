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


class RestoreTests(unittest.TestCase):
    def exercise(self, failure=None, join_failure=False, restore_failure=False):
        args = SimpleNamespace(ssid='Robot Wi-Fi', robot_ip='192.168.43.1')
        def run(command, **kwargs):
            if ':TeamCode:installDebug' in command:
                self.assertEqual(kwargs['env']['ANDROID_SERIAL'], '192.168.43.1:5555')
                self.assertIn('--offline', command)
                if failure:
                    raise failure
            return SimpleNamespace(stdout='', stderr='')
        with patch.object(module, 'run', side_effect=run), \
             patch.object(module.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='device\n')), \
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
