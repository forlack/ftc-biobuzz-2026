# Roadmap: change the robot without rebuilding

**Goal:** middle schoolers tune numbers, paths and autonomous routines without Java and
without the 3-minute build. Java stays the place where new *abilities* are written: a new
mechanism, a new autonomous action. Data files and a GUI handle the *tuning and arranging*.

Agreed 2026-10-22. Step 4 changed to our own dashboard the same day.

**Status 2026-10-27:** steps 1 to 4 are done, tested on the robot, and on `main` (the
dashboard replaced Panels; its Auton tab replaced the Pedro visualizer). What is next is under
"Next project ideas" below. See [dashboard/README.md](dashboard/README.md) and the robot
test log in [dashboard/ROBOT-TEST.md](dashboard/ROBOT-TEST.md).

## Principles

- **The robot holds the live files** in `/sdcard/FIRST/`. Git holds the history. Scripts
  copy between them: `pull` after practice, then commit.
- **A bad file never stops the robot.** Each value falls back to its code default with a
  telemetry warning, and the last good save is kept as a backup.
- **Every step is useful on its own.** Stop after any one and nothing is half-built.

## Steps

### 1. `robot.json` + "Save to robot" in Panels: done 2026-10-22
`lib/RobotSettings` saves every `public static` setting of `FieldCentricJava` and
`MecanumDrive` to `/sdcard/FIRST/settings/robot.json` and reads it back at INIT. Tune live in
Panels, turn on `SAVE_TO_ROBOT`, then `./robot-sync pull` and commit.
See [settings/README.md](settings/README.md). (Since step 4 it covers every `@Configurable`
class, and saving is the dashboard Tune tab's **Save to robot**.)

Also done: Pedro AutoTune's web server is out of competition builds (`--tuning` adds it).

### 2. Autonomous as data: drives, steps and functions in one JSON: done
Before this, the paths came from a pasted Pedro export (`paths/auto.java.txt`) and the order
of steps was Java. Now (file version 3, `lib/AutoRoute`):

- **One auton file** (`autos/NAME.json`): where it starts, then the steps in order, drives and
  functions alike. Each drive starts where the step before ended, so nothing needs lining up:
  ```json
  { "version": 3, "name": "Saturday",
    "start": { "x": 56, "y": 9, "heading": 270 },
    "steps": [
      { "action": "wait", "params": { "seconds": 1 } },
      { "drive": { "name": "to shoot", "segments": [ { "to": [60, 16] } ],
                   "heading": { "type": "keep" } } },
      { "action": "shoot", "params": { "seconds": 3.5 } },
      { "drive": { "name": "park",
                   "segments": [ { "via": [[15.7, 29.3]], "to": [13.2, 96.1] } ],
                   "heading": { "type": "turn", "degrees": 90 },
                   "speedPercent": 60,
                   "events": [ { "at": 0.45, "action": "deployStinger" } ] } } ] }
  ```
- **Functions are Java, named once:** `@AutoFunction` methods on the mechanisms (intake on,
  stinger down, ...) and timed ones in `lib/AutoStepFunctions` (`shoot`, `wait`, ...). The
  editor lists exactly what the robot has, so a typo cannot happen.
- **Several autons** (left, right, two-cycle) are several files, picked at INIT.

### 3. A path editor that puts robot functions on the paths: done (as the Auton tab)
*Done differently (2026-10-25): instead of forking the Pedro Pathing visualizer for good, the
dashboard's Auton tab is our own editor; the fork is retired.* What it was meant to do, and
the Auton tab now does:

- Draw drives on the field, then **put functions on them**: on a drive at a point along it
  (stinger down at 45%), or between drives (`shoot 3.5 s`, `wait 0.5 s`).
- **The function list comes from the robot**, with what settings each takes, so the editor
  only offers functions that exist.
- **Saves the step 2 JSON straight to the robot,** not Java text.
- **Preview:** animates the robot through the auton with the waits included, to check timing
  against the 30 s autonomous before going to the field.

### 4. Our own dashboard, served by the robot: done (replaced Panels)
One web page that fits our robot, served from the Robot Controller app, so on robot Wi-Fi
there is nothing to install and nothing to sync: open it, change things, Save, press INIT.

| Feature | How |
|---|---|
| **Telemetry** | Everything already goes through `DriveDashboard.addData`; also stream it to the page 10–20×/s from a background thread, never from the loop |
| **Variables** | `RobotSettings` already finds every setting by reflection; add list/set messages. Typed inputs grouped by mechanism |
| **Save / sync** | Real "Save to robot" button over `robot.json` (replaces the `SAVE_TO_ROBOT` switch), "changed since last pull", restore backup |
| **Charts** | Telemetry numbers over time (flywheel RPM vs. target), one small library such as uPlot |
| **OpMode control** | List, Init, Start, Stop via the SDK's `@OnCreateEventLoop` hook and the OpMode manager. Practice only; the Driver Station rules at matches |
| **Hardware map** | Show and edit the active config (`/sdcard/FIRST/*.xml`): device names, ports, types, as a form instead of XML. Save, then restart the app to apply. Validate names against what the code asks for, so a typo shows before INIT |
| **Field view, paths, autos** | Robot pose on the field; later the step 3 path and action editor lives here too |

- **Plain HTML and JavaScript, no Node:** no build step, packed into the APK, readable by
  students. One vendored chart file.
- **Small HTTP + websocket server** started late through the SDK's own hooks, on its own port
  (not 8001/8002 Panels, 8080 RC, 10158 AutoTune). It catches every error, so it can never
  crash the Robot Controller (the AutoTune lesson).
- Ran alongside Panels until it did everything we used; Panels was then removed (2026-10-23).
- Read FTC Dashboard (acmerobotics, open source) for how it hooks the SDK and controls
  OpModes; build our own smaller version.
- Rough size: 2 evenings for server + telemetry + variables + Save; 2 more for charts,
  OpMode control, field view and hardware map; then load testing on the robot.

## Next project ideas (2026-10-27)

Each is a self-contained project. Priority (2026-10-30): the webcam (auto-aim, then distance
-> flywheel speed), B trigger gating, C alliance mirror, odometry correction from the hive
tags, A timed functions in the Auton tab, the rest of C, E. D (auto-reconnect) is done.

### Next up: webcam auto-aim and distance-based flywheel speed (decided 2026-10-30)

**Built 2026-10-30 (waiting for the webcam to test):** steps 2-5 below. `Alliance.ALLIANCE`
(RED / BLUE, Tune tab, saved; operator D-pad left / right switches it in teleop; first
telemetry line), `mechanisms/Vision` (BIOBUZZ clusters; keeps our color, `HIVE_TARGET`, and up
by `HIVE_UP_MIN_HEIGHT_IN`, 38 in above the tiles; telemetry `Hive` with distance and angle, `Hive ignored` with why; Live
values `Hive range in`, `Hive bearing deg`, `Hive height in`; a `hiveSeen`
condition), and the Live tab's Camera card (`GET /api/camera`). Without "Webcam 1" in the map
everything else runs and the telemetry says so. The simulator has a pretend camera built from
the manual's HIVE geometry (lib/BiobuzzField), running the same hive choice (lib/HiveFinder):
drive with the Gamepad drawer and watch the `Hive` line; Simulator RED_HIVE_UP / BLUE_HIVE_UP
pick which CELL is up. Next: step 6 (auto aim, a student project, below), then 7-8.

**Auto aim (student project).** Goal: while the operator holds a button (Y is free), the
robot turns to face our hive by itself; the driver can still drive. What you have to work with:

- `robot.vision.hive()`: the hive to aim at, or `null` when none is in view. Its `bearing` is
  the angle to the hive in degrees (+ = the hive is to the left), `range` the distance in
  inches across the floor, `height` how high its tags are.
- `robot.vision.seen()`: true when a hive is in view.
- In `FieldCentricJava.updateDriving`, `turn` is the right stick (+ = turn clockwise). Whatever
  you put in `turn` is what the robot does.
- Settings: a `public static` field in a `@Configurable` class shows on the Tune tab and can be
  changed while the robot runs. Telemetry: `Dash.put("name", number)` makes a Live chart.
- Test it in the simulator first (`./simulate`, Field Centric (Java), take gamepad 2 in the
  Gamepad drawer): the simulated camera sees the hive from wherever you drive. Then the robot.

Things to think about: how hard to turn when the hive is far off vs. nearly straight ahead;
what happens when the hive is lost; how you know you are "on target"; and why small turns
might not move the robot at all (look at what `MecanumDrive.drive` does to the stick).

Like last season: turn to face the hive, and pick the flywheel speed from the distance. A USB
webcam with the FTC SDK's AprilTag processor (a HuskyLens was considered and dropped: it only
reports flat boxes in pixels, has to be taught each tag, and has no cluster support).

**What the SDK gives us (SDK 12.0, already installed):** the BIOBUZZ tag library groups the
tags into four clusters of four 3.25" tags (at -6.5, -2.75, +2.75, +6.5 in from the center):
RED SCORING 30-33, RED AUDIENCE 34-37, BLUE AUDIENCE 38-41, BLUE SCORING 42-45. A cluster
detection gives its position relative to the camera: bearing (deg), range (in), height `z`
and elevation, and how much of the cluster was found. It does NOT give the clusters' field
positions (all 0,0,0, because the hive moves), so it is for aiming, not field position, unless
we measure the hive's "up" position ourselves. New-API note: detections are single tags or
clusters (`instanceof AprilTagSingleDetection` / `AprilTagClusterDetection`).

1. **Hardware:** a calibrated USB webcam (Logitech C920 / C270 have built-in SDK calibrations;
   a global-shutter camera blurs less while turning) on a Control Hub USB port, facing the way
   the robot shoots. Driver Station: Configure Robot -> 24621-Biobuzz -> Scan -> name it
   `Webcam 1` -> save; then `./robot-sync pull`. (A webcam is identified by its serial number,
   so it must be plugged in for the scan.)
2. **`Vision` mechanism** (`mechanisms/Vision`, like the others): VisionPortal + AprilTag
   processor with the BIOBUZZ library, 640x480, short exposure (see the SDK's
   `ConceptAprilTagOptimizeExposure`). Finds our alliance's scoring cluster and reports bearing,
   range and height. Live values: `Tag seen`, `Tag bearing deg`, `Tag range in`, `Tag z`,
   `Tag elevation`.
3. **Alliance:** an `ALLIANCE` setting (RED / BLUE) so the robot only aims at its own scoring
   cluster (never the other hive); an auton could set it.
4. **Hive up or down:** from the cluster's height (`z` / elevation) against a Tune threshold
   (`HIVE_UP_MIN_HEIGHT_IN`, height above the tiles; the manual gives ~45 in up, ~30 in down), picked from what the Live tab shows with the hive up and down. If the
   tags are hidden when it is down, "not seen" is enough.
5. **Camera view on the Live tab:** a small picture (about 5 frames a second) with the
   detected tags outlined, for mounting and tuning.
6. **Auto-aim in teleop:** the student project above.
7. **Auto-aim in the auton:** an `aimAtHive` timed step (turn until within a few degrees, with
   a time limit) and an `onTarget` condition; they appear in the Auton tab by themselves.
8. **Distance -> flywheel speed:** a short table on the Tune tab (range in, Pollen RPM, Nectar
   RPM), blended between rows, filled by shooting from marked distances. Used while the tag is
   seen; holds the last speed when it is lost.
9. **Check `Loop ms`:** vision runs on its own thread, but confirm it on the robot.
10. **Later:** fake detections in the simulator (from its pose) to test aiming without the
   robot; correcting the odometry from an "up" hive once its field position is measured.

**Open questions:** which webcam; turn the robot (assumed) or a turret; which cluster the robot
shoots at (scoring vs. audience) and when the hive is up — game rules, the team knows.

### A. Build timed functions in the Auton tab (like `shoot`, without Java)

**The idea (from the team):** build your own timed function the way `shoot` is built, by
dragging steps in: "triggers on, wait for a time, triggers off". The function can have its own
settings (a time, an angle) that an auton fills in when it uses it.

**What exists today** (so the plan builds on it):

- A timed function is a small **sub-sequence**: when the auton reaches `shoot`, its steps run in
  its place, one per loop. Today they are written in Java in `lib/AutoStepFunctions.java`
  (how to add one: `dashboard/DEVELOPER.md`, "A timed function"):
  ```java
  sequence.run("flywheels on", flywheelsOn);
  sequence.waitUntil("wait for flywheels", flywheelsReady, 2);   // 2 s limit
  sequence.run("triggers on", triggersOn);
  sequence.waitSeconds(p.getDouble("seconds"));
  sequence.run("triggers off", triggersOff);
  ```
- Steps use functions **by name** (`robot.action("triggersOn")`), which is why the same
  function runs on the robot and in the simulator. A dashboard-built function would do the same.
- **Conditions** are what "wait until" waits for. A condition is an `@AutoFunction` method on a
  mechanism that returns `boolean`; the robot finds it like any function but hides it from the
  function list (it does nothing by itself). `AutoStepFunctions` looks it up by name
  (`robot.condition("flywheelsReady")`) and gives it to `waitUntil`. Today there is one,
  `flywheelsReady` (both wheels within `VelocityMotor.RPM_TOLERANCE`, 100 RPM, of their
  targets). Writing one:
  ```java
  @AutoFunction(name = "armAtTarget", label = "arm at target", help = "The arm has reached where it was sent.")
  public boolean atTarget() {
      return Math.abs(motor.getCurrentPosition() - target) < 20;
  }
  ```
  Rules: only answer, never move anything; it is called every loop while something waits on
  it, so keep it quick; true means "done". Give it a `label` and `help` so it reads well in the
  "wait until" list. The simulator answers `true` for any condition it does not simulate, so
  the auton never gets stuck there (`flywheelsReady` is simulated for real).

**The editor:** a **Functions** section in the Auton tab, next to the sequence:

```
Function: shoot                                   label: "shoot"
  Settings:  Feed time [3.5] s (0.1 - 15)                       + setting
  Steps:
   1. flywheels on
   2. wait until [flywheels ready v]   give up after [2] s
   3. triggers on
   4. wait [Feed time v] s           <- a step can use the function's own setting
   5. triggers off                                              + step
```

- **Step kinds:**
  - run a function: any instant one (intake on, stinger down, ...), with its own settings;
  - wait N seconds;
  - wait until a condition: a dropdown of the conditions, plus a **required** "give up after
    N s" limit, so a sensor that never says yes cannot freeze the auton. New conditions in the
    code appear in the dropdown by themselves;
  - turn to N degrees.
- **Settings:** declared once per function (name, label, unit, default, min, max). Any number
  box in a step is either a fixed number or "use setting v". An auton using the function shows
  those boxes, as it does for `shoot` now.
- **On a drive or between drives:** like `shoot`, a function can also run part way along a
  drive (the robot keeps driving while it runs), unless it contains "turn to", which only makes
  sense between drives (as `turnTo` and `wait` are today).
- **Used by:** editing a function changes every auton that uses it, so show "used by:
  saturday, Double" next to it. *Open question:* that, or each auton keeps its own copy?
  (Suggested: shared, with "used by".)

**Robot side:**

- **Stored** on the robot, one file per function (e.g. `functions/shoot.json`), shared by every
  auton, with History, snapshots, the lock and `robot-sync` like the other robot files.
- **Read at INIT** and added as step functions exactly like the Java ones, so an auton cannot
  tell the difference. Checked before PLAY like autons are: refuse an unknown function or
  condition, a name that clashes with a code function, and a function that uses itself (or a
  loop of functions using each other).
- **Simulator:** reads the same files, so they work there too.
- **Preview:** waits count their seconds; a "wait until" counts a short guess (about 0.5 s),
  capped by its give-up limit.

**Decisions so far:**

1. Keep the Java `shoot` at first; dashboard functions are extra. Once a dashboard version has
   run on the robot, decide whether to rebuild `shoot` that way and delete the Java one (one way
   to do it).
2. "Used by" vs. copies: open (see above).
3. Build in two steps: (1) robot side plus a basic editor (functions, settings, the four step
   kinds); (2) drag to reorder and polish.

**Implementation notes** (for whoever builds it):

- The robot's file list only allows `settings/robot.json`, `autos/*.json` and `*.xml`
  (`DashboardServer.MANAGED`); `functions/*.json` must be added there, to `robot-sync`'s
  patterns, to snapshots (`managedNames`) and to the Robot tab's file list. Not under
  `autos/`, or it would show up as an auton.
- `/api/actions` (the catalog) does not list conditions today (they are hidden); the editor
  needs them, with their labels, for the "wait until" dropdown.
- Validation on save (PUT) should build the function against the action list, the same way
  auton files are fully built before they are accepted.
- Settings references inside steps need a JSON shape, e.g. `{"param": "seconds"}` in place of
  a number.

### B. Gate the triggers on flywheel speed in teleop

Today the operator's RB runs the triggers whether or not the flywheels are at speed. Seen on the
robot: Nectar dips about 20% after a shot and looked to recover before the next ball, but a gate
would protect a shot when the wheels overshoot or have not quite recovered.

- **Two thresholds (hysteresis)**, so a volley does not stutter: start feeding only within
  `FLYWHEEL_READY_RPM` (e.g. 100) of the targets; keep feeding while within a looser
  `FLYWHEEL_KEEP_RPM` (e.g. 250); a bigger dip pauses the triggers until back within the start
  threshold. Both on the Tune tab, plus a switch to turn gating off live.
- The driver's A + RB force shoot stays ungated (override). Optional: rumble the operator's
  gamepad when the flywheels become ready. The auton's `shoot` could use the same pair.
- **Measure first:** chart `Pollen/Nectar RPM` and their targets on the Live tab during a 3-ball
  volley; read how deep each dip is and how long recovery takes, and compare where balls 2 and
  3 land. Set the keep threshold just under the dip that hurts accuracy. Slow recovery is a
  flywheel tuning question (P / F; the Flywheel Tuner's "time to ready").
- `RPM_TOLERANCE` is a code constant today (not on the Tune tab); the start threshold would
  replace it for the flywheels.

### C. Auton tab extras

- **Alliance mirror:** flip an auton to the other alliance's side instead of drawing it twice
  (agreed).
- **Snapping** points to the field tiles.
- **Drag to reorder** steps in the sequence list.
- **Run in simulator** straight from the Auton tab.

### D. Dashboard reconnects by itself after a Wi-Fi drop: done 2026-10-30

The robot sends its state twice a second; after 3 s of silence (a dropped link often leaves the
socket "open" but quiet) the page drops the connection and opens a new one, and it reconnects
straight away when the computer comes back online. No refresh needed.

### E. Switch hardware maps from the dashboard

"Use this map" switches maps in the simulator, but on the robot it still shows the Driver
Station steps (Configure Robot, pick it, Activate). Doing it from the dashboard would also let
restoring a snapshot switch to its map.

## Risks

- **Two copies of the truth** (robot vs. Git). Make `./robot-sync pull` part of the
  end-of-practice routine, and show the "changed" indicator in step 4.
- **Scope.** Steps 2–4 are weeks of evenings, not one session. Do them in order.
- **Match-day safety.** Validate before saving, keep backups, and never let INIT crash
  because of a file.
