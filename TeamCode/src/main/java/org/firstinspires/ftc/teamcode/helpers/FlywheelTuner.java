package org.firstinspires.ftc.teamcode.helpers;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.teamcode.FieldCentricJava;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.lib.FlywheelTuning;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;

import java.util.Locale;

/**
 * Finds F, P, I and D for both flywheels at the target RPMs from robot.json / FieldCentricJava.
 * The procedure is in lib/FlywheelTuning (checked on the desktop by tests/java/FlywheelTuningCheck):
 * F from two fixed powers (saved as F at 12.5 V), then the same P values on both wheels, each
 * tried 3 times, then I and D, kept only if they clearly help.
 *
 * Both wheels run at the same time and spin up to full speed: keep hands and balls clear.
 * Takes about 2 minutes. Press STOP at any time to stop the wheels.
 * At the end, press A to put the results in the settings and save robot.json.
 * Watch it on the dashboard's Live tab: the Flywheels chart, and every trial's score.
 *
 * It tunes with no ball going through. Check with real shots afterwards on the Tune tab.
 */
@TeleOp(name = "Flywheel Tuner", group = "Diagnostics")
public class FlywheelTuner extends LinearOpMode {

    private static final double TICKS_PER_REV = 28;      // goBILDA 5202 1:1, same as VelocityMotor

    @Override
    public void runOpMode() {
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);
        RobotSettings settings = new RobotSettings().load();
        HubRig rig = new HubRig();

        telemetry.addData("Flywheel Tuner", "Both flywheels spin up to full speed. Keep hands and balls clear.");
        telemetry.addData("Takes", "about 2 minutes. STOP stops the wheels.");
        telemetry.addData("Settings", settings.status());
        telemetry.addData("Battery", "%.2f V", rig.volts());
        for (int w = 0; w < rig.wheels(); w++) telemetry.addData(rig.name(w) + " target RPM", rig.targetRPM(w));
        telemetry.update();
        waitForStart();

        FlywheelTuning.Result result = null;
        try {
            result = FlywheelTuning.run(rig, FieldCentricJava.FLYWHEEL_F, FieldCentricJava.FLYWHEEL_F_AT_10V);
        } finally {
            FlywheelTuning.stop(rig);
        }
        if (result != null) showResults(result, settings);
    }

    private void showResults(FlywheelTuning.Result result, RobotSettings settings) {
        boolean compensation = FieldCentricJava.FLYWHEEL_VOLTAGE_COMPENSATION;
        double oldF = FieldCentricJava.FLYWHEEL_F, oldF10 = FieldCentricJava.FLYWHEEL_F_AT_10V;
        // With compensation off the robot uses FLYWHEEL_F at every voltage: save today's F then.
        double fNow = 0;
        int counted = 0;
        for (FlywheelTuning.Wheel wheel : result.wheels) {
            if (wheel.fNow > 0) {
                fNow += wheel.fNow;
                counted++;
            }
        }
        fNow = counted == 0 ? FieldCentricJava.FLYWHEEL_F : Math.round(fNow / counted * 100) / 100.0;
        FlywheelTuning.Wheel pollen = result.wheels.get(0), nectar = result.wheels.get(1);
        boolean found = result.fAt12_5V > 0 && pollen.best != null && nectar.best != null;

        // Every trial, dashboard only (the Driver Station screen is too small for the table).
        for (FlywheelTuning.Wheel wheel : result.wheels) {
            for (FlywheelTuning.Trial trial : wheel.trials) Dash.put(wheel.name + " " + trial.label, trial.describe());
        }

        boolean saved = false;
        while (opModeIsActive()) {
            if (found && !saved && gamepad1.a) {
                FieldCentricJava.FLYWHEEL_F = compensation ? result.fAt12_5V : fNow;
                if (compensation) FieldCentricJava.FLYWHEEL_F_AT_10V = result.fAt10V;
                FieldCentricJava.POLLEN_FLYWHEEL_P = pollen.best.p;
                FieldCentricJava.POLLEN_FLYWHEEL_I = pollen.best.i;
                FieldCentricJava.POLLEN_FLYWHEEL_D = pollen.best.d;
                FieldCentricJava.NECTAR_FLYWHEEL_P = nectar.best.p;
                FieldCentricJava.NECTAR_FLYWHEEL_I = nectar.best.i;
                FieldCentricJava.NECTAR_FLYWHEEL_D = nectar.best.d;
                settings.save();
                saved = true;
            }
            telemetry.addData("Next", !found ? "Nothing to apply: see the warnings."
                    : saved ? "Applied. " + settings.status() + "  (./robot-sync pull to keep it in Git)"
                    : "Press A to apply and save to robot.json. STOP to discard.");
            if (!result.warning.isEmpty()) telemetry.addData("WARNING", result.warning);
            telemetry.addData("Battery during test", "%.2f V", result.volts);
            if (compensation) {
                telemetry.addData("FLYWHEEL_F (at 12.5 V)", "%.2f  (was %.2f)", result.fAt12_5V, oldF);
                telemetry.addData("FLYWHEEL_F_AT_10V", "%.2f  (was %.2f)", result.fAt10V, oldF10);
            } else {
                telemetry.addData("FLYWHEEL_F (compensation off)", "%.2f  (was %.2f)", fNow, oldF);
            }
            for (FlywheelTuning.Wheel wheel : result.wheels) {
                if (!wheel.warning.isEmpty()) telemetry.addData(wheel.name + " WARNING", wheel.warning);
                if (wheel.best == null) continue;
                telemetry.addData(wheel.name + " best", String.format(Locale.US, "P %s  I %s  D %s   %s",
                        FlywheelTuning.format(wheel.best.p), FlywheelTuning.format(wheel.best.i),
                        FlywheelTuning.format(wheel.best.d), wheel.best.describe()));
            }
            telemetry.addData("Every trial", "on the dashboard's Live tab, telemetry table");
            telemetry.update();
            sleep(50);
        }
    }

    /** The real flywheels for FlywheelTuning. Wheel 0 is Pollen, 1 is Nectar. */
    private final class HubRig implements FlywheelTuning.Rig {
        private final String[] names = {"Pollen", "Nectar"};
        private final DcMotorEx[] motors = {
                flywheel("shooterPollen", DcMotorSimple.Direction.REVERSE),   // same as FieldCentricJava
                flywheel("shooterNectar", DcMotorSimple.Direction.FORWARD)};
        private final double[] targets = {FieldCentricJava.POLLEN_FLYWHEEL_RPM, FieldCentricJava.NECTAR_FLYWHEEL_RPM};
        private final double[] lastRpm = new double[2], shownTarget = new double[2];
        private final long[] lastRead = new long[2];
        private final long start = System.nanoTime();
        private double lastVolts = 12.5;

        public int wheels() { return 2; }
        public String name(int w) { return names[w]; }
        public double targetRPM(int w) { return targets[w]; }

        public void power(int w, double power) {
            if (motors[w].getMode() != DcMotor.RunMode.RUN_WITHOUT_ENCODER) {
                motors[w].setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            }
            motors[w].setPower(power);
            shownTarget[w] = 0;
        }

        public void pidf(int w, double p, double i, double d, double f) {
            motors[w].setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            motors[w].setVelocityPIDFCoefficients(p, i, d, f);
        }

        public void targetRPM(int w, double rpm) {
            motors[w].setVelocity(rpm * TICKS_PER_REV / 60.0);
            shownTarget[w] = rpm;
        }

        public double rpm(int w) {
            lastRpm[w] = motors[w].getVelocity() * 60.0 / TICKS_PER_REV;
            lastRead[w] = System.nanoTime();
            return lastRpm[w];
        }

        public double volts() {
            lastVolts = FieldCentricJava.flywheelBatteryVoltage(hardwareMap, lastVolts);
            return lastVolts;
        }

        public double seconds() { return (System.nanoTime() - start) / 1e9; }
        public void pause(double seconds) { sleep(Math.max(1, Math.round(seconds * 1000))); }
        public boolean active() { return opModeIsActive(); }

        public void status(String text) {
            telemetry.addData("Status", text);
            telemetry.addData("Battery", "%.2f V", lastVolts);
            for (int w = 0; w < 2; w++) {
                // Reuse the procedure's own reading when it is fresh: every read is a hub call.
                double rpm = System.nanoTime() - lastRead[w] < 30_000_000L ? lastRpm[w] : rpm(w);
                telemetry.addData(names[w] + " RPM", Math.round(rpm));
                Dash.put(names[w] + " target", shownTarget[w]);   // the Flywheels chart's target lines
            }
            telemetry.update();
        }

        private DcMotorEx flywheel(String name, DcMotorSimple.Direction direction) {
            DcMotorEx motor = hardwareMap.get(DcMotorEx.class, name);
            motor.setDirection(direction);
            motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
            return motor;
        }
    }
}
