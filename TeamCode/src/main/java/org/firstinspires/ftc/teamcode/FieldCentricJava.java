package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.CRServo;

import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;
import com.bylazar.configurables.annotations.Configurable;

/**
 * Field-centric teleop. Java version of the Blocks OpMode "Field Centric (Best)".
 *
 * The loop never waits for anything -- read AGENTS.md before adding to it.
 *
 * Controls:
 *   left stick        drive (field-centric)
 *   right stick X     turn
 *   right trigger     hold for slow mode
 *   B                 toggle intake
 *   right bumper      hold pollen gate open
 *   left bumper       hold nectar gate open
 *   A (btn 2)         make the way you are facing the new "forward"
 *   D-pad Up / Down   raise / lower the speed limit
 *   Y (btn 4)         switch between velocity mode and raw power
 *   X (btn 1)         switch between BRAKE and coasting
 */
@Configurable
@TeleOp(name = "Field Centric (Java)", group = "Drive")
public class FieldCentricJava extends LinearOpMode {

    public static double INTAKE_POWER = 0.75;
    public static double POLLEN_FLYWHEEL_POWER = 0.7;
    public static double NECTAR_FLYWHEEL_POWER = 0.8;

    private MecanumDrive chassis;
    private DriveDashboard dashboard;

    /** What the gamepad looks like this time through the loop. */
    private Gamepad pad;

    // Has to be declared ABOVE the buttons -- they add themselves to it as they are created.
    private final Button.Group buttons = new Button.Group();

    // ---- THE BUTTON MAP -- the only place a control is tied to a real button.
    // Each arrow is read fresh every loop, so it always sees the current `pad`.
    private final Button speedUp        = buttons.add(() -> pad.dpad_up);
    private final Button speedDown      = buttons.add(() -> pad.dpad_down);
    private final Button velocityMode   = buttons.add(() -> pad.y);
    private final Button brakeMode      = buttons.add(() -> pad.x);
    private final Button slowMode       = buttons.add(() -> pad.right_trigger_pressed);
    private final Button resetFront     = buttons.add(() -> pad.a);
    private  final Button toggleIntake  = buttons.add(() -> pad.b);
    private  final Button pollenGate    = buttons.add(() -> pad.right_bumper);
    private  final Button nectarGate    = buttons.add(() -> pad.left_bumper);

    /** Only used by the telemetry example below. */
    private int speedUpPresses;

    private DcMotorEx shooterNectar;
    private DcMotorEx intake;
    private DcMotorEx shooterPollen;
    private CRServo triggerPollen;
    private CRServo transportPollen;
    private CRServo triggerNectar;
    private CRServo transportNectar;


    @Override
    public void runOpMode() {
        chassis = new MecanumDrive(hardwareMap);
        // The name must match the robot config on the Driver Station exactly, including case.
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        intake = hardwareMap.get(DcMotorEx.class, "intake");
        intake.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        shooterPollen = hardwareMap.get(DcMotorEx.class, "shooterPollen");
        shooterPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        shooterPollen.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
        transportNectar = hardwareMap.get(CRServo.class, "transportNectar");
        shooterNectar = hardwareMap.get(DcMotorEx.class, "shooterNectar");
        shooterNectar.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
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
            updateIntake();
            updateServos();
            updateShooter();
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

    private void updateIntake(){
        if(toggleIntake.pressed()){
            toggleIntake.toggle();
        }
        intake.setPower(toggleIntake.active ? INTAKE_POWER : 0);
        dashboard.addData("intake power", intake.getPower());
    }
    private void updateServos(){
        transportPollen.setPower(1);
        triggerNectar.setPower(nectarGate.down() ? 1  : 0);
        triggerPollen.setPower(pollenGate.down() ? 1 : 0);
        transportNectar.setPower(1);
    }
    private void updateShooter(){
        shooterPollen.setPower(POLLEN_FLYWHEEL_POWER);
        shooterNectar.setPower(NECTAR_FLYWHEEL_POWER);
        dashboard.addData("pollen flywheel power", shooterPollen.getPower());
        dashboard.addData("nectar flywheel power", shooterNectar.getPower());
    }
}
