package org.firstinspires.ftc.teamcode.lib;

import com.bylazar.gamepad.PanelsGamepad;
import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * All the Panels dashboard wiring: the browser gamepad, and the numbers on screen.
 *
 * This only shows things -- it never controls the robot. Everything sent here also reaches
 * the Driver Station, so an OpMode using this needs no telemetry calls of its own.
 */
public class DriveDashboard {

    private final MecanumDrive drive;
    private final Telemetry driverStation;
    private final TelemetryManager panels;

    public DriveDashboard(MecanumDrive drive, Telemetry driverStation) {
        this.drive = drive;
        this.driverStation = driverStation;
        this.panels = PanelsTelemetry.INSTANCE.getTelemetry();
    }

    /** Lets a gamepad plugged into the Panels browser tab drive alongside the real one. */
    public Gamepad mergeGamepad(Gamepad driverStationGamepad) {
        return PanelsGamepad.INSTANCE.getFirstManager().asCombinedFTCGamepad(driverStationGamepad);
    }

    /** Call once before waitForStart(). */
    public void showReady(String opModeName) {
        panels.addLine(opModeName + " ready.");
        panels.addData("factory velocity PIDF", drive.getFactoryPidf());
        panels.update(driverStation);
    }

    /** Call once per loop, last. */
    public void update() {
        panels.addData("mode", drive.isVelocityMode() ? "VELOCITY (encoder PID)" : "RAW POWER");
        panels.addData("zero power", drive.isBrakeMode() ? "BRAKE" : "FLOAT (coast)");
        panels.addData("default speed", round(drive.getDefaultSpeed()));
        panels.addData("speed being used", round(drive.getLastSpeedUsed()));

        panels.addLine("");
        panels.addData("heading (degrees)", round(drive.getLastHeadingDegrees()));
        panels.addData("heading (radians)", drive.getLastHeadingRadians());
        panels.addData("stick strafe", drive.getLastStrafe());
        panels.addData("stick forward", drive.getLastForward());
        panels.addData("stick turn", drive.getLastTurn());
        panels.addData("field X", drive.getLastFieldX());
        panels.addData("field Y", drive.getLastFieldY());

        panels.addLine("");
        // Encoders stuck at 0 while the wheels turn means they are not wired -- use RAW POWER.
        String[] names = {"FL", "FR", "BL", "BR"};
        DcMotorEx[] motors = drive.getMotors();
        for (int i = 0; i < motors.length; i++) {
            panels.addData("encoder " + names[i], motors[i].getCurrentPosition());
        }
        for (int i = 0; i < motors.length; i++) {
            panels.addData("speed " + names[i], round(motors[i].getVelocity()));
        }

        panels.addLine("");
        panels.addData("PIDF tuning",
                drive.isTuningPidf() ? "ON (using Panels values)" : "off (using factory values)");
        if (drive.isTuningPidf()) {
            panels.addData("PIDF in motors", drive.getMotorPidf());
        }
        panels.addData("PIDF factory", drive.getFactoryPidf());

        panels.update(driverStation);
    }

    /** Adds your own line, for parts of the robot the dashboard does not know about. */
    public void addLine(String line) {
        panels.addLine(line);
    }

    public void addData(String label, Object value) {
        panels.addData(label, value);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
