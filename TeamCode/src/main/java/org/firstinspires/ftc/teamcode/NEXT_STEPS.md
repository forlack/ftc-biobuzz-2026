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

## Why a class this time

We have **two flywheels**. Without a class, every constant, every setup line, and every
helper method gets written twice, and once more for autonomous. Change `TICKS_PER_REV` and
you have to remember to change it everywhere.

A **class** is a blueprint. You write the flywheel code **once**, in `Flywheel.java`, then
make as many flywheels from it as you need:

```java
leftFlywheel  = new Flywheel(hardwareMap, "leftFlywheel",  DcMotorSimple.Direction.FORWARD);
rightFlywheel = new Flywheel(hardwareMap, "rightFlywheel", DcMotorSimple.Direction.REVERSE);
```

Each `new Flywheel(...)` is a separate **object** with its own motor and its own target
speed, all built from the same code. You already use objects like this: `chassis` is a
`MecanumDrive` object, and `intake` is a `DcMotorEx` object.

## `static` vs. not `static`

Before writing it, decide which values are **shared** and which belong to **each** flywheel:

| | Keyword | Example | Means |
|---|---|---|---|
| Shared by all flywheels | `static` | `TICKS_PER_REV`, PIDF | one copy, used by every flywheel |
| Each flywheel's own | *(none)* | `motor`, `targetRPM` | every object gets its own copy |

If `targetRPM` were `static`, setting the left flywheel's speed would also change the
right's, because there'd be only one variable. That's the most common class bug, so watch
for it.

## Part 1: Write `lib/Flywheel.java`

Create a new file in the `lib` folder, next to `Button.java` and `MecanumDrive.java`. Build
it up one piece at a time.

**1a. Package, imports, and the class:**

```java
package org.firstinspires.ftc.teamcode.lib;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

/** A flywheel that holds a set RPM using the hub's velocity PID. */
public class Flywheel {

}
```

The `package` line must match the folder. That's how the teleop finds the class later.

**1b. The shared constants**, inside the class:

```java
// Shared by every flywheel.
public static double TICKS_PER_REV = 28;     // goBILDA 6000 RPM 1:1 -- check yours
public static double RPM_TOLERANCE = 100;    // how close counts as "ready"
public static double P = 0, I = 0, D = 0, F = 0;   // velocity PIDF, tune F first
```

**1c. Each flywheel's own state:**

```java
// Each flywheel has its own.
private final DcMotorEx motor;
private double targetRPM = 0;
```

`private` means only `Flywheel` itself can touch them. The teleop has to go through the
methods below, the same rule as `Button`'s `active`.

**1d. The constructor.** This runs once per `new Flywheel(...)`, and sets that one motor up:

```java
public Flywheel(HardwareMap hardwareMap, String name, DcMotorSimple.Direction direction) {
    motor = hardwareMap.get(DcMotorEx.class, name);
    motor.setDirection(direction);
    motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
    motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);  // never BRAKE a flywheel
    motor.setVelocityPIDFCoefficients(P, I, D, F);
}
```

Why pass in `name` and `direction`? Those are the only things that differ between the two
flywheels. Everything else is identical, which is why it can be written once.

Two flywheels on a shooter usually spin **opposite** ways to throw the ball between them, so
one will likely be `REVERSE`.

**1e. The methods**, what the teleop is allowed to ask a flywheel to do:

```java
/** Spin to this speed. 0 = off. */
public void setTargetRPM(double rpm) {
    targetRPM = rpm;
    motor.setVelocity(rpm * TICKS_PER_REV / 60.0);
}

public void stop() {
    setTargetRPM(0);
}

/** How fast it is actually spinning. */
public double getRPM() {
    return motor.getVelocity() * 60.0 / TICKS_PER_REV;
}

public double getTargetRPM() {
    return targetRPM;
}

/** Close enough to the target to shoot? */
public boolean atSpeed() {
    return targetRPM > 0 && Math.abs(getRPM() - targetRPM) < RPM_TOLERANCE;
}
```

Build now (`./gradlew :TeamCode:assembleDebug`). Nothing uses the class yet, but this
catches typos early.

## Part 2: Use it in the teleop

**2a. Robot config.** Add both motors on the Driver Station, named `leftFlywheel` and
`rightFlywheel`, with their **encoder cables plugged in**.

**2b. Import it**, at the top of `FieldCentricJava.java` with the other `lib` imports:

```java
import org.firstinspires.ftc.teamcode.lib.Flywheel;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
```

**2c. Fields:**

```java
private static final double SHOOT_RPM = 4000;    // start lower, tune later
private Flywheel leftFlywheel;
private Flywheel rightFlywheel;
```

**2d. Make the two objects** in `runOpMode()`, before `waitForStart()`:

```java
leftFlywheel  = new Flywheel(hardwareMap, "leftFlywheel",  DcMotorSimple.Direction.FORWARD);
rightFlywheel = new Flywheel(hardwareMap, "rightFlywheel", DcMotorSimple.Direction.REVERSE);
```

**2e. A toggle button** on gamepad 2. This needs the toggle-button lesson done first:

```java
private final Button flywheelToggle = buttons.addToggle(() -> pad2.y);
```

**2f. `updateFlywheels()`**, plus one call to it in the loop:

```java
private void updateFlywheels() {
    double rpm = flywheelToggle.isOn() ? SHOOT_RPM : 0;
    leftFlywheel.setTargetRPM(rpm);
    rightFlywheel.setTargetRPM(rpm);

    boolean ready = leftFlywheel.atSpeed() && rightFlywheel.atSpeed();

    dashboard.addData("left rpm",  Math.round(leftFlywheel.getRPM()));
    dashboard.addData("right rpm", Math.round(rightFlywheel.getRPM()));
    dashboard.addData("flywheels ready", ready);
}
```

Look how short that is. All the details live in `Flywheel`, so the teleop only says
*what* it wants. Ready to shoot means **both** wheels are at speed, hence the `&&`.

## Part 3: Check, then tune

**3a. Check before tuning.** Deploy with PIDF all at 0 and turn the flywheels on. They
probably won't reach speed yet, which is fine. Check that **both** `left rpm` and `right rpm`
*change* when spinning. One stuck at 0 means its encoder isn't plugged in.

Also check they spin the directions you want. If one is backwards, flip `FORWARD`/`REVERSE`
in 2d. That's the only line to change.

**3b. Tune**, one number at a time, redeploying between changes. Since the PIDF constants
live in `Flywheel`, tuning them once tunes **both** wheels:
1. Raise **F** until RPM settles near the target on its own. Starting guess:
   `F = 32767 / maxTicksPerSecond`.
2. Raise **P** until the wheels recover quickly after a shot without wobbling.
3. Only add **I** if they settle a little below target and stay there.

**Upgrade later:** live tuning in Panels. Put `@Configurable` on `Flywheel` (it's in `lib/`,
so the Panels import stays out of the teleop, like `MecanumDrive`). You'd also need the
flywheel to re-send PIDF when a value changes, the way `MecanumDrive.applyPidfTuning()` does.

**Bonus:** watch how far RPM drops when a ball goes through, and how long it takes to be
"ready" again. Those two numbers tell you how fast you can fire repeat shots.

## Part 4: Autonomous gets it for free

No copying. Import `Flywheel` in the auto, make the two objects the same way in `init()`,
and use them in the sequence:

```java
.run(() -> {
    leftFlywheel.setTargetRPM(SHOOT_RPM);
    rightFlywheel.setTargetRPM(SHOOT_RPM);
})
.waitUntil(() -> leftFlywheel.atSpeed() && rightFlywheel.atSpeed())   // never shoot early
.run(() -> feeder.setPower(1))
```

That's the payoff of the class: fix a bug or retune in `Flywheel.java`, and the teleop,
the auto, and both wheels all get it at once.

## Stretch: different PIDF per wheel

If the two wheels behave differently (different wear, belt tension, motor), one shared PIDF
might not suit both. You'd move `P`, `I`, `D`, and `F` from `static` to per-object fields
and pass them into the constructor. A good test of whether the static vs. instance idea
clicked.
