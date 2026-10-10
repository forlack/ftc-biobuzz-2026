# Next Steps: Toggle Buttons

A follow-on to the `toggle()` method you added to `Button`. Read it through, then try the
change yourself before looking at the finished code at the bottom.

## Where we are now

`Button` has an `active` field and a `toggle()` method, and the intake uses them like this:

```java
if (toggleIntake.pressed()) {
    toggleIntake.toggle();          // forget this line and active never changes
}
intake.setPower(toggleIntake.active ? 0.2 : 0);
```

It works. But there are three things we can improve.

## Problem 1: you have to remember to flip it

Every toggle button needs that `if (pressed()) toggle();` written out by hand. Forget it once
and the button silently never turns on.

The button already knows when it was pressed, because `update()` works that out every loop.
So the button could flip itself.

## Problem 2: not every button is a toggle

If *every* button flipped on a press, hold buttons like `slowMode` and `pollenGate` would be
tracking a toggle state nobody uses. Confusing to read.

**Fix: make toggling opt-in.** Choose the kind of button when you create it:

```java
private final Button slowMode     = buttons.add(() -> pad.right_bumper);       // hold
private final Button toggleIntake = buttons.addToggle(() -> pad.left_bumper);  // tap on/off
```

Now the button map tells you which controls are taps and which are holds, just by reading it.

## Problem 3: `active` is public

Any code can write `toggleIntake.active = true;` and the button loses track of its own state.

**Fix: make it `private`**, and give other code approved ways to read and change it:

| Method | What it does |
|---|---|
| `isOn()` | read the state |
| `toggle()` | flip it from code |
| `setOn(true/false)` | force it on or off from code, e.g. stop the intake when the robot is full |

This is the same idea as `pressed()` and `down()`: other code can *ask* the button about
itself, but only the button changes its own state.

## One more decision: what does `isOn()` mean for a hold button?

If a hold button's `isOn()` just returned `active`, it would **always be false**, with no
error. Someone writes `slowMode.isOn()` by mistake and slow mode never works. That kind of
bug is hard to find.

Better: for a hold button, `isOn()` means **"is it held right now."** That's natural, since a
hold button *is* on while you hold it. And it means `isOn()` works for **both** kinds, so you
can switch the intake between tap-to-toggle and hold-to-run by changing only the button map.
`updateIntake()` doesn't change at all.

Catch: `toggle()` and `setOn()` won't change what a hold button's `isOn()` reports, because
it reads the real button. If you need to control a button from code, make it with
`addToggle`.

## Try it yourself

1. In `Button.java`, add a `private final boolean togglesOnPress;` and set it from the
   constructor.
2. In `update()`, flip `active` when `togglesOnPress && pressed()`.
3. Make `active` private. Add `isOn()` and `setOn(boolean)`. Keep `toggle()`.
4. Make `isOn()` return `active` for toggle buttons and `now` for hold buttons.
5. In `Button.Group`, add `addToggle(...)` next to `add(...)`.
6. In `FieldCentricJava`, create the intake button with `addToggle`, and simplify
   `updateIntake()` so it just reads `isOn()`.
7. Build, deploy, and check that tap-on/tap-off still works.

**Bonus:** add a hold button that runs the intake backwards to clear a jam. Then you'll have
a toggle and a hold button working together in one method.

---

## Finished code (peek only after trying)

**`Button.java`** (the changed parts):

```java
private final boolean togglesOnPress;
private boolean active;

public Button(BooleanSupplier source, boolean togglesOnPress) {
    this.source = source;
    this.togglesOnPress = togglesOnPress;
}

public void update() {
    before = now;
    now = source.getAsBoolean();
    if (togglesOnPress && pressed()) {
        active = !active;
    }
}

/** Toggle button: has it been tapped on? Hold button: is it held right now? */
public boolean isOn() {
    return togglesOnPress ? active : now;
}

public void toggle() {
    active = !active;
}

public void setOn(boolean on) {
    active = on;
}
```

**`Button.Group`:**

```java
public Button add(BooleanSupplier source) {
    return register(new Button(source, false));
}

public Button addToggle(BooleanSupplier source) {
    return register(new Button(source, true));
}

private Button register(Button button) {
    all.add(button);
    return button;
}
```

**`updateIntake()`**, with the bonus jam-clearing reverse:

```java
private void updateIntake() {
    double power = 0;
    if (reverseIntake.down()) {             // hold: only while held
        power = -INTAKE_POWER;
    } else if (toggleIntake.isOn()) {       // toggle: tap on, tap off
        power = INTAKE_POWER;
    }
    intake.setPower(power);
    dashboard.addData("intake", toggleIntake.isOn() ? "ON" : "off");
}
```

`condition ? a : b` is the **ternary operator**, a one-line if/else. It shows up constantly in
robot code.

---

# Next Steps: Flywheel with Velocity Control

The shooter needs to hit the **same speed every shot**. Setting power isn't enough for that:
`setPower(0.8)` spins slower as the battery drains, and every shot slows the wheel down when
the ball passes through. **Velocity control** fixes both. You ask for a speed, and the hub
reads the encoder and adjusts the power to hold it.

## Background

**Ticks per second.** The motor's encoder counts *ticks* as it turns. `setVelocity()` takes
ticks per second, not RPM. To convert:

```
ticks/sec = RPM × ticksPerRev / 60
```

`ticksPerRev` depends on the motor. Look it up on the motor's product page. goBILDA 6000 RPM
(1:1) motors, a common flywheel choice, are **28 ticks per revolution**. Geared motors are
much higher.

**The hub already has a velocity PID.** In `RUN_USING_ENCODER` mode the hub runs a PIDF
loop for you. You don't write the PID yourself; you tune its four numbers:

| | What it does | Tune |
|---|---|---|
| **F** (feedforward) | the power it *expects* to need for a speed | **first, and does most of the work** |
| **P** | pushes harder the further off speed it is | second, for recovery after a shot |
| **I** | fixes a small error that hangs around | small, only if needed |
| **D** | damps overshoot | usually 0 for a flywheel |

You've seen this before: `MecanumDrive` uses the same hub PIDF for the wheels (`VEL_P`,
`VEL_I`, `VEL_D`, `VEL_F`). Look at `applyPidfTuning()` there.

## Steps

Everything goes straight into `FieldCentricJava.java`, the same way you added the intake.

**1. Robot config.** Add the motor on the Driver Station (Configure Robot), named
`flywheel`. **Plug in the encoder cable**, or velocity control can't work.

**2. Constants and the motor field**, up with your other fields:

```java
private static final double TICKS_PER_REV = 28;     // goBILDA 6000 RPM 1:1 -- check yours
private static final double SHOOT_RPM = 4000;       // start lower, tune later
private static final double RPM_TOLERANCE = 100;    // how close counts as "ready"

// Velocity PIDF. Tune F first (see step 8).
private static final double FLY_P = 0;
private static final double FLY_I = 0;
private static final double FLY_D = 0;
private static final double FLY_F = 0;

private DcMotorEx flywheel;
private double flywheelTargetRPM = 0;
```

**3. Set it up in `runOpMode()`**, before `waitForStart()`:

```java
flywheel = hardwareMap.get(DcMotorEx.class, "flywheel");
flywheel.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
flywheel.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
flywheel.setVelocityPIDFCoefficients(FLY_P, FLY_I, FLY_D, FLY_F);
```

**Always FLOAT on a flywheel, never BRAKE.** A heavy spinning wheel slammed to a stop is
hard on the motor and gearbox. Let it coast down.

**4. Three small helper methods**, at the bottom of the class:

```java
/** RPM -> the ticks per second that setVelocity() wants. */
private double rpmToTicksPerSecond(double rpm) {
    return rpm * TICKS_PER_REV / 60.0;
}

/** How fast the flywheel is actually spinning. */
private double flywheelRPM() {
    return flywheel.getVelocity() * 60.0 / TICKS_PER_REV;
}

/** Close enough to the target to shoot? */
private boolean flywheelAtSpeed() {
    return flywheelTargetRPM > 0
            && Math.abs(flywheelRPM() - flywheelTargetRPM) < RPM_TOLERANCE;
}
```

`flywheelAtSpeed()` is the "ready to shoot" signal. It's the most important of the three.

**5. A button.** Use a toggle (from the section above), on gamepad 2 since that driver runs
the mechanisms:

```java
private final Button flywheelToggle = buttons.addToggle(() -> pad2.y);
```

This needs the toggle-button lesson done first, because `addToggle` and `isOn()` come from
there.

**6. `updateFlywheel()`**, plus one call to it in the loop next to `updateIntake()`:

```java
private void updateFlywheel() {
    flywheelTargetRPM = flywheelToggle.isOn() ? SHOOT_RPM : 0;
    flywheel.setVelocity(rpmToTicksPerSecond(flywheelTargetRPM));

    dashboard.addData("flywheel target", flywheelTargetRPM);
    dashboard.addData("flywheel rpm", Math.round(flywheelRPM()));
    dashboard.addData("flywheel ready", flywheelAtSpeed());
}
```

**7. Check it before tuning.** Deploy with all four PIDF numbers at 0 and turn the flywheel
on. It probably won't reach speed yet, which is fine. Check that `flywheel rpm` *changes*
when it spins. If it stays at 0, the encoder isn't plugged in.

**8. Tune it**, one number at a time, redeploying between changes:
1. Raise **F** until the RPM settles near the target on its own. Starting guess:
   `F = 32767 / maxTicksPerSecond`, where `maxTicksPerSecond` is the motor's top RPM run
   through `rpmToTicksPerSecond()`.
2. Raise **P** until the wheel recovers quickly after a shot without wobbling.
3. Only add **I** if it settles a little below target and stays there.

Redeploying for every change is slow. Once it basically works, live tuning in Panels is a
good upgrade: put `@Configurable` on the class and make the numbers `public static` instead
of `private static final`.

**Bonus:** watch how far `flywheel rpm` drops when a ball goes through, and how long it takes
to get back to "ready." Those two numbers tell you how fast you can fire repeat shots.

## What about autonomous?

Autonomous is a separate OpMode, so it can't see these helper methods. For now, copy the
constants, setup, and helpers into the auto as well. Then wait for speed before feeding:

```java
.run(() -> { flywheelTargetRPM = SHOOT_RPM;
             flywheel.setVelocity(rpmToTicksPerSecond(SHOOT_RPM)); })
.waitUntil(this::flywheelAtSpeed)           // never shoot before it's ready
.run(() -> feeder.setPower(1))
```

Notice you've now written the same code twice. If you change `TICKS_PER_REV`, you have to
remember to change it in both places. **That duplication is exactly the problem a subsystem
class solves**, like `MecanumDrive` does for the drivetrain. A good next lesson once the
flywheel works.
