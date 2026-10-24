package org.biobuzz.sim;

import com.pedropathing.algorithm.Foresight;
import com.pedropathing.drivetrain.DrivePowers;
import com.pedropathing.drivetrain.Drivetrain;
import com.pedropathing.follower.Follower;
import com.pedropathing.localization.Localizer;
import com.pedropathing.localization.MotionState;
import com.pedropathing.math.Pose;
import com.pedropathing.math.Twist;

import org.firstinspires.ftc.teamcode.pedro.ForesightConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * Plugs the simulated chassis into Pedro's real Follower and Foresight, with our robot's
 * tuned ForesightConstants. Only the hardware layer is pretend; the path following is Pedro.
 */
public final class SimPedro {
    private SimPedro() { }

    public static Follower follower(SimWorld world) {
        return new Follower(new SimLocalizer(world), new SimDrivetrain(world), new Foresight(ForesightConstants.config));
    }

    /** Acts like the Pinpoint: reports where the simulated robot is. */
    static final class SimLocalizer implements Localizer {
        private final SimWorld world;
        private MotionState state = MotionState.zero();

        SimLocalizer(SimWorld world) {
            this.world = world;
            update();
        }

        @Override public void setPose(Pose pose) {
            world.teleport(pose.x(), pose.y(), pose.heading());
            update();
        }

        @Override public MotionState state() { return state; }

        @Override public void update() {
            double[] pose = world.pose();
            double[] velocity = world.robotVelocity();
            state = MotionState.ofTwist(new Pose(pose[0], pose[1], pose[2]),
                    new Twist(velocity[0], velocity[1], velocity[2]));
        }

        @Override public void reset() { setPose(new Pose(0, 0, 0)); }

        @Override public Map<String, Object> debug() { return new HashMap<>(); }
    }

    /** Acts like the four mecanum motors, with the same power scaling as Pedro's Mecanum. */
    static final class SimDrivetrain implements Drivetrain {
        private final SimWorld world;

        SimDrivetrain(SimWorld world) { this.world = world; }

        private static double[] wheels(DrivePowers p) {
            return new double[]{
                    p.forward() - p.strafe() - p.turn(),   // front left
                    p.forward() + p.strafe() + p.turn(),   // front right
                    p.forward() + p.strafe() - p.turn(),   // back left
                    p.forward() - p.strafe() + p.turn(),   // back right
            };
        }

        @Override public void drive(DrivePowers powers, boolean voltageCompensation) {
            double[] w = wheels(powers);
            double biggest = 1;
            for (double value : w) biggest = Math.max(biggest, Math.abs(value));
            for (int i = 0; i < 4; i++) w[i] /= biggest;
            double forward = (w[0] + w[1] + w[2] + w[3]) / 4;
            double left = (-w[0] + w[1] + w[2] - w[3]) / 4;
            double turn = (-w[0] + w[1] - w[2] + w[3]) / 4;
            world.setDrivePowers(forward, left, turn);
        }

        /** Largest t in [0, 1] so that base + t * extra keeps every wheel within full power. */
        @Override public double maxScaling(DrivePowers base, DrivePowers extra) {
            double[] b = wheels(base), a = wheels(extra);
            double t = 1;
            for (int i = 0; i < 4; i++) {
                if (a[i] > 1e-9) t = Math.min(t, (1 - b[i]) / a[i]);
                else if (a[i] < -1e-9) t = Math.min(t, (-1 - b[i]) / a[i]);
            }
            return SimWorld.clamp(t, 0, 1);
        }

        @Override public void stop() { world.setDrivePowers(0, 0, 0); }

        @Override public void stop(boolean brake) { stop(); }

        @Override public Map<String, Object> debug() { return new HashMap<>(); }

        @Override public double interpolateVelocity(double forward, double strafe, double angle) {
            return 1 / (Math.abs(Math.cos(angle)) / forward + Math.abs(Math.sin(angle)) / strafe);
        }
    }
}
