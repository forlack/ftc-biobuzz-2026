import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.FlywheelTuning;

import java.util.Random;

/**
 * Runs the real tuning procedure (lib/FlywheelTuning) on a model of two identical flywheels,
 * several times with different noise, and checks they get the same answer.
 *
 * The model: a REV hub velocity PIDF loop every 20 ms on a speed reading that moves in steps
 * of 20 ticks/s (about 43 RPM) plus noise, driving a spinning mass with friction, on a battery
 * that sags under load. It is a model, not the robot: it checks the procedure, not the numbers.
 *
 *   mkdir -p /tmp/fwt && javac -d /tmp/fwt TeamCode/src/main/java/org/firstinspires/ftc/teamcode/lib/FlywheelCompensation.java \
 *       TeamCode/src/main/java/org/firstinspires/ftc/teamcode/lib/FlywheelTuning.java tests/java/FlywheelTuningCheck.java \
 *       && java -cp /tmp/fwt FlywheelTuningCheck
 */
public class FlywheelTuningCheck {

    static final class Model implements FlywheelTuning.Rig {
        final Random noise;
        final double[] target = {3400, 3650};
        double time, battery = 12.6;
        final double[] velocity = new double[2], measured = new double[2], output = new double[2];
        final double[] integral = new double[2], lastError = new double[2], setpoint = new double[2];
        final double[][] pidf = new double[2][4];
        final boolean[] open = new boolean[2];
        final double[] friction = {0.04, 0.04};
        double speedNoise = 6;
        double sampleTimer;

        Model(long seed) { noise = new Random(seed); }

        public int wheels() { return 2; }
        public String name(int w) { return w == 0 ? "Pollen" : "Nectar"; }
        public double targetRPM(int w) { return target[w]; }
        public void power(int w, double power) { open[w] = true; output[w] = power; }
        public void pidf(int w, double p, double i, double d, double f) {
            open[w] = false;
            pidf[w] = new double[]{p, i, d, f};
            integral[w] = 0;
        }
        public void targetRPM(int w, double rpm) { setpoint[w] = rpm * 28 / 60.0; }
        public double rpm(int w) { return measured[w] * 60 / 28.0; }
        public double volts() { return battery + noise.nextGaussian() * 0.02; }
        public double seconds() { return time; }
        public boolean active() { return true; }
        public void status(String text) { }

        public void pause(double seconds) {
            for (double t = 0; t < seconds; t += 0.001) step(0.001);
        }

        void step(double dt) {
            time += dt;
            sampleTimer += dt;
            boolean sample = sampleTimer >= 0.02;
            if (sample) sampleTimer = 0;
            double load = 0;
            for (int w = 0; w < 2; w++) {
                if (sample) {
                    measured[w] = Math.round((velocity[w] + noise.nextGaussian() * speedNoise) / 20) * 20.0;
                    if (!open[w]) {
                        double error = setpoint[w] - measured[w];
                        // Integrated per second. On the robot (2026-10-24) the cost kept falling as I
                        // rose to 3; summing per 20 ms cycle instead made I = 3 overshoot badly.
                        integral[w] = Math.max(-20000, Math.min(20000, integral[w] + error * 0.02));
                        double derivative = (error - lastError[w]) / 0.02;
                        lastError[w] = error;
                        double[] k = pidf[w];
                        output[w] = Math.max(-1, Math.min(1,
                                (k[0] * error + k[1] * integral[w] + k[2] * derivative + k[3] * setpoint[w]) / 32767.0));
                    }
                }
                double drive = Math.signum(output[w]) * Math.max(0, Math.abs(output[w]) - friction[w]);
                double reachable = drive * 2800 * battery / 12.0;
                velocity[w] += (reachable - velocity[w]) * dt / 0.45;
                load += Math.abs(output[w]);
            }
            battery += (12.6 - 0.15 * load - battery) * dt / 0.05;
        }
    }

    public static void main(String[] args) {
        // Quiet: the noise first assumed. Noisy: more speed-reading noise, closer to what the
        // robot showed on 2026-10-24 (the old tuner picked P 25, the lowest it tried).
        for (double noiseLevel : new double[]{6, 30}) {
            int runs = 6, consistent = 0;
            double firstP = 0;
            System.out.println("Speed noise " + noiseLevel + " ticks/s:");
            for (long seed = 1; seed <= runs; seed++) {
                Model model = new Model(seed);
                model.speedNoise = noiseLevel;
                FlywheelTuning.Result result = FlywheelTuning.run(model, 11, 12);
                FlywheelTuning.Trial best = result.best;
                System.out.printf("  seed %d: F@12.5V %.2f (F@10V %.2f) | P %s I %s D %s | %s | %d trials, %.0f s%n",
                        seed, result.fAt12_5V, result.fAt10V, FlywheelTuning.format(best.p),
                        FlywheelTuning.format(best.i), FlywheelTuning.format(best.d), best.describe(),
                        result.trials.size(), result.seconds);
                if (seed == 1) {
                    firstP = best.p;
                    for (FlywheelTuning.Trial trial : result.trials) System.out.println("      " + trial.label() + ": " + trial.describe());
                }
                // Where the cost is flat the exact P is luck; what matters is that the chosen one
                // is as good as the best one tried (within 0.5% of target).
                if (best.cost() <= lowestPCost(result) + 0.005) consistent++;
                System.out.println("      similar P: " + FlywheelTuning.format(result.similarPLow) + " to "
                        + FlywheelTuning.format(result.similarPHigh));
                double fUsed = FlywheelCompensation.f(result.volts, true, result.fAt12_5V, result.fAt10V);
                double fNeeded = (result.wheels.get(0).fNow + result.wheels.get(1).fNow) / 2;
                if (Math.abs(fUsed - fNeeded) > 0.3) throw new AssertionError("F conversion is off: " + fUsed);
                if (result.seconds > 240) throw new AssertionError("takes too long: " + result.seconds + " s");
            }
            System.out.println("  " + consistent + " of " + runs + " runs chose coefficients within 0.5% of the best P tried");
            if (consistent < runs) throw new AssertionError("the chosen coefficients should be as good as the best");
        }

        // A draggier Nectar: the shared F is too low for it. The tuner must say so.
        Model draggy = new Model(99);
        draggy.friction[1] = 0.12;
        FlywheelTuning.Result result = FlywheelTuning.run(draggy, 11, 12);
        System.out.println("Draggy Nectar: " + result.best.label() + "; warning: " + result.warning);
        if (result.warning.isEmpty()) throw new AssertionError("different F per wheel should be reported");
        System.out.println("PASS");
    }

    static double lowestPCost(FlywheelTuning.Result result) {
        double lowest = Double.POSITIVE_INFINITY;
        for (FlywheelTuning.Trial trial : result.trials) lowest = Math.min(lowest, trial.cost());
        return lowest;
    }
}
