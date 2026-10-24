package org.firstinspires.ftc.teamcode.dashboard;

/**
 * How much the dashboard sends, saved in robot.json like any other setting. Measure with
 * ./dashboard-load (or the Robot tab's Load card), and lower this if watching the dashboard
 * slows the robot's loop.
 */
public final class DashboardConfig {
    private DashboardConfig() { }

    /**
     * Most loops a second sent to the charts. 0 sends every loop; 50 sends at most 50 a
     * second, skipping the rest. Less = less robot CPU and Wi-Fi, coarser charts.
     */
    public static int MAX_SAMPLES_PER_SECOND = 0;
}
