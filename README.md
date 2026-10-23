# BioBuzz — Team 24621 Total Chaos

Robot code for FIRST Tech Challenge team 24621, Total Chaos.

This is a **fork of the official [FTC Robot Controller SDK](https://github.com/FIRST-Tech-Challenge/FtcRobotController)**.
Everything outside `TeamCode/` is FIRST's code, unmodified except for two build files
(see [AGENTS.md](AGENTS.md#repo-modifications-vs-stock-sdk)).

| | |
|---|---|
| FTC SDK | 12.0 (BIOBUZZ, 2026-2027) |
| Android Studio | Narwhal 3 Feature Drop or later |
| Pedro Pathing | revhub 3.0.1 / tuning 1.0.1 |
| Gradle / AGP | 9.1.0 / 8.13.2 |
| Panels dashboard | 1.0.17 |
| JDK | Android Studio Embedded JDK (17+; JBR 25 verified) |

For FIRST's own documentation and SDK release notes, go to the source rather than a stale
copy here:

- [Upstream README](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/master/README.md)
- [SDK releases and changelogs](https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases)
- [FTC documentation](https://ftc-docs.firstinspires.org/)

Current tuning targets the competition robot. Historical test-platform measurements
in AGENTS.md must not replace the current values in `pedro/Constants.java`.

## Desktop handoff — 2026-10-22

The SD-card autonomous reader is **tested on the robot**. Pull `main` on the desktop
before editing. Paste Pedro's complete Java export into `paths/auto.java.txt`;
`paths/auto.json` keeps timing and named speed limits. Both files are versioned.
See [the path workflow](paths/README.md) for supported syntax and upload instructions.

Current autonomous starts at **(56, 8, 270°)**, drives to **(60, 16, 270°)**,
shoots for **3.5 seconds**, then follows the curve to **(13.2008, 96.0738, 180°)**
to park. Startup wait is 1 second. The stinger stays up; intake defaults to 700 RPM.
TeleOp auto-selection requires Driver Station **OpMode Auto Queue** and the timed auto run.

On the Linux desktop, save the robot Wi-Fi connection in NetworkManager once, then
run `./deploy-linux -r 24621-RC --stay` from your terminal. Deploy uploads both path
files automatically; `-m` additionally uploads the versioned hardware XML.
For path-only edits, join robot Wi-Fi and run `./update-paths`, then re-INIT.
No rebuild or app restart is needed. Both deploy helpers preserve Gradle colors and
live progress in a terminal. Normal full deploy starts on internet Wi-Fi to build first.

Wi-Fi credentials remain in each machine's private configuration outside Git and
must be set up separately. `local.properties` and Android Studio settings are also
local. The robot identifies as **Control Hub v1.0, Android 7.1.2** in install logs;
file loading uses FileInputStream for compatibility. Do not replace it with
`java.nio.file.Files`, which needs Android 8.

Hardware config remains `robot-config/2222-Config.xml` (including `stinger` on
Expansion Hub servo port 2). No fresh hub XML was available during this handoff;
pull it after any Driver Station hardware configuration changes.

## Getting started

1. Install **Android Studio** and let it install the Android SDK.
2. Clone this repo and open the **`FtcRobotController/`** folder — the one with
   `settings.gradle`, not its parent.
3. **Select Android Studio’s Embedded JDK before the first sync** (Settings → Build Tools → Gradle).
   See [AGENTS.md](AGENTS.md) for why.
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
with hidden input and saves it there; press Return for an open network. It never
reads Keychain. Example structure:

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
robot network and `-c` for the current network to come back to; either choice becomes
the new default. Names already in the private file match without regard to case,
spaces, or punctuation.

```bash
./deploy -r "ROBOT WIFI" -c "HOME WIFI"
./deploy -c Ender    # come back to Ender, use the remembered robot
./deploy             # uses both remembered networks
```

`-c` is also spelled `--current-wifi`, `--home-wifi` or `--return-wifi`.

**To stay on the robot's network afterwards** — for Panels, logs, or quick re-deploys — add
`--stay` (or `-s`):

```bash
./deploy --stay
# Panels at http://192.168.43.1:8001, adb still connected
```

It still builds on your normal Wi-Fi first, so dependencies download while you have
internet. If joining the robot's network fails, it returns you to your Wi-Fi anyway, so you
are never left with no network. Re-join your normal Wi-Fi yourself when you are done.
`-h`/`--help` shows help. `--robot-ip` and `--config`
provide other overrides. Router-address
checks provide limited verification when macOS redacts SSIDs. If restoration
fails, reconnect manually; force-killing the script or shutting down the Mac
cannot run cleanup.

**School networks.** Open networks work: enter an empty password when prompted. A captive
portal may still require browser sign-in after reconnecting if its cached authorization has
expired. WPA2-Enterprise networks — a username and password or certificate — are not
supported by this script's restore step. It fails safely, telling you to pick the network
from the Wi-Fi menu, and the code is already deployed by that point. Do one `deploy` run on
that network early, when it does not matter, rather than finding out before a demo.

Where USB is available, prefer it: `./gradlew :TeamCode:installDebug` keeps your internet and
switches no networks. This script is for when USB is not an option.

**On Linux (NetworkManager), use `./deploy-linux`.** Same idea and the same flags (`-r`, `-c`,
`--stay`, `--check`), but simpler: NetworkManager already stores the robot network's password,
so the script never handles one. Join the robot network once from the Wi-Fi menu, then:

```bash
./deploy-linux -r "ROBOT WIFI" --check   # first time: name the saved connection, dry run
./deploy-linux                           # build, deploy, come back to your Wi-Fi
./deploy-linux --stay                    # ...or stay on the robot network for Panels
```

The robot network name is remembered in `~/.config/ftc/deploy-wifi.json` (names only, no
passwords). Run it from your own terminal — if an AI agent runs it, the agent loses its
internet connection while you are on the robot network.

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
with an APK signed by a different key.

**Switching between your own computers does NOT cause this.** FIRST's build signs with
`libs/ftc.debug.keystore`, which is committed in this repo, so every clone signs with the same
key. You see this error only when the hub has an app signed some *other* way — the
factory-installed Robot Controller, or a build from a different team's repo.

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


### The robot config is in the repo

The robot config — which device is on which port, under which name — normally lives only on
the hub. A copy is kept in `robot-config/2222-Config.xml` so it travels with the code.

**Load it onto a hub** (a new hub, one that lost its config, or after editing the XML here) —
add `-m` / `--hardware-map` to a deploy, which uploads every `robot-config/*.xml` before
installing the app:

```bash
./deploy -m            # or ./deploy-linux -m
```

**Only the map changed?** `./update-hardware-map` uploads it and restarts the Robot Controller
app — no build, so it works with no internet or while already on the robot network:

```bash
./update-hardware-map          # Mac or Linux; add --stay to stay on the robot Wi-Fi
```

or by hand: `adb -s <serial> push robot-config/2222-Config.xml /sdcard/FIRST/`

If `2222-Config` is already the active config, the app restart from the deploy loads the new
file. Otherwise, on the Driver Station: **⋮ → Configure Robot → `2222-Config` → Activate.**
Copying the file does not make it active.

The file records **wiring**. If the other robot's devices are on different ports, the names
load fine but point at the wrong hardware — run **Motors Test** before driving.

**After changing the config on the Driver Station,** save it back so GitHub stays current:

```bash
adb pull /sdcard/FIRST/2222-Config.xml robot-config/
git add robot-config && git commit -m "Update robot config" && git push
```

## What's here

Autonomous paths can be edited and uploaded without rebuilding. See
[paths/README.md](paths/README.md) for the Pedro export and JSON settings format, first setup, and `./update-paths`.

All team code lives in `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/`.

| OpMode | Driver Station name | Group | What it does |
|---|---|---|---|
| `FieldCentricJava` | Field Centric (Java) | Drive | Main teleop |
| `TemplateTeleOp` | Template TeleOp | Template | `@Disabled` starter to copy |
| `MotorsTest` | Motors Test | Diagnostics | Per-motor and robot-level direction checks |
| `PedroAutonomous` | Pedro Pathing Autonomous | Autonomous | SD-card Pedro export: shoot, then park |
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
