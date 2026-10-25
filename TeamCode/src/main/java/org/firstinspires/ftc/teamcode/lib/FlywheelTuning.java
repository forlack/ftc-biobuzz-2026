package org.firstinspires.ftc.teamcode.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The flywheel tuning procedure, in plain Java: the Flywheel Tuner OpMode runs it on the robot,
 * and the desktop check (tests/java/FlywheelTuningCheck.java) runs the same code on a model.
 *
 * The flywheels are identical, so they get one answer: every trial runs on all of them at once
 * and their scores are pooled (twice the data in the same time).
 *
 * 1. F: each wheel at two fixed powers; the speed-vs-power line gives the power needed at the
 *    target, so F = 32767 x power / target ticks per second. Measured at today's battery and
 *    converted to F at 12.5 V with the robot's own compensation slope (FlywheelCompensation).
 * 2. P: a coarse sweep ({@link #P_COARSE}), then narrowing in between the best and its
 *    neighbours, going past the ends of the range if the best is at an end.
 * 3. I with that P, from {@link #I_START} upwards, then D from {@link #D_START} upwards, each
 *    going further while the cost keeps falling. Then P is checked again around its value
 *    with the chosen I and D, since the three interact.
 *
 * Each trial: hold at 70% of target, jump to target, and score the step ("cost" = average
 * error + steady error and wobble at the end + a penalty for overshooting more than 3%, as
 * fractions of the target; lower is better), {@link #REPEATS} times on every wheel.
 * The hub's speed reading is noisy (steps of about 40 RPM), so a value only wins if it beats
 * the others by more than the repeats vary; a tie goes to the gentler setting (smaller P,
 * smaller or no I and D).
 */
public final class FlywheelTuning {

    public static final double[] P_COARSE = {5, 10, 20, 35, 50};
    public static final double P_MIN = 2, P_MAX = 300;
    public static final double I_START = 3, D_START = 5;
    /** Each further I or D try is this much bigger, up to the cap. */
    static final double GROW = 1.6, I_MAX = 60, D_MAX = 60;
    static final int MIN_EXTRA_TRIES = 3;
    public static final int REPEATS = 3;

    static final double LOW_POWER = 0.4, HIGH_POWER = 0.8;
    static final double SETTLE_SECONDS = 2.0, MEASURE_SECONDS = 1.0;
    static final double HOLD_SECONDS = 1.0, STEP_SECONDS = 1.5, TAIL_SECONDS = 0.5;
    static final double SAMPLE_SECONDS = 0.01, SMOOTH_SECONDS = 0.1;
    static final double HOLD_FRACTION = 0.7, OVERSHOOT_ALLOWED = 0.03, SETTLED_BAND = 0.03;
    /** The smallest difference in cost that counts, whatever the repeats say (0.1% of target). */
    static final double MIN_MARGIN = 0.001;

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

    /** One coefficient set, tried REPEATS times on every wheel. */
    public static final class Trial {
        public final double p, i, d;
        final List<Double> costs = new ArrayList<>();
        double settleSum, overshootSum;

        Trial(double p, double i, double d) {
            this.p = p;
            this.i = i;
            this.d = d;
        }

        public String label() {
            return "P " + format(p) + "  I " + format(i) + "  D " + format(d);
        }

        public double cost() {
            double sum = 0;
            for (double cost : costs) sum += cost;
            return costs.isEmpty() ? Double.POSITIVE_INFINITY : sum / costs.size();
        }

        /** How uncertain cost() is: the standard error of all its step tests. */
        double standardError() {
            int n = costs.size();
            if (n < 2) return 0;
            double mean = cost(), squares = 0;
            for (double cost : costs) squares += (cost - mean) * (cost - mean);
            return Math.sqrt(squares / (n - 1) / n);
        }

        public double settleSeconds() { return settleSum / Math.max(1, costs.size()); }
        public double overshoot() { return overshootSum / Math.max(1, costs.size()); }

        public String describe() {
            double settle = settleSeconds();
            return String.format(Locale.US, "cost %.2f%% ±%.2f  settle %s  over %.1f%%", cost() * 100,
                    standardError() * 100,
                    Double.isFinite(settle) ? String.format(Locale.US, "%.2fs", settle) : "never",
                    overshoot() * 100);
        }
    }

    /** What was measured on one wheel. */
    public static final class Wheel {
        public final String name;
        public final double targetRPM;
        /** F needed at the battery voltage of the test, and converted to 12.5 V. */
        public double fNow, fAt12_5V;
        public String warning = "";

        Wheel(String name, double targetRPM) {
            this.name = name;
            this.targetRPM = targetRPM;
        }

        double targetTicks() { return targetRPM * 28 / 60.0; }   // flywheels: 28 ticks per rev
    }

    /** The answer: shared F (both ends of the compensation line) and one P, I, D for all wheels. */
    public static final class Result {
        public final List<Wheel> wheels = new ArrayList<>();
        /** Every trial, in the order tried. */
        public final List<Trial> trials = new ArrayList<>();
        public Trial best;
        public double volts, fAt12_5V, fAt10V;
        public String warning = "";
        public double seconds;
        /** P values (with the chosen I and D) that scored as well as the chosen one, within the noise. */
        public double similarPLow, similarPHigh;
    }

    /**
     * Runs the whole procedure. {@code fAt12_5V} and {@code fAt10V} are the current settings;
     * only their ratio is used, so the new F keeps the calibrated compensation slope.
     * Returns null if stopped early.
     */
    public static Result run(Rig rig, double fAt12_5V, double fAt10V) {
        double began = rig.seconds();
        Result result = new Result();
        for (int w = 0; w < rig.wheels(); w++) result.wheels.add(new Wheel(rig.name(w), rig.targetRPM(w)));
        if (!findF(rig, result, fAt12_5V > 0 ? fAt10V / fAt12_5V : 1)) return null;
        if (result.fAt12_5V <= 0) return result;

        // P: coarse, then narrow in (and past an end if the best is there).
        for (double p : P_COARSE) if (attempt(rig, result, p, 0, 0) == null) return null;
        for (int round = 0; round < 3; round++) {
            Trial best = choose(result, 0, 0, null);
            double below = neighbour(result, best, false), above = neighbour(result, best, true);
            double lower = Double.isNaN(below) ? Math.max(P_MIN, best.p / 2) : Math.sqrt(below * best.p);
            double upper = Double.isNaN(above) ? Math.min(P_MAX, best.p * 1.5) : Math.sqrt(above * best.p);
            boolean tried = false;
            for (double p : new double[]{lower, upper}) {
                p = roundP(p);
                if (find(result, p, 0, 0) == null && p >= P_MIN && p <= P_MAX) {
                    if (attempt(rig, result, p, 0, 0) == null) return null;
                    tried = true;
                }
            }
            if (!tried) break;
        }
        Trial chosen = choose(result, 0, 0, null);

        // I, then D, on top: upwards while the cost keeps falling.
        chosen = extra(rig, result, chosen, true);
        if (chosen == null) return null;
        chosen = extra(rig, result, chosen, false);
        if (chosen == null) return null;

        // P again, around its value, with the chosen I and D.
        for (double scale : new double[]{0.7, 1.4}) {
            double p = roundP(chosen.p * scale);
            if (p >= P_MIN && p <= P_MAX && find(result, p, chosen.i, chosen.d) == null) {
                if (attempt(rig, result, p, chosen.i, chosen.d) == null) return null;
            }
        }
        result.best = choose(result, chosen.i, chosen.d, chosen);
        result.similarPLow = result.similarPHigh = result.best.p;
        for (Trial trial : result.trials) {
            if (trial.i == result.best.i && trial.d == result.best.d && !better(result.best, trial)) {
                result.similarPLow = Math.min(result.similarPLow, trial.p);
                result.similarPHigh = Math.max(result.similarPHigh, trial.p);
            }
        }
        stop(rig);
        result.seconds = rig.seconds() - began;
        return result;
    }

    /** I (integral true) or D trials upwards from the start value; returns the best set so far. */
    private static Trial extra(Rig rig, Result result, Trial base, boolean integral) {
        double value = integral ? I_START : D_START, max = integral ? I_MAX : D_MAX;
        Trial best = base, previous = base;
        for (int tries = 0; value <= max; tries++) {
            Trial trial = attempt(rig, result, base.p, integral ? value : base.i, integral ? base.d : value);
            if (trial == null) return null;
            if (better(trial, best)) best = trial;
            // Keep going while it is still getting better (and at least a few tries).
            if (tries + 1 >= MIN_EXTRA_TRIES && !(trial.cost() < previous.cost())) break;
            previous = trial;
            value = Math.round(value * GROW * 10) / 10.0;
        }
        return best;
    }

    /** True if {@code a} is better than {@code b} by more than the noise. */
    static boolean better(Trial a, Trial b) {
        return a.cost() < b.cost() - margin(a, b);
    }

    /** Differences in cost smaller than this are noise. */
    static double margin(Trial a, Trial b) {
        double se = Math.sqrt(a.standardError() * a.standardError() + b.standardError() * b.standardError());
        return Math.max(MIN_MARGIN, 2 * se);
    }

    /**
     * Lowest cost among the trials with this I and D (and {@code include}); a tie within the
     * noise goes to the smaller P.
     */
    static Trial choose(Result result, double i, double d, Trial include) {
        Trial best = include;
        for (Trial trial : result.trials) {
            if (trial.i == i && trial.d == d && (best == null || trial.cost() < best.cost())) best = trial;
        }
        if (best == null) return null;
        Trial chosen = best;
        for (Trial trial : result.trials) {
            if (trial.i == i && trial.d == d && trial.p < chosen.p && trial.cost() <= best.cost() + margin(trial, best)) {
                chosen = trial;
            }
        }
        return chosen;
    }

    /** The nearest P tried below (or above) this one with no I or D, or NaN. */
    private static double neighbour(Result result, Trial of, boolean above) {
        double found = Double.NaN;
        for (Trial trial : result.trials) {
            if (trial.i != 0 || trial.d != 0 || trial.p == of.p || (trial.p > of.p) != above) continue;
            if (Double.isNaN(found) || Math.abs(trial.p - of.p) < Math.abs(found - of.p)) found = trial.p;
        }
        return found;
    }

    private static Trial find(Result result, double p, double i, double d) {
        for (Trial trial : result.trials) if (trial.p == p && trial.i == i && trial.d == d) return trial;
        return null;
    }

    static double roundP(double p) {
        return p < 20 ? Math.round(p * 2) / 2.0 : Math.round(p);
    }

    /** Runs one coefficient set REPEATS times on every wheel at once. Null if stopped. */
    private static Trial attempt(Rig rig, Result result, double p, double i, double d) {
        Trial trial = new Trial(p, i, d);
        result.trials.add(trial);
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            double f = FlywheelCompensation.f(rig.volts(), true, result.fAt12_5V, result.fAt10V);
            for (int w = 0; w < rig.wheels(); w++) {
                rig.pidf(w, p, i, d, f);
                rig.targetRPM(w, result.wheels.get(w).targetRPM * HOLD_FRACTION);
            }
            String label = "Trial " + result.trials.size() + ": " + trial.label() + "  (" + (repeat + 1) + " of " + REPEATS + ")";
            if (!wait(rig, HOLD_SECONDS, label)) return null;

            List<List<double[]>> samples = new ArrayList<>();
            for (int w = 0; w < rig.wheels(); w++) {
                rig.targetRPM(w, result.wheels.get(w).targetRPM);
                samples.add(new ArrayList<double[]>());
            }
            double start = rig.seconds();
            while (rig.active()) {
                double t = rig.seconds() - start;
                if (t > STEP_SECONDS) break;
                for (int w = 0; w < rig.wheels(); w++) samples.get(w).add(new double[]{t, rig.rpm(w)});
                rig.status(label);
                rig.pause(SAMPLE_SECONDS);
            }
            if (!rig.active()) return null;
            for (int w = 0; w < rig.wheels(); w++) {
                double[] score = score(samples.get(w), result.wheels.get(w).targetRPM);
                trial.costs.add(score[0]);
                trial.settleSum += score[1];
                trial.overshootSum += score[2];
            }
        }
        return trial;
    }

    /** Step 1: F from two fixed powers. False if stopped. */
    private static boolean findF(Rig rig, Result result, double slope) {
        double[] low = measureAtPower(rig, LOW_POWER);
        double voltsLow = rig.volts();
        double[] high = measureAtPower(rig, HIGH_POWER);
        result.volts = (voltsLow + rig.volts()) / 2;
        if (!rig.active()) return false;
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
            stop(rig);
            return true;
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
