package org.firstinspires.ftc.teamcode.lib;

/**
 * Turns the robot to face the hive: a PI controller on the hive's angle (degrees, + = the hive
 * is to the left, so the robot must turn counter-clockwise; from {@link HiveFinder}). The answer
 * is a turn stick value for the drive (+ = clockwise), used in place of the driver's turn stick
 * while the aim button is held. Plain Java: the teleop and the simulator share it.
 *
 * I only builds up within {@code iZoneDegrees} of the target (so a big swing cannot wind it up),
 * is limited so it alone can never ask for more than {@code maxTurn}, and starts from zero each
 * time the button is pressed or the hive is lost.
 */
public final class AimController {
    private double integral;     // degree-seconds
    private long lastNanos;
    private double error, turn;
    private boolean onTarget;

    /**
     * One loop: the turn stick value (+ clockwise) that brings the hive's angle to 0. No hive in
     * view: 0 (and I starts over).
     */
    public double update(HiveFinder.Sighting hive, double p, double i, double iZoneDegrees,
                         double maxTurn, double toleranceDegrees) {
        long now = System.nanoTime();
        double seconds = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (hive == null) {
            integral = 0;
            error = 0;
            turn = 0;
            onTarget = false;
            return 0;
        }
        error = hive.bearing;
        if (Math.abs(error) < iZoneDegrees) {
            integral += error * seconds;
        } else {
            integral = 0;
        }
        if (i > 0) {
            integral = clamp(integral, -maxTurn / i, maxTurn / i);
        }
        double counterClockwise = clamp(p * error + i * integral, -maxTurn, maxTurn);
        onTarget = Math.abs(error) <= toleranceDegrees;
        turn = -counterClockwise;
        return turn;
    }

    /** The button was let go: start over next time. */
    public void reset() {
        integral = 0;
        lastNanos = 0;
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
