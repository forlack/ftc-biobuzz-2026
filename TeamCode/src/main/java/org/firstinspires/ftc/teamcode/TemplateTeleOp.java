package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;

/**
 * Starting point for a new teleop. Copy this file, rename the class and the @TeleOp name,
 * then delete @Disabled so it shows up on the Driver Station.
 *
 * Driving already works. To add your own part of the robot, write an updateXxx() method and
 * add one call to the loop. The one rule: never wait inside the loop -- no sleep(), no
 * while(motor.isBusy()). Use a state machine instead. See AGENTS.md.
 */
@Disabled
@TeleOp(name = "Template TeleOp", group = "Template")
public class TemplateTeleOp extends LinearOpMode {

    private MecanumDrive chassis;
    private DriveDashboard dashboard;
    private Gamepad pad;

    // Has to be declared ABOVE the buttons -- they add themselves to it as they are created.
    private final Button.Group buttons = new Button.Group();

    // Tie your controls to buttons here. Any true/false test works, such as
    // buttons.add(() -> pad.right_trigger > 0.5)
    private final Button slowMode   = buttons.add(() -> pad.right_bumper);
    private final Button resetFront = buttons.add(() -> pad.a);
    // private final Button armUp  = buttons.add(() -> pad.dpad_up);

    @Override
    public void runOpMode() {
        chassis = new MecanumDrive(hardwareMap);
        dashboard = new DriveDashboard(chassis, telemetry);
        // TODO: your hardware, such as arm = hardwareMap.get(DcMotorEx.class, "Arm");
        dashboard.showReady("Template TeleOp");

        waitForStart();
        if (!opModeIsActive()) {
            return;
        }
        chassis.resetHeading();

        while (opModeIsActive()) {
            readGamepad();
            updateDriving();
            // TODO: updateArm();
            dashboard.update();
        }
    }

    private void readGamepad() {
        pad = dashboard.mergeGamepad(gamepad1);
        buttons.update();
    }

    private void updateDriving() {
        // left_stick_y is negative when you push the stick forward, so flip it.
        double strafe = pad.left_stick_x;
        double forward = -pad.left_stick_y;
        double turn = pad.right_stick_x;

        if (slowMode.down()) {
            chassis.drive(strafe, forward, turn, MecanumDrive.SLOW_SPEED);
        } else {
            chassis.drive(strafe, forward, turn);
        }

        if (resetFront.down()) {
            chassis.resetHeading();
        }
    }

    // private void updateArm() {
    //     arm.setPower(armUp.down() ? 0.5 : 0.0);
    //     dashboard.addData("arm position", arm.getCurrentPosition());
    // }
}
