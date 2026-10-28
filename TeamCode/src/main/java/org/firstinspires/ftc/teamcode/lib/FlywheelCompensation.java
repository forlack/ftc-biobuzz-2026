package org.firstinspires.ftc.teamcode.lib;

/**
 * Flywheel F for the battery voltage. The motor power the hub sends is a fraction of the
 * battery voltage, so the same speed needs more power from a lower battery:
 *
 *   F used = F (set for 12.5 V) x 12.5 / battery volts
 *
 * 11 at 12.5 V is 12.0 at 11.5 V and 13.75 at 10 V; a fresh 13.2 V battery gets 10.4. The
 * voltage is limited to 9..14 V so a bad reading cannot give a wild F, and the result is
 * rounded to 0.01 so the hub only gets a new PIDF when the value really changes.
 *
 * Give it the battery averaged over about a second ({@link Average}), not the latest reading:
 * driving briefly pulls the battery down to about 10 V, and following that dip spiked the
 * flywheels about 4% (seen on the robot). Compensation is for a battery running down over a
 * match, not for those dips; the flywheels' own P and I handle them. Plain Java, so the
 * simulator uses the same math.
 */
public final class FlywheelCompensation {
    private FlywheelCompensation() {
    }

    public static final double NOMINAL_VOLTS = 12.5;

    /**
     * The battery voltage averaged over about {@code seconds} (an exponential average, updated
     * every loop): a short dip barely moves it, a battery running down does. 0 seconds: the
     * latest reading as it is.
     */
    public static final class Average {
        private double volts = Double.NaN;
        private long lastNanos;

        public double update(double reading, double seconds) {
            long now = System.nanoTime();
            if (Double.isNaN(volts) || seconds <= 0) {
                volts = reading;
            } else {
                double elapsed = (now - lastNanos) / 1e9;
                volts += (reading - volts) * (1 - Math.exp(-elapsed / seconds));
            }
            lastNanos = now;
            return volts;
        }

        public double volts() {
            return volts;
        }
    }

    /** With {@code enabled} false this is just {@code fAt12_5V}, whatever the voltage. */
    public static double f(double volts, boolean enabled, double fAt12_5V) {
        if (!enabled) {
            return fAt12_5V;
        }
        double limited = Math.max(9, Math.min(14, volts));
        return Math.round(fAt12_5V * NOMINAL_VOLTS / limited * 100) / 100.0;
    }
}
