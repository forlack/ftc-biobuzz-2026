# FTC Workspace — Agent Notes

Living context for this workspace. Update as things change.

Last updated: 2026-10-24

---

## Total Chaos Dashboard + simulator — 2026-10-23 (branch `dashboard`, not merged)

Built while the robot was with another team member; verified only against the simulator.
Full guide: `dashboard/README.md`. Robot test procedure + log: `dashboard/ROBOT-TEST.md`.

**Handoff / state (2026-10-23):**
- Name: **Total Chaos Dashboard** (team Total Chaos 24621; BIOBUZZ is the season's game).
  Visible strings renamed; internal names (`org.biobuzz.sim`, `biobuzz.*` storage keys,
  `assets/biobuzz/`, Visualizer branch `biobuzz`, the BIOBUZZ field image) deliberately kept.
- The event on 2026-10-24 runs `main`. The first robot test of this branch is after that:
  follow ROBOT-TEST.md exactly and fill in its log. Merge into `main` only when the user asks,
  after a passing robot test. Leave the local checkout on `main` when not working on the branch.
- Unverified on hardware: everything. Specific unknowns: dashboard load on the Control Hub
  (`./dashboard-load`), Panels coexisting at :8001, `useHardwareMap` on Android (returns the
  Driver Station steps; switching via `RobotConfigFileManager` + robot restart is untested), the
  sim on the Mac (needs Java, e.g. Android Studio's JBR as JAVA_HOME).
- **Teleop telemetry** (2026-10-23): all of it in `FieldCentricJava.updateTelemetry()`, each
  motor speed read once per loop (was 3 hub reads per motor); `telemetry.addData` for lines
  shown on both, `Dash.put` for chart-only values (targets, `Flywheels on/ready`, sticks,
  `Loop ms`). Names match PedroAutonomous and the sim. Next idea after the first robot
  baseline: `LynxModule` bulk caching (AUTO) to cut hub reads further; compare with
  `./dashboard-load`.
- **Memory:** `Dash` ring buffer is 1,000 frames (~2 MB measured on the JVM with the teleop's
  ~23 values; it was 4,000 / 7-10 MB). Control Hub: RK3328 quad Cortex-A53, **1 GB RAM**
  shared with Android. Each loop still allocates two small maps (~2 KB of garbage per loop,
  ~200 KB/s at 100 Hz, even with no viewer); if robot loop times show GC spikes, reuse maps.
- Flywheel Tuner on this branch also feeds the Live tab's Flywheels chart (RPM only, no target
  lines); results still applied with A and saved to robot.json.
- Ideas discussed, not built: packaging as its own repo/library (two artifacts: core dashboard,
  and a Pedro add-on); robot pose on the field in teleop once Pinpoint coordinates are trusted;
  a per-viewer Detail selector; min/max thinning; listing USB cameras from the robot.

Key facts for agents:

- **Shared plain-Java core** (compiled into the app AND the desktop sim):
  `dashboard/{DashboardServer,Dash,RobotHost,Base64Codec}`, `lib/{RobotFiles,RobotSettings,
  Actions,StandardActions,Mechanisms,AutoRoute,AutoSequence}`, `pedro/ForesightConstants`.
  No Android or FTC SDK imports in these, Java 8 syntax, Android 7 APIs only (no
  java.nio.file, no java.util.Base64, no String.join). `-Pdashboard` lint: no NewApi.
- `RobotFiles.root()` is /sdcard/FIRST, or the sim's SD card (`biobuzz.storage` property).
- **Server** on port 8686: static page + JSON API + websocket telemetry (`Dash.put`/`frame`,
  every loop, ring buffer). Files API limited to settings/robot.json, autos/*.json,
  root *.xml (history/ and snapshots/ read-only); PUT validates (routes are fully built against the action list) and supports
  `X-Base-Hash` conflict refusal. Missing page files fall back to `visualizer/<path>`
  because the visualizer requests `/fields/...` etc. at the root.
- **Panels removed on this branch (2026-10-23)**: no `com.bylazar` dependency or Maven repo
  (verified: 0 references in the APK; `-Ptuning` still resolves). The dashboard replaces it and
  is in **every** build (`-Pdashboard` / `--dashboard` still accepted, no effect). Lost with
  Panels: the browser virtual gamepad (teleop now reads gamepad1/gamepad2 directly) and the
  `SAVE_TO_ROBOT` switch (use the Tune tab's Save).
- **Developer API** (guide: `dashboard/DEVELOPER.md`, modelled on FTC Dashboard/Panels):
  `@Configurable` (`teamcode.dashboard.Configurable`) marks settings classes;
  `TeamCode/build.gradle` generates `teamcode.generated.ConfigurableClasses.ALL` from sources
  containing a `@Configurable` line, and `new RobotSettings()` uses it (no runtime scanning; a
  DexFile scan was rejected because TeamCode classes span 7 dex files in debug APKs). The sim
  build generates its own list + mirrors the same way (`sim/build.gradle`). Telemetry:
  `telemetry = new DashboardTelemetry(telemetry)` (implements the SDK `Telemetry`, delegates
  to the Driver Station, `addData` → `Dash.put`, `update` → `Dash.frame`; the SDK skips its
  internal auto-update for non-`TelemetryInternal` telemetry, like FTC Dashboard's
  MultipleTelemetry, so one frame per `update()`). New `Mechanisms` methods should be
  `default` no-ops so the sim needs no change.
- **Android hook** `TeamCode/src/dashboard/java/.../AndroidDashboard` (`@OnCreateEventLoop`,
  starts after 5 s, all errors swallowed). Page packed as `assets/biobuzz/` (originally to
  avoid Panels' `assets/web/`; kept).
- **Routes** `autos/*.json` v2 (`lib/AutoRoute`): start, paths (points = start, controls, end;
  heading linear/constant/tangential; maxSpeed; events {at, action, params}), steps
  ({follow} | {action, params}). `PedroAutonomous.ROUTE` (a robot.json setting) picks the file,
  D-pad at INIT cycles; no file → INIT shows the problem (the old `paths/` Java-export
  reader, `update-paths` and their tests were removed on this branch 2026-10-23).
- **Simulator** `sim/` (own Gradle build, `./simulate`): compiles the shared core from TeamCode
  plus generated mirrors of the settings classes (same simple names, so robot.json is shared;
  regenerated from the real sources every build). Physics: hub velocity PIDF formula, 20 ms
  velocity sampling, voltage-dependent motor, shots, battery sag, mecanum chassis with Pedro's
  real Follower/Foresight through `SimPedro` Localizer/Drivetrain adapters.
- **Visualizer fork** github.com/forlack/Visualizer branch `biobuzz`: `src/lib/robot/`
  (RobotPanel, route export/import, field markers), `robotEvents` on paths, `robotAction` on
  sequence wait items, service worker removed (it cached the dashboard's `/`). Side panel
  has two tabs (2026-10-24): **Paths** (start point, selected path) and **Auton Sequence**
  (RobotPanel); both stay mounted, the choice is remembered in localStorage. Built into
  `dashboard/web/visualizer/` by `./build-visualizer` (committed).
- `./robot-sync` (HTTP to the dashboard, adb fallback; state in `.robot-sync.json`, ignored).
- Tests: `python3 -m unittest discover -s tests`; `node tests/e2e/dashboard.e2e.mjs` (91
  browser checks vs a fresh sim on 8687; headless Chromium from ~/.cache/ms-playwright).
  Kill a stray test sim with `pkill -f "[-]-port 8687"` (a bare `pkill -f biobuzz-sim` matches
  the agent's own shell). The user's `./simulate` runs from a private copy of the build, so
  rebuilding never crashes it, but it must be restarted to pick up changes.
- **Hardware maps** (`web/js/hardware.js`): any `*.xml` on the robot can be opened (picker marks
  the active one "(in use)", read from `RobotConfigFileManager.getActiveConfig()`); New map
  (Control Hub + IMU, optional Expansion Hub at an address, default 2), Save as copy, add/remove
  the one Expansion Hub, Control Hub always listed first (file order kept), webcams and other
  root-level USB devices shown for renaming (serials come from a Driver Station scan). Saving
  an unchanged map reproduces 2222-Config.xml byte for byte (checked 2026-10-23).
  `POST /api/hardware/active` → `RobotHost.useHardwareMap`: the sim switches (remembered in
  `<sdcard>/.sim-active-config`); Android returns Driver Station steps. `REQUIRED` in
  hardware.js lists the device names the Java code needs; keep it in step.
- **Adding things** (settings, Dash values, auton functions via Mechanisms + StandardActions +
  SimMechanisms): see "Adding things" in dashboard/README.md.
- **Robot cost: measured by the dashboard itself, in loop ms.** Dash times its own put/pose/
  frame calls and adds `Dashboard ms` to every frame (`Dash.COST_KEY`; direct in-loop cost).
  The user cares about loop time, not CPU %: the Live toolbar shows `Loop X ms · dashboard +Y ms
  · KB/s`. `/api/state` "load" (Robot tab Load card): viewers, loopMs, dashboardMs, KB/s, and
  for diagnosis dashboard CPU (send thread, % of one core) and whole-app CPU (/proc/self/stat,
  % of one core, assumes 100 ticks/s). `Loop ms` itself is still sent by each OpMode (a
  central one in Dash.frame would also time the sim's 20 Hz idle frames). `./dashboard-load [--url ...]` prints 0/1/2 viewers. Sim on
  the desktop after the stream rewrite: ~0.3% of a core total for 1-3 viewers, 14 KB/s
  (~145 B/loop) per viewer (was 40 KB/s, 410 B/loop), no loop-time change (the sim paces
  itself at 100 Hz, so only the robot can show a loop-time cost). Not yet measured on
  the Control Hub. Knob (setting, Tune tab): `DashboardConfig.MAX_SAMPLES_PER_SECOND` (0 = every
  loop). Stream (`TelemetryStream`): one thread builds each batch once (20/s) for all viewers;
  names sent once per connection then values by position, text only on change; viewers not on
  the Live tab send `{type:subscribe, frames:false}` and get status only. Browser decoder:
  `dashboard/web/js/frames.js`. Teleop battery read throttled to
  4 Hz (a voltage read is a hub transaction).

## Competition update — 2026-10-24

**Merged into `dashboard` on 2026-10-24** (merge of main `3d9495f`): compensation and
STINGER_DOWN 0.42 brought over; the formula is in `lib/FlywheelCompensation.java` (plain Java,
shared with the simulator; FieldCentricJava.compensatedFlywheelF delegates). On this branch
the "Panels" settings below are on the Tune tab; teleop's one 4 Hz battery read (lowest hub)
feeds both the F and `Battery V`; teleop and auton show `Flywheel compensated F`. The route
changes went into `autos/saturday.json` (start y 9, path2 end heading 90°); the main-only
Pedro export reader (`PedroPathExport`, `paths/`) stays removed here. Check the robot's saved
robot.json: a saved `FLYWHEEL_F = 12` overrides the new 11 default. **`FLYWHEEL_FIXED_F` removed
on this branch** (user's call, 2026-10-24): compensation off now uses `FLYWHEEL_F` at every
voltage (11, not main's 12; set FLYWHEEL_F = 12 for the pre-competition behaviour). A saved
FLYWHEEL_FIXED_F in robot.json only gives a settings warning; the next Save drops it. FlywheelTuner still writes
one FLYWHEEL_F (now meaning F at 12.5 V).

Local competition main now applies voltage-compensated flywheel F in TeleOp and
PedroAutonomous: linear F=11 at 12.5 V, F=12 at 10 V, clamped outside this range.
Panels FLYWHEEL_VOLTAGE_COMPENSATION (default true) toggles this live in both
OpModes; false uses FLYWHEEL_FIXED_F (default 12, prior competition behavior).
Panels FLYWHEEL_F is the 12.5 V endpoint; FLYWHEEL_F_AT_10V is the low endpoint.
Voltage sampled at 4 Hz, lowest positive finite hub reading, previous value if invalid.
F is rounded to 0.01 and sent only when coefficients change. Intake PIDF unchanged.
Dashboard shows battery V and applied F. Prior discussions never landed compensation.
Current path start updated to (56,9,270°), park endpoint heading now 90°; sequence remains path1, shoot 3.5s, path2 park.
Reader now accepts Pedro imports/class wrapper as well as snippets; exclude webpage text.
Stinger-down default is 0.42. User confirmed autonomous and TeleOp auto-queue worked
on competition day. Panels telemetry intermittently froze; cause unconfirmed (hub
unreachable for log inspection). Do not attribute the earlier auton failure conclusively
to export format: user reports no file edit at that time, and failure logs are unavailable.
After competition, user requested these tested changes pushed to main and will integrate
into dashboard themselves. Dashboard branch has not been deployed or merged here.

## Robot settings and the no-rebuild roadmap — 2026-10-22

- **Event Saturday 2026-10-24** with one autonomous (the current path-file route). Keep
  changes before then low-risk; roadmap steps 2–4 come after.
- **`robot.json`:** `lib/RobotSettings` saves every public static setting of
  `FieldCentricJava.SETTINGS` (`FieldCentricJava`, `MecanumDrive`) to
  `/sdcard/FIRST/settings/robot.json` when Panels `RobotSettings.SAVE_TO_ROBOT` is turned on
  (teleop loop), and loads it at INIT in teleop and `PedroAutonomous`. Reloads only when the
  file's mtime changed, so unsaved Panels edits survive re-INIT. Bad values warn and keep
  the default; an unreadable file falls back to `robot.json.bak`, then to code defaults.
  `@RobotSettings.NotSaved` marks live switches (`FLYWHEELS_ON`). Repo copy:
  `settings/robot.json` via `./robot-sync pull|push`. Details: `settings/README.md`.
- **AutoTune is opt-in.** Its NanoHTTPD server starts unconditionally whenever the
  `tuning` library is in the APK (a Sinister `OnCreateEventLoop` hook; no off switch), and it
  crashed the RC app at boot once. `Tuning.java` and `procedures/` moved to
  `TeamCode/src/tuning/java`; `TeamCode/build.gradle` adds that folder and the dependency
  only with `-Ptuning`. Deploy with `./deploy-linux --tuning` (or `./deploy --tuning`) to tune;
  a normal deploy removes it again. Verified: normal APK has no autotune classes.
- **Flywheel Tuner** (`helpers/FlywheelTuner`, Diagnostics): both flywheels at their
  robot.json target RPMs. F from a two-point speed-vs-power line (0.4 and 0.8 power, no
  encoder) solved for the power at target: F = 32767 × power / target ticks/s. P from step
  tests (70% → 100%) at 0.25–3× the current P: fastest settle within 2% with overshoot ≤ 5%
  and wobble ≤ 1.5%; fallback = closest to target. A applies (shared FLYWHEEL_F = average of
  the two) and saves robot.json. Scoring checked in a desktop simulation; untested on hardware.
- `@Sorter` annotations removed from `FieldCentricJava` (Panels order only; user found it
  bulky).
- **Roadmap** for autos as JSON and our own Pedro visualizer with robot actions on paths:
  `ROADMAP.md`.

## Desktop handoff — 2026-10-22 (main; superseded on branch `dashboard`)

On `main` the auton is the pasted Pedro export in `paths/` (auto.java.txt + auto.json,
uploaded by deploy and `./update-paths`). On `dashboard` those are removed: `autos/*.json`
holds whole autons and deploy uploads only autons the robot does not have. Hub is Android
7.1.2: use FileInputStream, never java.nio.file. Intake default 700 RPM. Run deploy-linux
from the user's terminal, never an agent session. Never invent hardware changes.

## Hardware

> **Current code targets Team 24621's competition robot.** The test-platform notes
> below are historical; current Pinpoint and Foresight values were supplied on 2026-10-17.
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

Forward/backward (X) odometry pod physically flipped on 2026-10-15: current Pedro
`xPodDirection` is FORWARD, `yPodDirection` remains REVERSED. Measured competition-robot offsets (2026-10-17) are
`xPodOffset = 0.20296776388573837` inches and `yPodOffset = 0.2779039060036967` inches. Historical test-chassis measurements below
do not describe this mounting. Verify with Localization Check: pushing forward increases X.

Hardware names are case-sensitive. Updated from the XML on 2026-10-10:

| Device | Name | Hub | Port | Type |
|---|---|---|---|---|
| Motor | `frontLeft` | Control Hub (173) | 0 | goBILDA 5202 |
| Motor | `backLeft` | Control Hub (173) | 1 | goBILDA 5202 |
| Motor | `intake` | Control Hub (173) | 2 | goBILDA 5202 |
| Motor | `shooterNectar` | Control Hub (173) | 3 | goBILDA 5202 |
| CR servo | `transportNectar` | Control Hub (173) | 0 | continuous rotation |
| CR servo | `triggerNectar` | Control Hub (173) | 1 | continuous rotation |
| Motor | `frontRight` | Expansion Hub 2 (2) | 0 | goBILDA 5202 |
| Motor | `backRight` | Expansion Hub 2 (2) | 1 | goBILDA 5202 |
| Motor | `shooterPollen` | Expansion Hub 2 (2) | 2 | goBILDA 5202 |
| CR servo | `transportPollen` | Expansion Hub 2 (2) | 0 | continuous rotation |
| CR servo | `triggerPollen` | Expansion Hub 2 (2) | 1 | continuous rotation |
| Servo | `stinger` | Expansion Hub 2 (2) | 2 | positional |
| Odometry | `pinpoint` | Control Hub (173) | I2C bus 0 (shares it with the IMU; DS slot 1) | goBILDA Pinpoint |
| IMU | `imu` | Control Hub (173) | I2C bus 0, port 0 | Control Hub BHI260AP |

The drivetrain is split across both hubs. Motor names changed from `FrontLeft`,
`BackLeft`, `FrontRight`, `BackRight` to `frontLeft`, `backLeft`, `frontRight`,
`backRight`; TeleOp, Pedro and Motors Test use the new names. Existing intake and
pollen-trigger code keeps the same names despite the port changes. TeleOp now controls
both shooters, both transport servos and both triggers. Intake toggles on B; bumpers
hold the matching gates open. Shooters and transports run continuously after PLAY.
The pollen shooter, transport and trigger use REVERSE direction with positive power.

**The config XML is versioned** at `robot-config/2222-Config.xml` (pulled 2026-10-09). Push
it to a hub with `./deploy -m` / `./deploy-linux -m` (pushes `robot-config/*.xml` to
`/sdcard/FIRST/` before the install, so the app restart loads it), then Activate on the DS if
it isn't already the active config. Re-pull and commit
after every DS config change, or the repo copy goes stale. See README.

Drivetrain is **mecanum**. TeleOp IMU orientation: logo **DOWN**, USB **LEFT** (updated 2026-10-10).
Motor directions: `frontRight`/`backRight` FORWARD, `frontLeft`/`backLeft` REVERSE. All BRAKE.

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

### Dead-wheel odometry math (reference — the Pinpoint does this in firmware)

Worth understanding because it is *why* the offsets exist. **This is the classic 3-pod
derivation, which our Pinpoint does NOT use for heading** — it has its own IMU.

Two parallel pods, `gap` apart, with +X forward, +Y left and counter-clockwise positive:

```
dTheta   = (dR - dL) / gap               # R travels further => turning LEFT (CCW, positive)
dForward = (dL + dR) / 2                 # symmetric pods; cancels rotation
dStrafe  = dPerpPod - dTheta * d_perp    # remove the perpendicular pod's rotation share
```

`d_perp` is the perpendicular pod's fore/aft offset — exactly `strafePodX`. **That
subtraction is what the offsets are for.** Get its sign wrong and the robot spirals.

Mind the sign convention: a point on the **left** moves *backward* when rotating CCW, so the
left pod reads **less**. Hence `(dR - dL)`, not the reverse.

Then rotate the robot-frame delta into field coordinates. Using the mid-step heading is the
cheap approximation:

```
theta_mid = theta + dTheta/2
dx_field  = dForward*cos(theta_mid) - dStrafe*sin(theta_mid)
dy_field  = dForward*sin(theta_mid) + dStrafe*cos(theta_mid)
```

The robot actually traces an arc, not a chord. The exact integration (the "pose exponential"
that Pedro and Road Runner use) is:

```
dx_arc = ( dForward*sin(dTheta) + dStrafe*(cos(dTheta) - 1) ) / dTheta
dy_arc = ( dForward*(1 - cos(dTheta)) + dStrafe*sin(dTheta) ) / dTheta
# then rotate by the PREVIOUS heading; guard dTheta ~ 0 to avoid dividing by zero
```

The two agree to many decimals at small `dTheta`; the difference shows up in fast tight
turns, where the chord cuts the corner and the error accumulates across a whole auto.

**Robot network:** competition SSID is `24621-RC`, team **24621**. The Control Hub
is the AP at **192.168.43.1**. Credentials remain outside Git in private deploy settings.
The historical filename `2222-Config.xml` does not determine the Wi-Fi or team name.

The laptop's normal WiFi (10.0.0.x) **cannot reach the robot**. To talk to the
Control Hub, either plug in USB (preferred — keeps internet) or join its Wi-Fi.

---

## Software versions

Both sides must match or the Driver Station nags about it (warning only, but it fails
competition inspection).

- FTC SDK / Robot Controller: **12.0** (BIOBUZZ, 2026-2027 season)
- Driver Station app: **12.0** — must be updated to match, or the DS nags
- Pedro Pathing: **2.1.2** — builds clean against 12.0, verified 2026-09-12
- Panels dashboard: **fullpanels 1.0.13** (Field 1.0.7; BIOBUZZ field images)
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
implementation 'com.bylazar:fullpanels:1.0.13'     // bylazar maven
```

**`build.common.gradle`** — `compileSdkVersion 30` → `compileSdk 34`.
Pedro's androidx transitives refuse to compile against 30. `minSdk`/`targetSdk` are
**unchanged**, so runtime behavior matches stock. The official Pedro Quickstart makes the
same bump, so this is sanctioned, not a hack.

`local.properties` (points at the SDK) is gitignored and won't follow to a team repo.

---

## Panels dashboard

> **Branch `dashboard`:** Panels is removed there (replaced by the Total Chaos Dashboard).
> This section describes `main`.

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

### Why "Enable/Disable Panels" has no Android icon on the DS

Cosmetic upstream omission, not a problem with our setup. The DS picks an OpMode's icon from
`OpModeMeta.Source` (`ANDROID_STUDIO`, `BLOCKLY`, `ONBOTJAVA`, `BUILTIN`). Panels builds its
entry with `setName`/`setFlavor`/`setGroup` and never calls `setSource`, and the `Builder`
constructor leaves `source = null` — so there is no icon to draw. Our OpModes get
`ANDROID_STUDIO` automatically from the `@TeleOp` annotation scanner.

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

### macOS Wi-Fi deploy helper (2026-09-12)

Run `./deploy` from the repository root. Private Wi-Fi configuration lives
outside Git in `~/.config/ftc/deploy-wifi.json`, mode 600. Never copy credentials or
personal network names into tracked files. The helper does not access Keychain.
`--check` validates setup without switching. When macOS hides the SSID, the
configured return network is used. Missing passwords are requested with hidden
input and saved to that file; an empty password marks an open network. `-r`
selects and remembers the robot Wi-Fi; `-c` selects and remembers the network to
come back to. With neither flag, both remembered
networks are used. Known names match case-insensitively with spaces and punctuation
ignored. `-h`/`--help` shows usage.

Live testing confirmed that supplying passwords explicitly fixed networksetup's
-3900 join failures. An ADB disconnect failure when no transport existed is now
nonfatal. The complete build, Wi-Fi switch, offline install, and return cycle
passed using credentials in memory; the private-file configuration is a later
change. Tests simulate cleanup after success, failure, timeout, and interruption.

USB on this personal Mac works through a dock's USB-A connection to the Control
Hub's USB-C port; the user confirmed an Android Studio deployment. Direct USB-C
failed to enumerate. Its cause is unconfirmed. The SDK's ADB 37.0.1 server is now
in use.

```fish
adb devices                              # confirm hub is on USB
./gradlew :TeamCode:installDebug
```

### Linux Wi-Fi deploy helper (`deploy-linux`, 2026-10-11)

Counterpart to the macOS `deploy` for this CachyOS box. Uses NetworkManager: `nmcli connection
up <saved connection>` to join the robot and to return, so it never touches a Wi-Fi password —
the robot network must be saved once via the Wi-Fi menu. Same flags as the Mac script (`-r`,
`-c`, `--stay`, `-m/--hardware-map`, `--map-only`, `--check`; `./update-hardware-map` runs
the right one with `--map-only`: push XML, `am force-stop` + relaunch the RC app, no build), same build-first ordering, same restore-in-`finally` with Ctrl+C
ignored during restore, same adb kill-server escalation. Prefers the SDK's adb from
`local.properties`. Tests: `tests/test_deploy_linux.py`.

**Do not run it from an agent session on this machine** — joining the robot network drops the
agent's internet mid-run.

### Deploying over Wi-Fi instead of USB

`persist.adb.tcp.port = 5555` is already set on our Control Hub, so adb-over-TCP is on
permanently and survives reboots — no `adb tcpip` step. The hub is its own AP at
**192.168.43.1**.

```fish
adb connect 192.168.43.1:5555
./gradlew --offline :TeamCode:installDebug
# dashboard: http://192.168.43.1:8001   (no adb forward needed on this network)
```

**`--offline` is mandatory here.** The robot network has no internet, and Gradle will otherwise stall
reaching Maven Central. Verified working — the build only needs the network to *download*
dependencies, and everything is already in `~/.gradle/caches`. Corollary: a new machine, or a
dependency version bump, must sync once on real internet first or `--offline` fails with "no
cached version". Android Studio's Run button does **not** pass `--offline`; toggle it in the
Gradle tool window or sync hangs.

**Multiple devices.** With both hubs on USB — and again if a Wi-Fi connection is added on top
— Gradle refuses to pick one. Name it:

```fish
ANDROID_SERIAL=0ef75e560c41cbdf ./gradlew :TeamCode:installDebug   # Control Hub over USB
ANDROID_SERIAL=192.168.43.1:5555 ./gradlew --offline :TeamCode:installDebug
```

This also matters for plain adb commands: `adb -s 0ef75e560c41cbdf logcat` etc., or you may
be talking to the Driver Hub by mistake.

### Deploy troubleshooting (all hit 2026-09-12)

**adb over Wi-Fi wedges as `offline`.** `adb connect` then reports "already connected" and
no-ops, so it never re-handshakes and stays offline forever. `adb disconnect` alone does not
clear it — verified. Recovery, in order:

```fish
adb disconnect 192.168.43.1:5555
adb kill-server && adb start-server
adb connect 192.168.43.1:5555
```

If still offline, **power-cycle the Control Hub**. "Restart Robot" on the DS restarts only the
app, not adbd. Cause here was switching transports without a clean disconnect, leaving adbd
holding a dead session.

**`--offline` needs a real BUILD first, not a Gradle sync.** A sync resolves enough to
configure the project; it does not download everything `assembleDebug` needs. On a new machine
run `./gradlew :TeamCode:assembleDebug` on real internet until BUILD SUCCESSFUL, *then*
`--offline` works. Symptom otherwise: "Could not resolve all files for configuration".
Competition rule: build successfully on internet before leaving, and after any dependency bump.

**Two adb binaries is a trap.** adb is a client plus a server on port 5037, and **only the
server touches USB**. Running a different adb binary does nothing if an old server is still
alive — the new client just connects to the old server. Use `pkill -f adb` (not
`adb kill-server`, which can leave a zombie), and check ownership with `lsof -i :5037`. Keep
only the Android SDK's adb; delete any Homebrew one.

**Wi-Fi adb working while USB fails is not a contradiction.** TCP adb is just a socket; USB
adb has to claim the device. So a broken or blocked adb can work perfectly over Wi-Fi and see
nothing over USB.

**Check the OS's own USB tree before suspecting anything clever.** A charge-only cable is
indistinguishable from a policy block. Check `System Report -> USB` (or `ioreg -p IOUSB -w 0`; `system_profiler
SPUSBDataType` is unreliable on Apple Silicon) — if the device is not in the OS's own tree,
suspect cable, dongle, or port before anything clever. On that Mac the hub eventually appeared
with a different cable but adb still could not claim it, which is consistent with an
endpoint-management agent allowing enumeration but blocking interface claim. Unconfirmed.

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

**It does NOT recur per machine.** Corrected 2026-10-09 — earlier versions of this file
said each computer's `~/.android/debug.keystore` would cause a rejection when switching
machines. Wrong: `build.common.gradle` signs both debug and release builds with
`libs/ftc.debug.keystore`, **which is committed in the repo**, so every clone signs
identically. Evidence: on 2026-10-03 the Linux box installed over the Mac's build with no
rejection. The August mismatch was real only because the hub held the *factory* FIRST-signed
app. Switching between the Linux box, the Mac and any laptop needs no key copying.

---

## Setting up on another machine

Android Studio brings everything. **As of SDK 12.0 no separate JDK install is needed** — see
the JDK note below, which used to be the main obstacle and no longer is.

1. **Install Android Studio.** SDK 12.0 requires **Narwhal 3 Feature Drop or later**.
2. **Clone the repo.** Open the **`FtcRobotController/`** folder — the one containing
   `settings.gradle` — *not* its parent, and not the nested module of the same name.
3. **Gradle JDK → Embedded JDK**, before the first sync.
4. **Sync**, on real internet. Studio fetches Gradle 9.1, the FTC SDK, Pedro and Panels.
5. **Then run an actual build**, still on internet:
   `./gradlew :TeamCode:assembleDebug`. A sync is *not* enough — see the `--offline` note in
   Deploy troubleshooting.
6. Deploy with the Run button or `./gradlew :TeamCode:installDebug`.

### macOS specifics

Verified on a work MacBook 2026-09-12.

- **Gradle JDK: Embedded JDK.** No Homebrew JDK, no `brew install openjdk`.
- **adb must come from the Android SDK, not Homebrew.** Homebrew's adb worked over Wi-Fi and
  could not see the hub over USB — TCP adb is just a socket, USB adb has to claim the device.
  Put the SDK's first on PATH and delete the Homebrew one:
  ```bash
  echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.zshrc
  brew uninstall android-platform-tools
  ```
  macOS does **not** put adb on PATH for you, and needs no USB driver (unlike Windows).
- **`system_profiler SPUSBDataType` is unreliable on Apple Silicon** — often prints nothing
  useful. Use `ioreg -p IOUSB -w 0`, or the GUI System Report → USB.
- **A work-managed Mac may block adb from claiming USB devices** while still letting macOS
  enumerate them: visible in System Report, invisible to `adb devices`, with a fresh server and
  the correct binary. Consistent with an endpoint-management agent; unconfirmed on ours. Wi-Fi
  deploy sidesteps it entirely, since no USB is involved. Prefer a personal machine.
- **Personal GitHub on a work machine:** use a per-repo **deploy key** with write access rather
  than signing in — generate the key on the Mac, add the public half from another device, and
  no account credential ever lands on managed hardware.

### The JDK gotcha — RESOLVED as of SDK 12.0

Historical, kept because it bit us for weeks and the old advice is still all over the internet.

Under **Gradle 8.9** (SDK 11.x) the valid JDK window was **17–22**: AGP 8.7 needed ≥17, Gradle
8.9 accepted ≤22. Android Studio 2026.1 bundles **JBR 25**, so a fresh install failed on first
sync with *"select a JVM version that is at least 8 and at most 22."* That forced an explicit
JDK 17 and is why `~/.gradle/gradle.properties` on the Linux box pins
`org.gradle.java.home`.

**SDK 12.0 moved to Gradle 9.1 / AGP 8.13.2, and JBR 25 builds cleanly** — verified
2026-09-12 with `./gradlew -Dorg.gradle.java.home=/opt/android-studio/jbr`. So **Embedded JDK
is now the right answer** on a new machine, and the Linux JDK-17 pin is harmless but no longer
required.

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
| **The build toolchain** | JDK **17+** | AGP 8.13 needs ≥17; Gradle 9.1 is happy on JBR 25 |

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
- [x] Toolchain from scratch; builds with zero environment variables
- [x] SDK 12.0 (BIOBUZZ, 2026-2027), merged on release day 2026-09-12
- [x] Panels 1.0.17 (BIOBUZZ field images)
- [x] **Pedro Pathing 3.0.1**, merged 2026-10-03 and verified on the test chassis:
      AutoTune ran, Tests pass, Localization Check reads correctly, the out-and-back test path
      returns to its start
- [x] Found and fixed a real AutoTune bug (Pinpoint pods always reported FORWARD); our copy is
      fixed and **upstream PR #115 merged 2026-10-08**
- [x] Teleop framework (`lib/`) hardware-verified; stick deadzone added
- [x] Autonomous framework `lib/AutoSequence` with follow / run / wait / `at()` markers /
      `aimAt()` / `turnTo()`
- [x] First mechanisms written by the student: `intake` motor (toggle) and `triggerPollen`
      CR servo (hold), plus `toggle()`/`active` added to `Button`

Next:
- [ ] **Tune the velocity motors** with F = 12 as the start (see Velocity motors below):
      check each settles at target, then trim F per motor, then P
- [x] Re-copied upstream `PinpointTuner.java` and `ForesightTuner.java` (2026-10-14); both now
      match Quickstart master exactly. Pedro libs are still the latest (revhub 3.0.1, tuning 1.0.1)
- [ ] Upstream **Quickstart PR #120** (opened 2026-10-14): `Tests.java` Line/Curve/Interpolation
      ignored the Distance input (a local `double distance = 48;` shadowed the field). Fixed
      locally the same way and verified on hardware; re-copy upstream once it merges
- [x] Removed `PanelsDemo`, `TemplateTeleOp`, `TemplateAutonomous` and `NEXT_STEPS.md`;
      moved `MotorsTest` and `LocalizationCheck` into `helpers/` (2026-10-22)
- [ ] Optional: rename the robot SSID and config file to the real team number; delete the
      16 MB of stale `/sdcard/FIRST/java/srcBackups/` zips

Competition-robot Pinpoint and Foresight results supplied on 2026-10-17 are applied in
`pedro/Constants.java`. Drivetrain names/directions are unchanged. Run the Tests procedure
after deployment to validate these values on hardware.

---

## OpModes in TeamCode

| Class | DS name | Group | Purpose |
|---|---|---|---|
| `FieldCentricJava` | Field Centric (Java) | Drive | Java port of the Blocks teleop |
| `helpers.MotorsTest` | Motors Test | Diagnostics | Robot- and motor-level direction checks |
| `helpers.LocalizationCheck` | Localization Check | Diagnostics | Pinpoint direction and distance check |
| `helpers.FlywheelTuner` | Flywheel Tuner | Diagnostics | Measures flywheel F and P; A saves to `robot.json` |
| `PedroAutonomous` | Pedro Pathing Autonomous | — | Out-and-back path, state machine |
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

### Pedro 3.0.0 — released 2026-09-10; ADOPTED 3.0.1 on 2026-10-03

Historical reasoning below, kept for context. We took **3.0.1** after it had a patch release.

We build against **2.1.2**. v3.0.0 is a major release and changes things we have already
done, so it was deliberately deferred rather than skipped:

- **Foresight** replaces Predictive Braking — our measured `kLinear 0.0574 / kQuad 0.00376`
  are Predictive Braking constants and would stop applying.
- **New Path API**, "much less verbose" — breaking changes to `PedroAutonomous`.
- **Pose Factory** — a new way to build poses, so `Constants.java` likely shifts.
- **AutoTune** — a robot-hosted tuning webpage that replaces the `Tuning.java` OpMode.

Reasoning for waiting: it was two days old, and taking it the same day as the SDK 12.0 jump
would have made any failure impossible to attribute. The argument *for* taking it is real
though — we are between robots, every constant gets re-measured on the competition chassis
anyway, and AutoTune could make that re-tune much cheaper. **Evaluate on a branch, not in
place.** If we take it, re-copying the old `Tuning.java` is wasted work.

### Pedro 3 AutoTune — what we learned (2026-10-03, branch `pedro-3`)

Ran all four procedures on the test chassis. Mecanum and Foresight output was used as-is.
**The Pinpoint strafe pod came out wrong and had to be hand-corrected:**

| | AutoTune said | Correct, proven on hardware |
|---|---|---|
| `yPodDirection` | FORWARD | **REVERSED** |
| `yPodOffset` | −6.58 | **+6.58** |

Symptoms of the inverted strafe axis: the Tests **hold test drifted when pushed** (it
corrected in the direction of the push), the **pod test stalled and failed**, and the line
test overshot. First run, the student spun the robot clockwise when it asked for
counter-clockwise — but re-runs still showed FORWARD. **Root cause found and fixed
2026-10-03:** the stock tuner reads its answer *after Stop*, when the Pinpoint has already
reset `y` to 0, so `y < 0` is always false and every pod reports FORWARD regardless of the
push. Instrumented log: y = −14.96" during a 15" left push, then `FINAL y=0.0`. Our
`PinpointTuner.java` keeps the last pre-Stop reading; verified on hardware, it now returns
REVERSED / +6.62 on its own. Reported upstream as **Quickstart PR #115**, <https://github.com/Pedro-Pathing/Quickstart/pull/115> (opened 2026-10-03; **merged 2026-10-08** by maintainer BaronClaps). Still worth the
Localization Check Run **Localization Check** (Diagnostics):
pod end away from you, push LEFT, and `y` must go UP.

Flipping the encoder direction flips the offset's sign, so keep the measured magnitude and
negate it.

Other 3.x gotchas hit:
- **Natural deceleration is POSITIVE in 3.x** (tuner: 35.6 / 56.2). The 2.x zero-power
  accelerations were negative; carrying one over made Tests throw a config error.
- **AutoTune is only in `--tuning` builds** (see the 2026-10-22 note at the top).
- **There is no Tuning OpMode on the Driver Station.** AutoTune is the web page at
  `http://localhost:10158` (USB, after `adb forward tcp:10158` and `tcp:12649`) or
  `http://192.168.43.1:10158` on robot Wi-Fi. It launches its own OpModes.
- The web servers take ~30 s after app start to bind, and adb forwards drop on every
  install — re-add them.

### Pinpoint `setOffsets` argument order

Counter-intuitive and easy to get backwards. From Pedro's own `PinpointLocalizer.java:60`:

```java
setOffsets(constants.forwardPodY, constants.strafePodX, constants.distanceUnit);
// → odo.setOffsets(xOffset, yOffset, unit);
```

So the driver's **first** argument is `forwardPodY` and the **second** is `strafePodX`:

```java
pinpoint.setOffsets(2.125, 6.15, DistanceUnit.INCH);   // forwardPodY, strafePodX
```

goBILDA names each argument after the pod's *measuring* axis, not the axis the offset runs
along — so the **forward** pod's offset goes in the **x** slot. Encoder directions take the
obvious order: `setEncoderDirections(forward, strafe)` = `(FORWARD, REVERSED)` for us.

Offsets only correct rotational contamination, so a robot pushed in straight lines reads
correctly even with them at zero; errors appear only when it rotates. Good demo: zero the
offsets, spin in place, watch x/y wander.

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
  This is only correct because `frontLeft`/`backLeft` are REVERSE. Don't "correct" it
  without re-testing drive feel.
- Powers use a flat 0.25 / 0.5 multiplier with **no normalization** (no divide-by-max).

Added on top of the original: Panels telemetry (mirrored to the DS), live `@Configurable`
tunables, and the Panels virtual gamepad via `asCombinedFTCGamepad(gamepad1)` — which no-ops
to plain `gamepad1` when no browser gamepad is connected.

As of the 2026-08-27 refactor the math lives in `lib/MecanumDrive.drive()` and the
tunables (`SLOW_SPEED`, `INITIAL_SPEED`, `VEL_*`, …) are `@Configurable` statics on
`MecanumDrive`, not on the OpMode. Panels finds them either way — its `ClassFinder` scans the
whole classpath for `@Configurable`, it does not look only inside OpModes (verified against
`configurables-1.0.5.aar`).

> The Driver Station also lists `FieldCentricBest (Blocks to Java)` — an older OnBotJava
> auto-conversion living in `/sdcard/FIRST/java`, unrelated to this port. And the original
> Blocks `Field Centric (Best)` is still there as a fallback. Three similar names; pick
> **Field Centric (Java)**.

---

## Autonomous framework (`lib/AutoSequence`)

### Auton files

`PedroAutonomous` reads `autos/<ROUTE>.json` (lib/AutoRoute) at INIT; see the dashboard
section at the top of this file. Android 7: FileInputStream only.

Built 2026-10-03 for Pedro 3. Same rule as teleop: **never block** — `update()` once per loop
advances when the current step finishes.

```java
auto = new AutoSequence(follower)
        .follow(toShootSpot)                       // drive a Path
        .at(0.7, () -> arm.setPower(1))            // fire part way along that path
        .aimAt(GOAL)                               // stand still, turn to face a point
        .run(() -> shooter.setVelocity(SHOOT))     // one-shot action
        .waitUntil(() -> shooterAtSpeed())         // gate on a condition
        .waitSeconds(0.4)
        .follow(home);
```

- `at(fraction, action)` uses `follower.completion()`. A marker the path never reaches **still
  fires at step end** — skipping it silently would strand a mechanism.
- `aimAt(pose)` / `turnTo(radians)` hold position via `follower.hold()` until heading error
  < `AIM_TOLERANCE_DEGREES` (2°). For aiming *while* driving use
  `Paths.line(a, b).facingPoint(GOAL)` instead — no step needed.
- BIOBUZZ shape: intake runs the whole match (set in `start()`), so the real steps are drive,
  aim, shoot. `PedroAutonomous.java` is the worked example.

## Velocity motors (`lib/VelocityMotor`)

Written by the student 2026-10-10 — a flywheel lesson, generalized. Used for
`shooterPollen`, `shooterNectar` (28 ticks/rev, the default) and `intake` (145.1 ticks/rev).
Each motor has its **own** PIDF, set only through `setPIDF()`; the fields are private
(2026-10-11) because writing `shooter.P = 50` changed the number without sending it to the
motor. Read them with `getP()`…`getF()`.

**Defaults: P = 200, I = 0, D = 0, F = 12** (F set 2026-10-11, was 0).

**Why F matters — the "always 200 RPM low" symptom.** With F = 0 the shooter settled about
200 RPM under target (4000 asked, ~3800 read) every time. That is steady-state error from
P-only control: P outputs power only from error, so the wheel settles where the error is just
big enough to supply the power it needs to hold speed. More P shrinks the gap but never closes
it. F supplies that baseline power with no error required. Side effect of the old setup:
`atSpeed()` uses `RPM_TOLERANCE = 100`, so sitting 200 low meant **"ready" never went true**.

**F = 32767 / max ticks per second.** 6000 RPM × 28 / 60 = 2800 → F ≈ 11.7; intake 1150 RPM ×
145.1 / 60 ≈ 2781 → F ≈ 11.8. Hence 12 for all three to start. Refine per motor by running at
full power, reading `getVelocity()`, and using the measured value. Then: settles low → raise F
or add a little I; settles high → lower F; oscillates → P is now too strong on top of F.

## Student lessons

### Live mechanism RPM tuning (2026-10-10)

`FieldCentricJava.java` exposes Panels Configurables for `INTAKE_RPM` (300),
`POLLEN_FLYWHEEL_RPM` (3850), and `NECTAR_FLYWHEEL_RPM` (4050). FieldCentricJava
reads them every loop and reports target and measured RPM through DriveDashboard. Intake
still requires its toggle; flywheels run continuously after PLAY. All three motors use
lib/VelocityMotor with RUN_USING_ENCODER and setVelocity. RPM conversion assumes
28 ticks/revolution; confirm the actual motors. Current PIDF is P=200, I=D=F=0, applied
in the constructor, and needs hardware tuning. setPIDF(p, i, d, f) applies new coefficients
immediately. PIDF fields are not live Panels settings.
Live edits reset on RC app restart; save chosen defaults in FieldCentricJava.java. Motors Test is not
listed in Configurables. This OpMode uses a Configurable annotation directly by user preference.

## Known issue: AutoTune's web server can crash the RC app at boot

Seen 2026-10-09: `FATAL EXCEPTION ... java.net.SocketException: Socket is closed` at
`NanoHTTPD$ServerRunnable.run` during hub boot, which force-finished
`FtcRobotControllerActivity`. It recovered, but if that happens at a match the robot is dead
until the app restarts. If it repeats, load the `tuning` dependency only when tuning (or move
the `@Tuner` methods out of the competition build).

## Process rule: build what you commit, before pushing

On 2026-10-09 a commit was pushed that did not compile: the student had changed
`FieldCentricJava` to use a new `Button.toggle()` that was still uncommitted, and the
pre-push build check ran in a chain that pushed even when it failed. Fixed within a minute,
but `main` was broken. **Build the exact tree being committed, and push only if that build
passes** (`./gradlew ... | grep -q SUCCESSFUL && git push`). The student edits files between
reviews, so re-check `git status` right before committing.

## Hub contents (cleaned 2026-09-12)

`/sdcard/FIRST` was pruned so the Driver Station lists only our Android Studio OpModes.
**Full backup first:** `~/ftc/backups/20260912-144553-controlhub-FIRST/` (150 files, 17 MB,
including `2222-Config.xml`, blocks, and the OnBotJava sources).

Deleted: all 7 OnBotJava sources in `/sdcard/FIRST/java/src`, all 16 build artifacts in
`/sdcard/FIRST/java/build` (the jar/dex there is what actually registers those OpModes, so
deleting sources alone is not enough), and the one **autonomous** Blocks program,
*Example Auto-Odo with Explanations in English*.

Kept: the four Blocks **teleops** — *Field Centric (Best)* (the original our Java port came
from), *Pinpoint*, *Robot Centric (Better)*, *Robot Centric (Simplified)*. Also kept
`/sdcard/FIRST/java/srcBackups/` (16 MB of old build zips — dead weight, safe to delete).

Registration is re-scanned on RC app restart; `am force-stop` then relaunch is enough, and
the log line to grep for is `OpmodeRegistration: registered {Class} as {DS name}`.

---

## Rejected: Gradle configuration cache

Gradle suggests it on every build. **Tested 2026-09-12 and not adopted.** It works — no
incompatibility errors — but the gain is irrelevant here:

| Build | Time |
|---|---|
| incremental, no config cache | 881 ms |
| incremental, cache reused | 401 ms |

It only speeds up the *configuration* phase. Our costs are elsewhere: a clean build is ~90 s
(compile + dex) and `installDebug` ~27 s (pushing the APK). Enabling it persistently also
means editing `gradle.properties`, an upstream file, adding a merge conflict for half a
second — and it is still `[Incubating]`, failing in confusing serialization errors that a
student has no way to interpret. Use `./gradlew --configuration-cache` ad hoc if ever needed.
