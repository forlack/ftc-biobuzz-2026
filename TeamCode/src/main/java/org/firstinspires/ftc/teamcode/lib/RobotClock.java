package org.firstinspires.ftc.teamcode.lib;

/**
 * The time for history and snapshot names. The Control Hub forgets the time when it is powered
 * off and starts at 1970 until the Driver Station connects and sets it. Until then, the time
 * the dashboard's browser sends with each request (X-Client-Time) stands in, so history
 * versions and snapshots made from the dashboard are named with the right date anyway.
 *
 * Only the names: the files' own dates stay 1970, because Android does not let an app change
 * file dates in shared storage (/sdcard). The dashboard shows a 1970 file date as unknown.
 *
 * Plain Java, so the simulator uses it too.
 */
public final class RobotClock {
    private RobotClock() {
    }

    /** Before this (2020-01-01), the robot's clock was never set. */
    public static final long SET = 1577836800000L;

    private static volatile long offset;   // browser time minus robot time, while the robot's is unset

    // The simulator's --unset-clock: a robot clock that starts at 1970, like a Control Hub
    // that has just been switched on.
    private static final boolean UNSET = Boolean.getBoolean("biobuzz.unsetClock");
    private static final long STARTED = System.currentTimeMillis();

    private static long robot() {
        return UNSET ? System.currentTimeMillis() - STARTED : System.currentTimeMillis();
    }

    /** A browser said what time it is. Used only while the robot's own clock is unset. */
    public static void heard(long browserMillis) {
        long robot = robot();
        if (robot >= SET) {
            offset = 0;
        } else if (browserMillis >= SET) {
            offset = browserMillis - robot;
        }
    }

    /** Now: the robot's clock when it is set, else the browser's, else (no browser yet) 1970. */
    public static long now() {
        long robot = robot();
        return robot >= SET ? robot : robot + offset;
    }

    /** A file date for the dashboard: 0 when it is a 1970 date (unknown). */
    public static long shown(long fileMillis) {
        return fileMillis >= SET ? fileMillis : 0;
    }
}
