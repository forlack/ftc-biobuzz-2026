# BioBuzz — FTC Team 24620

Robot code for FIRST Tech Challenge team 24620.

This is a **fork of the official [FTC Robot Controller SDK](https://github.com/FIRST-Tech-Challenge/FtcRobotController)**.
Everything outside `TeamCode/` is FIRST's code, unmodified except for two build files
(see [AGENTS.md](AGENTS.md#repo-modifications-vs-stock-sdk)).

| | |
|---|---|
| FTC SDK | 12.0 (BIOBUZZ, 2026-2027) |
| Android Studio | Narwhal 3 Feature Drop or later |
| Pedro Pathing | 2.1.2 |
| Gradle / AGP | 9.1.0 / 8.13.2 |
| Panels dashboard | 1.0.12 |
| JDK | 17 |

For FIRST's own documentation and SDK release notes, go to the source rather than a stale
copy here:

- [Upstream README](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/master/README.md)
- [SDK releases and changelogs](https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases)
- [FTC documentation](https://ftc-docs.firstinspires.org/)

> **This repo is tuned for a TEST PLATFORM, not a competition robot.** Pod offsets, measured
> velocities, braking constants and `mass` all belong to that specific chassis. The structure
> carries over to a new robot; the numbers do not.

## Getting started

1. Install **Android Studio** and let it install the Android SDK.
2. Clone this repo and open the **`FtcRobotController/`** folder — the one with
   `settings.gradle`, not its parent.
3. **Set the Gradle JDK to 17 before the first sync** (Settings → Build Tools → Gradle).
   This is the step everyone misses; see [AGENTS.md](AGENTS.md) for why.
4. Sync. Gradle, the FTC SDK and Pedro download automatically.

Deploy with the Run button, or:

```bash
cd FtcRobotController
./gradlew :TeamCode:installDebug              # compile + push over USB
adb forward tcp:8001 tcp:8001                 # Panels — forwards drop on every install
adb forward tcp:8002 tcp:8002
```

Then open <http://localhost:8001> for the dashboard.

## What's here

All team code lives in `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/`.

| OpMode | Driver Station name | Group | What it does |
|---|---|---|---|
| `FieldCentricJava` | Field Centric (Java) | Drive | Main teleop |
| `TemplateTeleOp` | Template TeleOp | Template | `@Disabled` starter to copy |
| `MotorsTest` | Motors Test | Diagnostics | Per-motor and robot-level direction checks |
| `PedroAutonomous` | Pedro Pathing Autonomous | — | Out-and-back path |
| `PanelsDemo` | Panels Demo | Diagnostics | Dashboard smoke test, no hardware |
| `pedroPathing.Tuning` | Tuning | — | Official Pedro tuning suite |

## The teleop framework

`lib/` is framework; everything outside it is yours.

```
lib/Button.java          down() is a state, pressed()/released() are events
lib/MecanumDrive.java    motors, IMU, field-centric math, speed, PIDF
lib/DriveDashboard.java  all Panels wiring
```

A teleop is one loop that **never waits**:

```java
while (opModeIsActive()) {
    readGamepad();
    updateDriveSettings();
    updateDriving();
    dashboard.update();
}
```

To add something to the robot, write an `updateXxx()` method, have it publish its own
telemetry with `dashboard.addData(...)`, and add one call to the loop.

**The one rule: never block the loop.** No `sleep()`, no `while (motor.isBusy())`. The Robot
Controller will stop the robot if the loop stops turning over, and gamepads only refresh
between iterations. Use a state machine instead — `PedroAutonomous` is the worked example.

Start from `TemplateTeleOp.java`: copy it, rename the class and the `@TeleOp` name, and
delete `@Disabled`.

## More detail

[AGENTS.md](AGENTS.md) is the real documentation — hardware and wiring, the toolchain,
tuning results and how they were measured, Pedro setup, Panels, naming and comment
conventions, and the gotchas that have already cost us time.

## License

FIRST's original code is under the license in [LICENSE](LICENSE). Team code in `TeamCode/`
is ours.
