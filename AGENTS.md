# FTC Workspace — Agent Notes

Living context for this workspace. Update as things change.

Last updated: 2026-10-09

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
| Motor | `intake` | Expansion Hub 2, motor port 0 | added 2026-10-09 |
| CR servo | `triggerPollen` | servo port 0 | continuous rotation, added 2026-10-09 |

An **Expansion Hub** ("Expansion Hub 2", RS-485 address 2) now hangs off the Control Hub —
the Control Hub's four motor ports are all taken by the drivetrain, so mechanisms go there.

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

**Robot network:** The SSID is kept in the private deploy configuration outside Git.
The Control Hub is the AP at **192.168.43.1**; Driver Hub associates at
192.168.43.13. Team is **24620**.

> **Naming mismatch (low priority):** the config and robot SSID both carry an old
> team number. **This is a TEST PLATFORM, not the
> competition robot**, so there's no inspection concern. Rename to `24620-RC` whenever
> convenient (Control Hub network settings); code doesn't care, since OpModes reference
> device names, not the filename.

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

**It recurs per machine, not just once.** The debug key lives at `~/.android/debug.keystore`
and is generated per computer, so the same rejection appears the first time each new machine
deploys — and again when you switch back, since the hub then holds the other machine's
signature. Hit on 2026-09-12 after deploying from the Mac over Wi-Fi and then returning to
the Linux box.

Permanent fix: copy `~/.android/debug.keystore` from whichever machine is canonical to the
others. All of them then sign identically and the hub takes builds from any of them. Worth
doing before handing the repo to students on several laptops.

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
6. **Copy `~/.android/debug.keystore`** from whichever machine is canonical, before the first
   deploy. Skip this and you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and an uninstall dance
   every time you alternate machines.
7. Deploy with the Run button or `./gradlew :TeamCode:installDebug`.

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
      fixed and **upstream PR #115** is open
- [x] Teleop framework (`lib/`) hardware-verified; stick deadzone added
- [x] Autonomous framework `lib/AutoSequence` with follow / run / wait / `at()` markers /
      `aimAt()` / `turnTo()`
- [x] First mechanisms written by the student: `intake` motor (toggle) and `triggerPollen`
      CR servo (hold), plus `toggle()`/`active` added to `Button`

Next:
- [ ] Student lessons in `TeamCode/.../NEXT_STEPS.md`, in order: **toggle buttons**
      (opt-in `addToggle`, private state, `isOn()`), then **two flywheels** via a
      `lib/Flywheel` class with velocity PIDF
- [ ] Test the intake and pollen servo on the robot
- [ ] Watch PR #115; when it merges, re-copy upstream `PinpointTuner.java` (ours already has
      the fix, so this is only to stay in sync)
- [ ] Delete `PanelsDemo.java` when it stops being useful
- [ ] Optional: rename the robot SSID and config file to the real team number; delete the
      16 MB of stale `/sdcard/FIRST/java/srcBackups/` zips

On the competition robot: run AutoTune again (Mecanum → Pinpoint → Foresight → Tests) and
paste the output. Every number in `pedro/Constants.java` belongs to the test chassis.

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
REVERSED / +6.62 on its own. Reported upstream as **Quickstart PR #115**, <https://github.com/Pedro-Pathing/Quickstart/pull/115> (opened 2026-10-03; approved by a non-maintainer 2026-10-04, not yet merged). Still worth the
Localization Check Run **Localization Check** (Diagnostics):
pod end away from you, push LEFT, and `y` must go UP.

Flipping the encoder direction flips the offset's sign, so keep the measured magnitude and
negate it.

Other 3.x gotchas hit:
- **Natural deceleration is POSITIVE in 3.x** (tuner: 35.6 / 56.2). The 2.x zero-power
  accelerations were negative; carrying one over made Tests throw a config error.
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
  This is only correct because `FrontLeft`/`BackLeft` are REVERSE. Don't "correct" it
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
  aim, shoot. `TemplateAutonomous.java` is the starter.

## Student lessons

`TeamCode/src/main/java/org/firstinspires/ftc/teamcode/NEXT_STEPS.md` holds the next
lessons for the student, written to be worked through *before* reading the answers. When
helping with them, **guide rather than write the code** — the parent asked for that
explicitly ("tell me what to do next so I can learn").

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

---

## Scratch files

`TeamCode/src/main/java/org/firstinspires/ftc/teamcode/PanelsDemo.java` — hardware-free
smoke test. Orbits a circle on the field view, streams telemetry, exposes `radius`,
`orbitRadius`, `speed` as live-editable `@Configurable` statics. Safe to delete.
