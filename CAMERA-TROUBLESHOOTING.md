# Camera failures and robot restarts — investigation log

Updated 2026-11-12. Team 24621 Total Chaos.

## 2026-11-12 practice: three captures with ./camera-log

Background: 4 days stable at home plus a 1-hour Smoke Test on a stand, then a camera
dropout at practice. Captures are in `~/Desktop/ftc-camera-20261112-*` (not in Git).
Time mapping used below: app `monotonicMs` = `/proc/uptime` = kernel dmesg seconds
(within ~1 s); libusb's bracketed timestamps are NOT kernel time.

**1. "Zombie" camera after a STOP (capture 153719, dead battery in the robot).**
Run healthy to the end (48 FPS at STOP). First STOP that session: Expansion Hub 2
unresponsive ~0.25 s (`timeout: abandoning waiting 250ms ... mod=2`, then `REV Hub #2
has reconnected`). Second STOP: camera closed normally, then the kernel logged
`usb 1-1: reset high-speed USB device number 2` twice, 0.8 s and 6.4 s after STOP,
with no OpMode running and nothing in logcat (our code has no USB reset). Re-INIT 4
minutes later: open, MJPEG 640x480 negotiation, alt setting and "streaming started"
all succeeded with no errors, but the camera never sent a frame (STREAMING, 0 FPS).
Later a DS Restart Robot reported the Expansion Hub missing; a hard reboot fixed it.
The team then found a nearly dead battery had been put in by mistake. Two unrelated
devices (RS-485 hub and USB camera) failing together points at power (brownout),
which matches the battery. Not proven: voltage was not logged then.

**2. Deploying while TeleOp was running (capture 155941).** The install killed the
app with the camera streaming. Kernel: `Signal while waiting for configure endpoint
command`, `Not enough bandwidth for altsetting 0`. The new app's first INIT failed:
`setInterfaceAltSetting(1, 5) failed ... UVC_ERROR_IO`, kernel `Not enough bandwidth
for altsetting 5`. The xHCI bandwidth stayed reserved for the killed app. Our code
was not involved (fails before any frame). The Oct 31 "insufficient USB bandwidth"
error is likely the same thing. **Rule: press STOP on the DS before deploying;** if
forgotten, reboot after the deploy. (Not built: deploy refusing while an OpMode runs.)

**3. Long clean session (capture 160334, good battery, ~22 min).** 7 TeleOp runs:
7 clean starts and stops, 0 kernel USB resets, 0 bandwidth/UVC errors, 0 hub
dropouts, no crashes. libusb "transfer still being processed" pairs only at each
STOP (harmless). 31 "failed to send EOF" (damaged frames), about 1-4 a minute, evenly
spread: the link still drops data sometimes. Use EOFs per minute as the cable-health
number before/after any cable or strain-relief change.

**4. Power switch (same day, after capture 3): leading suspect now.** With a new
battery the robot would not turn back on until someone touched the main power switch's
spade connectors; nothing felt loose. An intermittent main power contact explains all
of it: stable sitting still at home, failures after bumps on the field, the Expansion
Hub and the camera dropping out together (a flicker browns out both: the hub loses its
link, the Control Hub's USB 5 V to the camera dips -> kernel USB resets -> zombie
camera), and failures right after STOP, when people handle the robot. The dead battery
may have added to it or been a coincidence. A spade can be bad without feeling loose:
spread female terminal, crimp loose on the wire, oxidized/burnt contact, worn switch.
Next: inspect and tug each crimp; wiggle test with the robot on, TeleOp INIT and
`./camera-log` running (watch for `unresponsive`, `has reconnected`, `regained power
after a complete power loss`, `reset high-speed USB device`); replace the switch or
re-crimp with new terminals and add strain relief; then drive hard again while logging.

**5. Found (same day): half-plugged XT30 to the Control Hub.** While replacing the
power switch the team found one XT30 connector, the one feeding the Control Hub, only
half plugged in. An intermittent main power feed to the Control Hub matches every
symptom above. Switch replaced and connector seated. Confirm with a hard field session
under `./camera-log`: expect no USB resets, no hub dropouts, no zombie camera. If the
camera still fails with power solid, go back to the camera cable (EOF count) and the
decoder-crash notes below. Check every XT30 is fully seated during pre-match checks.

**Recovery, from these logs.** The zombie state survives a kernel USB reset and a full
re-INIT, so software cannot recover it; only removing the camera's power does (hard
reboot; unplugging/replugging the camera is untested since the Nov 7 re-INIT fix and
is the best quick try). DS Restart Robot worked once and failed once: some failures
are app-side and recoverable that way, the zombie state is not. Order to try:
Restart Robot, then replug camera + INIT, then hard reboot. Mark each with Enter +
a note in `./camera-log`.

**Prevention.** A solid main power switch and connectors (see 4), charged batteries; strain relief on
the camera cable. In software only indirect help: the drive slew rate
(`MecanumDrive.SLEW_RATE_ON`) reduced the battery dip from 9.5 V to ~10.5 V.

**Software changes 2026-11-12:**
- Aim lights turn **white** when the camera is not working (`Vision.working()`: no
  frame in the last 1 s, including STREAMING with 0 FPS, camera off, failed or
  missing). Otherwise red (no hive) / green (hive). White is 2200 us (goBILDA Product
  Insight #4: above 1900 us is solid white). Expect ~1 s of white at INIT while the
  camera starts. Field Centric only. Deployed and running on the robot.
- Diagnostics samples (once a second) now include `batteryMinV` (lowest battery
  reading since the last sample, null with no OpMode) and `batteryReads`. Reads are
  the existing 4 Hz ones, so millisecond spikes are missed; for those, look for the
  hubs' own messages in logcat (`unresponsive`, `has reconnected`, `regained power
  after a complete power loss`). Not yet on the robot.

## Fixed: camera dead after re-INIT (cause found, robot-tested 2026-11-07)

**Cause (our code).** At OpMode STOP the SDK's EasyOpenCV closes the camera through
its own OpMode listener (`OpenCvCameraBase$OpModeNotifications.onOpModePostStop` ->
`closeCameraDevice()`), but never tells the VisionPortal. `VisionPortalImpl` only
sets `CAMERA_DEVICE_CLOSED` inside its own `close()`, which nothing called, so the
old portal reported `STREAMING` forever. Our 2-second guard (added earlier on Nov 7)
waited for CLOSED on every later INIT, timed out, skipped vision, and never forgot
the old session: camera dead until the app restarted; unplug/replug could not help.
Verified in the SDK 12.0 / EasyOpenCV 1.7.3 sources and bytecode in the Gradle cache.

**Fix (`mechanisms/Vision.java`, TeleOp `finally` calls `robot.vision.close()`):**

- At STOP we call `VisionPortal.close()` ourselves, on a background thread (it can
  block while a camera is still opening; an OpMode stuck in STOP makes the SDK
  restart the robot). EasyOpenCV's own close then finds the camera closed: its close
  is synchronized and skips a null camera, so the two cannot collide.
- INIT opens a new session only once the last one reports CLOSED (asking it to
  close if STOP never did), waiting up to 2 s. If it never closes, vision is skipped
  with "INIT again, or Restart Robot". A new HardwareMap (the SDK makes one per robot
  start) means the robot restarted, so the old session is forgotten.
- Aim drops the target when no new frame arrives for 0.5 s (`getFreshDetections()`),
  so a frozen camera cannot keep steering toward an old bearing.
- `enableLiveView(false)`: the hub has no screen; the dashboard preview is separate.
- Driver Station line `Camera:` shows the portal state. Wait for STREAMING before STOP.

**Robot test 2026-11-07:** repeated INIT / STOP: camera reached STREAMING every time.
About 8 minutes of TeleOp on the ground (no hard hits), stock modified cable:
`adb logcat` counted 22 "failed to send EOF" and 0 UVC errors.

**Still open: the mid-drive restart after the camera feed dies.** The most likely
mechanism is a native crash in MJPEG decoding: EasyOpenCV's
`mjpegBufToRgbaMat` calls libjpeg-turbo 3.0.3 `tj3Decompress8` into a 640x480
buffer without `tj3DecompressHeader` or a check that the frame is complete, and the
Oct 31 log has a SIGABRT (stack corruption) in `tj3Decompress8`. A native crash
cannot be caught in Java. Damaged frames (missing EOF) come from the USB link, so a
clean cable is the first fix. Next crash: `adb shell cat /proc/uptime` (hub reboot
vs app crash) and `adb logcat -d -b crash` before power cycling.

Possible later software step (not done): a patched EasyOpenCV that drops frames
without JPEG start/end markers (`FF D8` ... `FF D9`), checks the header size, and
requests a lower frame rate. EasyOpenCV always asks for the camera's maximum
(`getMaxFramesPerSecond`, 120 at 640x480); VisionPortal cannot change it.
YUY2 (no decoder) exists only at 1280x800 / 1280x720 @ 10 fps: ruled out (lag).

## Earlier finding: repeated INIT was a reproduction case

Latest robot observation from the team:

1. INIT TeleOp: camera preview works.
2. Press INIT again: camera dies.
3. INIT another one or two times: robot can crash/reboot.

This is stronger evidence than the earlier intermittent failures. It points the
next investigation at camera teardown/recreation during repeated INIT, including
INIT -> INIT without PLAY. It does **not** establish which layer fails. We have
not captured a fatal trace for this exact sequence. A reported robot reboot may
be an RC app/event-loop restart or a full hub reboot; distinguish using uptime.

Follow-up comparison: with `VISION_ON = false`, the team can repeatedly STOP,
INIT, re-INIT, STOP, and INIT without problems. This narrows the reproduction to
the camera-enabled execution path. It does not distinguish an SDK/session
lifecycle problem from camera/USB faults triggered by active streaming. The team
did not specify a new physical cable change for this comparison.

**Do not keep pressing INIT to recover a dead camera.** The team has observed
that doing so can escalate the failure. Capture logs before rebooting when possible.

## Software after removing the failed recovery experiment (before the fix above)

- No automatic USB reset or camera reopen/retry loop.
- No `RESET_CAMERA` Tune switch, hidden Android ioctl code, or USB reset helper.
- Normal STOP relies on SDK camera cleanup. TeleOp's `finally` calls
  `Vision.stopUpdates()` to suppress our target/preview callbacks only.
- Before opening a new portal, Vision waits at most two seconds for the previous
  portal to report `CAMERA_DEVICE_CLOSED`. If it does not, vision is skipped;
  the software does not deliberately overlap camera sessions.
- Autonomous uses `new Robot(hardwareMap, false)` and opens no camera. Current
  routes do not need vision. Future `hiveSeen` autonomous conditions need an
  explicit decision about enabling the camera.
- `VISION_ON = false` before INIT skips camera startup. `CAMERA_FEED_ON = false`
  disables only browser preview; AprilTag processing can still run.
- Settings now load before the dashboard opens. INIT still loads settings and
  resets NotSaved switches; unchanged saved files preserve live tuning.

Skipping a stuck camera is intended to leave driving available. We have not
proven immunity to native camera crashes or USB electrical faults. The two-second
guard does not abort a blocking SDK call or reset the physical device.

## Hardware and configuration

| Item | Known configuration |
|---|---|
| Hub | Control Hub v1.0; Android 7.1.2 / API 25 |
| RC SDK dependencies | FTC 12.0.0 |
| Camera | goBILDA global shutter, SKU 3122-0004-0001; OV9782 sensor; logs identify UC852 |
| USB port | Team confirmed Control Hub USB 3.0 port |
| Connection | Plug modified for clearance against a rail; wires attached at 90 degrees near exposed plug wiring; USB 3 plug/cable joined to USB 2 camera wiring |
| Hardware map | `24621-Biobuzz-Main.xml`; camera name `Webcam 1` |
| Capture | 640x480 MJPEG; log reports negotiated 120 FPS |
| Calibration | fx/fy 545.584; cx 350.588; cy 223.150 |
| Browser preview | Decoded frame re-encoded as JPEG quality 85, maximum 10/s while requested; off by default in code |
| Dashboard | `http://192.168.43.1:8686` |

The resolution, MJPEG format, and intrinsics match the goBILDA manual. Capture
FPS and browser preview FPS are different; limiting preview to 10/s does not
limit USB capture to 10/s. No capture-rate limit has been implemented.

The extra USB 3 SuperSpeed conductors are not needed for USB 2 operation if
properly isolated. The modified connector's data-pair geometry, shielding,
solder joints, power/ground, and strain relief remain unverified. No intact-cable
comparison has yet established or ruled out the modification as a cause.

## Observed symptoms

- Camera may fail after code installation or when transitioning between OpModes.
- Accidental TeleOp START -> STOP -> autonomous INIT/PLAY preceded a reported crash.
- Camera can die while the rest of the robot continues working.
- Dead camera means no browser image, no auto aim, and aim lights no longer
  respond to targets. The lights are commanded indicators, not an independent
  camera-health test.
- Restarting TeleOp often does not restore the camera. Rebooting the robot/DS has
  restored operation in reported tests. Unplug/replug alone is not established
  as a reliable recovery.
- User could not reproduce a crash in a camera-unplugged test. Reconnecting and
  repeatedly switching OpModes also sometimes ran without crashing. Intermittency
  prevents treating either observation as proof of a root cause.
- TeleOp once turned/drove without intended controller input. Reconnecting or
  restarting the controller restored control in a later incident. No captured
  input trace explains it. Camera errors alone do not establish a drive command.
- Autonomous occasionally strafed incorrectly or stopped early, then worked
  after reboot. A Pinpoint/localizer, wiring, or startup-state cause remains
  unproven. Repeated route logs loaded the same Double route/hash/start pose.
- First dashboard access after joining robot Wi-Fi sometimes loads nothing for
  approximately 30 seconds, then works. It occurred before the latest settings
  fix. Relationship to camera failures remains unproven.

## What the logs actually show

### USB stream startup failure

```text
UvcStream: 640x480 120fps
UvcDevice: setInterfaceAltSetting(1, 5) failed
Uvc: libusb_set_interface_alt_setting() failed: rc=-1(UVC_ERROR_IO)
UvcStreamHandle: uvc_stream_start()=-1(UVC_ERROR_IO)
UvcDeviceHandle: java.io.IOException: unable to start streaming
CameraManager: camera reports error ... OtherError
```

This confirms USB-level stream startup failed. It does not distinguish bad
signal/power, device firmware, resource allocation, or driver/session state.

### Shutdown with pending transfers

```text
libusb: Device handle closed while transfer was still being processed,
        but the device is still connected as far as we know
libusb: A cancellation hasn't even been scheduled on the transfer
        for which the device is closing
```

These occurred at TeleOp STOP, including runs without a crash. They also returned
after our extra asynchronous close was removed. They indicate incomplete USB
transfer cleanup; they may be consequences of another failure, not the first
cause. Similar errors appear after the fatal exception in a historical FTC report.

### Missing end-of-frame marker

```text
UvcStream: frame=...: previous frame failed to send EOF
```

Observed repeatedly, sometimes while the robot continued operating. Indicates
a frame-boundary problem in the USB stream. Not enough to identify cable vs.
camera firmware/driver, and not a reliable predictor of a crash by itself.

### Portal did not finish closing

```text
Previous camera session is still closing; vision skipped
```

Generated by our two-second guard. The prior portal did not report CLOSED, so we
did not open another. Explains why re-INIT can leave vision unavailable, but not
why cleanup first got stuck.

### Other evidence and limits

- An older Oct 31 Desktop log contains a fatal SIGABRT with stack corruption in
  `libturbojpeg.so`, including `tj3Decompress8`. This is a real native decoder
  crash; it is not a captured trace for every Nov 7 incident.
- That older log also contains a USB altsetting bandwidth allocation failure.
- Downloaded Nov 7 snapshots show roughly 33-39 MB Java usage against a 512 MB
  heap limit, hundreds of MB system memory available, and low-memory false.
  These snapshots do not show memory exhaustion; post-failure snapshots cannot
  exclude a transient issue at an earlier instant.
- Some dashboard/session recreations occurred while hub uptime continued to
  increase: those particular events do not prove a full hub power reboot.
- A previous snapshot contains a Robocol receive timeout around the reported DS
  failure. Robot-side logs do not contain the Driver Station app's crash trace.
- Latest pre-recovery download contained an I2C read expecting 1 byte but getting
  0 and a 676 ms setup/config-command delay. Device and relevance are unknown.
- Zoom/pan/tilt `error -9` probes are generally unsupported-control queries,
  distinct from the stream-start errors. The Problems view filters these probes.
- `Camera: off (not used by this OpMode)` and `off (Vision.VISION_ON)` are expected
  disabled states currently logged as WARN. They are not camera crash evidence.
- Status once reported STREAMING despite native start errors and zero FPS.
  Portal state alone is not proof of fresh frames.

## Experiments and changes, in order

| Experiment/change | Result |
|---|---|
| Hard reboot robot and DS | Restored operation in reported tests; failures later recurred. Does not identify cause. |
| Reduce/disable browser preview | User saw only a few ms loop improvement; vision still active. Preview alone did not explain added loop time. |
| Camera unplugged | No crash reproduced during that test window. |
| Reconnect camera and alternate auton/TeleOp | Sometimes stable, with EOF errors still appearing. |
| Adjust modified USB wiring | Team adjusted it while testing; no demonstrated resolution. |
| Stop opening camera in autonomous | Implemented; avoids unnecessary autonomous camera startup. Camera failures still occurred in TeleOp. |
| Clear selected targets when portal is not STREAMING | Implemented; prevents using a target while explicitly stopped/disconnected. Does not detect all stale frames. |
| Add explicit asynchronous camera close at STOP | Added after the original failures. Did not demonstrate a fix; therefore cannot explain the original failures. |
| Remove that explicit close; SDK owns STOP cleanup | Implemented. Camera failures and pending-transfer errors still occurred. |
| Wait up to 2 s for prior session CLOSED | Implemented. Detects stuck cleanup and skips vision; does not recover it. |
| Low-level USB reset, five attempts with 2 s waits | **Repeated robot tests did not recover the failed camera. Removed at user's request.** |
| Repeated INIT without PLAY | First INIT preview works; another INIT kills camera; another one or two can precede reboot. Latest concrete reproduction case. |
| Repeated STOP/INIT/re-INIT with VISION_ON false | Team reports no problems. Camera-disabled comparison is stable; camera-enabled lifecycle remains implicated, without identifying hardware vs. software. |
| Close the portal ourselves at STOP; INIT waits for a truthful CLOSED | **Fixed re-INIT.** See "Fixed" at the top. Robot-tested Nov 7. |

The removed USB experiment used Android UsbManager to open only the configured
webcam and reflected Android 7 `Os.ioctlInt`/`MutableInt` to request Linux
`USBDEVFS_RESET` (`0x5514`). It was a bus reset, not a power cycle. A worker
requested cleanup and tried reopening after confirmed CLOSED. A one-shot
`RESET_CAMERA` Tune switch enabled manual tests. Native calls could outlast the
polling timeout. The team saw five attempts and repeatedly no recovery. No new
download was available to distinguish ioctl rejection from later reopen failure,
but **the recovery failed its practical robot test either way**. Do not reintroduce
it or present more retries as a solution without new evidence and authorization.

Other Nov 7 fixes (dashboard OpMode-state reporting, reduced LED/logcat noise,
auton route read/hash guards, B+D-pad route selection, deploy ADB fallback,
saved-settings startup) are not proven fixes for the camera. No Pedro constants,
motor directions, or Pinpoint directions were changed for this investigation.

## Logging and evidence files

Locally reviewed files (not copied into Git):

- Desktop `robot-crash-log.txt`: older Oct 31 Android/native crash evidence.
- Downloads `robot-problems.json`: Nov 7 13:48 download.
- Downloads `robot-problems(1).json`: Nov 7 14:10 download.
- Downloads `robot-problems(2).json`: Nov 7 14:53 download; after app startup,
  camera had not yet initialized in that session. No new UVC error in that snapshot.

These JSON downloads are **combined bounded snapshots, not full robot logs**.
They contain event tails, a current diagnostic context, and an on-demand Android
logcat tail. Event files persist across app sessions; old warnings remain visible.
Full reboot may lose Android logcat. The hub clock jumped by roughly an hour in
reviewed data, so wall-clock timestamps and newest-first sorting can mislead.
Compare session IDs, monotonic milliseconds, uptime, and process IDs where present.

Current capture behavior:

- App events are queued and flushed about once a second, not written per loop.
- Camera status reports about once a second while Vision updates.
- Performance samples about once a second; two files up to 1 MB each.
- Events use two files up to 512 KB each. Viewer tails are bounded.
- Android logcat is collected on demand, not continuously; current Problems
  collection requests warnings/errors plus UvcStream debug lines for EOF, up to
  300 entries. Repeats are grouped in the browser; downloaded JSON retains raw data.
- LED transition event spam was removed; LED command telemetry remains.

Capture promptly after failure, before power cycling if possible:

**Laptop capture helper:** run `./camera-log` on robot Wi-Fi (or
`./camera-log --serial USB_SERIAL` over USB). It discovers SDK ADB, saves unfiltered
logcat to a timestamped `~/Desktop/ftc-camera-*` folder, and prints camera/USB
messages and warnings/errors live. Leave it running during practice. When the
camera drops, press Enter (or type a note and Enter) to mark the time and save USB
state, uptime/boot ID, processes, memory, available kernel messages, and saved app
logs **before re-INIT/reboot**. Logcat continues during snapshots; q or Ctrl+C ends
recording. ADB disconnects trigger reconnection without restarting ADB or the robot;
gaps and possible replay of buffered lines are reported. Kernel logs may be denied.
The helper only reads the robot and never clears logs or changes settings.

1. Robot tab -> Problems -> Download, then also inspect Events for lifecycle order.
2. If ADB works, preserve all available Android buffers and uptime:

```sh
adb connect 192.168.43.1:5555
adb -s 192.168.43.1:5555 logcat -d -b all -v threadtime > camera-failure-logcat.txt
adb -s 192.168.43.1:5555 shell cat /proc/uptime > camera-failure-uptime.txt
adb -s 192.168.43.1:5555 shell dumpsys meminfo com.qualcomm.ftcrobotcontroller > camera-failure-memory.txt
adb -s 192.168.43.1:5555 pull /sdcard/FIRST/logs camera-failure-app-logs
```

Record which button was pressed, whether PLAY ever ran, whether STOP was pressed
between INITs, preview/tag/FPS behavior, and whether only the app or the whole
hub restarted. Keep captured logs local until reviewed for private information.

## Research and what it does—and does not—establish

- [goBILDA camera manual](https://www.gobilda.com/content/user_manuals/3122-0004-0001_user_guide.pdf):
  OV9782, recommended 640x480 MJPEG, calibration, USB 3 port connection.
- [OV9782 VisionPortal power-cycle report, January 2024](https://ftc-community.firstinspires.org/t/visionportal-opencv-pipeline-requires-robot-power-cycle-to-function-after-running-once/980):
  similar failure after one run; developer advised removing manual cleanup in
  that older SDK. Similarity is not proof of a current SDK 12 bug.
- [Same reporter's later MJPEG failure](https://ftc-community.firstinspires.org/t/solved-mjpeg-mode-cameras-wont-call-processframe-sometimes/1058):
  their intermittent startup issue was resolved by removing a USB adapter.
- [Historical shutdown-race discussion](https://ftc-community.firstinspires.org/t/crash-in-easyopencv-when-attempting-to-stop-streaming-on-a-visionportal/792)
  and [original crash log](https://gist.github.com/AusreisserSF/2e70a2d190cb9cb68b3f33fae52feda2):
  automatic/manual cleanup interaction; same libusb messages after a fatal error.
- [FIRST SDK release notes](https://github.com/FIRST-Tech-Challenge/FtcRobotController#version-91-20240215-115542):
  SDK 9.1 fixed a stopStreaming-immediately-followed-by-close race; 9.2 fixed
  another close error. Our SDK is newer; do not call these known current defects.
- [FIRST vision resource guidance](https://ftc-docs.firstinspires.org/en/latest/apriltag/vision_portal/visionportal_cpu_and_bandwidth/visionportal-cpu-and-bandwidth.html):
  SDK automatic shutdown, avoiding rapid lifecycle toggles, monitoring FPS,
  disabling unused RC LiveView, and fresh-detection access.
- [FIRST USB port practices](https://ftc-docs.firstinspires.org/en/latest/robot_building/best_practices/robot-best-practices.html#usb-port-selection-best-practices):
  USB 2 shares a bus with Wi-Fi. **Our camera is confirmed on USB 3**, so that
  particular shared-bus concern is not an explanation established for this robot.
- [libusb device reset documentation](https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html):
  bus reset can require rediscovery; it is not removal of physical USB power.

## Remaining work, not implemented

1. Capture the fatal/native crash trace for INIT -> INIT, then a minimum stock
   ConceptAprilTag-style OpMode to compare with our TeleOp under the same conditions.
   Include cancelled INIT and rapid INIT/STOP transitions. Do not repeatedly crash
   the robot just to collect redundant warnings.
2. Compare with an intact cable/plug, temporarily moving hardware for clearance.
   Keep USB 3, resolution, settings, and test sequence fixed so one factor changes.
3. Done (Nov 7): aim drops the target after 0.5 s without a fresh frame.
4. Done (Nov 7): LiveView disabled. No capture-FPS cap (needs a patched EasyOpenCV),
   exposure/gain controls, or new camera manager was added.
5. Investigate dashboard initial-connect latency separately: first connection
   delay vs. fast TCP/slow state response. Idle state updates currently read hub
   battery voltage directly; a stall there is a code suspect, not a proven cause.
6. If investigating unintended motion, inspect both DS and virtual gamepad input.
   Physical controller unplug does not exclude a browser that owns virtual control.
   Locking the dashboard releases virtual controls. Aim can request rotation from
   operator Y/shoot or driver forceShoot; no trace has identified which input, if
   any, was active during the reported unintended movement.

The re-INIT failure's cause is confirmed and fixed (top of this file). The mid-drive
restart has no confirmed cause yet. Do not describe the failed reset experiment as
working recovery.
