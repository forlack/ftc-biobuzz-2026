import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.FlywheelTuning;

import java.util.Random;

/**
 * Runs the real tuning procedure (lib/FlywheelTuning) on a model of two identical flywheels,
 * several times with different noise, and checks they get the same answer.
 *
 * The model: a REV hub velocity PIDF loop every 20 ms (error summed each cycle for I) on a speed reading that moves in steps
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
                    measured[w] = Math.round((velocity[w] + noise.nextGaussian() * 6) / 20) * 20.0;
                    if (!open[w]) {
                        double error = setpoint[w] - measured[w];
                        // Summed every 20 ms cycle, not per second: with the SDK's default velocity
                        // coefficients (P 10, I 3, F 0) that is what gets a motor to speed in about a second.
                        integral[w] = Math.max(-20000, Math.min(20000, integral[w] + error));
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
        int same = 0, runs = 8;
        for (long seed = 1; seed <= runs; seed++) {
            FlywheelTuning.Result result = FlywheelTuning.run(new Model(seed), 11, 12);
            FlywheelTuning.Wheel a = result.wheels.get(0), b = result.wheels.get(1);
            System.out.printf("seed %d: %.2f V, F@12.5V %.2f (F@10V %.2f) | Pollen P %s I %s D %s | Nectar P %s I %s D %s%n",
                    seed, result.volts, result.fAt12_5V, result.fAt10V,
                    FlywheelTuning.format(a.best.p), FlywheelTuning.format(a.best.i), FlywheelTuning.format(a.best.d),
                    FlywheelTuning.format(b.best.p), FlywheelTuning.format(b.best.i), FlywheelTuning.format(b.best.d));
            if (seed == 1) {
                for (FlywheelTuning.Trial trial : a.trials) System.out.println("    Pollen " + trial.label + ": " + trial.describe());
            }
            if (ratio(a.best.p, b.best.p) <= 1.34 && a.best.i == b.best.i && a.best.d == b.best.d) same++;
            // The F found reproduces the model's need at this voltage.
            double fUsed = FlywheelCompensation.f(result.volts, true, result.fAt12_5V, result.fAt10V);
            if (Math.abs(fUsed - (a.fNow + b.fNow) / 2) > 0.3) throw new AssertionError("F conversion is off: " + fUsed);
        }
        System.out.println(same + " of " + runs + " runs gave both identical wheels the same I and D and a P at most one step apart");
        if (same < runs - 1) throw new AssertionError("identical wheels should get (nearly) the same P");

        // A draggier Nectar: the shared F is too low for it. The tuner must say so, and still
        // find coefficients that hold speed (more P, or I).
        Model draggy = new Model(99);
        draggy.friction[1] = 0.12;
        FlywheelTuning.Result result = FlywheelTuning.run(draggy, 11, 12);
        FlywheelTuning.Wheel nectar = result.wheels.get(1);
        System.out.println("Draggy Nectar: P " + FlywheelTuning.format(nectar.best.p) + " I "
                + FlywheelTuning.format(nectar.best.i) + ", " + nectar.best.describe() + "; warning: " + result.warning);
        if (result.warning.isEmpty()) throw new AssertionError("different F per wheel should be reported");
        if (nectar.best.cost() > 0.05) throw new AssertionError("the draggy wheel should still hold speed");
        System.out.println("PASS");
    }

    static double ratio(double a, double b) { return Math.max(a, b) / Math.min(a, b); }
}
