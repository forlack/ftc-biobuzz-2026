# TeamCode — BioBuzz 24620

You are in the right place. **All of our robot code lives in this folder.** The sibling
`FtcRobotController` module is FIRST's SDK — read it, don't edit it.

## Start here

Copy **`TemplateTeleOp.java`**, rename the class and the `@TeleOp(name = ...)`, and delete
the `@Disabled` line so it shows up on the Driver Station. Driving already works, so you can
go straight to whatever you are adding.

## What is in this folder

| File | What it is |
|---|---|
| `TemplateTeleOp.java` | Starter to copy. Disabled on purpose. |
| `FieldCentricJava.java` | Our real teleop. A worked example of everything below. |
| `MotorsTest.java` | Checks each motor turns the right way. Run this when driving feels wrong. |
| `PedroAutonomous.java` | Autonomous that follows a path. Shows a state machine. |
| `PanelsDemo.java` | Dashboard test, no hardware needed. |
| `lib/` | The framework. Read it if you are curious; you rarely need to change it. |
| `pedroPathing/` | Pedro Pathing config and its tuning OpModes. |

## How a teleop works

One loop that runs 50–200 times a second. Each method does a little work and returns:

```java
while (opModeIsActive()) {
    readGamepad();
    updateDriving();
    updateArm();          // ← yours
    dashboard.update();
}
```

**The one rule: never wait inside the loop.** No `sleep()`, no `while (motor.isBusy())`. The
Robot Controller stops the robot if the loop stops going around, and the gamepad only updates
between times through. For something with steps — raise arm, pause, open claw — use a state
machine that does one step per loop. `PedroAutonomous.java` shows how.

## Adding your own part of the robot

1. Get the hardware in `runOpMode()`:
   ```java
   arm = hardwareMap.get(DcMotorEx.class, "Arm");   // name must match the robot config
   ```
2. Bind a button up with the others:
   ```java
   private final Button armUp = buttons.add(() -> pad.dpad_up);
   ```
3. Write the method and add one call to the loop:
   ```java
   private void updateArm() {
       arm.setPower(armUp.down() ? 0.5 : 0.0);
       dashboard.addData("arm position", arm.getCurrentPosition());
   }
   ```

## Buttons

| | Means | Use for |
|---|---|---|
| `down()` | held right now | hold-to-do-something |
| `pressed()` | just pushed down | toggles, one-shot actions |
| `released()` | just let go | rarely |

Use `pressed()` for anything that switches something on or off. A button press lasts about 30
trips through the loop, so `down()` would run your action 30 times.

## Telemetry

`dashboard.addData("label", value)` puts a number on the screen — it goes to both the Panels
dashboard and the Driver Station. It must come **before** `dashboard.update()`, which sends
everything and empties the list.

Show `down()`, not `pressed()`. `pressed()` is only true for one loop out of thirty, so the
screen would almost always catch it as false and look broken.

## Sending code to the robot

Over USB:

```bash
cd ~/ftc/FtcRobotController
./gradlew :TeamCode:installDebug
adb forward tcp:8001 tcp:8001    # Panels, at http://localhost:8001
adb forward tcp:8002 tcp:8002    # these drop every time you install
```

On the robot's Wi-Fi, no forwarding is needed — Panels is at <http://192.168.43.1:8001>.

Or use the Run button in Android Studio. See the repo's `README.md` for the full Wi-Fi
instructions.

## Going deeper

- `AGENTS.md` in the repo root — our hardware, tuning numbers, and every mistake we have
  already made and fixed.
- FIRST's example OpModes: `FtcRobotController/java/org.firstinspires.ftc.robotcontroller/external/samples`.
  Their naming tells you what each one is for — `Basic`, `Sensor`, `Robot`, `Concept` — and
  `sample_conventions.md` in that folder explains the system.
