# FTC Workspace — Agent Notes

Living context for this workspace. Update as things change.

Last updated: 2026-09-12

---

## Hardware

> **This is a TEST/DEVELOPMENT PLATFORM, not the competition robot.**
>
> Everything about the *setup* transfers to the competition bot — SDK version, Pedro
> integration, Panels, the OpMode structure, the toolchain. **The measured constants do
> not.** Pod offsets, `xVelocity`/`yVelocity`, zero-power acceleration, predictive braking,
> and `mass` are all properties of this specific chassis. Budget a full re-tune when the
> competition robot exists: Offsets Tuner → velocity → zero-power → PIDs.

| Device | Serial / Address | OS | Notes |
|---|---|---|---|
| REV Driver Hub | `B67DRMNXYT` | Driver Hub OS **1.2.0** (current) | `PX30_RDS`, Android 10 |
| REV Control Hub | `0ef75e560c41cbdf` | Control Hub OS **1.1.6** (current) | `ch_v1_box`, Android 10 |

### Robot configuration (`2222-Config.xml`)

All on the Control Hub's single Lynx module (address 173):

| Device | Name | Port | Type |
|---|---|---|---|
| Motor | `BackRight` | 0 | goBILDA 5202 |
| Motor | `FrontRight` | 1 | goBILDA 5202 |
| Motor | `BackLeft` | 2 | goBILDA 5202 |
| Motor | `FrontLeft` | 3 | goBILDA 5202 |
| Odometry | `odo` | I2C bus 1, port 0 | goBILDA **Pinpoint** |
| IMU | `imu` | I2C bus 0, port 0 | Control Hub BHI260AP |

Drivetrain is **mecanum**. IMU orientation: logo **UP**, USB **FORWARD**.
Motor directions: `FrontRight`/`BackRight` FORWARD, `FrontLeft`/`BackLeft` REVERSE. All BRAKE.

The **Pinpoint** is the key asset for Pedro — it's a dedicated odometry computer, so Pedro
should use its `PinpointLocalizer` rather than drive-encoder odometry.

**Odometry pod mounting:** goBILDA **4-bar** pods, both on the robot's **back bar**, ~6"
apart. Chassis is ~16×16", so the center of rotation is ~8" from the back edge. Measured
offsets are `strafePodX = 6.15` and `forwardPodY = 2.125` (Offsets Tuner, mean of 4 spins,
2026-08-04). The earlier figures `-7.0` / `3.2` were taken before `strafeEncoderDirection`
was corrected to REVERSED and are **invalid** — see `Constants.java`.

Pedro models only **one offset per pod**: the perpendicular distance that determines how
rotation contaminates that pod's reading. The forward pod's fore/aft position and the strafe
pod's left/right position are not part of the math, so the 6" spacing never appears in
`Constants.java`.

### Where to mount pods on the NEXT robot

**Minimize each pod's offset from the center of rotation.** Rotating at rate `w`, a pod at
perpendicular distance `d` reads a spurious `w * d`. The Pinpoint cancels it using the
configured offset, but the leftover error scales with `d` — both from heading-rate error and
from error in the measured offset. At `d = 0` there is nothing to cancel.

| Pod | Ideal | Test platform |
|---|---|---|
| Forward / parallel | on the centerline, `forwardPodY ~ 0` | 2.125" |
| Strafe / perpendicular | at the center of rotation, `strafePodX ~ 0` | **6.15"** |

> **Do NOT apply 3-pod reasoning here.** In a classic dead-wheel setup two parallel pods
> derive heading from their difference, so a wide separation improves angular resolution.
> The **Pinpoint has its own IMU** for heading (`recalibrateIMU`, `resetPosAndIMU`,
> `yawScalar`), so pod SEPARATION is irrelevant. Only each pod's offset from the center of
> rotation matters, and smaller is better.

This is a refinement, not a correctness issue — the offsets exist so imperfect mounting works.
It matters most in autos with lots of rotation, where the error accumulates.

**Robot network:** SSID `2222-RC` (5 GHz). Control Hub is the AP at **192.168.43.1**;
Driver Hub associates at 192.168.43.13. Team is **24620**.

> **Naming mismatch (low priority):** the config is `2222-Config.xml` and the SSID is
> `2222-RC`, both carrying an old team number. **This is a TEST PLATFORM, not the
> competition robot**, so there's no inspection concern. Rename to `24620-RC` whenever
> convenient (Control Hub network settings); code doesn't care, since OpModes reference
> device names, not the filename.

The laptop's normal WiFi (`Ender`, 10.0.0.x) **cannot reach the robot**. To talk to the
Control Hub, either plug in USB (preferred — keeps internet) or join `2222-RC`.

---

## Software versions

Both sides must match or the Driver Station nags about it (warning only, but it fails
competition inspection).

- FTC SDK / Robot Controller: **12.0** (BIOBUZZ, 2026-2027 season)
- Driver Station app: **12.0** — must be updated to match, or the DS nags
- Pedro Pathing: **2.1.2** — builds clean against 12.0, verified 2026-09-12
- Panels dashboard: **fullpanels 1.0.12**
- Gradle **9.1.0** (repo wrapper), AGP **8.13.2**, JDK **17**
- Android Studio **Narwhal 3 Feature Drop or later** is now required by FIRST

### Upgraded 11.2 -> 12.0 on 2026-09-12 (release day)

`git merge v12.0` conflicted on **README.md only**. Both gradle files auto-merged
correctly — the SDK deps went to `12.0.0` and our three Pedro/Panels lines survived
untouched, as did `compileSdk 34`. Stock 12.0 still ships `compileSdkVersion 30`, so that
bump is still ours to carry.

The toolchain jump is the real content of this release: Gradle 8.9 -> 9.1.0 and AGP 8.7.0
-> 8.13.2, and the root `build.gradle` moved from `buildscript { classpath ... }` to a
`plugins {}` block. JDK 17 still works. Gradle warns that deprecated features make the
build incompatible with **Gradle 10** — that is upstream's problem to fix, not ours.

**Breaking change we dodged:** AprilTag detections are now singleton-or-cluster, and legacy
`for (AprilTagDetection d : detections)` loops no longer compile. Nothing in `TeamCode/`
uses AprilTag, so we were unaffected — but the reference artifact's AprilTag boilerplate is
now out of date, and any new vision code must check `instanceof AprilTagSingleDetection`
first. See <https://ftc-docs.firstinspires.org/apriltag-clusters>.

> **BIOBUZZ AprilTags MOVE**, so FIRST says they are not suitable for absolute field
> localization. Odometry carries the localization load this season; tags are for aiming.

> `v11.2.1` was a source-only tooling patch with no APK assets — superseded by 12.0.

---

## Toolchain layout

| Thing | Path |
|---|---|
| JDK 17 | `/usr/lib/jvm/java-17-openjdk` |
| Android SDK | `~/Android/Sdk` (platforms 30 + 34, build-tools 35, platform-tools) |
| FTC repo | `~/ftc/FtcRobotController` (branch `main`, forked from tag `v11.2`) |
| Env script | `~/ftc/env.fish` |
| Config backups | `~/ftc/backups/<timestamp>-controlhub/` |

System default `java` is **JDK 26** and is deliberately left alone. JDK 17 is scoped to the
build via `env.fish` — the FTC/AGP toolchain will not build on 26.

```fish
source ~/ftc/env.fish
cd ~/ftc/FtcRobotController
./gradlew :TeamCode:assembleDebug
```

---

## Repo modifications vs. stock SDK

Two tracked files differ from upstream. Both are required by Pedro.

**`build.dependencies.gradle`**
```gradle
maven { url = "https://mymaven.bylazar.com/releases" }   // Panels only

implementation 'com.pedropathing:ftc:2.1.2'        // Maven Central; pulls in :core
implementation 'com.pedropathing:telemetry:1.0.0'  // Maven Central
implementation 'com.bylazar:fullpanels:1.0.12'     // bylazar maven
```

**`build.common.gradle`** — `compileSdkVersion 30` → `compileSdk 34`.
Pedro's androidx transitives refuse to compile against 30. `minSdk`/`targetSdk` are
**unchanged**, so runtime behavior matches stock. The official Pedro Quickstart makes the
same bump, so this is sanctioned, not a hack.

`local.properties` (points at the SDK) is gitignored and won't follow to a team repo.

---

## Panels dashboard

Auto-starts — **no glue code required**. `com.bylazar.panels.Panels` carries
`@WebHandlerRegistrar`, `@OnCreateEventLoop`, and `@OpModeRegistrar`, so the SDK's
annotation scanner wires it up when the RC app boots.

| Port | Purpose |
|---|---|
| **8001** | HTTP / dashboard UI |
| **8002** | WebSocket (live data — forward this too or the UI stays static) |
| 5800/5801/5805/5807 | Limelight proxy plugin |

**Access over USB** (no need to join robot WiFi):
```fish
adb forward tcp:8001 tcp:8001
adb forward tcp:8002 tcp:8002
# then http://localhost:8001
```
**Access over robot WiFi:** `http://192.168.43.1:8001`

> Hitting `/` immediately after RC boot returns the 27-byte string
> `Panels has not started yet.` — that's a startup race, not a failure. It resolves within
> seconds. `/app` serves the real UI the whole time.

### Panels shows *only* what an OpMode sends it

It is not a passive monitor — it draws nothing unless running code calls into it. A blank
dashboard with a lone "Templates" button almost always means **no Panels-aware OpMode is
running**, not a broken install. Templates only define widget layout; they don't produce data.

**Blocks OpModes cannot drive Panels.** The API is Java-only (`PanelsTelemetry`,
`PanelsField`, `@Configurable`). Team 24620's existing teleop `Field Centric (Best)` is a
Blocks program, so it will never populate the dashboard. Pedro Pathing is likewise Java-only
— porting that teleop to a Java OpMode in TeamCode is a prerequisite for both.

Debug order when the dashboard looks dead:
1. `adb logcat | grep -iE "switch to OpMode"` — is a Panels-aware OpMode actually running?
2. Did you press **PLAY**, not just INIT?
3. Is port **8002** forwarded? Without it the page renders but never updates.
4. Gamepad values need a controller bound on the DS (**Start + A**).

### The gamepad plugin is a virtual controller (browser → robot)

Common misread. Panels' gamepad feature does **not** display the Driver Station's controller.
`Plugin.onMessage` accepts `gamepad0`/`gamepad1` JSON **from the browser** and feeds it into
the robot; `asCombinedFTCGamepad()` merges it with the real DS gamepad. It's for driving the
robot from a controller plugged into your laptop.

The OpMode must opt in, in Java:
```java
Gamepad g = PanelsGamepad.INSTANCE.getFirstManager().asCombinedFTCGamepad(gamepad1);
```
Blocks cannot call this, so browser gamepad input is unreachable from Blocks OpModes.

API (verified against the AARs, not docs):
```java
TelemetryManager t = PanelsTelemetry.INSTANCE.getTelemetry();
t.addData("k", v); t.update(telemetry);   // passing telemetry mirrors to Driver Station

FieldManager f = PanelsField.INSTANCE.getField();
f.setOffsets(FieldPresets.INSTANCE.getPEDRO_PATHING());
f.setStyle(fill, outline, width); f.moveCursor(x, y); f.circle(r); f.update();
```
`@Configurable` on a class exposes its `public static` fields for live editing in the browser.

---

## Daily workflow

Team code lives **only** here:
```
~/ftc/FtcRobotController/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/
```
(The sibling `FtcRobotController/` module is the stock SDK — don't edit. Unrelated to the
legacy OnBotJava sources in `/sdcard/FIRST/java` on the hub.)

```fish
cd ~/ftc/FtcRobotController        # required — bare ./gradlew from ~/ftc fails
./gradlew :TeamCode:assembleDebug  # compile only
./gradlew :TeamCode:installDebug   # compile + push (Control Hub on USB)
adb forward tcp:8001 tcp:8001; adb forward tcp:8002 tcp:8002   # forwards drop on every install
```

**No `source env.fish` needed.** Gradle self-configures from two untracked files:

| File | Provides |
|---|---|
| `~/.gradle/gradle.properties` | `org.gradle.java.home` → JDK 17 (default is JDK 26, which fails) |
| `FtcRobotController/local.properties` | `sdk.dir` → Android SDK (also where AGP finds `adb`) |

Verified with `env -i HOME=... PATH=/usr/bin:/bin ./gradlew :TeamCode:assembleDebug` — builds
with zero environment variables.

`~/.gradle/gradle.properties` is user-global, so it pins **every** Gradle project to JDK 17.
Fine today (no other Gradle projects). If one later needs a different JDK, drop that line and
set it per-project instead.

`env.fish` is now optional — source it only to get `sdkmanager`/`avdmanager`/JDK 17 on PATH.

### Live variable tuning

Any `public static` field on a `@Configurable` class appears in the Panels **Configurables**
panel and can be edited while an OpMode runs — changes land on the next loop iteration.
Supports double/int/boolean/String/enum. Must be `static`; instance fields are not scanned.

**Values are not persisted** — they revert to source defaults on RC restart. Bake keepers
into the source.

Verified live on the hub via the WebSocket:
`{"fieldName":"NORMAL_MULTIPLIER","type":"DOUBLE","value":"0.5"}`

> Configurables are pushed over the **WebSocket (8002)** in an `initialConfigurables` message
> on client connect — they are **not** on any HTTP endpoint. Probing `/api/configurables`
> proves nothing. `scratchpad/wsprobe.py` is a dependency-free raw-WS client for checking.

---

## Deploying to the Control Hub

```fish
adb devices                              # confirm hub is on USB
./gradlew :TeamCode:installDebug
```

### Gotcha: signature mismatch (one-time)
The factory RC app is FIRST-signed; your build is debug-signed. First deploy fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. adb **refuses safely** — nothing is destroyed.

Fix — uninstall first:
```fish
adb uninstall com.qualcomm.ftcrobotcontroller
adb install -r TeamCode/build/outputs/apk/debug/TeamCode-debug.apk
```
This is safe because **robot configs, OnBotJava sources, and Blocks live in `/sdcard/FIRST`**,
which is external storage and survives app uninstall. Back it up anyway:
```fish
adb pull /sdcard/FIRST ~/ftc/backups/$(date +%Y%m%d-%H%M%S)-controlhub
```
Note `adb uninstall -k` is **not supported** on this Android build — it errors out and tells
you to use `adb shell cmd package uninstall -k`. Plain uninstall is fine given the above.

---

## Setting up on another machine (school laptop, Windows, etc.)

Much shorter than the Linux CLI setup — Android Studio bundles or fetches almost everything.

1. **Install Android Studio.** It brings its own JVM and can install the Android SDK.
2. **Clone the team repo** (see Repo & remotes below). Open the **`FtcRobotController/`**
   folder — the one containing `settings.gradle` — not its parent.
3. **Set the Gradle JDK BEFORE the first sync** (see gotcha below).
4. **Sync**. Studio downloads Gradle 8.9, the FTC SDK, and Pedro automatically.
5. Deploy with the Run button, or `./gradlew :TeamCode:installDebug`.

### ⚠ The JDK gotcha will repeat on every new machine

Android Studio 2026.1 bundles **JBR 25**; Gradle 8.9 accepts at most **22**; AGP 8.7 requires
at least **17**. So a fresh install fails on first sync with
*"Gradle JVM version 25 ... select a JVM version that is at least 8 and at most 22."*

Fix, before syncing:
**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → 17** (21 also
works — the valid window is 17–22, both are LTS).

Also set **File → Project Structure → Project → SDK** to the same JDK, or the editor resolves
nothing even after a successful sync (they are two separate settings).

> The `org.gradle.java.home` pin in `~/.gradle/gradle.properties` is **user-level and NOT in
> the repo**, deliberately, so it can't break teammates on other machines. It does not travel.

### What doesn't travel (all gitignored, all auto-regenerated)

| Path | Regenerated by |
|---|---|
| `local.properties` | Studio, on first sync (holds the Android SDK path) |
| `.idea/` | Studio |
| `build/`, `.gradle/` | Gradle |

### Two Java versions — don't confuse them

| | Version | Why |
|---|---|---|
| **Your OpMode code** | Java **8** (`sourceCompatibility 1.8`) | what Android's runtime expects; unchanged from stock FTC |
| **The build toolchain** | JDK **17–22** | AGP 8.7 needs ≥17, Gradle 8.9 needs ≤22 |

Nothing in TeamCode uses Java 17 language features. The JDK version is purely about what runs
Gradle and AGP.

## Repo & remotes

Stock clone points `origin` at **FIRST's** repo, which you cannot push to. The team layout is:

```
origin    -> the team's own repo   (push/pull team work)
upstream  -> FIRST-Tech-Challenge/FtcRobotController   (pull future SDK releases)
```

With that, a new-season SDK bump is a merge from `upstream`, not a fresh clone and re-port.

GitHub **free accounts get unlimited private repos** (since 2019) and unlimited collaborators
(since 2020) — a private team repo costs nothing.

## Editor / IDE

**VS Code + `redhat.java` does NOT work for this project.** Verified 2026-08-04: the
extension installs and starts, but Eclipse JDT/Buildship cannot import **Android Gradle**
projects (`com.android.application`). Symptoms:

- `imu.` returns nothing; completion falls back to `editor.wordBasedSuggestions`, offering
  English words scraped from code comments ("about", "achieve", "actually")
- JDT log repeats `src/main/java/org/firstinspires/ftc/teamcode [in TeamCode] does not exist`
- Files are reported at a flat path (`/FieldCentricJava.java`) with no package
- **No `.classpath` is generated at all** — the definitive check:
  `find "$HOME/.config/Code - OSS/User/workspaceStorage/*/redhat.java/jdt_ws" -name .classpath`

No settings tuning fixes this; there is no classpath to work with. Use **Android Studio**
(IntelliJ + Android plugin resolves AAR deps natively) — also the official FTC tooling.

`FtcRobotController/.vscode/settings.json` exists with completion tuning. Harmless, but moot
for Java. `.vscode/` is NOT in `.gitignore` (only `.idea/` is), so it shows in `git status`.

## Environment gotchas

- **After `installDebug`, port forwards go stale.** The RC app restarts as a new process and
  the old forward points at a dead socket — `curl` returns nothing (exit 52) even though
  Panels is running fine. Fix: `adb forward --remove-all` then re-add both ports. Check
  logcat for `PANELS:` lines before assuming Panels is broken.
- **Shell cwd resets between tool calls.** Always `cd /home/chase/ftc/FtcRobotController &&`
  in the same command as `./gradlew`, or the build silently fails with
  `no such file or directory: ./gradlew`. Don't let a grep filter swallow that error.
- Verify compile-classpath claims with a **negative control** (import a bogus class, confirm
  it fails) — a clean build can otherwise be a no-op `UP-TO-DATE` task.
- The Driver Hub has **no `curl`**, so you can't probe the robot's HTTP endpoints from it.
  `ping` works.

---

## Status

Done:
- [x] Driver Station 11.0 → 11.2 on Driver Hub
- [x] JDK 17 + Android SDK installed; toolchain verified end-to-end
- [x] FtcRobotController v11.2 cloned, branch `main`
- [x] Pedro Pathing 2.1.2 wired in, imports compile-verified
- [x] Panels installed; server + WebSocket (HTTP 101) verified live on the hub
- [x] Robot Controller 11.1 → 11.2; version mismatch cleared; configs preserved
- [x] `PanelsDemo` TeleOp registered on the hub (log: `registered {PanelsDemo} as {Panels Demo}`)
- [x] Panels dashboard confirmed working live by the user via `Panels Demo`
- [x] Blocks `Field Centric (Best)` ported to Java as `FieldCentricJava`, deployed and
      registered on the hub (log: `registered {FieldCentricJava} as {Field Centric (Java)}`)

Next:
- [ ] Run **Panels Demo** (group *Diagnostics*) from the DS and confirm the field view animates
      — first attempt ran the Blocks OpMode `Field Centric (Best)` instead, which cannot
      drive Panels (see above)
- [ ] **Test-drive `Field Centric (Java)`** — wheels off the ground first, verify each
      direction matches the Blocks version before trusting it on the field
- [ ] Delete `TeamCode/.../PanelsDemo.java` once satisfied — it's a smoke test, not real code
- [ ] Author Pedro `Constants.java` from the real `2222-Config.xml` hardware names
- [ ] Pedro tuning: forward/lateral multipliers → heading → drive PIDs

---

## TeleOp framework (`lib/`)

Built 2026-08-27 so students coming from Blocks can start on robot logic instead of
plumbing. The split is deliberate: **everything in `lib/` is framework, everything outside
it is theirs.**

```
teamcode/
├── FieldCentricJava.java   (101)  the working teleop
├── TemplateTeleOp.java      (74)  @Disabled starter to copy
└── lib/
    ├── Button.java          (55)  input helper
    ├── MecanumDrive.java   (214)  motors, IMU, field-centric math, modes, PIDF push
    └── DriveDashboard.java  (91)  every line of Panels
```

**The checkable criterion: a student-facing OpMode has ZERO `com.bylazar` imports.** All
Panels — telemetry, the browser gamepad merge, the PIDF readout — sits behind
`DriveDashboard`. If a bylazar import appears in an OpMode, something leaked; move it.

### The loop model

Every teleop is one non-blocking loop. Each subsystem gets a thin slice per pass, 50–200
times a second:

```java
while (opModeIsActive()) {
    readGamepad();
    updateDriveSettings();
    updateDriving();
    dashboard.update();
}
```

**Never block.** No `sleep()`, no `while (motor.isBusy())`. The RC watchdog stops the robot
if the loop stops turning over, and gamepads only refresh between iterations. For a
multi-step action use a state machine that advances one step per pass — `PedroAutonomous`
is the worked example.

To add a subsystem: write `updateXxx()`, have it publish its own telemetry via
`dashboard.addData()`, add one call to the loop. Nothing else changes.

### `Button`

Wraps a gamepad button so a *level* becomes an *event*:

| | meaning | use for |
|---|---|---|
| `down()` | held right now | hold-to-act (slow mode, heading reset) |
| `pressed()` | just went down | toggles, one-shot actions |
| `released()` | just came up | nothing yet |

`pressed()` matters because at ~100 Hz a human press spans roughly 30 loops — `down()` would
fire a toggle 30 times. `Button.Group.update()` reads every button once per loop, so all
three queries are pure reads: call them in any order, any number of times, same answer.

Two non-obvious things:

- **The lambda is a deferred read, not a value.** `buttons.add(() -> pad.dpad_up)` stores
  *instructions* to read `pad.dpad_up`, evaluated on each `update()`. Passing `pad.dpad_up`
  directly would capture a dead `boolean` (and NPE, since `pad` is null at construction).
  This only works because `pad` is a **field** — a captured local would have to be
  effectively final.
- **Declaration order is load-bearing.** `Button.Group buttons` must be declared *above* the
  buttons, because Java initialises instance fields in source order and each `add()` writes
  into that list. Below them, every button hits a null list at construction.

### Naming

Names are written for a student who just came off Blocks, not for brevity.

- Say what it does in ordinary words: `changeSpeedLimit(+1)`, not `bumpSpeedCap(+1)`.
- **No single-letter or math-shorthand parameters.** `drive(strafe, forward, turn, slow)`,
  never `(x, y, rx)`. The kinematics still uses `fieldX`/`fieldY` internally, where the
  reader is already looking at the math.
- Prefer the word the team says out loud: *heading* over *yaw*, *speed limit* over *speed
  cap*, *initial* over *starting*.
- **Name the thing, not the verb that happened to it.** The stored PIDF numbers are
  `motorP/I/D/F` — "what is in the motors" — because `appliedP`/`sentP` read as though
  something was applied to the motors as power.
- No `Button` suffix on Button fields; `slowMode.down()` already reads as a button.
- Dashboard labels follow the same rule — "stick forward", "encoder FL", not "rotY".

### Comment style

Kept deliberately sparse — dense comment blocks are what made the pre-refactor teleop hard
for a beginner to read.

- One line of javadoc per class and per non-obvious method. A block only where a one-liner
  genuinely cannot carry it.
- Inline comments only for things that would **surprise** a reader: declaration order,
  level-vs-edge, why the heading reset runs after the drive math.
- Everything longer — derivations, history, why a sign is what it is — goes **here**, not in
  the source.

---

## OpModes in TeamCode

| Class | DS name | Group | Purpose |
|---|---|---|---|
| `FieldCentricJava` | Field Centric (Java) | Drive | Java port of the Blocks teleop |
| `TemplateTeleOp` | Template TeleOp | Template | `@Disabled` starter for students to copy |
| `MotorsTest` | Motors Test | Diagnostics | Robot- and motor-level direction checks |
| `PedroAutonomous` | Pedro Pathing Autonomous | — | Out-and-back path, state machine |
| `PanelsDemo` | Panels Demo | Diagnostics | Dashboard smoke test, no hardware |
| `pedroPathing.Tuning` | Tuning | — | Official Pedro tuning suite (menu of routines) |

---

## Pedro Pathing

Files live in `TeamCode/.../teamcode/pedroPathing/`:

- **`Constants.java`** — hand-written for this robot. Mecanum + Pinpoint localizer.
- **`Tuning.java`** — copied **verbatim** from the official Pedro Quickstart (1792 lines,
  one `@TeleOp` that presents a menu via `SelectableOpMode`). Don't hand-edit; re-fetch from
  the Quickstart if it needs updating.

`Tuning.java` depends on exactly one thing: `Constants.createFollower(hardwareMap)`.

### Driving the Tuning menu

`Tuning` extends `SelectableOpMode`, which presents **nested folders** — the top level is only
`Localization`, `Automatic`, `Manual`, `Tests`, `Swerve` (ignore Swerve; we're mecanum).
Entries like "Localization Test" live one level down.

Input is handled in **`init_loop()`**, so you navigate **after INIT and before PLAY**:

| Button | Action |
|---|---|
| D-pad Up/Down | move highlight |
| **Right Bumper** | select / enter folder |
| **Left Bumper** | go back |

Gamepad must be bound on the DS (**Start + A**) or nothing registers.

**Offsets Tuner** (in the Localization folder) derives `forwardPodY`/`strafePodX` by spinning
the robot — more accurate than measuring to an eyeballed center of rotation.

**Pinpoint confirmed alive on the I2C bus:**
`goBILDA® Pinpoint Odometry Computer  odo  module 173; bus 1; addr7=0x31`

`GoBildaPinpointDriver` ships in **FTC SDK 11.2** (`com.qualcomm.hardware.gobilda`) — no
external driver dependency needed.

### Constants still to fill in

`Constants.java` marks these `TODO-MEASURE` / `TODO-TUNE` / `TODO-CONFIRM`. Paths will not
follow correctly until they're real:

| Value | Source |
|---|---|
| ~~`forwardPodY` = 3.2, `strafePodX` = -7.0~~ | **DONE** — Offsets Tuner, 2026-08-03 |
| ~~`encoderResolution` = 4-bar~~ | **DONE** — confirmed by team |
| ~~`mass` = 5.0 kg~~ | **DONE** — confirmed by team |
| `xVelocity` / `yVelocity` | Forward/Lateral **Velocity** tuners (Automatic folder) |
| `forward`/`lateralZeroPowerAcceleration` | Zero Power Acceleration tuners |
| PIDF coefficients | PID tuners, translational → heading → drive |

Tuning order matters — later steps assume earlier ones are correct. Do velocity and
zero-power-acceleration **before** PIDs: those are feedforward, so bad values make the PIDs
fight wrong predictions. Tuning PIDs first is wasted effort.

Run tuners on the **competition surface** — foam tiles vs. hard floor changes both velocity
and braking.

**Deploy state (2026-08-04):** the hub runs a build functionally identical to the repo. Edits
since the last successful `installDebug` are comments only. Constants are complete enough to
follow paths now; remaining placeholders (`xVelocity`, `yVelocity`, zero-power accel, PIDs)
affect *quality* of following, not whether it runs.

### ⚠ Upstream bug: lateral tuners say "right", actually go LEFT

**Both** lateral tuners in the *Automatic* folder command `setTeleOpDrive(0, 1, 0, true)`
(positive lateral) while their telemetry says the robot will run **to the right**. Positive
lateral is **LEFT**.

Proof, from Pedro's own reference teleop in the same file (~line 183):
```java
follower.setTeleOpDrive(-gamepad1.left_stick_y, -gamepad1.left_stick_x, ..., true);
```
FTC's `left_stick_x` is positive to the right, so negating it means stick-right passes a
NEGATIVE lateral. For that teleop to be correct, negative lateral = right, therefore
**positive lateral = left** (standard +Y convention). Pedro's own docs also say left.

Affected lines (upstream master, 2026-08-04): **486**, **662** (javadoc), **691**.

Reported upstream twice — Quickstart **#12** (closed 2025-09-27) and **#22** (closed
2025-12-27) — and **neither fix landed**. Quickstart has issues **disabled**, which likely
explains the bulk closures.

**FIXED UPSTREAM.** A PR from this workspace was merged on 2026-08-05:
<https://github.com/Pedro-Pathing/Quickstart/pull/84> — approved by BeepBot99, merge commit
`d3aea9c`. Note the repo: **`Pedro-Pathing/Quickstart`**, not `Pedro-Pathing/PedroPathing`
(where #84 is an unrelated PR by another author). The local `Tuning.java` still carries the
old text until it is re-copied from upstream.

**Impact is cosmetic** — lateral velocity and deceleration are symmetric, so tuning numbers
are still valid. But it cost this team hours: the robot moved left as instructed-otherwise,
and we wrongly suspected motor directions and localizer config. **Trust the robot, not the
telemetry text, on lateral direction.**

### Pinpoint has no ticks-to-inches multipliers

`ThreeWheelConstants`, `TwoWheelConstants`, and `DriveEncoderConstants` all expose
`forwardTicksToInches`/`strafeTicksToInches`. **`PinpointConstants` does not** — the Pinpoint
converts in firmware from the declared pod type. So the Localization folder's *Forward Tuner*
and *Lateral Tuner* multipliers have nowhere to be stored, and should already read ~1.0.

If they aren't 1.0, the only knobs are:

| Field | Meaning |
|---|---|
| `customEncoderResolution` | ticks per **`distanceUnit`** (INCH here — Pedro passes `constants.distanceUnit` to `setEncoderResolution`) |
| `yawScalar` | heading scale |

4-bar = **19.894367 ticks/mm = 505.3169 ticks/inch**; swingarm = 13.262912 ticks/mm.
To apply multiplier *M*: `customEncoderResolution = 505.3169 / M`.

**One value covers both axes** — forward and strafe cannot be corrected independently. If they
disagree, that's wrong pod type / slipping pod / bad offsets, not a resolution problem.

Don't confuse these with the **Velocity** tuners in the *Automatic* folder — those produce
`xVelocity`/`yVelocity` on `MecanumConstants` and are a required step.

### External tools (both verified live)

| URL | Use |
|---|---|
| https://visualizer.pedropathing.com | Draw paths on a field, export `PathBuilder` Java |
| https://javadoc.io/doc/com.pedropathing | Authoritative 2.1.2 API reference |

> The visualizer may emit **Pedro 1.x** code, which will not compile against 2.1.2. Tells:
> `Constants.setConstants(FConstants.class, LConstants.class)` or a two-arg `Follower`
> constructor. Translate to the 2.x `FollowerBuilder` API if so.

### The Blocks → Java port

`FieldCentricJava` is a faithful port of Blocks `Field Centric (Best)`. **Sign conventions
were preserved exactly, not "fixed":**
- `x` and `theta` are negated; `y` is **not** — despite a Blocks comment claiming it is.
  This is only correct because `FrontLeft`/`BackLeft` are REVERSE. Don't "correct" it
  without re-testing drive feel.
- Powers use a flat 0.25 / 0.5 multiplier with **no normalization** (no divide-by-max).

Added on top of the original: Panels telemetry (mirrored to the DS), live `@Configurable`
tunables, and the Panels virtual gamepad via `asCombinedFTCGamepad(gamepad1)` — which no-ops
to plain `gamepad1` when no browser gamepad is connected.

As of the 2026-08-27 refactor the math lives in `lib/MecanumDrive.drive()` and the
tunables (`SLOW_SPEED`, `INITIAL_MAX_SPEED`, `VEL_*`, …) are `@Configurable` statics on
`MecanumDrive`, not on the OpMode. Panels finds them either way — its `ClassFinder` scans the
whole classpath for `@Configurable`, it does not look only inside OpModes (verified against
`configurables-1.0.5.aar`).

> The Driver Station also lists `FieldCentricBest (Blocks to Java)` — an older OnBotJava
> auto-conversion living in `/sdcard/FIRST/java`, unrelated to this port. And the original
> Blocks `Field Centric (Best)` is still there as a fallback. Three similar names; pick
> **Field Centric (Java)**.

---

## Scratch files

`TeamCode/src/main/java/org/firstinspires/ftc/teamcode/PanelsDemo.java` — hardware-free
smoke test. Orbits a circle on the field view, streams telemetry, exposes `radius`,
`orbitRadius`, `speed` as live-editable `@Configurable` statics. Safe to delete.
