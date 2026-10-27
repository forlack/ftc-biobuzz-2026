package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
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

    private final List<Mechanism> mechanisms = new ArrayList<>();
    private final HardwareMap hardwareMap;
    private double batteryVolts = 12.5;
    private long nextBatteryRead;

    /** Finds every mechanism's hardware. Moves nothing. */
    public Robot(HardwareMap hardwareMap) {
        this.hardwareMap = hardwareMap;
        intake = add(new Intake(hardwareMap));
        flywheels = add(new Flywheels(hardwareMap, this::batteryVolts));
        triggers = add(new Triggers(hardwareMap));
        stinger = add(new Stinger(hardwareMap));
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

    /** "3385 / 3400 RPM (-0.4%)" for the Driver Station. */
    static String rpmLine(double rpm, double target) {
        String line = Math.round(rpm) + " / " + Math.round(target) + " RPM";
        return target == 0 ? line : String.format(Locale.US, "%s (%+.1f%%)", line, (rpm - target) / Math.abs(target) * 100);
    }
}
