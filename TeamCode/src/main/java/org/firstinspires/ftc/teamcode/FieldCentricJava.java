package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;

/**
 * Field-centric teleop. Java version of the Blocks OpMode "Field Centric (Best)".
 *
 * The loop never waits for anything -- read AGENTS.md before adding to it.
 *
 * Controls:
 *   left stick        drive (field-centric)
 *   right stick X     turn
 *   right bumper      hold for slow mode
 *   A (btn 2)         make the way you are facing the new "forward"
 *   D-pad Up / Down   raise / lower the speed limit
 *   Y (btn 4)         switch between velocity mode and raw power
 *   X (btn 1)         switch between BRAKE and coasting
 */
@TeleOp(name = "Field Centric (Java)", group = "Drive")
public class FieldCentricJava extends LinearOpMode {

    private MecanumDrive chassis;
    private DriveDashboard dashboard;

    /** What the gamepad looks like this time through the loop. */
    private Gamepad pad;

    // Has to be declared ABOVE the buttons -- they add themselves to it as they are created.
    private final Button.Group buttons = new Button.Group();

    // ---- THE BUTTON MAP -- the only place a control is tied to a real button.
    // Each arrow is read fresh every loop, so it always sees the current `pad`.
    private final Button speedUp      = buttons.add(() -> pad.dpad_up);
    private final Button speedDown    = buttons.add(() -> pad.dpad_down);
    private final Button velocityMode = buttons.add(() -> pad.y);
    private final Button brakeMode    = buttons.add(() -> pad.x);
    private final Button slowMode     = buttons.add(() -> pad.right_bumper);
    private final Button resetFront   = buttons.add(() -> pad.a);

    /** Only used by the telemetry example below. */
    private int speedUpPresses;

    @Override
    public void runOpMode() {
        chassis = new MecanumDrive(hardwareMap);
        dashboard = new DriveDashboard(chassis, telemetry);
        dashboard.showReady("Field Centric (Java)");

        waitForStart();
        if (!opModeIsActive()) {
            return;
        }
        chassis.resetHeading();

        while (opModeIsActive()) {
            readGamepad();
            updateDriveSettings();
            updateDriving();
            dashboard.update();
        }
    }

    /** Reads the gamepad once, so everything below sees the same thing. */
    private void readGamepad() {
        pad = dashboard.mergeGamepad(gamepad1);
        buttons.update();
    }

    /** The buttons that change how the robot drives, rather than driving it. */
    private void updateDriveSettings() {
        if (speedUp.pressed()) {
            chassis.changeDefaultSpeed(+1);
            speedUpPresses++;
        }
        if (speedDown.pressed()) {
            chassis.changeDefaultSpeed(-1);
        }
        if (velocityMode.pressed()) {
            chassis.toggleVelocityMode();
        }
        if (brakeMode.pressed()) {
            chassis.toggleBrakeMode();
        }

        // ---- EXAMPLE: putting your own numbers on the dashboard ----
        // Add lines like these anywhere BEFORE dashboard.update() runs -- it sends
        // everything and then empties the list, so anything added after is too late.
        //
        // Show down(), not pressed(). pressed() is true for one loop out of the ~30 a press
        // lasts, so the screen would almost always catch it as false and look broken. Hold
        // D-pad Up and watch: "held" stays true the whole time, "presses" goes up by one.
        dashboard.addData("speed up held", speedUp.down());
        dashboard.addData("speed up presses", speedUpPresses);
    }

    private void updateDriving() {
        // left_stick_y is negative when you push the stick forward, so flip it.
        double strafe = pad.left_stick_x;
        double forward = -pad.left_stick_y;
        double turn = pad.right_stick_x;

        // Start from the driver's default speed, then let any mode override it. Adding
        // turbo or a precision mode is one more line here -- MecanumDrive never changes.
        double speed = chassis.getDefaultSpeed();
        if (slowMode.down()) {
            speed = MecanumDrive.SLOW_SPEED;
        }

        chassis.drive(strafe, forward, turn, speed);

        // After the driving on purpose, so this loop still uses the old heading.
        if (resetFront.down()) {
            chassis.resetHeading();
        }
    }
}
