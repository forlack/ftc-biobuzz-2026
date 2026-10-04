package org.firstinspires.ftc.teamcode;

import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.pedropathing.api.Paths;
import com.pedropathing.api.PoseFactory;
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
 * Shaped for BIOBUZZ: the intake runs the whole time, so it is set once and forgotten. The
 * real steps are DRIVE, AIM, SHOOT, repeated.
 *
 * Nothing here waits. AutoSequence advances when each step finishes, so the loop keeps
 * running at full speed.
 */
@Disabled
@Autonomous(name = "Template Autonomous", group = "Template")
public class TemplateAutonomous extends OpMode {

    /** Poses in degrees, so the numbers match what you measure on the field. */
    private static final PoseFactory FIELD = PoseFactory.degrees();

    private static final Pose START = FIELD.of(56, 8, 90);
    private static final Pose SHOOT_SPOT = FIELD.of(63, 29, 180);

    /** What we aim at. Only x and y matter. */
    private static final Pose GOAL = FIELD.of(72, 120, 0);

    private TelemetryManager panels;
    private Follower follower;
    private AutoSequence auto;

    // TODO: your hardware
    // private DcMotorEx intake;
    // private DcMotorEx shooter;
    // private DcMotorEx feeder;

    // public static double INTAKE_POWER = 1.0;
    // public static double SHOOT_VELOCITY = 1800;   // ticks/sec, tune on the robot

    @Override
    public void init() {
        panels = PanelsTelemetry.INSTANCE.getTelemetry();
        follower = Constants.create(hardwareMap);
        follower.setPose(START);

        // intake = hardwareMap.get(DcMotorEx.class, "Intake");
        // shooter = hardwareMap.get(DcMotorEx.class, "Shooter");
        // feeder = hardwareMap.get(DcMotorEx.class, "Feeder");

        // facingPoint aims at the goal WHILE driving, so the robot arrives already pointed.
        Path toShootSpot = Paths.line(START, SHOOT_SPOT).facingPoint(GOAL);
        Path home = Paths.line(SHOOT_SPOT, START).linear(SHOOT_SPOT, START);

        auto = new AutoSequence(follower)
                .follow(toShootSpot)
                .aimAt(GOAL)            // stand still and finish the aim
                // .run(() -> shooter.setVelocity(SHOOT_VELOCITY))
                // .waitUntil(() -> shooter.getVelocity() >= SHOOT_VELOCITY - 50)
                // .run(() -> feeder.setPower(1.0))
                // .waitSeconds(0.6)
                // .run(() -> feeder.setPower(0.0))
                // .run(() -> shooter.setVelocity(0))
                .follow(home);

        panels.debug("Status", "Initialized");
        panels.update(telemetry);
    }

    @Override
    public void start() {
        // The intake runs for the whole auto, so it is not a step.
        // intake.setPower(INTAKE_POWER);
    }

    @Override
    public void loop() {
        follower.update();
        auto.update();

        Pose pose = follower.pose();
        panels.debug("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
        panels.debug("X", pose.x());
        panels.debug("Y", pose.y());
        panels.debug("Heading (deg)", Math.toDegrees(pose.heading()));
        // panels.debug("Shooter vel", shooter.getVelocity());
        panels.update(telemetry);
    }

    @Override
    public void stop() {
        // intake.setPower(0);
    }
}
