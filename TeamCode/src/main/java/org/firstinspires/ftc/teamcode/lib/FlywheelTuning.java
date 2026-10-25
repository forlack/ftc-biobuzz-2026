package org.firstinspires.ftc.teamcode.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The flywheel tuning procedure, in plain Java: the Flywheel Tuner OpMode runs it on the robot,
 * and the desktop check (tests/java/FlywheelTuningCheck.java) runs the same code on a model.
 *
 * 1. F: each wheel at two fixed powers; the speed-vs-power line gives the power needed at the
 *    target, so F = 32767 x power / target ticks per second. Measured at today's battery and
 *    converted to F at 12.5 V with the robot's own compensation slope (FlywheelCompensation).
 * 2. P: the same P values for every wheel. Each is tried {@link #REPEATS} times: hold at 70% of
 *    target, jump to target, and score how far from target the wheel was over the step.
 * 3. I, then D, with the chosen P: kept only if they clearly beat what is already chosen.
 *
 * Score ("cost") = average error over the step + steady error and wobble at the end + a
 * penalty for overshooting more than 3%, all as fractions of the target. Lower is better.
 * The hub's speed reading is noisy (steps of about 40 RPM), and with a good F the cost is
 * nearly flat over a wide range of P, so the lowest single score is mostly luck. Instead a
 * smooth curve, cost = a + b/P + c*P, is fitted through every P measurement, and the chosen P
 * is the point of diminishing returns: the smallest P within {@link #P_TOLERANCE} of the
 * curve's best. I and D are only used if they cut the cost by {@link #MUST_IMPROVE}.
 */
public final class FlywheelTuning {

    public static final double[] P_VALUES = {25, 50, 75, 100, 150, 200, 250, 300};
    public static final double[] I_VALUES = {0.5, 1, 3};
    public static final double[] D_VALUES = {2, 5};
    public static final int REPEATS = 3;

    static final double LOW_POWER = 0.4, HIGH_POWER = 0.8;
    static final double SETTLE_SECONDS = 2.0, MEASURE_SECONDS = 1.0;
    static final double HOLD_SECONDS = 1.0, STEP_SECONDS = 1.5, TAIL_SECONDS = 0.5;
    static final double SAMPLE_SECONDS = 0.01, SMOOTH_SECONDS = 0.1;
    static final double HOLD_FRACTION = 0.7, OVERSHOOT_ALLOWED = 0.03, SETTLED_BAND = 0.03;
    /** More P must improve the fitted cost by more than this (0.1% of target) to be worth it. */
    static final double P_TOLERANCE = 0.001;
    /** I or D must cut the cost by at least this (0.5% of target), well beyond the noise. */
    static final double MUST_IMPROVE = 0.005;

    private FlywheelTuning() { }

    /** The hardware, or a model of it. Wheel numbers are 0, 1, ... */
    public interface Rig {
        int wheels();
        String name(int wheel);
        double targetRPM(int wheel);
        /** Open loop: plain power, no speed control. */
        void power(int wheel, double power);
        /** Speed control with these coefficients (hub units). */
        void pidf(int wheel, double p, double i, double d, double f);
        void targetRPM(int wheel, double rpm);
        double rpm(int wheel);
        double volts();
        double seconds();
        void pause(double seconds);
        boolean active();
        /** Called about every 10 ms while running, to show progress. */
        void status(String text);
    }

    /** One coefficient set's step tests on one wheel. */
    public static final class Trial {
        public final String label;
        public final double p, i, d;
        final List<Double> costs = new ArrayList<>();
        double settleSum, overshootSum;

        Trial(String label, double p, double i, double d) {
            this.label = label;
            this.p = p;
            this.i = i;
            this.d = d;
        }

        public double cost() {
            double sum = 0;
            for (double cost : costs) sum += cost;
            return costs.isEmpty() ? Double.POSITIVE_INFINITY : sum / costs.size();
        }

        public double settleSeconds() { return settleSum / Math.max(1, costs.size()); }
        public double overshoot() { return overshootSum / Math.max(1, costs.size()); }

        public String describe() {
            double settle = settleSeconds();
            return String.format(Locale.US, "cost %.2f%%  settle %s  over %.1f%%", cost() * 100,
                    Double.isFinite(settle) ? String.format(Locale.US, "%.2fs", settle) : "never",
                    overshoot() * 100);
        }
    }

    /** Everything found for one wheel. */
    public static final class Wheel {
        public final String name;
        public final double targetRPM;
        /** F needed at the battery voltage of the test, and converted to 12.5 V. */
        public double fNow, fAt12_5V;
        public final List<Trial> trials = new ArrayList<>();
        public Trial best;
        public String warning = "";

        Wheel(String name, double targetRPM) {
            this.name = name;
            this.targetRPM = targetRPM;
        }

        double targetTicks() { return targetRPM * 28 / 60.0; }   // flywheels: 28 ticks per rev
    }

    /** The answer: shared F (both ends of the compensation line) and each wheel's P, I, D. */
    public static final class Result {
        public final List<Wheel> wheels = new ArrayList<>();
        public double volts, fAt12_5V, fAt10V;
        public String warning = "";
    }

    /**
     * Runs the whole procedure. {@code fAt12_5V} and {@code fAt10V} are the current settings;
     * only their ratio is used, so the new F keeps the calibrated compensation slope.
     * Returns null if stopped early.
     */
    public static Result run(Rig rig, double fAt12_5V, double fAt10V) {
        Result result = new Result();
        for (int w = 0; w < rig.wheels(); w++) result.wheels.add(new Wheel(rig.name(w), rig.targetRPM(w)));
        double slope = fAt12_5V > 0 ? fAt10V / fAt12_5V : 1;

        // 1. F.
        double[] low = measureAtPower(rig, LOW_POWER);
        double voltsLow = rig.volts();
        double[] high = measureAtPower(rig, HIGH_POWER);
        result.volts = (voltsLow + rig.volts()) / 2;
        if (!rig.active()) return null;
        double sum = 0;
        int count = 0;
        for (int w = 0; w < rig.wheels(); w++) {
            Wheel wheel = result.wheels.get(w);
            double rpmPerPower = (high[w] - low[w]) / (HIGH_POWER - LOW_POWER);
            if (!(rpmPerPower > 0)) {
                wheel.warning = "did not speed up with more power (" + Math.round(low[w]) + " -> "
                        + Math.round(high[w]) + " RPM); check direction and wiring";
                continue;
            }
            double powerAtTarget = HIGH_POWER + (wheel.targetRPM - high[w]) / rpmPerPower;
            if (powerAtTarget > 1.0) {
                wheel.warning = "target needs " + Math.round(powerAtTarget * 100) + "% power: more than the motor has";
            }
            wheel.fNow = 32767.0 * powerAtTarget / wheel.targetTicks();
            // Compensation gives F(V) = F12.5 * (1 + fraction * (slope - 1)); solve for F12.5.
            double fraction = Math.max(0, Math.min(1, (12.5 - result.volts) / 2.5));
            wheel.fAt12_5V = wheel.fNow / (1 + fraction * (slope - 1));
            sum += wheel.fAt12_5V;
            count++;
        }
        if (count == 0) {
            result.warning = "no wheel sped up; F not found";
            return result;
        }
        result.fAt12_5V = round2(sum / count);
        result.fAt10V = round2(result.fAt12_5V * slope);
        if (count == 2) {
            double a = result.wheels.get(0).fAt12_5V, b = result.wheels.get(1).fAt12_5V;
            if (Math.abs(a - b) > 0.1 * Math.max(a, b)) {
                result.warning = String.format(Locale.US,
                        "the wheels need different F (%.1f vs %.1f): one may have more friction", a, b);
            }
        }

        // 2. P: every wheel tries the same values.
        for (double p : P_VALUES) {
            List<Trial> trials = new ArrayList<>();
            for (Wheel wheel : result.wheels) trials.add(new Trial("P " + format(p), p, 0, 0));
            if (!stepTests(rig, result, trials)) return null;
        }
        for (Wheel wheel : result.wheels) wheel.best = choosePStep(wheel.trials);

        // 3. I, then D, on top of each wheel's chosen P.
        for (double i : I_VALUES) {
            List<Trial> trials = new ArrayList<>();
            for (Wheel wheel : result.wheels) {
                trials.add(new Trial("I " + format(i) + " (P " + format(wheel.best.p) + ")", wheel.best.p, i, 0));
            }
            if (!stepTests(rig, result, trials)) return null;
        }
        for (Wheel wheel : result.wheels) wheel.best = chooseExtra(wheel, true);
        for (double d : D_VALUES) {
            List<Trial> trials = new ArrayList<>();
            for (Wheel wheel : result.wheels) {
                trials.add(new Trial("D " + format(d) + " (P " + format(wheel.best.p) + ", I "
                        + format(wheel.best.i) + ")", wheel.best.p, wheel.best.i, d));
            }
            if (!stepTests(rig, result, trials)) return null;
        }
        for (Wheel wheel : result.wheels) wheel.best = chooseExtra(wheel, false);
        stop(rig);
        return result;
    }

    /** The point of diminishing returns on a curve fitted through every P measurement. */
    static Trial choosePStep(List<Trial> trials) {
        List<Trial> pTrials = new ArrayList<>();
        for (Trial trial : trials) if (trial.i == 0 && trial.d == 0 && !trial.costs.isEmpty()) pTrials.add(trial);
        if (pTrials.isEmpty()) return null;
        double[] fit = fitCost(pTrials);
        double bestFitted = Double.POSITIVE_INFINITY;
        for (Trial trial : pTrials) bestFitted = Math.min(bestFitted, fitted(fit, trial.p));
        Trial chosen = null;
        for (Trial trial : pTrials) {
            if (fitted(fit, trial.p) <= bestFitted + P_TOLERANCE && (chosen == null || trial.p < chosen.p)) {
                chosen = trial;
            }
        }
        return chosen;
    }

    /** Least squares fit of cost = a + b/P + c*P through every repeat; returns {a, b, c}. */
    static double[] fitCost(List<Trial> trials) {
        double[][] m = new double[3][4];
        for (Trial trial : trials) {
            double[] x = {1, 1 / trial.p, trial.p};
            for (double cost : trial.costs) {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) m[r][c] += x[r] * x[c];
                    m[r][3] += x[r] * cost;
                }
            }
        }
        // Gaussian elimination with partial pivoting.
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            double[] swap = m[col]; m[col] = m[pivot]; m[pivot] = swap;
            if (Math.abs(m[col][col]) < 1e-15) return new double[]{0, 0, 0};
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double factor = m[r][col] / m[col][col];
                for (int c = col; c < 4; c++) m[r][c] -= factor * m[col][c];
            }
        }
        return new double[]{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
    }

    static double fitted(double[] fit, double p) {
        return fit[0] + fit[1] / p + fit[2] * p;
    }

    /** Keeps the current best unless an I (or D) trial is clearly better, beyond the noise. */
    static Trial chooseExtra(Wheel wheel, boolean integral) {
        Trial base = wheel.best, best = base;
        for (Trial trial : wheel.trials) {
            boolean candidate = integral ? trial.i > 0 && trial.d == 0 : trial.d > 0;
            if (candidate && trial.p == base.p && (integral || trial.i == base.i)
                    && trial.cost() < base.cost() - MUST_IMPROVE && trial.cost() < best.cost()) {
                best = trial;
            }
        }
        return best;
    }

    /** REPEATS step tests of one trial per wheel, all wheels at once. False if stopped. */
    private static boolean stepTests(Rig rig, Result result, List<Trial> trials) {
        for (int w = 0; w < trials.size(); w++) result.wheels.get(w).trials.add(trials.get(w));
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            double f = FlywheelCompensation.f(rig.volts(), true, result.fAt12_5V, result.fAt10V);
            for (int w = 0; w < trials.size(); w++) {
                Trial trial = trials.get(w);
                rig.pidf(w, trial.p, trial.i, trial.d, f);
                rig.targetRPM(w, result.wheels.get(w).targetRPM * HOLD_FRACTION);
            }
            String label = trials.get(0).label.split(" \\(")[0] + "  (" + (repeat + 1) + " of " + REPEATS + ")";
            if (!wait(rig, HOLD_SECONDS, "Trying " + label)) return false;

            List<List<double[]>> samples = new ArrayList<>();
            for (int w = 0; w < trials.size(); w++) {
                rig.targetRPM(w, result.wheels.get(w).targetRPM);
                samples.add(new ArrayList<double[]>());
            }
            double start = rig.seconds();
            while (rig.active()) {
                double t = rig.seconds() - start;
                if (t > STEP_SECONDS) break;
                for (int w = 0; w < trials.size(); w++) samples.get(w).add(new double[]{t, rig.rpm(w)});
                rig.status("Trying " + label);
                rig.pause(SAMPLE_SECONDS);
            }
            if (!rig.active()) return false;
            for (int w = 0; w < trials.size(); w++) {
                Trial trial = trials.get(w);
                double[] score = score(samples.get(w), result.wheels.get(w).targetRPM);
                trial.costs.add(score[0]);
                trial.settleSum += score[1];
                trial.overshootSum += score[2];
            }
        }
        return true;
    }

    /**
     * Scores one step: {cost, settle seconds, overshoot}. Samples are {seconds, rpm}; the speed is
     * smoothed over SMOOTH_SECONDS first, because single hub readings jump by about 40 RPM.
     */
    public static double[] score(List<double[]> samples, double target) {
        int n = samples.size();
        if (n == 0) return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0};
        double[] t = new double[n], e = new double[n];
        for (int k = 0; k < n; k++) {
            double sum = 0;
            int count = 0;
            for (int j = k; j >= 0 && samples.get(k)[0] - samples.get(j)[0] <= SMOOTH_SECONDS; j--) {
                sum += samples.get(j)[1];
                count++;
            }
            t[k] = samples.get(k)[0];
            e[k] = (sum / count - target) / target;
        }
        double end = t[n - 1];
        double absSum = 0, peak = Double.NEGATIVE_INFINITY, tailSum = 0, tailSquares = 0;
        int tailCount = 0;
        double settle = 0;
        for (int k = 0; k < n; k++) {
            absSum += Math.abs(e[k]);
            peak = Math.max(peak, e[k]);
            if (Math.abs(e[k]) > SETTLED_BAND) settle = t[k];
            if (t[k] >= end - TAIL_SECONDS) {
                tailSum += e[k];
                tailSquares += e[k] * e[k];
                tailCount++;
            }
        }
        double average = absSum / n;
        double overshoot = Math.max(0, peak);
        double steady = Math.abs(tailSum / tailCount);
        double wobble = Math.sqrt(Math.max(0, tailSquares / tailCount - (tailSum / tailCount) * (tailSum / tailCount)));
        double cost = average + steady + wobble + 2 * Math.max(0, overshoot - OVERSHOOT_ALLOWED);
        double settleSeconds = settle < end - TAIL_SECONDS ? settle : Double.POSITIVE_INFINITY;
        return new double[]{cost, settleSeconds, overshoot};
    }

    private static double[] measureAtPower(Rig rig, double power) {
        for (int w = 0; w < rig.wheels(); w++) rig.power(w, power);
        String label = "Measuring speed at " + Math.round(power * 100) + "% power";
        double[] sum = new double[rig.wheels()];
        if (!wait(rig, SETTLE_SECONDS, label)) return sum;
        int count = 0;
        double end = rig.seconds() + MEASURE_SECONDS;
        while (rig.active() && rig.seconds() < end) {
            for (int w = 0; w < rig.wheels(); w++) sum[w] += rig.rpm(w);
            count++;
            rig.status(label);
            rig.pause(SAMPLE_SECONDS);
        }
        for (int w = 0; w < sum.length; w++) sum[w] = count == 0 ? 0 : sum[w] / count;
        return sum;
    }

    private static boolean wait(Rig rig, double seconds, String label) {
        double end = rig.seconds() + seconds;
        while (rig.active() && rig.seconds() < end) {
            rig.status(label);
            rig.pause(SAMPLE_SECONDS);
        }
        return rig.active();
    }

    public static void stop(Rig rig) {
        for (int w = 0; w < rig.wheels(); w++) rig.power(w, 0);
    }

    static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    public static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
