package org.firstinspires.ftc.teamcode.helpers;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.FieldCentricJava;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds F and P for both flywheels, at the target RPMs from robot.json / FieldCentricJava.
 *
 * 1. F (feedforward): runs each wheel at two fixed powers and measures the speed, then works
 *    out the power needed at the target RPM. F = 32767 x that power / target ticks per second.
 * 2. P: with that F, tries several P values. For each one it holds the wheel at 70% of target,
 *    jumps to the target, and measures how fast it settles, how far it overshoots and how much
 *    it wobbles. The fastest P that does not overshoot or wobble too much wins.
 *
 * Both wheels run at the same time and spin up to full speed: keep hands and balls clear.
 * Takes about 40 seconds. Press STOP at any time to stop the wheels.
 * At the end, press A to put the results in the settings and save robot.json.
 *
 * It tunes with no ball going through. Check with real shots afterwards; a heavier shot load
 * may want a little more P.
 */
@TeleOp(name = "Flywheel Tuner", group = "Diagnostics")
public class FlywheelTuner extends LinearOpMode {

    private static final double TICKS_PER_REV = 28;      // goBILDA 5202 1:1, same as VelocityMotor
    private static final double LOW_POWER = 0.4, HIGH_POWER = 0.8;
    private static final double SETTLE_SECONDS = 2.0;   // spin-up before measuring a fixed power
    private static final double MEASURE_SECONDS = 1.0;
    private static final double HOLD_SECONDS = 1.5;      // at 70% before each step
    private static final double STEP_SECONDS = 2.0;      // recorded after each step
    private static final double BAND = 0.02;             // "settled" = within 2% of target
    private static final double MAX_OVERSHOOT = 0.05, MAX_WOBBLE = 0.015;
    /** P values tried, as multiples of the current P. */
    private static final double[] P_SCALES = {0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0};

    private VoltageSensor battery;

    /** One flywheel and everything measured about it. */
    private static class Wheel {
        final String name;
        final DcMotorEx motor;
        final double targetRPM, currentP, d;
        double f;
        final List<Trial> trials = new ArrayList<>();
        Trial best;
        String warning = "";

        Wheel(String name, DcMotorEx motor, double targetRPM, double currentP, double d) {
            this.name = name;
            this.motor = motor;
            this.targetRPM = targetRPM;
            this.currentP = currentP;
            this.d = d;
        }

        double rpm() {
            return motor.getVelocity() * 60.0 / TICKS_PER_REV;
        }

        double targetTicks() {
            return targetRPM * TICKS_PER_REV / 60.0;
        }
    }

    /** One P value's step test. */
    private static class Trial {
        final double p;
        double settleSeconds = Double.POSITIVE_INFINITY, overshoot, wobble, steadyError;
        final List<double[]> samples = new ArrayList<>();   // {seconds, rpm}

        Trial(double p) {
            this.p = p;
        }

        boolean passes() {
            return Double.isFinite(settleSeconds) && overshoot <= MAX_OVERSHOOT && wobble <= MAX_WOBBLE;
        }
    }

    @Override
    public void runOpMode() {
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);
        RobotSettings settings = new RobotSettings().load();
        battery = hardwareMap.voltageSensor.iterator().next();

        // Same names and directions as FieldCentricJava.
        Wheel pollen = new Wheel("Pollen", flywheel("shooterPollen", DcMotorSimple.Direction.REVERSE),
                FieldCentricJava.POLLEN_FLYWHEEL_RPM, FieldCentricJava.POLLEN_FLYWHEEL_P,
                FieldCentricJava.POLLEN_FLYWHEEL_D);
        Wheel nectar = new Wheel("Nectar", flywheel("shooterNectar", DcMotorSimple.Direction.FORWARD),
                FieldCentricJava.NECTAR_FLYWHEEL_RPM, FieldCentricJava.NECTAR_FLYWHEEL_P,
                FieldCentricJava.NECTAR_FLYWHEEL_D);
        Wheel[] wheels = {pollen, nectar};

        telemetry.addLine("Flywheel Tuner. Both flywheels spin up to full speed.");
        telemetry.addLine("Keep hands and balls clear. About 40 seconds. STOP stops the wheels.");
        telemetry.addData("Settings", settings.status());
        telemetry.addData("Battery", volts());
        for (Wheel wheel : wheels) {
            telemetry.addData(wheel.name + " target RPM", wheel.targetRPM);
        }
        telemetry.update();
        waitForStart();

        try {
            double startVolts = battery.getVoltage();
            findF(wheels);
            for (double scale : P_SCALES) {
                if (!opModeIsActive()) return;
                for (Wheel wheel : wheels) {
                    wheel.trials.add(new Trial(Math.max(1, Math.round(wheel.currentP * scale))));
                }
                stepTest(wheels);
            }
            for (Wheel wheel : wheels) {
                pickBest(wheel);
            }
            stopAll(wheels);
            showResults(wheels, startVolts, settings);
        } finally {
            stopAll(wheels);
        }
    }

    private DcMotorEx flywheel(String name, DcMotorSimple.Direction direction) {
        DcMotorEx motor = hardwareMap.get(DcMotorEx.class, name);
        motor.setDirection(direction);
        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        return motor;
    }

    /** Two fixed powers give a speed-vs-power line; F comes from the power that line needs at target. */
    private void findF(Wheel[] wheels) {
        for (Wheel wheel : wheels) {
            wheel.motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        double[] low = averageRPMAtPower(wheels, LOW_POWER);
        double[] high = averageRPMAtPower(wheels, HIGH_POWER);
        for (int i = 0; i < wheels.length; i++) {
            Wheel wheel = wheels[i];
            double rpmPerPower = (high[i] - low[i]) / (HIGH_POWER - LOW_POWER);
            if (!(rpmPerPower > 0)) {
                // The wheel did not speed up: wrong direction, unplugged, or jammed.
                wheel.warning = "did not speed up with more power (" + Math.round(low[i]) + " -> "
                        + Math.round(high[i]) + " RPM); F not changed";
                wheel.f = FieldCentricJava.FLYWHEEL_F;
                continue;
            }
            double powerAtTarget = HIGH_POWER + (wheel.targetRPM - high[i]) / rpmPerPower;
            if (powerAtTarget > 1.0) {
                wheel.warning = "target needs " + Math.round(powerAtTarget * 100)
                        + "% power: more than the motor has";
            }
            wheel.f = round1(32767.0 * powerAtTarget / wheel.targetTicks());
        }
    }

    private double[] averageRPMAtPower(Wheel[] wheels, double power) {
        for (Wheel wheel : wheels) {
            wheel.motor.setPower(power);
        }
        waitSeconds(SETTLE_SECONDS, "Measuring speed at " + Math.round(power * 100) + "% power", wheels);
        double[] sum = new double[wheels.length];
        int count = 0;
        long end = System.nanoTime() + (long) (MEASURE_SECONDS * 1e9);
        while (opModeIsActive() && System.nanoTime() < end) {
            for (int i = 0; i < wheels.length; i++) {
                sum[i] += wheels[i].rpm();
            }
            count++;
            sleep(10);
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] = count == 0 ? 0 : sum[i] / count;
        }
        return sum;
    }

    /** Runs the newest Trial of every wheel: hold at 70%, then step to target and record. */
    private void stepTest(Wheel[] wheels) {
        for (Wheel wheel : wheels) {
            Trial trial = latest(wheel);
            wheel.motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            wheel.motor.setVelocityPIDFCoefficients(trial.p, 0, wheel.d, wheel.f);
            wheel.motor.setVelocity(wheel.targetTicks() * 0.7);
        }
        waitSeconds(HOLD_SECONDS, "Trying P = " + describeP(wheels), wheels);

        for (Wheel wheel : wheels) {
            wheel.motor.setVelocity(wheel.targetTicks());
        }
        long start = System.nanoTime();
        while (opModeIsActive()) {
            double seconds = (System.nanoTime() - start) / 1e9;
            if (seconds > STEP_SECONDS) break;
            for (Wheel wheel : wheels) {
                latest(wheel).samples.add(new double[]{seconds, wheel.rpm()});
            }
            sleep(10);
        }
        for (Wheel wheel : wheels) {
            score(latest(wheel), wheel.targetRPM);
        }
    }

    /** Settling time, overshoot, wobble and steady error of one step, as fractions of target. */
    static void score(Trial trial, double target) {
        List<double[]> samples = trial.samples;
        if (samples.isEmpty()) return;
        double peak = 0;
        for (double[] sample : samples) peak = Math.max(peak, sample[1]);
        trial.overshoot = Math.max(0, (peak - target) / target);

        // Settled = the last time it was outside the band, if the tail stays inside.
        double settle = 0;
        for (double[] sample : samples) {
            if (Math.abs(sample[1] - target) > BAND * target) settle = sample[0];
        }
        double end = samples.get(samples.size() - 1)[0];
        trial.settleSeconds = settle < end - 0.25 ? settle : Double.POSITIVE_INFINITY;

        // Wobble and steady error over the last 0.75 s.
        double sum = 0, sumSquares = 0;
        int count = 0;
        for (double[] sample : samples) {
            if (sample[0] >= end - 0.75) {
                sum += sample[1];
                sumSquares += sample[1] * sample[1];
                count++;
            }
        }
        double mean = sum / count;
        trial.wobble = Math.sqrt(Math.max(0, sumSquares / count - mean * mean)) / target;
        trial.steadyError = (mean - target) / target;
    }

    /** Fastest passing trial; ties go to the smaller P. If none pass, the fastest anyway. */
    static void pickBest(Wheel wheel) {
        Trial best = null;
        for (Trial trial : wheel.trials) {
            if (trial.passes() && (best == null || trial.settleSeconds < best.settleSeconds - 0.02)) {
                best = trial;
            }
        }
        if (best == null) {
            // Nothing settled cleanly: take the one closest to target and steadiest.
            for (Trial trial : wheel.trials) {
                if (best == null || badness(trial) < badness(best)) best = trial;
            }
            if (wheel.warning.isEmpty()) {
                wheel.warning = "no P settled cleanly; check the wheel spins freely";
            }
        }
        wheel.best = best;
    }

    private static double badness(Trial trial) {
        return Math.abs(trial.steadyError) + trial.wobble + trial.overshoot;
    }

    private void showResults(Wheel[] wheels, double startVolts, RobotSettings settings) {
        double sharedF = round1((wheels[0].f + wheels[1].f) / 2);
        boolean saved = false;
        while (opModeIsActive()) {
            if (!saved && gamepad1.a) {
                // One F is shared by both flywheels in FieldCentricJava, so use the average.
                FieldCentricJava.FLYWHEEL_F = sharedF;
                FieldCentricJava.POLLEN_FLYWHEEL_P = wheels[0].best.p;
                FieldCentricJava.NECTAR_FLYWHEEL_P = wheels[1].best.p;
                settings.save();
                saved = true;
            }
            telemetry.addLine(saved ? "Applied. " + settings.status() + "  (./robot-sync pull to keep it in Git)"
                    : "Press A to apply and save to robot.json. STOP to discard.");
            telemetry.addData("Battery (start / now)", round1(startVolts) + " V / " + volts());
            telemetry.addData("FLYWHEEL_F (shared, average)", sharedF);
            for (Wheel wheel : wheels) {
                telemetry.addLine("");
                telemetry.addLine("--- " + wheel.name.toUpperCase(Locale.US) + " at " + Math.round(wheel.targetRPM) + " RPM ---");
                if (!wheel.warning.isEmpty()) telemetry.addData("WARNING", wheel.warning);
                telemetry.addData("F (this wheel)", wheel.f);
                telemetry.addData("Best P", wheel.best.p + "  (was " + wheel.currentP + ")");
                for (Trial trial : wheel.trials) {
                    telemetry.addLine(String.format(Locale.US, "%s P %-5s settle %s  over %4.1f%%  wobble %3.1f%%  error %+4.1f%%",
                            trial == wheel.best ? ">" : " ", fmt(trial.p),
                            Double.isFinite(trial.settleSeconds)
                                    ? String.format(Locale.US, "%.2fs", trial.settleSeconds) : "never",
                            trial.overshoot * 100, trial.wobble * 100, trial.steadyError * 100));
                }
            }
            telemetry.update();
            sleep(50);
        }
    }

    private void waitSeconds(double seconds, String status, Wheel[] wheels) {
        long end = System.nanoTime() + (long) (seconds * 1e9);
        while (opModeIsActive() && System.nanoTime() < end) {
            telemetry.addLine(status);
            telemetry.addData("Battery", volts());
            for (Wheel wheel : wheels) {
                telemetry.addData(wheel.name + " RPM", Math.round(wheel.rpm()));
            }
            telemetry.update();
            sleep(20);
        }
    }

    private static void stopAll(Wheel[] wheels) {
        for (Wheel wheel : wheels) {
            wheel.motor.setPower(0);
            wheel.motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        }
    }

    private static Trial latest(Wheel wheel) {
        return wheel.trials.get(wheel.trials.size() - 1);
    }

    private static String describeP(Wheel[] wheels) {
        StringBuilder text = new StringBuilder();
        for (Wheel wheel : wheels) {
            if (text.length() > 0) text.append(", ");
            text.append(wheel.name).append(' ').append(fmt(latest(wheel).p));
        }
        return text.toString();
    }

    private String volts() {
        return round1(battery.getVoltage()) + " V";
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
