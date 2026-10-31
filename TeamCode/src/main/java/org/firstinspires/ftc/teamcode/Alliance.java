package org.firstinspires.ftc.teamcode;

import org.firstinspires.ftc.teamcode.dashboard.Configurable;

/**
 * Which alliance we are this match: RED or BLUE. Set it before the match on the dashboard's
 * Tune tab (Save to robot keeps it); during a match the operator can switch it (teleop:
 * D-pad left = BLUE, D-pad right = RED). The Driver Station shows it on the first line.
 *
 * The camera only aims at our own alliance's hive tags.
 */
@Configurable
public class Alliance {
    /** RED or BLUE. Anything else counts as RED. */
    public static String ALLIANCE = "RED";

    private Alliance() {
    }

    public static boolean isBlue() {
        return "BLUE".equalsIgnoreCase(ALLIANCE.trim());
    }

    /** "RED" or "BLUE". */
    public static String name() {
        return isBlue() ? "BLUE" : "RED";
    }

    public static void set(boolean blue) {
        ALLIANCE = blue ? "BLUE" : "RED";
    }
}
