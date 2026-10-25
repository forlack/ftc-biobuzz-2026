package org.biobuzz.sim;

import java.util.Random;

/**
 * The simulated robot's physics, stepped 500 times a second on its own thread, like real
 * hardware that keeps moving whether or not the OpMode is looking.
 *
 * Everything public is synchronized: OpMode code (the "robot program") and the physics thread
 * touch it from different threads, just as an OpMode and the hub firmware do.
 */
public class SimWorld {
    public static final double STEP = 1.0 / 500;
    /** goBILDA 5202 1:1 encoder, same as VelocityMotor's default. */
    public static final double FLYWHEEL_TICKS_PER_REV = 28;
    public static final double INTAKE_TICKS_PER_REV = 145.1;

    private final Random noise = new Random(24621);
    private double time;

    // ---- Battery ----------------------------------------------------------
    private double batteryRest = 13.2;
    private double battery = 13.2;

    // ---- Motors -----------------------------------------------------------

    /**
     * A velocity-controlled motor: the REV hub's PIDF on top of a DC motor with inertia.
     * Hub units: output = (P*error + I*integral + D*derivative + F*target) / 32767, in ticks/s.
     */
    public final class VelocityMotor {
        final String name;
        final double freeTicksPerSecond;   // at 12 V
        final double timeConstant;         // seconds for the spinning mass to respond
        final double friction;             // power lost to friction
        double p = 10, i = 3, d = 0, f = 0;
        double target;                     // ticks/s; 0 with power mode = coast
        double velocity;                   // true ticks/s
        double measured;                   // what getVelocity() reports, updated every 20 ms
        double integral, lastError, output;
        private double sampleTimer;

        VelocityMotor(String name, double freeTicksPerSecond, double timeConstant, double friction) {
            this.name = name;
            this.freeTicksPerSecond = freeTicksPerSecond;
            this.timeConstant = timeConstant;
            this.friction = friction;
        }

        void step(double dt) {
            // Hub control loop, on the 20 ms velocity measurement like a REV hub.
            sampleTimer += dt;
            if (sampleTimer >= 0.02) {
                sampleTimer = 0;
                measured = velocity + noise.nextGaussian() * 6;
                if (target == 0) {
                    output = 0;
                    integral = 0;
                } else {
                    double error = target - measured;
                    // Summed every cycle, like the hub (see tests/java/FlywheelTuningCheck).
                    integral = clamp(integral + error, -20000, 20000);
                    double derivative = (error - lastError) / 0.02;
                    lastError = error;
                    output = clamp((p * error + i * integral + d * derivative + f * target) / 32767.0, -1, 1);
                }
            }
            // Motor: drive toward the speed this power gives at the current battery voltage.
            double drive = Math.signum(output) * Math.max(0, Math.abs(output) - friction);
            double reachable = drive * freeTicksPerSecond * battery / 12.0;
            velocity += (reachable - velocity) * dt / timeConstant;
        }

        public void setPIDF(double p, double i, double d, double f) {
            synchronized (SimWorld.this) {
                this.p = p;
                this.i = i;
                this.d = d;
                this.f = f;
            }
        }

        public void setTargetRPM(double rpm, double ticksPerRev) {
            synchronized (SimWorld.this) {
                target = rpm * ticksPerRev / 60.0;
            }
        }

        public double rpm(double ticksPerRev) {
            synchronized (SimWorld.this) {
                return measured * 60.0 / ticksPerRev;
            }
        }

        public double output() {
            synchronized (SimWorld.this) {
                return output;
            }
        }

        /** A ball going through steals some of the wheel's speed. */
        void hit(double fraction) {
            velocity *= 1 - fraction;
        }
    }

    public final VelocityMotor pollen = new VelocityMotor("shooterPollen", 2800, 0.45, 0.04);
    public final VelocityMotor nectar = new VelocityMotor("shooterNectar", 2800, 0.45, 0.04);
    public final VelocityMotor intake = new VelocityMotor("intake", 2780, 0.08, 0.06);

    // ---- Feeding and shots ---------------------------------------------------
    private boolean feeding;
    private int ballsPollen = 3, ballsNectar = 3;
    private double feedTimerPollen, feedTimerNectar;
    private int shotsFired;
    private double stinger;

    // ---- Drivetrain -------------------------------------------------------------
    /** Pedro coordinates: inches, x/y on a 144 in field, heading radians CCW. */
    private double x = 72, y = 72, heading = Math.PI / 2;
    /** Robot-frame velocity: forward in/s, left in/s, rad/s. */
    private double vForward, vLeft, omega;
    private double powerForward, powerLeft, powerTurn;
    public static final double MAX_FORWARD = 68.5, MAX_STRAFE = 58.2, MAX_TURN = 5.5;

    // ---- Stepping -----------------------------------------------------------------

    public synchronized void step(double dt) {
        time += dt;
        pollen.step(dt);
        nectar.step(dt);
        intake.step(dt);
        stepShots(dt);
        stepChassis(dt);
        // Battery sags under load and slowly drains.
        double load = Math.abs(pollen.output) + Math.abs(nectar.output) + 0.5 * Math.abs(intake.output)
                + 0.6 * (Math.abs(powerForward) + Math.abs(powerLeft) + Math.abs(powerTurn));
        batteryRest = Math.max(11.5, batteryRest - dt * 0.0004);
        double sagged = batteryRest - 0.45 * load;
        battery += (sagged - battery) * dt / 0.05;
    }

    private void stepShots(double dt) {
        if (!feeding) {
            feedTimerPollen = feedTimerNectar = 0.25;   // the first ball arrives shortly after
            return;
        }
        // The intake keeps refilling while it runs, a few balls a second at most.
        if (intake.measured > 400) {
            if (ballsPollen < 3 && noise.nextDouble() < dt * 1.2) ballsPollen++;
            if (ballsNectar < 3 && noise.nextDouble() < dt * 1.2) ballsNectar++;
        }
        feedTimerPollen -= dt;
        feedTimerNectar -= dt;
        if (feedTimerPollen <= 0 && ballsPollen > 0) {
            ballsPollen--;
            shotsFired++;
            pollen.hit(0.09 + noise.nextDouble() * 0.03);
            feedTimerPollen = 0.4;
        }
        if (feedTimerNectar <= 0 && ballsNectar > 0) {
            ballsNectar--;
            shotsFired++;
            nectar.hit(0.09 + noise.nextDouble() * 0.03);
            feedTimerNectar = 0.4;
        }
    }

    private void stepChassis(double dt) {
        double scale = battery / 12.5;
        double targetForward = powerForward * MAX_FORWARD * scale;
        double targetLeft = powerLeft * MAX_STRAFE * scale;
        double targetTurn = powerTurn * MAX_TURN * scale;
        vForward += (targetForward - vForward) * dt / 0.15;
        vLeft += (targetLeft - vLeft) * dt / 0.12;
        omega += (targetTurn - omega) * dt / 0.10;
        double cos = Math.cos(heading), sin = Math.sin(heading);
        x += (vForward * cos - vLeft * sin) * dt;
        y += (vForward * sin + vLeft * cos) * dt;
        heading = wrap(heading + omega * dt);
        // Field walls.
        double half = 8.5;
        if (x < half) { x = half; vForward *= 0.3; }
        if (x > 144 - half) { x = 144 - half; vForward *= 0.3; }
        if (y < half) { y = half; vLeft *= 0.3; }
        if (y > 144 - half) { y = 144 - half; vLeft *= 0.3; }
    }

    // ---- What OpMode code uses -------------------------------------------------------

    public synchronized double time() { return time; }

    public synchronized double battery() { return battery + noise.nextGaussian() * 0.01; }

    public synchronized void setFeeding(boolean on) { feeding = on; }

    public synchronized boolean feeding() { return feeding; }

    public synchronized int shotsFired() { return shotsFired; }

    public synchronized int balls() { return ballsPollen + ballsNectar; }

    public synchronized void loadBalls(int perSide) {
        ballsPollen = perSide;
        ballsNectar = perSide;
    }

    public synchronized void setStinger(double position) { stinger = position; }

    public synchronized double stinger() { return stinger; }

    /** Robot-frame drive powers, the same meaning as Pedro's DrivePowers. */
    public synchronized void setDrivePowers(double forward, double left, double turn) {
        powerForward = forward;
        powerLeft = left;
        powerTurn = turn;
    }

    public synchronized double[] pose() { return new double[]{x, y, heading}; }

    public synchronized double[] robotVelocity() { return new double[]{vForward, vLeft, omega}; }

    public synchronized void teleport(double x, double y, double heading) {
        this.x = x;
        this.y = y;
        this.heading = wrap(heading);
        vForward = vLeft = omega = 0;
    }

    /**
     * What the SDK does when an OpMode stops: every motor gets zero power. The robot coasts
     * to a stop; the battery and balls stay as they are.
     */
    public synchronized void stopAllMotors() {
        feeding = false;
        setDrivePowers(0, 0, 0);
        for (VelocityMotor motor : new VelocityMotor[]{pollen, nectar, intake}) {
            motor.target = 0;
            motor.output = 0;
            motor.integral = 0;
        }
    }

    /** Puts the robot back to a fresh start: full battery, preloads, wheels stopped. */
    public synchronized void reset() {
        batteryRest = 13.2;
        battery = 13.2;
        loadBalls(3);
        shotsFired = 0;
        feeding = false;
        setDrivePowers(0, 0, 0);
        vForward = vLeft = omega = 0;
        for (VelocityMotor motor : new VelocityMotor[]{pollen, nectar, intake}) {
            motor.target = 0;
            motor.output = 0;
            motor.integral = 0;
        }
    }

    static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    static double wrap(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /** Runs the physics in real time until the program exits. */
    public void startThread() {
        Thread thread = new Thread(() -> {
            long next = System.nanoTime();
            while (true) {
                step(STEP);
                next += (long) (STEP * 1e9);
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } catch (InterruptedException error) {
                        return;
                    }
                } else if (wait < -100_000_000) {
                    next = System.nanoTime();   // fell far behind (laptop asleep): catch up
                }
            }
        }, "sim-physics");
        thread.setDaemon(true);
        thread.start();
    }
}
