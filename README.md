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

### Deploy over USB

```bash
cd FtcRobotController
./gradlew :TeamCode:installDebug              # compile + push
adb forward tcp:8001 tcp:8001                 # Panels — forwards drop on every install
adb forward tcp:8002 tcp:8002
```

Dashboard at <http://localhost:8001>.

### Deploy over the robot's Wi-Fi

On macOS, run `./deploy` from the repository root. It builds on your
normal Wi-Fi, joins the robot network, installs with Gradle offline, and attempts
to restore the previous network after success, errors, timeout, or Ctrl+C. If adb stops
responding it restarts the adb server and retries, which clears a wedged Wi-Fi transport.

Wi-Fi names and passwords live in a private file **outside this repository**:
`~/.config/ftc/deploy-wifi.json`. If a password is missing, the script asks for it
with hidden input and saves it there. It never reads Keychain. Example structure:

```json
{
  "robot_ssid": "YOUR_ROBOT_WIFI",
  "robot_ip": "YOUR_ROBOT_IP",
  "return_wifi": "YOUR_NORMAL_WIFI",
  "passwords": {
    "YOUR_ROBOT_WIFI": "YOUR_ROBOT_PASSWORD",
    "YOUR_NORMAL_WIFI": "YOUR_NORMAL_PASSWORD"
  }
}
```

The script creates the file with mode 600. Passwords are plaintext in that local
file, readable only by your user (and administrators). Do not copy it into the
repository. `networksetup` takes passwords as process arguments, so they can
briefly be visible to local process inspection.

```bash
./deploy --check  # preflight only; no build or network changes
./deploy
```

The robot and main networks are remembered automatically. Use `-r` to select the
robot network and `--home-wifi` to select the home/main network; either choice becomes
the new default. Names already in the private file match without regard to case,
spaces, or punctuation.

```bash
./deploy -r "ROBOT WIFI" --home-wifi "HOME WIFI"
./deploy --home-wifi "HOME WIFI"  # uses the remembered robot
./deploy                          # uses both remembered networks
```

`-h`/`--help` shows help. `--robot-ip` and `--config`
provide other overrides. Router-address
checks provide limited verification when macOS redacts SSIDs. If restoration
fails, reconnect manually; force-killing the script or shutting down the Mac
cannot run cleanup.

**School and other enterprise networks.** `networksetup` can only join a network with a
password. If the network you return to uses WPA2-Enterprise — a username and password, or a
certificate, which is normal at schools — the restore step cannot rejoin it and will fail. It
fails safely, telling you to pick the network from the Wi-Fi menu, and the code is already
deployed by that point. Do one `deploy` run on that network early, when it does not matter,
rather than finding out before a demo.

Where USB is available, prefer it: `./gradlew :TeamCode:installDebug` keeps your internet and
switches no networks. This script is for when USB is not an option.

Run `python3 -B -m unittest discover -s tests -v` for simulated cleanup tests.

For a manual deployment:

The Control Hub listens for adb on port 5555 permanently, so there is no setup step on the
robot. Unplug USB first, then join the robot's network:

```bash
adb connect 192.168.43.1:5555
./gradlew --offline :TeamCode:installDebug
# Panels dashboard: http://192.168.43.1:8001   (no adb forward on this network)
```

`--offline` is required: the robot's network has no internet, and without it Gradle stalls
trying to reach Maven Central. It works because dependencies are already cached, so **do your
first sync on real internet** — on a new machine `--offline` will fail with "no cached
version." Android Studio's Run button does not pass `--offline`; use the toggle in the Gradle
tool window.

With more than one device attached, Gradle refuses to guess. Either unplug the others or name
the one you want:

```bash
ANDROID_SERIAL=192.168.43.1:5555 ./gradlew --offline :TeamCode:installDebug
```

### If the install is rejected

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

The **build** succeeded; the **push** was refused. Android will not replace an installed app
with an APK signed by a different key, and every computer generates its own debug key at
`~/.android/debug.keystore`. So this appears the first time you deploy from a new machine, or
the first time you deploy from your own machine over someone else's build.

```bash
adb uninstall com.qualcomm.ftcrobotcontroller
./gradlew :TeamCode:installDebug
```

Safe to do: robot configs, Blocks programs and OnBotJava sources live in `/sdcard/FIRST`,
which is external storage and survives an app uninstall. Back it up first if you want to be
certain:

```bash
adb pull /sdcard/FIRST ~/ftc/backups/$(date +%Y%m%d-%H%M%S)-controlhub
```

**To stop it happening again,** copy `~/.android/debug.keystore` from one machine to the
others. Every machine then signs identically and the hub accepts builds from any of them.

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
