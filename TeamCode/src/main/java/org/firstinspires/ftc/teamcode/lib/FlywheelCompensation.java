package org.firstinspires.ftc.teamcode.lib;

/**
 * Flywheel F for the battery voltage: a lower battery needs more F for the same speed.
 *
 * Linear between the F at 12.5 V and the F at 10 V, clamped at both ends, rounded to 0.01 so
 * the hub only gets a new PIDF when the value really changes. Calibrated at the 2026-10-24
 * competition (11 at 12.5 V, 12 at 10 V). Plain Java, so the simulator uses the same math.
 */
public final class FlywheelCompensation {
    private FlywheelCompensation() { }

    /** With {@code enabled} false this is just {@code fixedF} (the old behaviour). */
    public static double f(double volts, boolean enabled, double fixedF, double fAt12_5V, double fAt10V) {
        if (!enabled) return fixedF;
        double fraction = Math.max(0, Math.min(1, (12.5 - volts) / 2.5));
        return Math.round((fAt12_5V + fraction * (fAt10V - fAt12_5V)) * 100) / 100.0;
    }
}
