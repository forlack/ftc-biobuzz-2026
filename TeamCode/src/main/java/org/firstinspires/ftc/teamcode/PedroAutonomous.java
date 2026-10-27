package org.firstinspires.ftc.teamcode;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.AutoFunctions;
import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.AutoStepFunctions;
import org.firstinspires.ftc.teamcode.mechanisms.Robot;
import org.firstinspires.ftc.teamcode.pedro.Constants;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs an auton made in the dashboard's path editor: autos/ROUTE.json on the robot, one file
 * with the start pose, the paths, functions along the paths and the sequence in between.
 *
 * The route's functions (intake on, shoot, stinger down...) are the mechanisms' @AutoFunction
 * methods, run on the same mechanisms/Robot as the teleop.
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

    private Robot robot;

    @Override
    public void init() {
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);
        // Same robot.json as teleop, so the flywheel and stinger numbers match.
        RobotSettings settings = new RobotSettings().load();
        telemetry.addData("Settings", settings.status());
        for (String warning : settings.warnings()) telemetry.addData("Settings warning", warning);

        follower = Constants.create(hardwareMap);

        robot = new Robot(hardwareMap);
        // As before: stinger up and triggers stopped at INIT; nothing else moves until start
        // (the intake and flywheels start off).
        robot.stinger.up();
        robot.triggers.off();
        robot.update();

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
            auto = route.build(follower, AutoStepFunctions.create(AutoFunctions.of(robot)));
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
        robot.intake.on();
        robot.flywheels.on();
        robot.update();
    }

    @Override
    public void stop() {
        if (follower != null) follower.stop();
        if (robot != null) {
            robot.triggers.off();
            robot.intake.off();
            robot.flywheels.off();
            robot.update();
        }
    }

    @Override
    public void loop() {
        if (auto == null) return;
        follower.update();
        auto.update();
        robot.update();

        Pose pose = follower.pose();
        telemetry.addData("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
        telemetry.addData("Following", follower.isBusy());
        telemetry.addData("Completion", follower.completion());
        telemetry.addData("X", pose.x());
        telemetry.addData("Y", pose.y());
        telemetry.addData("Heading", Math.toDegrees(pose.heading()));

        // Dashboard only: the same names the teleop and the simulator chart.
        Dash.put("Route done", auto.finished());
        robot.telemetry(telemetry);
        long now = System.nanoTime();
        if (lastLoop != 0) Dash.put("Loop ms", (now - lastLoop) / 1e6);
        lastLoop = now;
        Dash.pose(pose.x(), pose.y(), pose.heading());
        telemetry.update();   // also ends this loop's dashboard frame
    }
}
