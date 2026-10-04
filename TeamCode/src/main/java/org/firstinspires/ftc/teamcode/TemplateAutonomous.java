package org.firstinspires.ftc.teamcode;

import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.pedropathing.api.PoseFactory;
import com.pedropathing.api.Paths;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.pedro.Constants;

/**
 * Starting point for a new autonomous. Copy this file, rename the class and the @Autonomous
 * name, then delete @Disabled so it shows up on the Driver Station.
 *
 * Driving paths already works. Define poses, build paths, then list what the robot should do
 * in order. Nothing in here waits -- AutoSequence advances when each step finishes, so the
 * loop never stops turning over.
 */
@Disabled
@Autonomous(name = "Template Autonomous", group = "Template")
public class TemplateAutonomous extends OpMode {

    /** Poses in degrees, so the numbers match what you measure on the field. */
    private static final PoseFactory FIELD = PoseFactory.degrees();

    private static final Pose START = FIELD.of(56, 8, 90);
    private static final Pose SCORE = FIELD.of(63, 29, 180);

    private TelemetryManager panels;
    private Follower follower;
    private AutoSequence auto;

    // TODO: your hardware
    // private DcMotorEx arm;
    // private Servo claw;

    @Override
    public void init() {
        panels = PanelsTelemetry.INSTANCE.getTelemetry();
        follower = Constants.create(hardwareMap);
        follower.setPose(START);

        // arm = hardwareMap.get(DcMotorEx.class, "Arm");

        Path toScore = Paths.line(START, SCORE).linear(START, SCORE);
        Path home = Paths.line(SCORE, START).linear(SCORE, START);

        auto = new AutoSequence(follower)
                .follow(toScore)
                // .at(0.7, () -> arm.setPower(1.0))   // starts 70% along, while still driving
                // .run(() -> claw.setPosition(0.8))   // after arriving
                // .waitSeconds(0.4)
                // .waitUntil(() -> arm.getCurrentPosition() > 500)
                .follow(home);

        panels.debug("Status", "Initialized");
        panels.update(telemetry);
    }

    @Override
    public void loop() {
        follower.update();
        auto.update();
        // TODO: updateArm();  -- anything that needs attention every loop goes here

        Pose pose = follower.pose();
        panels.debug("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
        panels.debug("X", pose.x());
        panels.debug("Y", pose.y());
        panels.debug("Heading (deg)", Math.toDegrees(pose.heading()));
        panels.update(telemetry);
    }
}
