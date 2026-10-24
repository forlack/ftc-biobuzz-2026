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
| Load saved values at INIT | `new RobotSettings().load();` | — |
| Show a value to drivers and chart it | `telemetry.addData("Name", value)` | Driver Station + Live tab |
| Show it formatted, still chartable | `telemetry.addData("Battery", "%.2f V", volts)` | Driver Station shows `12.35 V`, chart gets the number |
| Chart a value drivers don't need | `Dash.put("Name", value)` | Live tab only |
| A heading or instruction line | `telemetry.addLine("--- SHOOTERS ---")` | Driver Station only |
| Draw the robot on the field | `Dash.pose(x, y, headingRadians)` (Pedro inches) | Live tab field |
| End the loop | `telemetry.update()` (with the wrapper it also ends the dashboard frame) | — |
| Add an autonomous function | see [below](#an-autonomous-function) | Paths tab |

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
  (`FieldCentricJava.updateTelemetry()` is the example).
- **Call `telemetry.update()` once per loop.** Each call is one dashboard frame.
- **Slow reads** (battery voltage) only a few times a second. The teleop shows how.
- **Nobody watching costs almost nothing**: values only go into a small buffer.
- **See the cost**: the Live toolbar and the `Dashboard ms` chart series show how much of each
  loop is spent in the dashboard (`Dash.put`, `Dash.frame`); it is measured, not guessed.

## An autonomous function

Three steps, so one function runs on the robot and in the simulator:

1. In `lib/Mechanisms.java`, as a `default` method that does nothing (the simulator then needs
   no change): `default void arm(boolean up) { }`
2. In `PedroAutonomous.RobotMechanisms`, do the real thing:
   `@Override public void arm(boolean up) { armServo.setPosition(up ? Arm.UP : Arm.DOWN); }`
3. In `lib/StandardActions.java`, name it for the path editor:
   `.instant("armUp", "arm up", "Raise the arm.", () -> robot.arm(true))`
   (`.instant` = one shot, can go on a path at a %; `.step` = takes time, has settings like
   seconds, only between paths).

After a deploy (or restarting `./simulate`) it is in **Add to the sequence**. Names are
permanent once an auton uses them: the robot refuses an auton naming a function it does not
have.

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
