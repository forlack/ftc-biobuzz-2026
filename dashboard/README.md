# Total Chaos Dashboard

Our own robot dashboard: live telemetry and charts, settings you can change and save, the
path editor with robot actions, OpMode control, and the hardware map. It is a plain web page
(`dashboard/web/`, no build step) served by a small Java server that runs **in the Robot
Controller app** or **in the desktop simulator**. Same page, same server code, same files.

| Tab | What it does |
|---|---|
| **Live** | Charts of every loop (flywheel RPM vs target, % error, intake, battery, loop time), the field with the robot's trail, and every telemetry value. Pause, drag to zoom, double click to resume, Export CSV. **+ Add chart** plots any numbers the robot sends (remembered in that browser; ✕ removes). |
| **Tune** | Every setting in `robot.json`, grouped by class, with its code default and whether it is saved. Changes apply on the robot at once. A green dot means the robot runs what robot.json (or the code) says; yellow means changed but not saved yet. **Save to robot** keeps changes and turns every dot green. `live` switches (like `FLYWHEELS_ON`) are never saved. |
| **Paths** | Our fork of the Pedro Pathing visualizer. Draw paths, then build the **Auton Sequence**. The **Sequence** lists every step, with each path's on-path functions under it in the order they fire. **Add to the sequence**: a **Function** (shoot, wait, turn to…, placed after any step) or a **Function on a path** (pick the path, the function and % along; shown as ◆ on the field). **Save** writes `autos/<name>.json`; **Save as new…** makes another auton file. One file holds the whole auton: start, paths and every step. |
| **Robot** | Hardware maps as a form (checked against the names the code needs): pick any map on the robot (the one in use is marked), New map (Control Hub, plus an Expansion Hub if you have one), Save as copy, add or remove the Expansion Hub, rename webcams. **Use this map** switches maps in the simulator; on the robot it gives the Driver Station steps (Configure Robot → pick it → Activate) until switching from the dashboard is tested. Also every robot file to view, edit or download. |

The bar at the top picks an OpMode and runs **Init / Start / Stop**, and shows the battery.

## Try it without the robot: the simulator

```bash
./simulate                     # then open http://localhost:8686 (teleop starts running)
./simulate --fresh             # start over with a clean fake SD card
./simulate --idle              # start with no OpMode (battery and wheels still report)
./simulate --init "Pedro Pathing Autonomous" --start
```

The simulator runs our real code: the dashboard server, `RobotSettings`, the route reader
(`AutoRoute`), `AutoSequence`, our actions, and **Pedro's real follower** with our tuned
`ForesightConstants`. Only the hardware is pretend: flywheels with the hub's velocity PIDF,
battery sag, balls that slow the wheels when shot, and a mecanum chassis on the field.
Its SD card is `sim/sdcard/`, filled from the repo on first start.

`Simulator` settings in the Tune tab switch its pretend driver on or off (`DEMO_DRIVING`,
`DEMO_SHOOTING`, `FEED`).

## On the robot

The dashboard is only in builds made with `--dashboard`, until it has proven itself:

```bash
./deploy-linux --dashboard --stay      # or ./deploy --dashboard --stay on the Mac
# then open http://192.168.43.1:8686   (Panels is still at :8001)
```

It starts 5 seconds after the Robot Controller app, and every error inside it is caught, so a
problem means "no dashboard", never "no robot".

**First time on a robot, or after big changes:** follow [ROBOT-TEST.md](ROBOT-TEST.md), the
same checklist every time, and fill in its log.

### What it costs the robot

The Robot tab's **Load** card shows, every second: viewers, the dashboard's own CPU, the whole
app's CPU (like `top`), Wi-Fi traffic and the OpMode's loop time. To compare, start Field
Centric and run (with other dashboard tabs closed):

```bash
./dashboard-load                 # 0, then 1, then 2 viewers; prints a table
```

If the loop gets slower with viewers, set `DashboardConfig.MAX_SAMPLES_PER_SECOND` on the
Tune tab to 50 (or 25): the charts get at most that many loops a second. 0 sends every loop.
Save to keep it.

Already built in: each batch is prepared once for all viewers, value names are sent once and
then only numbers, and a browser that is not on the Live tab (or is hidden) gets no loop data.

## Keeping the robot and Git in step

The robot holds the live files; Git holds the history.

```bash
./robot-sync status     # what changed where
./robot-sync pull       # robot -> repo (after tuning or editing routes), then commit
./robot-sync push       # repo -> robot (after editing files in the repo)
```

Add `--url http://localhost:8686` to sync with the simulator. A file changed on **both**
sides since the last sync is a conflict and is skipped unless you add `--force`.

| Robot (`/sdcard/FIRST/`) | Repo |
|---|---|
| `settings/robot.json` | `settings/robot.json` |
| `autos/*.json` | `autos/*.json` |
| `*.xml` (hardware maps) | `robot-config/*.xml` |
| `history/`, `snapshots/` | `history/`, `snapshots/` (pull only) |

## Routes (`autos/*.json`)

Written by the path editor, read at INIT by `PedroAutonomous` (and the simulator). The
setting `PedroAutonomous.ROUTE` picks the file; D-pad up/down during INIT picks another.
Without the file, INIT shows what is missing. The format
is documented in `TeamCode/.../lib/AutoRoute.java`. The robot refuses to save a route with an
unknown action or a bad path, so a typo never reaches INIT.

Each route also saves the robot size and safety margin it was drawn with (`"robot":
{width, length, safetyMargin}`, inches); opening the route in the path editor puts them back.
The robot ignores them.

## Adding things

### A setting you can tune (Tune tab)

Add a `public static` (not `final`) number, boolean or String to `FieldCentricJava`,
`MecanumDrive` or `PedroAutonomous`:

```java
public static double ARM_UP = 0.8;     // in FieldCentricJava
```

That is all: it shows on the Tune tab, changes live, and Save writes it to `robot.json`. The
simulator picks it up by itself (its copies of those classes are generated from the real
files at every build).

- A live switch that should not be saved: add `@RobotSettings.NotSaved`.
- A whole new class of settings (say `Arm`): add it to `FieldCentricJava.SETTINGS`, and to the
  `mirrored` list in `sim/build.gradle` for the simulator.
- Renaming a setting makes an old `robot.json` warn about the old name; Save again to clear it.

### A value to watch or chart (Live tab)

In the OpMode's loop:

```java
Dash.put("Arm position", arm.getPosition());   // a number: can be charted
Dash.put("Arm state", "up");                     // text: telemetry table
```

In the teleop, all of this lives in `FieldCentricJava.updateTelemetry()`: `dashboard.addData`
for readable Driver Station / Panels lines, `Dash.put` for plain numbers to chart. Read each
sensor or motor speed **once** there and reuse the value: every read is a call to the hub that
costs loop time. `Dash.frame()` ends each loop (DriveDashboard does it for the teleop). New
names show in the telemetry table and in **+ Add chart** at once; the standard charts are in
`web/js/live.js`.

What the teleop sends (the autonomous and the simulator use the same names): `Intake RPM`,
`Intake target`, `Pollen RPM`, `Pollen target`, `Nectar RPM`, `Nectar target`,
`Flywheels on`, `Flywheels ready`, `Feeding` (1/0), `Stinger` (servo position), `Battery V`,
`Loop ms`, `Speed %`, `Heading`, `Drive forward` / `Drive strafe` / `Drive turn` (sticks,
-1 to 1), and text: `Intake` (ON/OFF/REVERSE), `Settings`.

### A function for the Auton Sequence (Paths tab)

Three small steps, because the same function runs on the robot and in the simulator:

1. **Say what the robot can do**, in `lib/Mechanisms.java`:
   ```java
   void arm(boolean up);
   ```
2. **Do it**, on the robot in `PedroAutonomous.RobotMechanisms`
   (`armServo.setPosition(up ? FieldCentricJava.ARM_UP : FieldCentricJava.ARM_DOWN)`) and in
   the simulator in `sim/.../SimOpModes.SimMechanisms` (an empty method is enough to build).
3. **Name it for the path editor**, in `lib/StandardActions.java`:
   ```java
   .instant("armUp", "arm up", "Raise the arm.", () -> robot.arm(true))
   ```
   `.instant` is a one-shot function: between paths, or **on a path** at a %. `.step` takes
   time (like `shoot` or `wait`) and can have settings such as seconds; steps go only between
   paths.

After a deploy (or restarting `./simulate`) "arm up" is in **Add to the sequence**: the path
editor asks the robot for its list, so the editor itself needs no change. **Function names
are permanent once used**: renaming `armUp` breaks autons saved with it (the robot refuses
them with a clear message). Teleop does not use `Mechanisms`; its buttons call the hardware
directly.

### Other pieces

- **The path editor** is <https://github.com/forlack/Visualizer> (branch `biobuzz`); our
  changes live in `src/lib/robot/`. After changing it run `./build-visualizer`, which builds it
  into `dashboard/web/visualizer/` (committed, so nobody else needs Node).
- **Server API:** see `TeamCode/.../dashboard/DashboardServer.java`; the live stream format is
  in `TelemetryStream.java` (browser side: `web/js/frames.js`).
- **Hardware maps:** `web/js/hardware.js`. The names the code needs (checked before saving)
  are its `REQUIRED` list: keep it in step with the Java code.

## Tests

```bash
python3 -m unittest discover -s tests     # scripts, sync logic
node tests/e2e/dashboard.e2e.mjs          # the whole thing in a headless browser vs the simulator
```

The end-to-end test starts its own simulator on port 8687 and a scratch SD card, then checks
86 things: live charts at loop rate (moving, closing, full size, pause), the compact stream
and its load, OpMode control, editing and saving a setting, history and restore, syncing into
a scratch repo, hardware maps (checks, New map, Expansion Hub, webcams, Use this map),
building a route with actions in the path editor, Save as new, the robot refusing a broken
route, snapshots, the lock, and running the route as the autonomous until Pedro parks the
simulated robot. Screenshots go to `tests/e2e/out/`. It needs Node and Playwright's Chromium
(`npx playwright install chromium`); the simulator alone needs neither.
