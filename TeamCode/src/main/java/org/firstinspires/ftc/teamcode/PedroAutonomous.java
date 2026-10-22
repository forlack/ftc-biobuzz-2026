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
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;
import org.firstinspires.ftc.teamcode.pedro.Constants;

/**
 * Drive to shoot, feed for two seconds, then lower the stinger partway along path two.
 *
 * PLACE THE ROBOT AT x=56, y=8, FACING 270 DEGREES BEFORE RUNNING.
 *
 * setPose() does not move the robot; it tells Pedro where the robot already is. Lie to it and
 * the follower immediately lurches to "correct".
 */
@Autonomous(name = "Pedro Pathing Autonomous", group = "Autonomous")
public class PedroAutonomous extends OpMode {

    private static final PoseFactory FIELD = PoseFactory.degrees();

    private static final Pose START = FIELD.of(56, 8, 270);
    private static final Pose POINT_1 = FIELD.of(60, 16, 270);
    private static final Pose POINT_2 = FIELD.of(19, 52, 180);
    private static final Pose PATH_2_CONTROL = FIELD.of(15.6919, 29.3313, 0);
    private static final Pose POINT_3 = FIELD.of(18, 52, 180);

    private TelemetryManager panels;
    private Follower follower;
    private AutoSequence auto;

    private VelocityMotor intake;
    private VelocityMotor shooterPollen;
    private VelocityMotor shooterNectar;
    private CRServo transportPollen, transportNectar;
    private CRServo triggerPollen, triggerNectar;
    private Servo stinger;
    private boolean stingerDown;


    @Override
    public void init() {
        panels = PanelsTelemetry.INSTANCE.getTelemetry();

        follower = Constants.create(hardwareMap);
        follower.setPose(START);

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
        stingerDown = false;
        stinger.setPosition(FieldCentricJava.STINGER_UP);
        shoot(false);

        // linear(a, b) interpolates heading between the two poses' headings while driving.
        Path path1 = Paths.line(START, POINT_1).linear(START, POINT_1);
        Path path2 = Paths.curve(POINT_1, PATH_2_CONTROL, POINT_2).linear(POINT_1, POINT_2);
        Path path3 = Paths.line(POINT_2, POINT_3).linear(POINT_2, POINT_3)
                .with(Constants.foresightConfig.maxVelocityConstraint.at(20.0));

        auto = new AutoSequence(follower)
                .waitSeconds(1.0)
                .follow(path1)
                .run(() -> shoot(true))
                .waitSeconds(3.5)
                .run(() -> shoot(false))
                .follow(path2)
                .at(0.5, this::toggleStinger)
                .follow(path3);

        panels.debug("Status", "Initialized");
        panels.debug("Place the robot at x=56 y=8 facing 270 degrees");
        panels.update(telemetry);
    }

    @Override
    public void start() {
        intake.setTargetRPM(FieldCentricJava.INTAKE_RPM);
        shooterPollen.setTargetRPM(FieldCentricJava.POLLEN_FLYWHEEL_RPM);
        shooterNectar.setTargetRPM(FieldCentricJava.NECTAR_FLYWHEEL_RPM);
        transportPollen.setPower(1);
        transportNectar.setPower(1);
    }

    /** Opens both shooting gates while enabled; call shoot(false) to stop feeding. */
    private void shoot(boolean enabled) {
        triggerPollen.setPower(enabled ? 1 : 0);
        triggerNectar.setPower(enabled ? 1 : 0);
    }

    /** Switches the stinger between its configured up and down positions. */
    private void toggleStinger() {
        stingerDown = !stingerDown;
        stinger.setPosition(stingerDown ? FieldCentricJava.STINGER_DOWN : FieldCentricJava.STINGER_UP);
    }

    @Override
    public void stop() {
        shoot(false);
        intake.stop();
        shooterPollen.stop();
        shooterNectar.stop();
        transportPollen.setPower(0);
        transportNectar.setPower(0);
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
