# Total Chaos Dashboard

Our own robot dashboard: live telemetry and charts, settings you can change and save, the
path editor with robot actions, OpMode control, and the hardware map. It is a plain web page
(`dashboard/web/`, no build step) served by a small Java server that runs **in the Robot
Controller app** or **in the desktop simulator**. Same page, same server code, same files.

| Tab | What it does |
|---|---|
| **Live** | Charts of every loop (flywheel RPM vs target, % error, intake, battery, loop time), the field with the robot's trail, and every telemetry value. Pause, drag to zoom, double click to resume, Export CSV. **+ Add chart** plots any numbers the robot sends (remembered in that browser; ✕ removes). The toolbar shows the OpMode loop time and how much of it the dashboard's own code takes (`Loop 10.1 ms · dashboard +0.01 ms · 17 KB/s`); `Dashboard ms` is also on the Battery & loop chart. |
| **Tune** | Every setting in `robot.json`, grouped by class, with its code default and whether it is saved. Changes apply on the robot at once. A green dot means the robot runs what robot.json (or the code) says; yellow means changed but not saved yet. **Save to robot** keeps changes and turns every dot green. `live` switches (like `FLYWHEELS_ON`) are never saved. |
| **Paths** | Our fork of the Pedro Pathing visualizer. The side panel has two tabs: **Paths** (start point and the selected path's settings) and **Auton Sequence**. Draw paths, then build the sequence. The **Sequence** lists every step, with each path's on-path functions under it in the order they fire. **Add to the sequence**: a **Function** (shoot, wait, turn to…, placed after any step) or a **Function on a path** (pick the path, the function and % along; shown as ◆ on the field). The pencil on a **follow** step sets that path's **top speed** as a % of the robot's calibrated top speed (empty = 100%). **Save** writes `autos/<name>.json`; **Save as new…** makes another auton file. One file holds the whole auton: start, paths and every step. |
| **Robot** | Hardware maps as a form (checked against the names the code needs): pick any map on the robot (the one in use is marked), New map (Control Hub, plus an Expansion Hub if you have one), Save as copy, add or remove the Expansion Hub, rename webcams. **Use this map** switches maps in the simulator; on the robot it gives the Driver Station steps (Configure Robot → pick it → Activate) until switching from the dashboard is tested. Also every robot file to view, edit or download, and an **adb** card: whether adb on the hub is answering, and **Restart adb** (turns it off and on, instead of a power cycle; needs a one-time `adb shell pm grant com.qualcomm.ftcrobotcontroller android.permission.WRITE_SECURE_SETTINGS`). |

The bar at the top picks an OpMode and runs **Init / Start / Stop**, and shows the battery.

**Gamepad drawer** (bottom of every tab; the **Gamepad** handle, or press **G**): a virtual
gamepad 1 or 2 for practice. Drag the sticks and click the buttons (right-click holds a
button down), or plug a controller into the laptop (the handle lights up when one is
detected). Closed or not driving, it shows what the robot gets on that gamepad (the Driver
Station's). **Take control** to drive; a green **Driving** pill in the top bar releases it.
The robot adds it to the Driver Station gamepad (stronger stick, either button), so the
Driver Station keeps working. Safety: one browser per gamepad; inputs older than 0.25 s
count as all zero; control ends when the OpMode stops, the tab is hidden, the browser leaves
or the dashboard is locked. **Not allowed in matches.** In code:
`drive = DashboardGamepads.combine(gamepad1, 1);` (Field Centric does this).

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
`DEMO_SHOOTING`, `FEED`). Drive it yourself with the **Gamepad drawer**: gamepad 1 and 2
work like on the robot (same buttons as Field Centric), and the pretend driver stops while you
drive.

## On the robot

On this branch the dashboard is in every build, and Panels is gone (it replaces Panels):

```bash
./deploy-linux --stay      # or ./deploy --stay on the Mac
# then open http://192.168.43.1:8686
```

It starts 5 seconds after the Robot Controller app, and every error inside it is caught, so a
problem means "no dashboard", never "no robot".

**First time on a robot, or after big changes:** follow [ROBOT-TEST.md](ROBOT-TEST.md), the
same checklist every time, and fill in its log.

### What it costs the robot

What matters is the **loop time**. Every loop, `Dash` times its own work (each `put`, `pose`
and `frame`, including waiting for the server) and sends it as **`Dashboard ms`**, next to the
OpMode's `Loop ms`. The sending to browsers runs on its own thread, so it can only slow the loop
indirectly (sharing the CPU); `./dashboard-load` measures that by comparing loop times with 0,
1 and 2 viewers (**+ loop**).

The Robot tab's **Load** card shows, every second: viewers, the loop time, the dashboard's ms
per loop, Wi-Fi traffic, and for diagnosing, the dashboard's and the whole app's CPU (% of one
core; the app is everything in the Robot Controller app: OpMode, SDK, Driver Station link,
dashboard). To compare, start Field Centric and run (with other dashboard tabs closed):

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

**Read [DEVELOPER.md](DEVELOPER.md)**: quick start, a cheat sheet, and how it maps to FTC
Dashboard and Panels. The short version:

- **A setting:** `public static double ARM_UP = 0.8;` in a class marked `@Configurable`
  (`teamcode.dashboard.Configurable`). It shows on the Tune tab and saves in `robot.json`;
  the robot and the simulator both find it.
- **Telemetry:** wrap once, `telemetry = new DashboardTelemetry(telemetry);`, then
  `telemetry.addData(...)` reaches the Driver Station and the dashboard (numbers chart).
  `Dash.put(...)` for dashboard-only values. Read each sensor once per loop.
- **A mechanism or an auton function:** one class in `mechanisms/` (hardware, settings,
  `@AutoFunction` methods, `update()`, `telemetry()`), plus a line in `mechanisms/Robot`.
  Teleop and auton both use it; its functions appear in the Auton Sequence by themselves.

What the teleop sends (the autonomous and the simulator use the same names): `Intake RPM`,
`Intake target`, `Pollen RPM`, `Pollen target`, `Nectar RPM`, `Nectar target`,
`Flywheels on`, `Flywheels ready`, `Feeding` (1/0), `Stinger` (servo position), `Battery V`,
`Flywheel compensated F`, `Loop ms`, `Dashboard ms`, `Speed %`, `Heading`, `Drive forward` /
`Drive strafe` / `Drive turn` (sticks, -1 to 1), where each loop's time goes (`Time drive ms`
(includes the IMU read), `Time intake+servos ms`, `Time shooter ms`, `Time sensors+telemetry ms`
(includes the bulk reads); the rest of `Loop ms` is the Driver Station telemetry and the SDK),
and text: `Intake` (ON/OFF/REVERSE/DISABLED), `Flywheels`, `Settings`.

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
91 things: live charts at loop rate (moving, closing, full size, pause), the compact stream
and its load, OpMode control, editing and saving a setting, history and restore, syncing into
a scratch repo, hardware maps (checks, New map, Expansion Hub, webcams, Use this map),
building a route with actions in the path editor, Save as new, the robot refusing a broken
route, snapshots, the lock, and running the route as the autonomous until Pedro parks the
simulated robot. Screenshots go to `tests/e2e/out/`. It needs Node and Playwright's Chromium
(`npx playwright install chromium`); the simulator alone needs neither.
