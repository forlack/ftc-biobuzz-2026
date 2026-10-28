package org.firstinspires.ftc.teamcode.helpers;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;
import org.firstinspires.ftc.teamcode.mechanisms.Flywheels;
import org.firstinspires.ftc.teamcode.mechanisms.Robot;

import java.util.Locale;

/**
 * Hand tuning for the flywheels: cycles both between a low speed (like the drop after a shot)
 * and their target RPM, over and over until STOP. Change each wheel's F, P, I, D
 * on the dashboard's Tune tab while it runs; they apply on the next loop, with the same
 * voltage compensation as the teleop. Watch the Flywheels chart on the Live tab.
 *
 * Every cycle it measures, per wheel: time to ready (from the jump until within
 * VelocityMotor.RPM_TOLERANCE of the target, the teleop's "ready"), overshoot, and the average
 * error over the last half second. Lower is better for all three.
 *
 * When you like the numbers, press Save to robot on the Tune tab.
 * Both wheels spin up to full speed: keep hands and balls clear.
 */
@Configurable
@Configurable.Hidden   // only needed while tuning: Show it on the Tune tab
@TeleOp(name = "Flywheel Tuner", group = "Diagnostics")
public class FlywheelTuner extends LinearOpMode {

    /** The low part of each cycle, as % of the target (a shot drops the wheel to about this). */
    public static double LOW_PERCENT = 70;
    public static double LOW_SECONDS = 1.0;
    public static double HIGH_SECONDS = 2.0;

    private static final int AVERAGE_OF = 5;

    /** One flywheel, the cycle in progress, and what the finished cycles measured. */
    private static final class Wheel {
        final String name;
        final VelocityMotor motor;
        double target;
        // The cycle in progress.
        boolean ready;
        double readyAt, peak, errorSum;
        int errorCount;
        // The last finished cycle (NaN until there is one), and recent times to ready.
        double readySeconds = Double.NaN, overshoot = Double.NaN, steadyError = Double.NaN;
        final double[] recentReady = new double[AVERAGE_OF];
        int cycles;

        Wheel(String name, VelocityMotor motor) {
            this.name = name;
            this.motor = motor;
        }
    }

    @Override
    public void runOpMode() {
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);
        RobotSettings settings = new RobotSettings().load();
        // Same names and directions as mechanisms/Flywheels.
        Wheel pollen = new Wheel("Pollen", new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE));
        Wheel nectar = new Wheel("Nectar", new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD));
        Wheel[] wheels = {pollen, nectar};

        telemetry.addData("Flywheel Tuner", "Both flywheels cycle between "
                + Math.round(LOW_PERCENT) + "% and their target until STOP. Keep hands and balls clear.");
        telemetry.addData("How", "Change F, P, I, D on the Tune tab while it runs; Save to robot when happy.");
        telemetry.addData("Settings", settings.status());
        telemetry.update();
        waitForStart();

        double volts = Robot.batteryVoltage(hardwareMap, 12.5);
        long nextVolts = 0;
        FlywheelCompensation.Average averageVolts = new FlywheelCompensation.Average();
        boolean high = false;
        long phaseStart = System.nanoTime();
        try {
            while (opModeIsActive()) {
                long now = System.nanoTime();
                if (now >= nextVolts) {
                    volts = Robot.batteryVoltage(hardwareMap, volts);
                    nextVolts = now + 250_000_000L;
                }
                double averaged = averageVolts.update(volts, Flywheels.FLYWHEEL_VOLTS_AVERAGE_SECONDS);
                apply(pollen, Flywheels.POLLEN_FLYWHEEL_P, Flywheels.POLLEN_FLYWHEEL_I, Flywheels.POLLEN_FLYWHEEL_D,
                        Flywheels.compensatedF(Flywheels.POLLEN_FLYWHEEL_F, averaged), Flywheels.POLLEN_FLYWHEEL_RPM);
                apply(nectar, Flywheels.NECTAR_FLYWHEEL_P, Flywheels.NECTAR_FLYWHEEL_I, Flywheels.NECTAR_FLYWHEEL_D,
                        Flywheels.compensatedF(Flywheels.NECTAR_FLYWHEEL_F, averaged), Flywheels.NECTAR_FLYWHEEL_RPM);

                // Switch between the low and high parts of the cycle.
                double inPhase = (now - phaseStart) / 1e9;
                if (inPhase >= (high ? HIGH_SECONDS : LOW_SECONDS)) {
                    if (high) for (Wheel wheel : wheels) finishCycle(wheel);
                    high = !high;
                    phaseStart = now;
                    inPhase = 0;
                    if (high) for (Wheel wheel : wheels) startCycle(wheel);
                }
                for (Wheel wheel : wheels) {
                    wheel.motor.setTargetRPM(high ? wheel.target : wheel.target * LOW_PERCENT / 100);
                }

                for (Wheel wheel : wheels) {
                    double rpm = wheel.motor.getRPM();
                    if (high) measure(wheel, rpm, inPhase);
                    Dash.put(wheel.name + " RPM", rpm);
                    Dash.put(wheel.name + " target", wheel.motor.getTargetRPM());
                    if (!Double.isNaN(wheel.readySeconds)) Dash.put(wheel.name + " time to ready s", wheel.readySeconds);
                    if (!Double.isNaN(wheel.overshoot)) Dash.put(wheel.name + " overshoot RPM", wheel.overshoot);
                    telemetry.addData(wheel.name, String.format(Locale.US,
                            "ready in %s (avg of %d: %s)  over %s RPM  error %s RPM",
                            seconds(wheel.readySeconds), Math.min(wheel.cycles, AVERAGE_OF),
                            seconds(average(wheel)), whole(wheel.overshoot), whole(wheel.steadyError)));
                    telemetry.addData(wheel.name + " PIDF", String.format(Locale.US, "P %s  I %s  D %s  F %.2f",
                            fmt(wheel.motor.getP()), fmt(wheel.motor.getI()), fmt(wheel.motor.getD()), wheel.motor.getF()));
                }
                telemetry.addData("Battery V", "%.2f", volts);
                telemetry.addData("Cycle", "%s, %.1f s", high ? "at target" : "low", inPhase);
                settings.update(telemetry::addData);
                telemetry.update();
            }
        } finally {
            for (Wheel wheel : wheels) wheel.motor.stop();
        }
    }

    /** Sends new coefficients only when one changed (the Tune tab, or the battery moving F). */
    private static void apply(Wheel wheel, double p, double i, double d, double f, double target) {
        VelocityMotor motor = wheel.motor;
        if (p != motor.getP() || i != motor.getI() || d != motor.getD() || f != motor.getF()) {
            motor.setPIDF(p, i, d, f);
        }
        wheel.target = target;
    }

    private static void startCycle(Wheel wheel) {
        wheel.ready = false;
        wheel.peak = Double.NEGATIVE_INFINITY;
        wheel.errorSum = 0;
        wheel.errorCount = 0;
    }

    private static void measure(Wheel wheel, double rpm, double inPhase) {
        if (!wheel.ready && Math.abs(rpm - wheel.target) < VelocityMotor.RPM_TOLERANCE) {
            wheel.ready = true;
            wheel.readyAt = inPhase;
        }
        wheel.peak = Math.max(wheel.peak, rpm);
        if (inPhase >= HIGH_SECONDS - 0.5) {
            wheel.errorSum += rpm - wheel.target;
            wheel.errorCount++;
        }
    }

    private static void finishCycle(Wheel wheel) {
        wheel.readySeconds = wheel.ready ? wheel.readyAt : Double.NaN;
        wheel.overshoot = Math.max(0, wheel.peak - wheel.target);
        wheel.steadyError = wheel.errorCount == 0 ? Double.NaN : wheel.errorSum / wheel.errorCount;
        // Never ready counts as the whole high part, so the average shows it.
        wheel.recentReady[wheel.cycles % AVERAGE_OF] = wheel.ready ? wheel.readyAt : HIGH_SECONDS;
        wheel.cycles++;
    }

    private static double average(Wheel wheel) {
        int n = Math.min(wheel.cycles, AVERAGE_OF);
        if (n == 0) return Double.NaN;
        double sum = 0;
        for (int k = 0; k < n; k++) sum += wheel.recentReady[k];
        return sum / n;
    }

    private static String seconds(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.2fs", value);
    }

    private static String whole(double value) {
        return Double.isNaN(value) ? "—" : String.valueOf(Math.round(value));
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
