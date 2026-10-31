package org.biobuzz.sim;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;

import org.biobuzz.sim.mirror.Alliance;
import org.biobuzz.sim.mirror.Flywheels;
import org.biobuzz.sim.mirror.Intake;
import org.biobuzz.sim.mirror.MecanumDrive;
import org.biobuzz.sim.mirror.PedroAutonomous;
import org.biobuzz.sim.mirror.RobotStub;
import org.biobuzz.sim.mirror.Stinger;
import org.firstinspires.ftc.teamcode.dashboard.CameraFeed;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.dashboard.VirtualGamepads;
import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.AutoFunctions;
import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.HiveFinder;
import org.firstinspires.ftc.teamcode.lib.Readouts;
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
        double pollenF, nectarF;

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
            pollenF = FlywheelCompensation.f(volts, Flywheels.FLYWHEEL_VOLTAGE_COMPENSATION, Flywheels.POLLEN_FLYWHEEL_F);
            nectarF = FlywheelCompensation.f(volts, Flywheels.FLYWHEEL_VOLTAGE_COMPENSATION, Flywheels.NECTAR_FLYWHEEL_F);
            world.pollen.setPIDF(Flywheels.POLLEN_FLYWHEEL_P, Flywheels.POLLEN_FLYWHEEL_I, Flywheels.POLLEN_FLYWHEEL_D, pollenF);
            world.nectar.setPIDF(Flywheels.NECTAR_FLYWHEEL_P, Flywheels.NECTAR_FLYWHEEL_I, Flywheels.NECTAR_FLYWHEEL_D, nectarF);
            world.intake.setPIDF(Intake.INTAKE_P, Intake.INTAKE_I, 0, Intake.INTAKE_F);
            world.pollen.setTargetRPM(flywheelsOn ? Flywheels.POLLEN_FLYWHEEL_RPM : 0, SimWorld.FLYWHEEL_TICKS_PER_REV);
            world.nectar.setTargetRPM(flywheelsOn ? Flywheels.NECTAR_FLYWHEEL_RPM : 0, SimWorld.FLYWHEEL_TICKS_PER_REV);
            world.intake.setTargetRPM(intakeTarget(), SimWorld.INTAKE_TICKS_PER_REV);
            world.setStinger(stingerDown ? Stinger.STINGER_DOWN : Stinger.STINGER_UP);
        }

        /** The same telemetry names the real mechanisms send (mechanisms/). */
        void telemetry() {
            // The same Driver Station lines as the robot (mechanisms/Flywheels, Intake).
            double pollenRpm = world.pollen.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV);
            double nectarRpm = world.nectar.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV);
            Dash.put("Flywheels", !flywheelsOn ? "OFF" : flywheelsReady() ? "READY" : "spinning up");
            Dash.put("Pollen error %", Readouts.percentOff(pollenRpm, flywheelsOn ? Flywheels.POLLEN_FLYWHEEL_RPM : 0));
            Dash.put("Nectar error %", Readouts.percentOff(nectarRpm, flywheelsOn ? Flywheels.NECTAR_FLYWHEEL_RPM : 0));
            Dash.put("Intake error %", Readouts.percentOff(world.intake.rpm(SimWorld.INTAKE_TICKS_PER_REV), intakeTarget()));
            Dash.put("Flywheel averaged V", averageVolts.volts());
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
            Dash.put("Pollen compensated F", pollenF);
            Dash.put("Nectar compensated F", nectarF);
            seeHive();
        }

        private final SimCamera camera = new SimCamera();

        /** The hive the robot would aim at (from the last look), or null. */
        HiveFinder.Sighting hive() {
            return finder.hive();
        }

        private long lastPicture;
        private volatile boolean drawing;
        private static final java.util.concurrent.ExecutorService PICTURES =
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "sim-camera-pictures");
                    thread.setDaemon(true);
                    return thread;
                });
        private final HiveFinder finder = new HiveFinder();

        /** The pretend camera, and the robot's own choice of hive (the same telemetry). */
        private void seeHive() {
            if (!org.biobuzz.sim.mirror.Vision.VISION_ON) {
                Dash.put("Hive", "no camera: off (Vision.VISION_ON)");
                return;
            }
            double[] pose = world.pose();
            boolean redScoringUp = !"AUDIENCE".equalsIgnoreCase(Simulator.RED_HIVE_UP.trim());
            boolean blueScoringUp = !"AUDIENCE".equalsIgnoreCase(Simulator.BLUE_HIVE_UP.trim());
            // The Live tab's Camera card: drawn pictures, CAMERA_FEED_FPS a second, only while someone watches.
            long now = System.nanoTime();
            long gap = (long) (1e9 / Math.max(1, org.biobuzz.sim.mirror.Vision.CAMERA_FEED_FPS));
            if (CameraFeed.wanted() && now - lastPicture > gap && !drawing) {
                lastPicture = now;
                drawing = true;
                // Drawn on its own thread (like the robot's), so this loop never waits for it.
                PICTURES.execute(() -> {
                    try {
                        byte[] picture = camera.picture(pose[0], pose[1], pose[2], redScoringUp, blueScoringUp);
                        if (picture != null) {
                            CameraFeed.publish(picture);
                        }
                    } finally {
                        drawing = false;
                    }
                });
            }
            finder.pick(camera.look(pose[0], pose[1], pose[2], redScoringUp, blueScoringUp),
                    "BLUE".equalsIgnoreCase(Alliance.ALLIANCE.trim()),
                    org.biobuzz.sim.mirror.Vision.HIVE_TARGET,
                    org.biobuzz.sim.mirror.Vision.HIVE_UP_MIN_HEIGHT_IN);
            Dash.put("Hive", finder.shortLine());
            Dash.put("Hive ignored", finder.ignoredLine());
            HiveFinder.Sighting hive = finder.hive();
            Dash.put("Hive seen", hive != null);
            if (hive != null) {
                Dash.put("Hive range in", hive.range);
                Dash.put("Hive bearing deg", hive.bearing);
                Dash.put("Hive height in", hive.height);
            }
        }
    }

    /**
     * "Field Centric (Java)": the real teleop's controls, driven from the dashboard's Gamepad
     * drawer (VirtualGamepads; the same buttons as FieldCentricJava). With nobody driving and
     * Simulator.DEMO_DRIVING / DEMO_SHOOTING on (off by default; --demo turns them on), a pretend
     * driver loops around the field and shoots.
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
        private final Button blueAllianceButton = buttons.add(() -> operateButtons[VirtualGamepads.DPAD_LEFT]);
        private final Button redAllianceButton = buttons.add(() -> operateButtons[VirtualGamepads.DPAD_RIGHT]);

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

            if (blueAllianceButton.pressed()) Alliance.ALLIANCE = "BLUE";
            if (redAllianceButton.pressed()) Alliance.ALLIANCE = "RED";
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
            Dash.put("Alliance", "BLUE".equalsIgnoreCase(Alliance.ALLIANCE.trim()) ? "BLUE" : "RED");
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
        public static boolean DEMO_DRIVING = false;
        @RobotSettings.NotSaved
        public static boolean DEMO_SHOOTING = false;
        @RobotSettings.NotSaved
        public static boolean FEED = false;
        /**
         * Which CELL of the red HIVE is up: AUDIENCE (the south one, as a match starts) or
         * SCORING (away from the audience).
         */
        @RobotSettings.NotSaved
        public static String RED_HIVE_UP = "AUDIENCE";
        /** Which CELL of the blue HIVE is up: SCORING (the north one, as a match starts) or AUDIENCE. */
        @RobotSettings.NotSaved
        public static String BLUE_HIVE_UP = "SCORING";
    }
}
