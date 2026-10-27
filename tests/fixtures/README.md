# Test fixtures

Frozen copies of robot files that the tests start from, so editing, renaming or deleting the
team's real autons (`autos/`) or hardware maps (`robot-config/`) never breaks a test.

- `autos/saturday.json`: the auton the dashboard e2e test edits and runs in the simulator
  (it parks at 13.2, 96.07 facing 90°). Copied from the real one on 2026-10-25.
- `24621-Biobuzz.xml`: the hardware map the e2e test's simulated robot uses.

Change these only together with the tests that check them.
