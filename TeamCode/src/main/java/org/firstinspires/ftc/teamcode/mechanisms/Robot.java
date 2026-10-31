package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.lib.Readouts;

import java.util.ArrayList;
import java.util.List;

/**
 * Every mechanism of the robot, made once from the hardware map, used by the teleop and the
 * autonomous alike:
 *
 *   robot = new Robot(hardwareMap);        // at INIT
 *   robot.flywheels.on();                  // say what you want, any time
 *   robot.update();                        // once per loop: the mechanisms do it
 *
 * Adding a mechanism: write its class ({@code implements Mechanism}: hardware names in its
 * constructor, @Configurable settings, @AutoFunction methods, update(), telemetry()), then
 * add it here: a {@code public final} field and one {@code add(...)} line in the constructor.
 * update() and telemetry() then run by themselves, and its auton functions appear in the
 * dashboard's Auton tab.
 *
 * The drive is not here: teleop drives with MecanumDrive, the autonomous with Pedro.
 */
public class Robot {
    public final Intake intake;
    public final Flywheels flywheels;
    public final Triggers triggers;
    public final Stinger stinger;
    public final Vision vision;

    private final List<Mechanism> mechanisms = new ArrayList<>();
    private final HardwareMap hardwareMap;
    private double batteryVolts = 12.5;
    private long nextBatteryRead;

    /** Finds every mechanism's hardware. Moves nothing. */
    public Robot(HardwareMap hardwareMap) {
        this.hardwareMap = hardwareMap;
        // This order is also the Driver Station's: Flywheels, Intake, Hive.
        flywheels = add(new Flywheels(hardwareMap, this::batteryVolts));
        intake = add(new Intake(hardwareMap));
        triggers = add(new Triggers(hardwareMap));
        stinger = add(new Stinger(hardwareMap));
        vision = add(new Vision(hardwareMap));
    }

    private <M extends Mechanism> M add(M mechanism) {
        mechanisms.add(mechanism);
        return mechanism;
    }

    /** Once per loop: reads the battery, then every mechanism moves to what was asked. */
    public void update() {
        readBattery();
        for (Mechanism mechanism : mechanisms) {
            mechanism.update();
        }
    }

    /**
     * Reads the battery, at most 4 times a second (a voltage read is a call to the hub that
     * takes a few ms). The flywheels' F and "Battery V" use it.
     */
    private void readBattery() {
        long now = System.nanoTime();
        if (now >= nextBatteryRead) {
            batteryVolts = batteryVoltage(hardwareMap, batteryVolts);
            nextBatteryRead = now + 250_000_000L;
        }
    }

    public double batteryVolts() {
        return batteryVolts;
    }

    /** Every mechanism's Driver Station lines and chart values. */
    public void telemetry(Telemetry telemetry) {
        for (Mechanism mechanism : mechanisms) {
            mechanism.telemetry(telemetry);
        }
    }

    /** The battery line, last on the Driver Station. */
    public void batteryTelemetry(Telemetry telemetry) {
        telemetry.addData("Battery V", "%.2f", batteryVolts);
    }

    /** Lowest valid hub voltage; the previous reading if no sensor is valid. */
    public static double batteryVoltage(HardwareMap map, double previous) {
        double volts = Double.POSITIVE_INFINITY;
        for (VoltageSensor sensor : map.voltageSensor) {
            double reading = sensor.getVoltage();
            if (Double.isFinite(reading) && reading > 0) {
                volts = Math.min(volts, reading);
            }
        }
        return Double.isFinite(volts) ? volts : previous;
    }

    /** How far off the target, as a % of it (0 with no target); for the dashboard's charts. */
    static double percentOff(double rpm, double target) {
        return Readouts.percentOff(rpm, target);
    }
}
