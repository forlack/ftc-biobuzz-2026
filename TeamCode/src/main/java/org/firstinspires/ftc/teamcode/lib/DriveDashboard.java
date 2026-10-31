package org.firstinspires.ftc.teamcode.lib;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.dashboard.Dash;

/**
 * The drive part of the teleop's telemetry, and the end of each loop's telemetry.
 *
 * This only shows things -- it never controls the robot. Give it the OpMode's telemetry after
 * wrapping it in {@link DashboardTelemetry}, so the lines reach the Driver Station and the
 * Total Chaos Dashboard.
 */
public class DriveDashboard {

    private final MecanumDrive drive;
    private final Telemetry telemetry;

    public DriveDashboard(MecanumDrive drive, Telemetry telemetry) {
        this.drive = drive;
        this.telemetry = telemetry;
    }

    /** Call once before waitForStart(). */
    public void showReady(String opModeName) {
        telemetry.addLine(opModeName + " ready.");
        telemetry.update();
    }

    /** Call once per loop, last: the drive's dashboard values, then sends this loop's telemetry. */
    public void update() {
        // Dashboard only (the Driver Station keeps to what the drivers act on).
        Dash.put("Speed %", Math.round(drive.getLastSpeedUsed() * 100));
        Dash.put("Default speed %", Math.round(drive.getDefaultSpeed() * 100));
        Dash.put("Stopping", drive.isBrakeMode() ? "BRAKE" : "COAST");
        Dash.put("Heading", Math.round(drive.getLastHeadingDegrees()));
        // What the driver's sticks are doing, -1 to 1.
        Dash.put("Drive forward", drive.getLastForward());
        Dash.put("Drive strafe", drive.getLastStrafe());
        Dash.put("Drive turn", drive.getLastTurn());
        telemetry.update();   // with DashboardTelemetry this also ends the dashboard frame
    }
}
