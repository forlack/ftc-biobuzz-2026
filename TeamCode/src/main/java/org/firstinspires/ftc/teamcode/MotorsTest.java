package org.firstinspires.ftc.teamcode;

import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.teamcode.pedro.Constants;

/**
 * Menu-driven drivetrain direction diagnostic. Combines the robot-level tests (through the
 * real Pedro Follower) and the raw per-motor tests in one OpMode.
 *
 * Every test prints what you SHOULD see, so you can compare against reality instead of
 * remembering conventions.
 *
 * Controls (Logitech numbering: 1=west, 2=south, 3=east, 4=north):
 *   D-pad Up / Down   choose a test
 *   A (2)  HOLD       run the selected test
 *   B (3)  tap        reset pose to (72, 72, 0)
 *
 * ROBOT tests drive through Follower.manual(), using the motor directions from
 * pedro/Constants.java -- so they verify exactly what path following will do.
 *
 * MOTOR tests set every motor to Direction.FORWARD and drive one at a time, ignoring all
 * config, to report hardware ground truth. Because hardwareMap returns shared motor
 * instances, directions are re-applied every loop from Constants.drivetrainConfig when a ROBOT
 * test is selected -- otherwise a motor test would leave the follower mis-configured.
 *
 * "Pod end" = the end of the robot where the odometry pods are mounted. On this robot that
 * is the FRONT as far as the config and Pedro are concerned (+X points toward it).
 */
@TeleOp(name = "Motors Test", group = "Diagnostics")
public class MotorsTest extends LinearOpMode {

    /** Power used by the motor diagnostics. */
    public static double POWER = 0.25;

    private static final int FIRST_MOTOR_TEST = 4;

    private static final String[] TEST_NAMES = {
            "ROBOT: Forward (+X)",
            "ROBOT: Backward (-X)",
            "ROBOT: Left (+Y)",
            "ROBOT: Right (-Y)",
            "MOTOR: FrontLeft (raw)",
            "MOTOR: FrontRight (raw)",
            "MOTOR: BackLeft (raw)",
            "MOTOR: BackRight (raw)",
            "MOTOR: All four (raw)",
    };

    private static final String[] EXPECTED = {
            "Robot drives TOWARD pod end.  pose x INCREASES",
            "Robot drives AWAY from pod end.  pose x DECREASES",
            "Robot slides LEFT.  pose y INCREASES",
            "Robot slides RIGHT.  pose y DECREASES",
            "FL wheel rolls BACKWARD (top moves AWAY from pod end)",
            "FR wheel rolls FORWARD (top moves TOWARD pod end)",
            "BL wheel rolls BACKWARD (top moves AWAY from pod end)",
            "BR wheel rolls FORWARD (top moves TOWARD pod end)",
            "Robot SPINS in place -- left and right oppose. Not a fault.",
    };

    @Override
    public void runOpMode() {
        TelemetryManager panels = PanelsTelemetry.INSTANCE.getTelemetry();

        DcMotor frontLeft = hardwareMap.get(DcMotor.class, "frontLeft");
        DcMotor backLeft = hardwareMap.get(DcMotor.class, "backLeft");
        DcMotor frontRight = hardwareMap.get(DcMotor.class, "frontRight");
        DcMotor backRight = hardwareMap.get(DcMotor.class, "backRight");
        DcMotor[] motors = {frontLeft, frontRight, backLeft, backRight};

        Follower follower = Constants.create(hardwareMap);
        follower.setPose(new Pose(72, 72, 0));

        panels.addLine("Motors Test ready. Clear floor / stand. Press PLAY.");
        panels.update(telemetry);
        waitForStart();


        int selected = 0;
        boolean lastUp = false, lastDown = false, lastRun = false;
        Pose startPose = follower.pose();

        while (opModeIsActive()) {
            if (gamepad1.dpad_up && !lastUp) {
                selected = (selected + TEST_NAMES.length - 1) % TEST_NAMES.length;
            }
            if (gamepad1.dpad_down && !lastDown) {
                selected = (selected + 1) % TEST_NAMES.length;
            }
            lastUp = gamepad1.dpad_up;
            lastDown = gamepad1.dpad_down;

            boolean running = gamepad1.a;
            boolean motorTest = selected >= FIRST_MOTOR_TEST;

            if (running && !lastRun) {
                startPose = follower.pose();
            }
            lastRun = running;

            if (motorTest) {
                // Raw hardware truth: no direction flags at all.
                for (DcMotor m : motors) {
                    m.setDirection(DcMotorSimple.Direction.FORWARD);
                }
                double p = running ? POWER : 0.0;
                int which = selected - FIRST_MOTOR_TEST;   // 0=FL 1=FR 2=BL 3=BR 4=all
                for (int i = 0; i < motors.length; i++) {
                    motors[i].setPower((which == 4 || which == i) ? p : 0.0);
                }
            } else {
                // Restore Pedro's directions so the follower behaves as configured.
                frontLeft.setDirection(Constants.drivetrainConfig.frontLeftDirection.get());
                backLeft.setDirection(Constants.drivetrainConfig.backLeftDirection.get());
                frontRight.setDirection(Constants.drivetrainConfig.frontRightDirection.get());
                backRight.setDirection(Constants.drivetrainConfig.backRightDirection.get());

                double forward = 0.0;
                double lateral = 0.0;
                if (running) {
                    switch (selected) {
                        case 0: forward = POWER; break;
                        case 1: forward = -POWER; break;
                        case 2: lateral = POWER; break;
                        default: lateral = -POWER; break;
                    }
                }
                follower.manual(forward, lateral, 0.0);
                follower.update();
            }

            if (gamepad1.b) {
                follower.setPose(new Pose(72, 72, 0));
                startPose = follower.pose();
            }

            Pose pose = follower.pose();

            panels.addLine(">>> " + TEST_NAMES[selected]);
            panels.addLine("EXPECT: " + EXPECTED[selected]);
            panels.addLine("");
            panels.addData("running", running ? "YES (holding A)" : "no - hold A (btn 2)");
            panels.addData("power", POWER);
            panels.addLine("");
            panels.addData("pose x", round(pose.x()));
            panels.addData("pose y", round(pose.y()));
            panels.addData("heading deg", round(Math.toDegrees(pose.heading())));
            panels.addData("dx since press", round(pose.x() - startPose.x()));
            panels.addData("dy since press", round(pose.y() - startPose.y()));
            if (motorTest) {
                panels.addLine("(motor test: pose not driven by follower)");
            }
            panels.addLine("");
            panels.addLine("D-pad = pick test | A(2) hold = run | B(3) = reset pose");
            panels.update(telemetry);
        }

        for (DcMotor m : motors) {
            m.setPower(0.0);
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
