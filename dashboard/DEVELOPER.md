# Total Chaos Dashboard: developer guide

How to make your code show up on the dashboard. If you have used **FTC Dashboard**
(acmerobotics) or **Panels**, it works the same way: one annotation for settings, one line to
wrap telemetry.

## Quick start

```java
@Configurable                                    // 1. its public static fields become settings
@TeleOp(name = "My TeleOp")
public class MyTeleOp extends LinearOpMode {
    public static double ARM_UP = 0.8;           //    shown on the Tune tab, saved in robot.json

    @Override
    public void runOpMode() {
        new RobotSettings().load();              // 2. use the values saved in robot.json
        telemetry = new DashboardTelemetry(telemetry);   // 3. telemetry also goes to the dashboard
        waitForStart();
        while (opModeIsActive()) {
            telemetry.addData("Arm position", arm.getPosition());   // Driver Station + chart
            telemetry.update();                                   // once per loop
        }
    }
}
```

Imports: `org.firstinspires.ftc.teamcode.dashboard.Configurable`,
`org.firstinspires.ftc.teamcode.lib.RobotSettings`,
`org.firstinspires.ftc.teamcode.lib.DashboardTelemetry`.

## Cheat sheet

| I want to… | Write | Where it shows |
|---|---|---|
| Tune a number live and save it | `@Configurable` on the class, `public static double X = 1;` | Tune tab, `robot.json` |
| A live switch that is never saved | `@RobotSettings.NotSaved` on the field | Tune tab |
| Settings only needed sometimes (a tuner) | `@Configurable.Hidden` on the class as well | Tune tab, under **Hidden** until you click Show |
| Load saved values at INIT | `new RobotSettings().load();` | — |
| Show a value to drivers and chart it | `telemetry.addData("Name", value)` | Driver Station + Live tab |
| Show it formatted, still chartable | `telemetry.addData("Battery", "%.2f V", volts)` | Driver Station shows `12.35 V`, chart gets the number |
| Chart a value drivers don't need | `Dash.put("Name", value)` | Live tab only |
| A heading or instruction line | `telemetry.addLine("--- SHOOTERS ---")` | Driver Station only |
| Draw the robot on the field | `Dash.pose(x, y, headingRadians)` (Pedro inches) | Live tab field |
| End the loop | `telemetry.update()` (with the wrapper it also ends the dashboard frame) | — |
| Add a mechanism or an auton function | see [below](#a-mechanism-teleop-auton-and-dashboard-at-once) | Tune tab, Paths tab |
| Let the Gamepad drawer drive (practice) | `drive = DashboardGamepads.combine(gamepad1, 1);` once per loop, then read only `drive` | the Driver Station gamepad plus the drawer's |

**Numbers and booleans** can be charted (**+ Add chart** on the Live tab); text goes to the
telemetry table. Use the **same name** for the same thing everywhere (`Pollen RPM` in teleop,
auton and simulator), so one chart works for all of them.

## Coming from FTC Dashboard or Panels

| FTC Dashboard | Panels | Total Chaos Dashboard |
|---|---|---|
| `@Config` | `@Configurable` | `@Configurable` (ours: `teamcode.dashboard.Configurable`) |
| `telemetry = new MultipleTelemetry(telemetry, FtcDashboard.getInstance().getTelemetry())` | `PanelsTelemetry.INSTANCE.getTelemetry()` … `update(telemetry)` | `telemetry = new DashboardTelemetry(telemetry)` |
| `TelemetryPacket.put("x", v)` | `panels.addData("x", v)` | `Dash.put("x", v)` (dashboard only) |
| `packet.fieldOverlay()` | `PanelsField` | `Dash.pose(x, y, heading)` |
| (gamepad in the browser) | `PanelsGamepad…asCombinedFTCGamepad(gamepad1)` | `DashboardGamepads.combine(gamepad1, 1)` |
| Values reset on restart | Values reset on restart | **Save** keeps them in `robot.json`, with history |

What is different on purpose:

- **Settings are saved.** Tune live, press **Save to robot**, and the values survive restarts
  and go into Git with `./robot-sync pull`. Every save is kept in History.
- **Every loop is charted**, not a sample, so short spikes show.
- **The same code runs in the desktop simulator** (`./simulate`), so most of this can be tried
  without the robot.

## Rules that keep the robot fast

- **Read each sensor or motor once per loop** and reuse the number. Every hardware read is a
  call to the hub that costs loop time; `telemetry.addData("RPM", motor.getRPM())` in three
  places is three reads. Put telemetry in one method at the end of the loop
  (each mechanism's `telemetry()`, called from `Robot.telemetry()`, is the example).
- **Call `telemetry.update()` once per loop.** Each call is one dashboard frame.
- **Slow reads** (battery voltage) only a few times a second. The teleop shows how.
- **Nobody watching costs almost nothing**: values only go into a small buffer.
- **See the cost**: the Live toolbar and the `Dashboard ms` chart series show how much of each
  loop is spent in the dashboard (`Dash.put`, `Dash.frame`); it is measured, not guessed.

## A mechanism (teleop, auton and dashboard at once)

Each mechanism is one class in `mechanisms/`: its hardware, its settings, its auton functions,
what it does every loop, and its telemetry. `mechanisms/Robot` makes them all; the teleop and
the autonomous both use that same `Robot`. An arm:

```java
package org.firstinspires.ftc.teamcode.mechanisms;

@Configurable                                   // UP / DOWN on the Tune tab, saved in robot.json
public class Arm {
    public static double UP = 0.8, DOWN = 0.2;

    private final Servo servo;
    private boolean up;

    public Arm(HardwareMap hardwareMap) {
        servo = hardwareMap.get(Servo.class, "arm");   // the name in the robot configuration
    }

    @AutoFunction(name = "armUp", label = "arm up", help = "Raise the arm.")
    public void up() { up = true; }

    @AutoFunction(name = "armDown", label = "arm down", help = "Lower the arm.")
    public void down() { up = false; }

    public void update() { servo.setPosition(up ? UP : DOWN); }       // once per loop

    public void telemetry(Telemetry telemetry) { telemetry.addData("Arm", up ? "UP" : "DOWN"); }
}
```

Then:

1. `mechanisms/Robot.java`: `public final Arm arm;`, `arm = new Arm(hardwareMap);` in the
   constructor, and `arm.update()` / `arm.telemetry(telemetry)` in `update()` / `telemetry()`.
2. Teleop (`FieldCentricJava`): a button, `if (armButton.pressed()) robot.arm.up();`, and
   `robot.arm.update()` in the loop.
3. `REQUIRED` in `dashboard/web/js/hardware.js`: add `arm`, so the Robot tab warns when the
   configuration lacks it.

After a deploy (or restarting `./simulate`): `Arm` is on the Tune tab, and **arm up** / **arm
down** are in the Auton Sequence's **Add to the sequence** (also as functions on a path). No
other file changes; the simulator lists them too (they do nothing there until someone
simulates an arm in `sim/SimOpModes.SimMechanisms` with the same names).

Rules for `@AutoFunction` (details in `lib/AutoFunction.java`):

- Public method on a mechanism that is a `public final` field of `Robot`.
- An action returns `void` and only sets what happens next; `update()` does the work. That way
  it can also fire part way along a path.
- Settings are `double` parameters marked `@AutoFunction.Param(name, label, unit, value, min, max)`;
  the editor shows a box for each.
- A `boolean` method is a condition (like `flywheelsReady`): not offered in the editor, but
  steps can wait for it. `listed = false` hides an action the same way (`feedOn`).
- The name is permanent once an auton uses it: the robot refuses an auton naming a function it
  does not have.

Functions that take time and combine others (`shoot`: spin up, wait until ready, feed for N
seconds) are written in `lib/StandardActions.java` with the function names.

Settings saved before a class existed: when settings move from one class to another with the
same names, `@RobotSettings.MovedFrom("OldClass")` keeps older robot.json files working (that is
how the intake, flywheel and stinger settings left `FieldCentricJava`).

## How it works (for the curious)

- **Settings:** `TeamCode/build.gradle` lists every class with a line `@Configurable` when the
  app is built (`generated/.../ConfigurableClasses.java`); `RobotSettings` reads that list. No
  scanning on the robot. The simulator's build makes the same list from the same sources.
- **Telemetry:** `DashboardTelemetry` implements the SDK's `Telemetry` and passes everything to
  the Driver Station unchanged; `addData` also calls `Dash.put`, and `update` calls
  `Dash.frame`. `Dash` keeps the last 1,000 loops in RAM (~2 MB); the server streams them to browsers on
  the Live tab (built once for all viewers, names sent once, then numbers).
- **Server:** plain Java, port 8686, in the Robot Controller app and in the simulator.
  API list at the top of `TeamCode/.../dashboard/DashboardServer.java`.
- **Path editor:** our fork of the Pedro visualizer, <https://github.com/forlack/Visualizer>
  (branch `biobuzz`), built into `dashboard/web/visualizer/` by `./build-visualizer`.
- **Tests:** `node tests/e2e/dashboard.e2e.mjs` drives the whole dashboard in a headless
  browser against the simulator.
