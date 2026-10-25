# TeamCode — BioBuzz 24620

You are in the right place. **All of our robot code lives in this folder.** The sibling
`FtcRobotController` module is FIRST's SDK — read it, don't edit it.

## Start here

Open **`FieldCentricJava.java`**. It is our real teleop, and the steps below add to it.
Driving already works, so you can go straight to whatever you are adding.

## What is in this folder

| File | What it is |
|---|---|
| `FieldCentricJava.java` | Our real teleop. A worked example of everything below. |
| `PedroAutonomous.java` | Runs an auton made in the dashboard's Paths tab. |
| `mechanisms/` | One class per mechanism (intake, flywheels, feeder, stinger) and `Robot`, which makes them all. Teleop and auton both use them. |
| `helpers/` | Checks to run when something seems wrong: `MotorsTest` (each motor turns the right way) and `LocalizationCheck` (the Pinpoint reads the right way). |
| `lib/` | The framework. Read it if you are curious; you rarely need to change it. |
| `pedro/` | Pedro Pathing config and its AutoTune procedures. |

## How a teleop works

One loop that runs 50–200 times a second. Each method does a little work and returns:

```java
while (opModeIsActive()) {
    readGamepad();
    updateDriving();
    robot.arm.update();   // ← yours (a mechanism)
    dashboard.update();
}
```

**The one rule: never wait inside the loop.** No `sleep()`, no `while (motor.isBusy())`. The
Robot Controller stops the robot if the loop stops going around, and the gamepad only updates
between times through. For something with steps — raise arm, pause, open claw — use a state
machine that does one step per loop. `PedroAutonomous.java` shows how.

## Adding your own part of the robot

A new mechanism (an arm, say) is **one class in `mechanisms/`**, and then the teleop, the
autonomous and the dashboard can all use it:

1. `mechanisms/Arm.java`: get the hardware in the constructor (the name must match the robot
   config), settings as `public static` fields (they show on the Tune tab), methods like
   `up()` / `down()`, an `update()` that moves the hardware once per loop, and `telemetry()`.
   Put `@AutoFunction(name = "armUp", label = "arm up")` on a method and the auton sequence
   can use it.
2. `mechanisms/Robot.java`: one field `public final Arm arm;`, made in the constructor, and its
   `update()` / `telemetry()` calls.
3. The teleop: bind a button with the others, and use it in the loop:
   ```java
   private final Button armUp = buttons.add(() -> operate.dpad_up);
   ...
   if (armUp.pressed()) robot.arm.up();
   robot.arm.update();
   ```

The whole worked example, with the rules: `dashboard/DEVELOPER.md` ("A mechanism").

## Buttons

| | Means | Use for |
|---|---|---|
| `down()` | held right now | hold-to-do-something |
| `pressed()` | just pushed down | toggles, one-shot actions |
| `released()` | just let go | rarely |

Use `pressed()` for anything that switches something on or off. A button press lasts about 30
trips through the loop, so `down()` would run your action 30 times.

## Telemetry

`telemetry.addData("label", value)` puts a value on the screen — it goes to the Driver Station
and the Total Chaos Dashboard, where numbers can be charted (the OpMode wraps `telemetry` once:
`telemetry = new DashboardTelemetry(telemetry)`). It must come **before** `dashboard.update()`,
which sends everything and empties the list. More: `dashboard/DEVELOPER.md`.

Show `down()`, not `pressed()`. `pressed()` is only true for one loop out of thirty, so the
screen would almost always catch it as false and look broken.

## Sending code to the robot

Over USB:

```bash
cd ~/ftc/FtcRobotController
./gradlew :TeamCode:installDebug
adb forward tcp:8686 tcp:8686    # dashboard, at http://localhost:8686 (drops on every install)
```

On the robot's Wi-Fi, no forwarding is needed — the dashboard is at <http://192.168.43.1:8686>.

Or use the Run button in Android Studio. See the repo's `README.md` for the full Wi-Fi
instructions.

## Going deeper

- `AGENTS.md` in the repo root — our hardware, tuning numbers, and every mistake we have
  already made and fixed.
- FIRST's example OpModes: `FtcRobotController/java/org.firstinspires.ftc.robotcontroller/external/samples`.
  Their naming tells you what each one is for — `Basic`, `Sensor`, `Robot`, `Concept` — and
  `sample_conventions.md` in that folder explains the system.
