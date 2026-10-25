package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.List;
import java.util.Locale;

/**
 * Every mechanism of the robot, made once from the hardware map, used by the teleop and the
 * autonomous alike:
 *
 *   robot = new Robot(hardwareMap);        // at INIT
 *   robot.flywheels.on();                  // say what you want, any time
 *   robot.update();                        // once per loop: the mechanisms do it
 *
 * Adding a mechanism: write its class (hardware names in its constructor, @Configurable
 * settings, @AutoFunction methods, update(), telemetry()), then add it here: a
 * {@code public final} field, made in the constructor, and its update()/telemetry() calls.
 * Its auton functions then appear in the dashboard's Auton Sequence by themselves.
 *
 * The drive is not here: teleop drives with MecanumDrive, the autonomous with Pedro.
 */
public class Robot {
    public final Intake intake;
    public final Flywheels flywheels;
    public final Feeder feeder;
    public final Stinger stinger;

    private final HardwareMap hardwareMap;
    private final List<LynxModule> hubs;
    private double batteryVolts = 12.5;
    private long nextBatteryRead;

    /** Finds every mechanism's hardware. Moves nothing. */
    public Robot(HardwareMap hardwareMap) {
        this.hardwareMap = hardwareMap;
        hubs = hardwareMap.getAll(LynxModule.class);
        intake = new Intake(hardwareMap);
        flywheels = new Flywheels(hardwareMap);
        feeder = new Feeder(hardwareMap);
        stinger = new Stinger(hardwareMap);
    }

    /**
     * Bulk reads: the first motor read in a loop fetches every encoder value of that hub in one
     * call. Then {@link #clearBulkCache} must start every loop.
     */
    public void useManualBulkReads() {
        for (LynxModule hub : hubs) hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);
    }

    public void clearBulkCache() {
        for (LynxModule hub : hubs) hub.clearBulkCache();
    }

    /** Every mechanism's update(), once per loop. */
    public void update() {
        readBattery();
        intake.update();
        flywheels.update(batteryVolts);
        feeder.update();
        stinger.update();
    }

    /**
     * Reads the battery, at most 4 times a second (a voltage read is a call to the hub that
     * takes a few ms). The flywheels' F and "Battery V" use it.
     */
    public void readBattery() {
        long now = System.nanoTime();
        if (now >= nextBatteryRead) {
            batteryVolts = batteryVoltage(hardwareMap, batteryVolts);
            nextBatteryRead = now + 250_000_000L;
        }
    }

    public double batteryVolts() { return batteryVolts; }

    /** Every mechanism's Driver Station lines and chart values. */
    public void telemetry(Telemetry telemetry) {
        intake.telemetry(telemetry);
        telemetry.addLine("");
        flywheels.telemetry(telemetry);
        feeder.telemetry(telemetry);
        stinger.telemetry(telemetry);
        telemetry.addData("Battery V", "%.2f", batteryVolts);
    }

    /** Lowest valid hub voltage; the previous reading if no sensor is valid. */
    public static double batteryVoltage(HardwareMap map, double previous) {
        double volts = Double.POSITIVE_INFINITY;
        for (VoltageSensor sensor : map.voltageSensor) {
            double reading = sensor.getVoltage();
            if (Double.isFinite(reading) && reading > 0) volts = Math.min(volts, reading);
        }
        return Double.isFinite(volts) ? volts : previous;
    }

    /** "3385 / 3400 RPM (-0.4%)" for the Driver Station. */
    static String rpmLine(double rpm, double target) {
        String line = Math.round(rpm) + " / " + Math.round(target) + " RPM";
        return target == 0 ? line : String.format(Locale.US, "%s (%+.1f%%)", line, (rpm - target) / Math.abs(target) * 100);
    }
}
