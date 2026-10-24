# Robot settings (`robot.json`)

The tunable numbers — intake and flywheel RPM and PIDF, stinger end points, drive speeds —
can live in a file on the robot, `/sdcard/FIRST/settings/robot.json`, instead of only in the
code. Changing the file needs **no build**.

## Tune and save

1. Run **Field Centric (Java)** and open the dashboard at <http://192.168.43.1:8686>, Tune tab.
2. Change numbers live.
3. When they are right, press **Save to robot**. Every dot turns green, and the save is kept in
   History.
4. Copy the file into the repo and commit it:
   ```bash
   ./robot-sync pull
   git add settings/robot.json && git commit -m "Tune flywheels" && git push
   ```

Unsaved changes still disappear when the Robot Controller app restarts. Save first.

## What happens at INIT

- **No file:** the code's values are used. Nothing breaks.
- **File changed since last time:** its values replace the code's.
- **File unchanged:** nothing is reloaded, so unsaved Tune tab edits survive a re-INIT.
- **A bad value** (typo, a word where a number goes, a name the code no longer has): that
  value keeps the code default, and a `Settings warning` line says which one.
- **The whole file is unreadable:** the backup from the save before, `robot.json.bak`, is
  used. If that is broken too, the code's values are used.

## Edit the file by hand

Edit `settings/robot.json` in any editor, then send it while on the robot Wi-Fi:

```bash
./robot-sync push      # the robot's old file becomes robot.json.bak
```

Press INIT to use it. The file groups values by class:

```json
{
  "version": 1,
  "FieldCentricJava": { "INTAKE_RPM": 700, "STINGER_DOWN": 0.45 },
  "MecanumDrive": { "SLOW_SPEED": 0.25 }
}
```

A value missing from the file keeps its code default, so a file may list only a few.

## For programmers

Every `public static` (not `final`) number, boolean or String in a class marked
`@Configurable` is a setting (today `FieldCentricJava`, `MecanumDrive`, `PedroAutonomous`,
`DashboardConfig`; the build lists them). A new one is saved automatically. Mark a live switch
that should not be saved, like `FLYWHEELS_ON`, with `@RobotSettings.NotSaved`. More:
`dashboard/DEVELOPER.md`. Renaming a variable makes old files warn about the old name;
re-save to clear it.
