package org.firstinspires.ftc.teamcode.dashboard;

/**
 * Knobs for how much work the dashboard does, saved in robot.json like any other setting.
 * Measure with ./dashboard-load (or the Robot tab's Load card), then turn these down if
 * watching the dashboard slows the robot's loop.
 */
public final class DashboardConfig {
    private DashboardConfig() { }

    /** How often each browser gets new data. Lower = less CPU and Wi-Fi. */
    public static int UPDATES_PER_SECOND = 20;

    /**
     * Chart detail: 0 sends every loop; a number caps the loops per second sent to each
     * browser (the rest are skipped, not averaged).
     */
    public static int CHART_SAMPLES_PER_SECOND = 0;
}
