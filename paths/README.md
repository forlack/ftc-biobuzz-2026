# Autonomous path files

Paste the complete **Java code** from Pedro's export into `auto.java.txt`, replacing
its contents. Include the PoseFactory line, every pose declaration, and all path
methods. Do not include the surrounding web page text or export-mode labels.
Coordinates already reflect Pedro's selected export mode; the robot does not mirror again.

`auto.json` holds settings only: `version` (1), `startupWaitSeconds`, `shootSeconds`,
and `speedLimits`. Leave `speedLimits` empty for normal Pedro limits, or use named
limits in inches/second, such as `"speedLimits": { "path2": 20 }`.
Keep both files together in this directory.

## Upload

Install the updated app once:

```bash
./deploy
```

Both `./deploy` and `./deploy-linux` automatically send both files to
`/sdcard/FIRST/paths/` before installing the app.

For later path edits:

1. Stop autonomous.
2. Paste the new export into `paths/auto.java.txt` and save.
3. Connect to robot Wi-Fi and run `./update-paths`.
4. Select **Pedro Pathing Autonomous**, press INIT, and check the start pose.

No compile or app restart is needed for path changes. `./update-paths` stays on the
current Wi-Fi. For USB: `./update-paths -s YOUR_DEVICE_SERIAL`.
The helper stages both files before replacing them; always upload while stopped.

## Supported export

The robot reads the text at INIT; it never compiles or executes Java. Supported:

- `private final PoseFactory poseFactory = PoseFactory.degrees();`
- Named `private final Pose ... = poseFactory.of(x, y, heading);` declarations.
- `public Path name()` methods returning `Paths.line(...)` or `Paths.curve(...)`.
- Curves with multiple control points.
- `.linear(startPose, endPose)` and `.constant(pose)` headings.
- Whitespace, Java comments, decimal/scientific numbers.

A pose named `start` and paths named `path1` and `path2` are required by the current
autonomous. Unsupported text, undefined poses, duplicate names, missing files and
invalid settings stop INIT with an error. Paths must connect as intended in Pedro;
the reader uses the exported start/end poses without adjusting them.

Any number of named paths can be loaded. The current Java action sequence starts
intake/flywheels/transports, waits, follows path1, shoots, stops feeding, and follows
path2 to park. It also follows path3 if present. Other exported paths are available
through `route.getPath("name")`, but need to be added to the Java action sequence
to run. Changing that sequence requires a build. The stinger stays up.

## Selecting TeleOp after autonomous

Autonomous specifies `preselectTeleOp = "Field Centric (Java)"`. Enable **Settings →
OpMode Auto Queue** on the Driver Station and use the autonomous timer. At normal
completion of the 30-second timed run, the DS preselects TeleOp; you still press INIT
and PLAY. Manually pressing STOP cancels auto-queue.
