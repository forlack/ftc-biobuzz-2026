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
| **Robot** | The hardware map as a form (checked against the names the code needs), and every robot file to view, edit or download. |

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

## For programmers

- **A new setting:** add a `public static` field to `FieldCentricJava`, `MecanumDrive` or
  `PedroAutonomous`. It appears in the Tune tab and in `robot.json` by itself. Mark live
  switches with `@RobotSettings.NotSaved`.
- **A new autonomous action:** add it to `lib/StandardActions.java`, using `lib/Mechanisms`.
  It appears in the path editor by itself, and works in the simulator once
  `sim/.../SimOpModes.SimMechanisms` does the same thing.
- **A new chart value:** `Dash.put("Name", number)` in the loop; `Dash.frame()` ends each
  loop (DriveDashboard does it for the teleop). The named charts are in `web/js/live.js`.
- **The path editor** is <https://github.com/forlack/Visualizer> (branch `biobuzz`); our
  changes live in `src/lib/robot/`. After changing it run `./build-visualizer`, which builds it
  into `dashboard/web/visualizer/` (committed, so nobody else needs Node).
- **Server API:** see `TeamCode/.../dashboard/DashboardServer.java`.

## Tests

```bash
python3 -m unittest discover -s tests     # scripts, sync logic
node tests/e2e/dashboard.e2e.mjs          # the whole thing in a headless browser vs the simulator
```

The end-to-end test starts its own simulator on port 8687 and a scratch SD card, then checks
47 things: live charts at loop rate, OpMode control, editing and saving a setting, syncing it
into a scratch repo, the hardware map checks, building a route with actions in the path editor, Save as new,
the robot refusing a broken route, and running the route as the autonomous until Pedro parks
the simulated robot. Screenshots go to `tests/e2e/out/`.
