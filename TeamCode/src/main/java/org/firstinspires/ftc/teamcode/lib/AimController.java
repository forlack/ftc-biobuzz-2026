package org.firstinspires.ftc.teamcode.lib;

/**
 * Turns the robot to face the hive: a PD controller on the hive's angle (degrees, + = the hive
 * is to the left, so the robot must turn counter-clockwise; from {@link HiveFinder}). The answer
 * is a turn stick value for the drive (+ = clockwise), used in place of the driver's turn stick
 * while the aim button is held. Plain Java: the teleop and the simulator share it.
 *
 * D brakes the turn as the angle closes, to stop overshoot. It uses how fast the angle changed
 * between two different camera readings: the camera gives a new angle less often than the loop
 * runs, and a repeated reading would otherwise look like the angle stopped, then jumped.
 * Within {@code dMinDegrees} of the target D is off, so camera noise there cannot twitch it.
 * D starts from zero each time the button is pressed or the hive is lost.
 *
 * On target (within {@code toleranceDegrees}) it sends no turn at all: the camera's angle
 * wobbles a little even when the robot is still, and P chasing that wobble twitches the robot.
 */
public final class AimController {
    private double derivative;   // degrees per second
    private double lastReading;
    private long lastReadingNanos;
    private double error, turn;
    private boolean onTarget;

    /**
     * One loop: the turn stick value (+ clockwise) that brings the hive's angle to 0. No hive in
     * view: 0 (and D starts over).
     */
    public double update(HiveFinder.Sighting hive, double p, double d, double dMinDegrees,
                         double maxTurn, double toleranceDegrees) {
        long now = System.nanoTime();
        if (hive == null) {
            reset();
            return 0;
        }
        error = hive.bearing;
        if (lastReadingNanos == 0) {
            derivative = 0;
            lastReading = error;
            lastReadingNanos = now;
        } else if (error != lastReading) {
            derivative = (error - lastReading) / Math.max(1e-3, (now - lastReadingNanos) / 1e9);
            lastReading = error;
            lastReadingNanos = now;
        } else if (now - lastReadingNanos > 200_000_000L) {
            derivative = 0;   // the same reading for 0.2 s: the angle really is not changing
        }
        double braking = Math.abs(error) < dMinDegrees ? 0 : d * derivative;
        onTarget = Math.abs(error) <= toleranceDegrees;
        if (onTarget) {
            turn = 0;
            return turn;
        }
        double counterClockwise = clamp(p * error + braking, -maxTurn, maxTurn);
        turn = -counterClockwise;
        return turn;
    }

    /** The button was let go: start over next time. */
    public void reset() {
        derivative = 0;
        lastReadingNanos = 0;
        error = 0;
        turn = 0;
        onTarget = false;
    }

    /** The hive's angle the last time it was seen while aiming, degrees (+ = left). */
    public double error() {
        return error;
    }

    /** The turn stick value sent last (+ = clockwise). */
    public double turn() {
        return turn;
    }

    /** Within the tolerance of facing the hive. */
    public boolean onTarget() {
        return onTarget;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
