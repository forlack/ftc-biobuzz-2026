# Testing the dashboard on the real robot

A repeatable checklist for putting the dashboard build on the robot after big changes, checking
it, and going back to a known good version if anything is wrong. Run it the same way every time
and fill in a row of the log at the bottom, so we can compare runs. (The dashboard was built on
branch `dashboard` and merged into `main` on 2026-10-25.)

Works from the Mac (`./deploy`) or the Linux box (`./deploy-linux`); the flags are the same.

## 1. Before you leave real internet

```bash
git fetch && git checkout main && git pull
./gradlew :TeamCode:assembleDebug     # must say BUILD SUCCESSFUL
```

The build must work once on real internet, or the `--offline` build during deploy fails
(the robot's Wi-Fi has no internet).

Optional, no robot needed: `./simulate`, then open <http://localhost:8686>. On a Mac without
Java on the PATH, use Android Studio's:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

## 2. Back up the robot first

On the robot's Wi-Fi (or USB):

```bash
adb pull /sdcard/FIRST ~/ftc/backups/$(date +%Y%m%d-%H%M%S)-before-dashboard
```

## 3. Deploy

```bash
./deploy --stay          # Mac;   Linux: ./deploy-linux --stay   (the dashboard is in every build)
```

`--stay` keeps the laptop on the robot's Wi-Fi so you can test. Deploy also uploads autons
in `autos/` that the robot does not have yet (it never replaces one already there). Wait about
10 seconds after the app restarts.

## 4. Checks

Tick each one; write down anything that fails.

| # | Check | Pass when |
|---|---|---|
| 1 | Driver Station | Connects, OpModes listed, Field Centric drives as before |
| 2 | No Panels | <http://192.168.43.1:8001> does **not** load (Panels was removed); the Driver Station telemetry looks as before |
| 3 | Dashboard | <http://192.168.43.1:8686> loads, says **Total Chaos 24621**, no SIMULATOR badge, top right **live** |
| 4 | Tune tab | Groups Flywheels, Intake, MecanumDrive, PedroAutonomous, Stinger, DashboardConfig with green dots (no FieldCentricJava group: its settings moved to the mechanisms, and values saved under it before still load: check FLYWHEEL_F, STINGER_DOWN and the RPMs are your tuned ones, then **Save to robot** once); **FlywheelTuner under Hidden** at the bottom (Show / Hide work); **no Simulator group**; no "No settings found" warning on the Driver Station |
| 5 | Robot tab | Map **24621-Biobuzz (in use)**, no red problems |
| 6 | OpMode bar | Init then Start **Field Centric (Java)** from the dashboard; Stop stops it |
| 7 | Live tab | Flywheel, intake, battery and loop charts move; telemetry table fills. **+ Add chart** offers `Flywheels ready`, `Feeding`, `Drive forward`…; toggling flywheels / holding feed shows on them. Driver Station lines read cleanly (one line per RPM with % off). (The field stays empty in teleop: teleop does not send its position yet.) |
| 7b | Flywheel F | Telemetry shows **Flywheel compensated F** between 11 and 12 (11 on a full battery, rising as it drops). Tune tab: `FLYWHEEL_F` is **11** and `STINGER_DOWN` **0.42** (if the robot's saved robot.json still says 12 / 0.45, those win: set them, Save. A settings warning about `FLYWHEEL_FIXED_F` is expected once: that setting is gone, Save clears it) |
| 7c | Loop time | Live tab, **+ Add chart**: `Loop ms` and the four `Time … ms` values. Note `Loop ms` (before bulk reads it was **23.4 ms**) and which `Time` is biggest. With a browser open there should be **no spikes every 0.5 s** any more (they were the dashboard reading the battery) |
| 7d | Intake switch | Tune tab: `INTAKE_ON` off stops the intake, on starts it; operator **X** flips the same switch (the Tune tab follows) |
| 8 | Tune live | Change `INTAKE_RPM` while running: the intake changes. **Save to robot**: dots go green; History shows the save |
| 9 | Dashboard cost | Optional, any time: how much the dashboard slows the robot's loop when people watch. Quick look: the Live toolbar's `Loop … ms · dashboard +… ms` (pass: dashboard under 0.5 ms). Full measurement: with Field Centric running and no other dashboard tabs open, run the commands below; pass: **in Dash** under 0.5 ms and **+ loop** under 1 ms |
| 10 | Auton | Paths tab: open `saturday` from the robot (start **56, 9, 270°**; it ends facing 90°), Save. On the Driver Station run **Pedro Pathing Autonomous** (ROUTE saturday): it drives the route and shoots |
| 11 | Leaving | Close the browser in the middle of teleop: the robot keeps running normally |
| 12 | Sync | `./robot-sync pull`, then commit what changed |
| 13 | Flywheel Tuner | Wheels clear. Tune tab: **Show** FlywheelTuner if you want to change the cycle. Run **Flywheel Tuner** (Diagnostics): the wheels cycle 70% → target until STOP. On the Tune tab change P (try 25, 50, 100…), then I, then D, watching **time to ready** (avg of 5) and overshoot on the Driver Station or Live tab. **Save to robot** the best; confirm with real shots |
| 14 | Gamepad drawer | Robot on blocks first. Field Centric running. Open the **Gamepad** drawer (G): pressing buttons on the **Driver Station** gamepad lights them in the picture. **Take control** of gamepad 1, drag the left stick gently: the wheels turn; let go: they stop. Gamepad 2: **A** toggles the flywheels. Close the laptop lid (or turn off its Wi-Fi) while holding a stick: the wheels stop within a moment. Stop the OpMode: the drawer says control ended. Loop time (check 9) about the same while driving |
| 15 | Restart adb | Once, while adb works (right after a reboot): `adb shell pm grant com.qualcomm.ftcrobotcontroller android.permission.WRITE_SECURE_SETTINGS`. Robot tab, **adb** card: says **Listening**, Restart adb is enabled. Next time adb sticks: note what the card says (Listening / Not listening), press **Restart adb**, then `adb disconnect; adb connect 192.168.43.1:5555` and `adb devices`. Write down whether it worked; if not, power-cycle as before |
| 16 | Path speed | Paths tab: pencil on a **follow** step, set a speed (say 40%), Save, run the auton: that path is visibly slower |
| 17 | Mechanisms refactor | Teleop: every button as before (intake X / D-pad down, flywheels A, gates RB, stinger LB, force shoot A+RB), Driver Station lines as before, loop time about the same. Auton: `saturday` runs exactly as before (intake and flywheels on, shoot, stinger down on path2, park). Paths tab, Add to the sequence: same functions as before |
| 18 | Tune and files | Turn `INTAKE_ON` off, Save to robot, Stop, Init again: it is back on. Change `DRIVE_SPEED` while driving: the speed changes at once (your old INITIAL_SPEED carried over). `INTAKE_I` is there. Change a value: ↶ puts back the saved one. Robot tab: **Delete** an auton you do not want; robot.json and the map in use have no Delete |

Load measurement for check 9 (Field Centric running, robot on blocks or driven gently):

```bash
./dashboard-load --seconds 30
./dashboard-load --seconds 30        # twice: differences under ~0.2 ms are noise
```

Also note the app's memory limit (once per hub):
`adb shell getprop dalvik.vm.heapgrowthlimit` — the dashboard's telemetry buffer uses ~2 MB of it.

What the columns mean: **loop** = OpMode loop time; **in Dash** = ms per loop inside the
dashboard's own code (expect well under 0.1 ms); **+ loop** = how much slower loops get with
viewers (everything the dashboard does, including its send thread). CPU is only for diagnosing:
dashboard CPU under ~5% of one core is as expected, over 20% means something is wrong.

Copy both tables into the log. If **+ loop** is 1 ms or more: Tune tab →
`DashboardConfig.MAX_SAMPLES_PER_SECOND` = 50, Save, measure again; then 25.

## 5. If something is wrong: back to a known good version

```bash
git log --oneline            # pick the last version that worked, e.g. 3d9495f (competition, with Panels)
git checkout <commit>
./deploy --stay              # Linux: ./deploy-linux --stay
git checkout main            # afterwards, back to the current code
```

The robot's files (settings, autons, hardware map) stay where they are. Versions before
2026-10-25 use Panels and read their auton from `paths/` instead of `autos/`.

## 6. Afterwards

Fill in the log and commit it:

```bash
git add dashboard/ROBOT-TEST.md && git commit -m "Robot test log" && git push
```

## Log

| Date | Who | Branch commit | Checks failed | Load: loop, in Dash, + loop (ms), KB/s, 0/1/2 viewers | MAX_SAMPLES | Notes |
|---|---|---|---|---|---|---|
| 2026-10-24 | parent (Linux) | dashboard after comp merge | none blocking | loop 23.4 ms, in Dash 0.28 ms; dashboard 1.8% of a core, app 86% | 0 | First robot run: dashboard, Tune, Live, auton OK. Loop spikes ~60 ms every 0.5 s (not the dashboard; logcat showed no frequent GC; cause open). Automatic flywheel tuner replaced by the hand-tuning loop |
| 2026-10-25 | parent (Linux, Mac) | dashboard 9ee5c52 → main a56f46e | none | not re-measured | 0 | Passed: 3, 4, 6, 7, 7c, 7d (INTAKE_ON follows operator X), 8, 10, 12, 14 (drawer; Linux Firefox with Logitech in X mode, Mac Chrome in D mode), 15 (Restart adb works), 16 (path speed), 17 (autons after refactor), 18 (live switches reset at INIT, DRIVE_SPEED live, INTAKE_I, undo, Delete). 13 (hand flywheel tuner) also done. Not yet run: 2, 9 with ./dashboard-load (optional), 11 |
