package org.firstinspires.ftc.teamcode.lib;

import java.util.Locale;

/** Telemetry text the robot and the simulator both show. Plain Java. */
public final class Readouts {
    private Readouts() {
    }

    /** How far off the target, as a % of it: -0.4 for 3385 of 3400. 0 with no target. */
    public static double percentOff(double rpm, double target) {
        return target == 0 ? 0 : (rpm - target) / Math.abs(target) * 100;
    }

    /** "3385 / 3400 RPM (-0.4%)": actual, target, and how far off as a % of the target. */
    public static String rpmLine(double rpm, double target) {
        String line = Math.round(rpm) + " / " + Math.round(target) + " RPM";
        if (target == 0) {
            return line;
        }
        return String.format(Locale.US, "%s (%+.1f%%)", line, percentOff(rpm, target));
    }
}
