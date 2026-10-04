package org.firstinspires.ftc.teamcode;

import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.pedropathing.api.PoseFactory;
import com.pedropathing.api.Paths;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.pedro.Constants;

/**
 * Out-and-back test path on Pedro Pathing 3.x. A placeholder for a real auto -- the point is
 * the shape, not the geometry.
 *
 * PLACE THE ROBOT AT x=56, y=8, FACING 90 DEGREES BEFORE RUNNING.
 *
 * setPose() does not move the robot; it tells Pedro where the robot already is. Lie to it and
 * the follower immediately lurches to "correct".
 */
@Autonomous(name = "Pedro Pathing Autonomous", group = "Autonomous")
public class PedroAutonomous extends OpMode {

    private static final PoseFactory FIELD = PoseFactory.degrees();

    private static final Pose START = FIELD.of(56.000, 8.000, 90);
    private static final Pose AWAY = FIELD.of(63.380, 28.855, 180);

    private TelemetryManager panels;
    private Follower follower;
    private AutoSequence auto;

    @Override
    public void init() {
        panels = PanelsTelemetry.INSTANCE.getTelemetry();

        follower = Constants.create(hardwareMap);
        follower.setPose(START);

        // linear(a, b) interpolates heading between the two poses' headings while driving.
        Path out = Paths.line(START, AWAY).linear(START, AWAY);
        Path back = Paths.line(AWAY, START).linear(AWAY, START);

        auto = new AutoSequence(follower)
                .follow(out)
                .waitSeconds(0.5)
                .follow(back);

        panels.debug("Status", "Initialized");
        panels.debug("Place the robot at x=56 y=8 facing 90 degrees");
        panels.update(telemetry);
    }

    @Override
    public void loop() {
        follower.update();
        auto.update();

        Pose pose = follower.pose();
        panels.debug("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
        panels.debug("Following", follower.isBusy());
        panels.debug("Completion", follower.completion());
        panels.debug("X", pose.x());
        panels.debug("Y", pose.y());
        panels.debug("Heading (deg)", Math.toDegrees(pose.heading()));
        panels.update(telemetry);
    }
}
