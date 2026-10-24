"""robot-sync's decisions: which side changed, and conflicts are never copied silently."""
from pathlib import Path
import runpy
import unittest

SYNC = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'robot-sync'))
compare, fingerprint, repo_path = SYNC['compare'], SYNC['fingerprint'], SYNC['repo_path']


class CompareTests(unittest.TestCase):
    def test_each_situation(self):
        old, new_robot, new_repo = b'old', b'robot edit', b'repo edit'
        robot = {'same': fingerprint(old), 'robot': fingerprint(new_robot), 'repo': fingerprint(old),
                 'both': fingerprint(new_robot), 'robot-only': fingerprint(old)}
        repo = {'same': old, 'robot': old, 'repo': new_repo, 'both': new_repo, 'repo-only': old}
        base = {name: fingerprint(old) for name in ('same', 'robot', 'repo', 'both')}
        result = compare(robot, repo, base)
        for name in ('same', 'robot', 'repo', 'both', 'robot-only', 'repo-only'):
            self.assertEqual(result[name], name)

    def test_never_synced_and_different_is_a_conflict(self):
        self.assertEqual(compare({'a': fingerprint(b'1')}, {'a': b'2'}, {})['a'], 'both')

    def test_fingerprint_matches_robot_hash(self):
        # RobotFiles.hash: first 8 bytes of SHA-1 as hex. sha1(b"abc") = a9993e36 4706816a ...
        self.assertEqual(fingerprint(b'abc'), 'a9993e364706816a')

    def test_hardware_maps_live_in_robot_config(self):
        self.assertTrue(str(repo_path('2222-Config.xml')).endswith('robot-config/2222-Config.xml'))
        self.assertTrue(str(repo_path('autos/saturday.json')).endswith('autos/saturday.json'))


if __name__ == '__main__':
    unittest.main()
