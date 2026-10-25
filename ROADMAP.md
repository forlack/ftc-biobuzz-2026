# Roadmap: change the robot without rebuilding

**Goal:** middle schoolers tune numbers, paths and autonomous routines without Java and
without the 3-minute build. Java stays the place where new *abilities* are written: a new
mechanism, a new autonomous action. Data files and a GUI handle the *tuning and arranging*.

Agreed 2026-10-22. Step 4 changed to our own dashboard the same day.

**Status 2026-10-25:** steps 2, 3 and 4 are built, tested on the robot, and merged into `main`
(the dashboard replaced Panels). See [dashboard/README.md](dashboard/README.md) and the robot
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
See [settings/README.md](settings/README.md).

Also done: Pedro AutoTune's web server is out of competition builds (`--tuning` adds it).

### 2. Autonomous as data: paths, steps and actions in one JSON
Before this, the paths came from a pasted Pedro export (`paths/auto.java.txt`) and the order
of steps was Java. Now:

- **One route file:** poses, paths, and the steps between and along them:
  ```json
  { "version": 2,
    "start": "start",
    "steps": [
      { "follow": "path1", "events": [ { "at": 0.5, "do": "flywheelsOn" } ] },
      { "do": "shoot", "seconds": 3.5 },
      { "wait": 0.5 },
      { "follow": "park" } ] }
  ```
- **Actions are Java, named once.** A small registry (`shoot`, `intakeOn`, `stingerDown`,
  `aimAt`, ...) maps each name to robot code. `lib/AutoSequence` already runs
  `follow` / `run` / `waitSeconds` / `at(fraction, ...)`, so the JSON just builds a sequence.
  A new action is a short Java lesson whose payoff shows up in the editor straight away.
- **Several autos** (left, right, two-cycle) become several files, picked at INIT.

### 3. Our own Pedro visualizer: draw paths and put robot actions on them
Fork the Pedro Pathing visualizer and add what `AutoSequence` can do:

- Draw paths as now, then **drop actions onto the field**: on a path at a point along it
  (`at 0.5: flywheelsOn`), or between paths (`shoot 3.5 s`, `wait 0.5 s`).
- **The action palette comes from the robot.** The robot code publishes its action list
  (names, plus what settings each takes, such as seconds), so the editor only offers actions
  that exist, and a typo cannot happen.
- **Output the step 2 JSON directly,** not Java text.
- **Preview:** animate the robot along the route with the waits included, to check timing
  against the 30 s autonomous before going to the field.

Built into the dashboard's Paths tab; it saves straight to the robot.

### 4. Our own dashboard, served by the robot (replaces Panels over time)
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
- **Run it alongside Panels** until it is better at everything we use, then drop Panels.
- Read FTC Dashboard (acmerobotics, open source) for how it hooks the SDK and controls
  OpModes; build our own smaller version.
- Rough size: 2 evenings for server + telemetry + variables + Save; 2 more for charts,
  OpMode control, field view and hardware map; then load testing on the robot.

## Risks

- **Two copies of the truth** (robot vs. Git). Make `./robot-sync pull` part of the
  end-of-practice routine, and show the "changed" indicator in step 4.
- **Scope.** Steps 2–4 are weeks of evenings, not one session. Do them in order.
- **Match-day safety.** Validate before saving, keep backups, and never let INIT crash
  because of a file.
