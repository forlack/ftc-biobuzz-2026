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

**1. Robot config.** Add the motor on the Driver Station (Configure Robot), named
`flywheel`. **Plug in the encoder cable**, or velocity control can't work. Check: in a test,
`getVelocity()` must change when the wheel spins.

**2. Make it a subsystem in `lib/`.** Create `lib/Flywheel.java`, the same idea as
`MecanumDrive`: the class handles the *mechanics*, and the OpMode decides *when*. Give it:

```java
public Flywheel(HardwareMap hardwareMap)   // get the motor, set mode and zero-power behavior
public void setTargetRPM(double rpm)       // 0 = off
public double getRPM()                     // actual speed, for the dashboard
public boolean atSpeed()                   // close enough to target to shoot?
```

**3. Setup inside the constructor:**

```java
flywheel.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
flywheel.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
```

**Always FLOAT on a flywheel, never BRAKE.** A heavy spinning wheel slammed to a stop is
hard on the motor and gearbox. Let it coast down.

**4. Make the numbers live-tunable.** Put `@Configurable` on `Flywheel` with public static
fields: `TARGET_RPM`, `P`, `I`, `D`, `F`, `TICKS_PER_REV`, and `RPM_TOLERANCE`. Since it's
in `lib/`, the Panels import stays out of your OpMode, just like `MecanumDrive`.

**5. Push PIDF only when it changes.** Copy the idea from `MecanumDrive.applyPidfTuning()`:
remember the last values you sent, and only call `setVelocityPIDFCoefficients(P, I, D, F)`
when one changes. Sending it every loop wastes hub bandwidth.

**6. `atSpeed()`:** true when `Math.abs(getRPM() - target) < RPM_TOLERANCE`. This is the
"ready to shoot" signal, and it's the most important method in the class.

**7. Wire it into the teleop.** Use a **toggle** button (from the section above!) for
spin-up and spin-down. Put RPM, target, and `atSpeed()` on the dashboard.

**8. Tune it.** In Panels, set P, I, and D to 0, then:
1. Set a target RPM. Raise **F** until the actual RPM settles near the target on its own.
   Starting guess: `F = 32767 / maxTicksPerSecond`.
2. Raise **P** until the wheel recovers quickly after a shot without wobbling.
3. Only add **I** if it settles a little below target and stays there.

Panels has a **graph** widget. Plotting target vs. actual RPM makes tuning much easier than
reading numbers.

**9. Bake the tuned numbers into the code.** Panels values reset when the robot restarts.

**10. Use it in autonomous.** This is why `atSpeed()` matters:

```java
.run(() -> flywheel.setTargetRPM(SHOOT_RPM))
.waitUntil(flywheel::atSpeed)               // never shoot before it's ready
.run(() -> feeder.setPower(1))
```

**Bonus:** track how far RPM drops when a ball goes through, and how long recovery takes.
Those two numbers tell you how fast you can fire repeat shots.
