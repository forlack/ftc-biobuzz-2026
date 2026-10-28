package org.biobuzz.sim;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;

import org.biobuzz.sim.mirror.Flywheels;
import org.biobuzz.sim.mirror.Intake;
import org.biobuzz.sim.mirror.MecanumDrive;
import org.biobuzz.sim.mirror.PedroAutonomous;
import org.biobuzz.sim.mirror.RobotStub;
import org.biobuzz.sim.mirror.Stinger;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.dashboard.VirtualGamepads;
import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.AutoFunctions;
import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.AutoStepFunctions;

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

    /**
     * The robot's auton functions in the simulator: every one the real mechanisms have (from
     * the generated RobotStub, so the editor lists the same), with the ones simulated here
     * (SimMechanisms' @AutoFunction methods, same names) doing the real thing.
     */
    static AutoFunctions functions(SimMechanisms robot) {
        return AutoFunctions.of(new RobotStub()).override(robot);
    }

    /** Mechanisms on simulated hardware, shared by teleop and autonomous. */
    static final class SimMechanisms {
        final SimWorld world;
        boolean intakeOn, intakeReverse, flywheelsOn, stingerDown;
        double flywheelF;

        SimMechanisms(SimWorld world) { this.world = world; }

        void intake(boolean on) { intakeOn = on; }
        void flywheels(boolean on) { flywheelsOn = on; }
        void triggers(boolean on) { world.setFeeding(on); }
        void stinger(boolean down) { stingerDown = down; }

        // The real mechanisms' auton functions, simulated (names as in mechanisms/).
        @AutoFunction(name = "intakeOn") public void intakeOn() { intake(true); }
        @AutoFunction(name = "intakeOff") public void intakeOff() { intake(false); }
        @AutoFunction(name = "flywheelsOn") public void flywheelsOn() { flywheels(true); }
        @AutoFunction(name = "flywheelsOff") public void flywheelsOff() { flywheels(false); }
        @AutoFunction(name = "triggersOn") public void triggersOn() { triggers(true); }
        @AutoFunction(name = "triggersOff") public void triggersOff() { triggers(false); }
        @AutoFunction(name = "deployStinger") public void deployStinger() { stinger(true); }
        @AutoFunction(name = "stowStinger") public void stowStinger() { stinger(false); }

        @AutoFunction(name = "flywheelsReady")
        public boolean flywheelsReady() {
            return flywheelsOn
                    && Math.abs(world.pollen.rpm(28) - Flywheels.POLLEN_FLYWHEEL_RPM) < 100
                    && Math.abs(world.nectar.rpm(28) - Flywheels.NECTAR_FLYWHEEL_RPM) < 100;
        }

        /** Like the teleop: holding reverse wins over the toggle; nothing when disabled. */
        double intakeTarget() {
            if (intakeReverse) return -Intake.INTAKE_RPM;
            return intakeOn ? Intake.INTAKE_RPM : 0;
        }

        private final FlywheelCompensation.Average averageVolts = new FlywheelCompensation.Average();

        /** Call every loop: applies targets and PIDF from the current settings. */
        void apply() {
            // Same voltage-compensated F as the robot, from the averaged battery.
            double volts = averageVolts.update(world.battery(), Flywheels.FLYWHEEL_VOLTS_AVERAGE_SECONDS);
            flywheelF = FlywheelCompensation.f(volts, Flywheels.FLYWHEEL_VOLTAGE_COMPENSATION,
                    Flywheels.FLYWHEEL_F);
            world.pollen.setPIDF(Flywheels.POLLEN_FLYWHEEL_P, Flywheels.POLLEN_FLYWHEEL_I, Flywheels.POLLEN_FLYWHEEL_D, flywheelF);
            world.nectar.setPIDF(Flywheels.NECTAR_FLYWHEEL_P, Flywheels.NECTAR_FLYWHEEL_I, Flywheels.NECTAR_FLYWHEEL_D, flywheelF);
            world.intake.setPIDF(Intake.INTAKE_P, Intake.INTAKE_I, 0, Intake.INTAKE_F);
            world.pollen.setTargetRPM(flywheelsOn ? Flywheels.POLLEN_FLYWHEEL_RPM : 0, SimWorld.FLYWHEEL_TICKS_PER_REV);
            world.nectar.setTargetRPM(flywheelsOn ? Flywheels.NECTAR_FLYWHEEL_RPM : 0, SimWorld.FLYWHEEL_TICKS_PER_REV);
            world.intake.setTargetRPM(intakeTarget(), SimWorld.INTAKE_TICKS_PER_REV);
            world.setStinger(stingerDown ? Stinger.STINGER_DOWN : Stinger.STINGER_UP);
        }

        /** The same telemetry names the real mechanisms send (mechanisms/). */
        void telemetry() {
            Dash.put("Pollen RPM", world.pollen.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV));
            Dash.put("Pollen target", flywheelsOn ? Flywheels.POLLEN_FLYWHEEL_RPM : 0);
            Dash.put("Nectar RPM", world.nectar.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV));
            Dash.put("Nectar target", flywheelsOn ? Flywheels.NECTAR_FLYWHEEL_RPM : 0);
            Dash.put("Intake RPM", world.intake.rpm(SimWorld.INTAKE_TICKS_PER_REV));
            Dash.put("Intake target", intakeTarget());
            Dash.put("Pollen power", world.pollen.output());
            Dash.put("Nectar power", world.nectar.output());
            Dash.put("Triggers", world.feeding());
            Dash.put("Shots", world.shotsFired());
            Dash.put("Stinger", world.stinger());
            Dash.put("Flywheels on", flywheelsOn);
            Dash.put("Flywheels ready", flywheelsReady());
            Dash.put("Flywheel compensated F", flywheelF);
        }
    }

    /**
     * "Field Centric (Java)": the real teleop's controls, driven from the dashboard's Gamepad
     * drawer (VirtualGamepads; the same buttons as FieldCentricJava). With nobody driving, a
     * pretend driver loops around the field and shoots (Simulator.DEMO_DRIVING / DEMO_SHOOTING).
     */
    public static final class TeleOp extends SimOpMode {
        private SimMechanisms robot;
        private Follower follower;
        private double started, headingZero;

        // Gamepads 1 (drive) and 2 (operate), filled from the dashboard each loop.
        private final float[] drive = new float[VirtualGamepads.AXES.length];
        private final boolean[] driveButtons = new boolean[VirtualGamepads.BUTTONS.length];
        private final float[] operate = new float[VirtualGamepads.AXES.length];
        private final boolean[] operateButtons = new boolean[VirtualGamepads.BUTTONS.length];

        // Same map as Flywheels.
        private final Button.Group buttons = new Button.Group();
        private final Button slowModeButton = buttons.add(() -> driveButtons[VirtualGamepads.LEFT_BUMPER]);
        private final Button turboButton = buttons.add(() -> driveButtons[VirtualGamepads.RIGHT_BUMPER]);
        private final Button resetFrontButton = buttons.add(() -> driveButtons[VirtualGamepads.B]);
        private final Button forceShootButton = buttons.add(() -> driveButtons[VirtualGamepads.A] && driveButtons[VirtualGamepads.RIGHT_BUMPER]);
        private final Button intakeToggleButton = buttons.add(() -> operateButtons[VirtualGamepads.X]);
        private final Button intakeReverseButton = buttons.add(() -> operateButtons[VirtualGamepads.DPAD_DOWN]);
        private final Button flywheelsToggleButton = buttons.add(() -> operateButtons[VirtualGamepads.A]);
        private final Button triggersButton = buttons.add(() -> operateButtons[VirtualGamepads.RIGHT_BUMPER]);
        private final Button stingerButton = buttons.add(() -> operateButtons[VirtualGamepads.LEFT_BUMPER]);

        @Override public void init() {
            settings.load();
            robot = new SimMechanisms(world);
            follower = SimPedro.follower(world);
            Dash.put("Status", "Field Centric (Java) ready. " + settings.status());
        }

        @Override public void start() {
            started = world.time();
            headingZero = world.pose()[2];
        }

        @Override public void loop() {
            boolean driving = read(1, drive, driveButtons);
            boolean operating = read(2, operate, operateButtons);
            buttons.update();
            double t = world.time() - started;

            if (driving) {
                driveFieldCentric();
            } else if (Simulator.DEMO_DRIVING && !operating) {
                // Pretend driver: a gentle loop around the field.
                follower.manual(0.35 * MecanumDrive.DRIVE_SPEED, 0.0, 0.25);
            } else {
                follower.manual(0, 0, 0);
            }
            follower.update();

            if (intakeToggleButton.pressed()) Intake.INTAKE_ON = !Intake.INTAKE_ON;
            robot.intake(Intake.INTAKE_ON);
            robot.intakeReverse = intakeReverseButton.down();
            if (flywheelsToggleButton.pressed()) Flywheels.FLYWHEELS_ON = !Flywheels.FLYWHEELS_ON;
            robot.flywheels(Flywheels.FLYWHEELS_ON);
            robot.stinger(stingerButton.down());
            boolean volley = !driving && !operating && Simulator.DEMO_SHOOTING
                    && Flywheels.FLYWHEELS_ON && (t % 6.0) > 4.5;
            robot.triggers(volley || Simulator.FEED || triggersButton.down() || forceShootButton.down());

            robot.apply();
            robot.telemetry();
            Dash.put("Intake", robot.intakeReverse ? "REVERSE" : robot.intakeOn ? "ON" : "OFF");
            Dash.put("Driver", driving || operating
                    ? "Dashboard gamepad " + (driving && operating ? "1 + 2" : driving ? "1" : "2")
                    : Simulator.DEMO_DRIVING ? "Demo (nobody driving)" : "Nobody");
            Dash.put("Settings", settings.status());
            common();
        }

        /** Gamepad {@code number} from the dashboard, or all zero when nobody drives it. */
        private static boolean read(int number, float[] axes, boolean[] pressed) {
            if (VirtualGamepads.read(number, axes, pressed)) return true;
            java.util.Arrays.fill(axes, 0);
            java.util.Arrays.fill(pressed, false);
            return false;
        }

        /** MecanumDrive.drive's field-centric math, sent to Pedro's drivetrain. */
        private void driveFieldCentric() {
            double strafe = ignoreDrift(drive[VirtualGamepads.LEFT_STICK_X]);
            double forward = ignoreDrift(-drive[VirtualGamepads.LEFT_STICK_Y]);
            double turn = ignoreDrift(drive[VirtualGamepads.RIGHT_STICK_X]);
            double speed = slowModeButton.down() ? MecanumDrive.SLOW_SPEED
                    : turboButton.down() ? MecanumDrive.TURBO_SPEED : MecanumDrive.DRIVE_SPEED;
            double yaw = world.pose()[2] - headingZero;
            double right = strafe * Math.cos(yaw) + forward * Math.sin(yaw);
            double ahead = -strafe * Math.sin(yaw) + forward * Math.cos(yaw);
            // Pedro: strafe is to the left, turn counter-clockwise.
            follower.manual(ahead * speed, -right * speed, -turn * speed);
            if (resetFrontButton.down()) headingZero = world.pose()[2];
        }

        private static double ignoreDrift(double value) {
            return Math.abs(value) < MecanumDrive.STICK_DEADZONE ? 0.0 : value;
        }

        @Override public void stop() {
            robot.flywheels(false);
            robot.intake(false);
            robot.triggers(false);
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
            Actions actions = AutoStepFunctions.create(functions(robot));
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
            robot.triggers(false);
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
