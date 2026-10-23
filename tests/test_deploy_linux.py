"""Exercise deploy-linux's network restore without touching real networks or hardware."""
import importlib.util
from importlib.machinery import SourceFileLoader
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

path = Path(__file__).resolve().parents[1] / 'deploy-linux'
spec = importlib.util.spec_from_loader('deploy_linux', SourceFileLoader('deploy_linux', str(path)))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fake_run(failure=None):
    commands = []

    def run(command, **kwargs):
        commands.append(command)
        if ':TeamCode:installDebug' in command:
            assert kwargs['env']['ANDROID_SERIAL'] == '192.168.43.1:5555'
            assert '--offline' in command
            if failure:
                raise failure
        return SimpleNamespace(stdout='device\n' if 'get-state' in command else '', stderr='')
    return run, commands


class LinuxDeployTests(unittest.TestCase):
    def deploy(self, stay=False, failure=None, bring_up_effect=None, hardware_map=False,
               map_only=False):
        args = SimpleNamespace(robot='Robot', robot_ip='192.168.43.1', stay=stay,
                               hardware_map=hardware_map or map_only, map_only=map_only)
        run, commands = fake_run(failure)
        with patch.object(module, 'run', side_effect=run), \
             patch.object(module, 'bring_up', side_effect=bring_up_effect) as bring_up:
            error = None
            try:
                module.deploy(args, 'Home', '/sdk/adb', {})
            except BaseException as exc:
                error = exc
        return bring_up, commands, error

    def test_success_restores_home(self):
        bring_up, _, error = self.deploy()
        self.assertIsNone(error)
        self.assertEqual([c.args[0] for c in bring_up.call_args_list], ['Robot', 'Home'])

    def test_failed_install_still_restores(self):
        bring_up, _, error = self.deploy(failure=subprocess.CalledProcessError(1, 'gradlew'))
        self.assertIsInstance(error, subprocess.CalledProcessError)
        self.assertEqual(bring_up.call_args_list[-1].args[0], 'Home')

    def test_ctrl_c_still_restores(self):
        bring_up, _, error = self.deploy(failure=KeyboardInterrupt())
        self.assertIsInstance(error, KeyboardInterrupt)
        self.assertEqual(bring_up.call_args_list[-1].args[0], 'Home')

    def test_stay_does_not_restore_or_disconnect(self):
        bring_up, commands, error = self.deploy(stay=True)
        self.assertIsNone(error)
        self.assertEqual([c.args[0] for c in bring_up.call_args_list], ['Robot'])
        disconnects = [c for c in commands if c[1:2] == ['disconnect']]
        self.assertEqual(len(disconnects), 1)   # only the pre-connect cleanup

    def test_stay_still_restores_when_join_fails(self):
        bring_up, _, error = self.deploy(stay=True,
                                         bring_up_effect=[RuntimeError('join failed'), None])
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(bring_up.call_args_list[-1].args[0], 'Home')

    def test_hardware_map_pushed_before_install(self):
        _, commands, error = self.deploy(hardware_map=True)
        self.assertIsNone(error)
        pushes = [i for i, c in enumerate(commands) if 'push' in c]
        install = next(i for i, c in enumerate(commands) if ':TeamCode:installDebug' in c)
        self.assertTrue(pushes)
        self.assertTrue(all(i < install for i in pushes))
        self.assertEqual(commands[pushes[0]][-1], '/sdcard/FIRST/')

    def test_paths_replaced_before_install(self):
        _, commands, error = self.deploy()
        self.assertIsNone(error)
        push = next(i for i, c in enumerate(commands) if 'push' in c and c[-1].endswith('auto.json.tmp'))
        rename = next(i for i, c in enumerate(commands) if 'mv' in c)
        install = next(i for i, c in enumerate(commands) if ':TeamCode:installDebug' in c)
        self.assertLess(push, rename)
        self.assertLess(rename, install)

    def test_no_hardware_map_without_flag(self):
        _, commands, _ = self.deploy()
        self.assertFalse([c for c in commands if 'push' in c and c[-1] == '/sdcard/FIRST/'])

    def test_map_only_restarts_app_without_install(self):
        bring_up, commands, error = self.deploy(map_only=True)
        self.assertIsNone(error)
        self.assertFalse([c for c in commands if ':TeamCode:installDebug' in c])
        push = next(i for i, c in enumerate(commands) if 'push' in c)
        stop = next(i for i, c in enumerate(commands) if 'force-stop' in c)
        self.assertLess(push, stop)
        self.assertTrue([c for c in commands if 'monkey' in c])
        self.assertEqual(bring_up.call_args_list[-1].args[0], 'Home')

    def test_tuning_flag_only_when_asked(self):
        _, commands, _ = self.deploy()
        install = next(c for c in commands if ':TeamCode:installDebug' in c)
        self.assertNotIn('-Ptuning', install)
        self.assertEqual(module.tuning_flag(SimpleNamespace(tuning=True)), ['-Ptuning'])

    def test_adb_server_restarted_before_connecting(self):
        _, commands, _ = self.deploy()
        kill = next(i for i, c in enumerate(commands) if c[1:] == ['kill-server'])
        connect = next(i for i, c in enumerate(commands) if c[1:2] == ['connect'])
        self.assertLess(kill, connect)

    def test_match_connection_ignores_case_and_punctuation(self):
        saved = ['Home', '2222-RC']
        self.assertEqual(module.match_connection('2222rc', saved), '2222-RC')
        self.assertEqual(module.match_connection('Unknown', saved), 'Unknown')


if __name__ == '__main__':
    unittest.main()
