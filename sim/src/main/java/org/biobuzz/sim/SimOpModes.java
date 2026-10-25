package org.biobuzz.sim;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;

import org.biobuzz.sim.mirror.FieldCentricJava;
import org.biobuzz.sim.mirror.MecanumDrive;
import org.biobuzz.sim.mirror.PedroAutonomous;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.Mechanisms;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.StandardActions;

import java.io.File;

/**
 * The simulated robot's OpModes. They do what the real ones do, using the same settings
 * (robot.json), the same telemetry names, the same actions and the same route reader, against
 * simulated hardware instead of motors.
 */
public final class SimOpModes {
    private SimOpModes() { }

    /** Settings groups: every @Configurable class (found when the simulator is built), in the
     *  robot's Tune tab order, plus the simulator's own. */
    public static final Class<?>[] SETTINGS = settingsClasses();

    private static Class<?>[] settingsClasses() {
        java.util.List<Class<?>> list = new java.util.ArrayList<>(java.util.Arrays.asList(
                org.biobuzz.sim.mirror.ConfigurableClasses.ALL));
        list.sort(RobotSettings.TAB_ORDER);
        list.add(Simulator.class);
        return list.toArray(new Class<?>[0]);
    }

    /** Like the SDK's OpMode, cut down to what the simulator needs. */
    public abstract static class SimOpMode {
        protected SimWorld world;
        protected RobotSettings settings;
        private long lastLoop;

        void attach(SimWorld world, RobotSettings settings) {
            this.world = world;
            this.settings = settings;
        }

        public abstract void init() throws Exception;
        public void initLoop() { }
        public void start() { }
        public abstract void loop();
        public void stop() { }

        /** Telemetry every OpMode shares: battery, loop time. */
        protected void common() {
            long now = System.nanoTime();
            if (lastLoop != 0) Dash.put("Loop ms", (now - lastLoop) / 1e6);
            lastLoop = now;
            Dash.put("Battery V", world.battery());
            double[] pose = world.pose();
            Dash.pose(pose[0], pose[1], pose[2]);
        }
    }

    /** Mechanisms on simulated hardware, shared by teleop and autonomous. */
    static final class SimMechanisms implements Mechanisms {
        final SimWorld world;
        boolean intakeOn, flywheelsOn, stingerDown;
        double flywheelF;

        SimMechanisms(SimWorld world) { this.world = world; }

        @Override public void intake(boolean on) { intakeOn = on; }

        /** On, and not switched off on the Tune tab (FieldCentricJava.INTAKE_ENABLED). */
        boolean intakeRunning() { return intakeOn && FieldCentricJava.INTAKE_ENABLED; }
        @Override public void flywheels(boolean on) { flywheelsOn = on; }
        @Override public void feed(boolean on) { world.setFeeding(on); }
        @Override public void stinger(boolean down) { stingerDown = down; }

        @Override public boolean flywheelsReady() {
            return flywheelsOn
                    && Math.abs(world.pollen.rpm(28) - FieldCentricJava.POLLEN_FLYWHEEL_RPM) < 100
                    && Math.abs(world.nectar.rpm(28) - FieldCentricJava.NECTAR_FLYWHEEL_RPM) < 100;
        }

        /** Call every loop: applies targets and PIDF from the current settings. */
        void apply() {
            // Same voltage-compensated F as the robot.
            flywheelF = FlywheelCompensation.f(world.battery(), FieldCentricJava.FLYWHEEL_VOLTAGE_COMPENSATION,
                    FieldCentricJava.FLYWHEEL_F, FieldCentricJava.FLYWHEEL_F_AT_10V);
            world.pollen.setPIDF(FieldCentricJava.POLLEN_FLYWHEEL_P, 0, FieldCentricJava.POLLEN_FLYWHEEL_D, flywheelF);
            world.nectar.setPIDF(FieldCentricJava.NECTAR_FLYWHEEL_P, 0, FieldCentricJava.NECTAR_FLYWHEEL_D, flywheelF);
            world.intake.setPIDF(FieldCentricJava.INTAKE_P, 0, 0, FieldCentricJava.INTAKE_F);
            world.pollen.setTargetRPM(flywheelsOn ? FieldCentricJava.POLLEN_FLYWHEEL_RPM : 0, SimWorld.FLYWHEEL_TICKS_PER_REV);
            world.nectar.setTargetRPM(flywheelsOn ? FieldCentricJava.NECTAR_FLYWHEEL_RPM : 0, SimWorld.FLYWHEEL_TICKS_PER_REV);
            world.intake.setTargetRPM(intakeRunning() ? FieldCentricJava.INTAKE_RPM : 0, SimWorld.INTAKE_TICKS_PER_REV);
            world.setStinger(stingerDown ? FieldCentricJava.STINGER_DOWN : FieldCentricJava.STINGER_UP);
        }

        /** The same telemetry names FieldCentricJava and PedroAutonomous send. */
        void telemetry() {
            Dash.put("Pollen RPM", world.pollen.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV));
            Dash.put("Pollen target", flywheelsOn ? FieldCentricJava.POLLEN_FLYWHEEL_RPM : 0);
            Dash.put("Nectar RPM", world.nectar.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV));
            Dash.put("Nectar target", flywheelsOn ? FieldCentricJava.NECTAR_FLYWHEEL_RPM : 0);
            Dash.put("Intake RPM", world.intake.rpm(SimWorld.INTAKE_TICKS_PER_REV));
            Dash.put("Intake target", intakeRunning() ? FieldCentricJava.INTAKE_RPM : 0);
            Dash.put("Pollen power", world.pollen.output());
            Dash.put("Nectar power", world.nectar.output());
            Dash.put("Feeding", world.feeding());
            Dash.put("Shots", world.shotsFired());
            Dash.put("Stinger", world.stinger());
            Dash.put("Flywheels on", flywheelsOn);
            Dash.put("Flywheels ready", flywheelsReady());
            Dash.put("Flywheel compensated F", flywheelF);
        }
    }

    /** "Field Centric (Java)": flywheels and intake as in the real teleop; demo driving and shooting. */
    public static final class TeleOp extends SimOpMode {
        private SimMechanisms robot;
        private Follower follower;
        private double started;

        @Override public void init() {
            settings.load();
            robot = new SimMechanisms(world);
            follower = SimPedro.follower(world);
            Dash.put("Status", "Field Centric (Java) ready. " + settings.status());
        }

        @Override public void start() {
            robot.intake(true);
            started = world.time();
        }

        @Override public void loop() {
            robot.flywheels(FieldCentricJava.FLYWHEELS_ON);
            // Pretend driver: a gentle loop around the field, and a volley every 6 seconds.
            double t = world.time() - started;
            if (Simulator.DEMO_DRIVING) {
                follower.manual(0.35 * MecanumDrive.INITIAL_SPEED, 0.0, 0.25);
            } else {
                follower.manual(0, 0, 0);
            }
            follower.update();
            boolean volley = Simulator.DEMO_SHOOTING && FieldCentricJava.FLYWHEELS_ON && (t % 6.0) > 4.5;
            robot.feed(volley || Simulator.FEED);
            robot.apply();
            robot.telemetry();
            Dash.put("Intake", !FieldCentricJava.INTAKE_ENABLED ? "DISABLED (Tune tab)" : robot.intakeOn ? "ON" : "OFF");
            Dash.put("Settings", settings.status());
            common();
        }

        @Override public void stop() {
            robot.flywheels(false);
            robot.intake(false);
            robot.feed(false);
            robot.apply();
            follower.stop();
        }
    }

    /** "Pedro Pathing Autonomous": runs autos/ROUTE.json with Pedro's follower. */
    public static final class Autonomous extends SimOpMode {
        private SimMechanisms robot;
        private Follower follower;
        private AutoSequence auto;
        private AutoRoute route;
        private double started;

        @Override public void init() throws Exception {
            settings.load();
            robot = new SimMechanisms(world);
            File file = RobotFiles.file("autos/" + PedroAutonomous.ROUTE + ".json");
            route = AutoRoute.read(file);
            follower = SimPedro.follower(world);
            follower.setPose(route.start);
            world.loadBalls(3);
            Actions actions = StandardActions.create(robot);
            auto = route.build(follower, actions);
            Dash.put("Status", "Route \"" + route.name + "\" from " + file.getName() + ": "
                    + auto.stepCount() + " steps. " + settings.status());
        }

        @Override public void initLoop() {
            Pose start = route.start;
            Dash.put("Start", String.format("%.1f, %.1f, %.0f°", start.x(), start.y(), Math.toDegrees(start.heading())));
            common();
        }

        @Override public void start() {
            // Same as the real PedroAutonomous: intake and flywheels run the whole match.
            robot.intake(true);
            robot.flywheels(true);
            started = world.time();
        }

        @Override public void loop() {
            follower.update();
            auto.update();
            robot.apply();
            robot.telemetry();
            Pose pose = follower.pose();
            Dash.put("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
            Dash.put("Auto time", world.time() - started);
            Dash.put("Completion", follower.completion());
            Dash.put("X", pose.x());
            Dash.put("Y", pose.y());
            Dash.put("Heading", Math.toDegrees(pose.heading()));
            Dash.put("Route done", auto.finished());
            common();
        }

        @Override public void stop() {
            follower.stop();
            robot.flywheels(false);
            robot.intake(false);
            robot.feed(false);
            robot.apply();
        }

        boolean overTime() {
            return world.time() - started > 30;
        }
    }

    /** Live switches for the simulator's pretend driver. Not saved to robot.json. */
    public static final class Simulator {
        @RobotSettings.NotSaved
        public static boolean DEMO_DRIVING = true;
        @RobotSettings.NotSaved
        public static boolean DEMO_SHOOTING = true;
        @RobotSettings.NotSaved
        public static boolean FEED = false;
    }
}
