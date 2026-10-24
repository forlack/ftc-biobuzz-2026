package org.firstinspires.ftc.teamcode;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.Mechanisms;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.StandardActions;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;
import org.firstinspires.ftc.teamcode.pedro.Constants;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs an auton made in the dashboard's path editor: autos/ROUTE.json on the robot, one file
 * with the start pose, the paths, functions along the paths and the sequence in between.
 *
 * During INIT, D-pad up/down picks another route file. Place the robot at the start pose shown.
 *
 * setPose() does not move the robot; it tells Pedro where the robot already is. Lie to it and
 * the follower immediately lurches to "correct".
 */
@Configurable
@Autonomous(name = "Pedro Pathing Autonomous", group = "Autonomous",
        preselectTeleOp = "Field Centric (Java)")
public class PedroAutonomous extends OpMode {

    /** Which route file in autos/ to run, without ".json". Chosen in the dashboard or with the D-pad at INIT. */
    public static String ROUTE = "saturday";

    private Follower follower;
    private AutoSequence auto;
    private Pose start;
    private String source = "";
    private String problem = null;
    private boolean lastUp, lastDown;
    private long lastLoop;

    private VelocityMotor intake;
    private VelocityMotor shooterPollen;
    private VelocityMotor shooterNectar;
    private CRServo transportPollen, transportNectar;
    private CRServo triggerPollen, triggerNectar;
    private Servo stinger;
    private RobotMechanisms robot;

    @Override
    public void init() {
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);
        // Same robot.json as teleop, so the flywheel and stinger numbers match.
        RobotSettings settings = new RobotSettings().load();
        telemetry.addData("Settings", settings.status());
        for (String warning : settings.warnings()) telemetry.addData("Settings warning", warning);

        follower = Constants.create(hardwareMap);

        intake = new VelocityMotor(hardwareMap, "intake", DcMotorSimple.Direction.FORWARD, 145.1);
        intake.setPIDF(FieldCentricJava.INTAKE_P, 0, 0, FieldCentricJava.INTAKE_F);
        shooterPollen = new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE);
        shooterPollen.setPIDF(FieldCentricJava.POLLEN_FLYWHEEL_P, 0,
                FieldCentricJava.POLLEN_FLYWHEEL_D, FieldCentricJava.FLYWHEEL_F);
        shooterNectar = new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD);
        shooterNectar.setPIDF(FieldCentricJava.NECTAR_FLYWHEEL_P, 0,
                FieldCentricJava.NECTAR_FLYWHEEL_D, FieldCentricJava.FLYWHEEL_F);

        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportNectar = hardwareMap.get(CRServo.class, "transportNectar");
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
        stinger = hardwareMap.get(Servo.class, "stinger");
        stinger.setPosition(FieldCentricJava.STINGER_UP);
        robot = new RobotMechanisms();
        robot.feed(false);

        prepare();
    }

    /** Builds the sequence from the chosen auton file, autos/ROUTE.json. */
    private void prepare() {
        problem = null;
        auto = null;
        File file = RobotFiles.file("autos/" + ROUTE + ".json");
        try {
            if (!file.isFile()) {
                throw new IllegalArgumentException("No auton file " + file.getPath()
                        + ". Save one from the dashboard's Paths tab, or pick another with the D-pad.");
            }
            AutoRoute route = AutoRoute.read(file);
            start = route.start;
            auto = route.build(follower, StandardActions.create(robot));
            source = file.getPath() + " (\"" + route.name + "\")";
            follower.setPose(start);
        } catch (Exception error) {
            problem = error.getMessage();
            auto = null;
        }
        showInit();
    }

    @Override
    public void init_loop() {
        // D-pad up/down: the next or previous route file in autos/.
        boolean up = gamepad1.dpad_up, down = gamepad1.dpad_down;
        if ((up && !lastUp) || (down && !lastDown)) {
            List<String> routes = routeNames();
            if (!routes.isEmpty()) {
                int index = routes.indexOf(ROUTE);
                index = (index + (up ? 1 : routes.size() - 1)) % routes.size();
                ROUTE = routes.get(index);
                prepare();
            }
        }
        lastUp = up;
        lastDown = down;
        Dash.frame();
    }

    private static List<String> routeNames() {
        List<String> names = new ArrayList<>();
        File[] files = RobotFiles.file("autos").listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                if (file.getName().endsWith(".json")) names.add(file.getName().replace(".json", ""));
            }
        }
        return names;
    }

    private void showInit() {
        if (problem != null) {
            telemetry.addData("ROUTE PROBLEM", problem);
            telemetry.addData("Route", ROUTE + "  (D-pad up/down to pick another)");
            Dash.put("Status", "Route problem: " + problem);
        } else {
            telemetry.addData("Route", ROUTE + "  (D-pad up/down to pick another)");
            telemetry.addData("From", source);
            telemetry.addData("Steps", auto.stepCount());
            telemetry.addData("Start X", start.x());
            telemetry.addData("Start Y", start.y());
            telemetry.addData("Start heading (deg)", Math.toDegrees(start.heading()));
            Dash.put("Status", "Route " + ROUTE + " ready: " + auto.stepCount() + " steps");
            Dash.put("Start", String.format(java.util.Locale.US, "%.1f, %.1f, %.0f°", start.x(), start.y(), Math.toDegrees(start.heading())));
        }
        telemetry.update();
    }

    @Override
    public void start() {
        if (auto == null) {
            requestOpModeStop();
            return;
        }
        // Intake and flywheels run the whole match.
        robot.intake(true);
        robot.flywheels(true);
    }

    @Override
    public void stop() {
        if (follower != null) follower.stop();
        if (robot != null) {
            robot.feed(false);
            robot.intake(false);
            robot.flywheels(false);
        }
    }

    @Override
    public void loop() {
        if (auto == null) return;
        follower.update();
        auto.update();

        Pose pose = follower.pose();
        telemetry.addData("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
        telemetry.addData("Following", follower.isBusy());
        telemetry.addData("Completion", follower.completion());
        telemetry.addData("X", pose.x());
        telemetry.addData("Y", pose.y());
        telemetry.addData("Heading", Math.toDegrees(pose.heading()));

        // Dashboard only: the same names the teleop and the simulator chart.
        Dash.put("Route done", auto.finished());
        robot.telemetry();
        long now = System.nanoTime();
        if (lastLoop != 0) Dash.put("Loop ms", (now - lastLoop) / 1e6);
        lastLoop = now;
        Dash.pose(pose.x(), pose.y(), pose.heading());
        telemetry.update();   // also ends this loop's dashboard frame
    }

    /** The robot's mechanisms, for the route's actions. */
    private final class RobotMechanisms implements Mechanisms {
        private boolean flywheelsOn, intakeOn;

        @Override public void intake(boolean on) {
            intakeOn = on;
            intake.setTargetRPM(on ? FieldCentricJava.INTAKE_RPM : 0);
        }

        @Override public void flywheels(boolean on) {
            flywheelsOn = on;
            shooterPollen.setTargetRPM(on ? FieldCentricJava.POLLEN_FLYWHEEL_RPM : 0);
            shooterNectar.setTargetRPM(on ? FieldCentricJava.NECTAR_FLYWHEEL_RPM : 0);
            // Same rule as teleop: the transports run while the flywheels do.
            transportPollen.setPower(on ? 1 : 0);
            transportNectar.setPower(on ? 1 : 0);
        }

        @Override public boolean flywheelsReady() {
            return flywheelsOn && shooterPollen.atSpeed() && shooterNectar.atSpeed();
        }

        @Override public void feed(boolean on) {
            triggerPollen.setPower(on ? 1 : 0);
            triggerNectar.setPower(on ? 1 : 0);
        }

        @Override public void stinger(boolean down) {
            stinger.setPosition(down ? FieldCentricJava.STINGER_DOWN : FieldCentricJava.STINGER_UP);
        }

        void telemetry() {
            Dash.put("Pollen RPM", shooterPollen.getRPM());
            Dash.put("Pollen target", shooterPollen.getTargetRPM());
            Dash.put("Nectar RPM", shooterNectar.getRPM());
            Dash.put("Nectar target", shooterNectar.getTargetRPM());
            Dash.put("Intake RPM", intake.getRPM());
            Dash.put("Intake target", intake.getTargetRPM());
            Dash.put("Flywheels ready", flywheelsReady());
            Dash.put("Stinger", stinger.getPosition());
        }
    }
}
