package org.firstinspires.ftc.teamcode;

import com.bylazar.configurables.annotations.Configurable;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;

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

    // Target speeds -- editable live in Panels.
    public static double INTAKE_RPM = 300;
    public static double POLLEN_FLYWHEEL_RPM = 3850;
    public static double NECTAR_FLYWHEEL_RPM = 4050;

    // Drive and dashboard.
    private MecanumDrive chassis;
    private DriveDashboard dashboard;
    private Gamepad pad;
    private int speedUpPresses;

    // Intake.
    private VelocityMotor intake;

    // Pollen shooter and feed.
    private VelocityMotor shooterPollen;
    private CRServo triggerPollen;
    private CRServo transportPollen;

    // Nectar shooter and feed.
    private VelocityMotor shooterNectar;
    private CRServo triggerNectar;
    private CRServo transportNectar;

    // Must be ABOVE the buttons -- they register themselves during construction.
    private final Button.Group buttons = new Button.Group();

    // Drive controls. Each lambda reads the current pad when buttons.update() runs.
    private final Button speedUp      = buttons.add(() -> pad.dpad_up);
    private final Button speedDown    = buttons.add(() -> pad.dpad_down);
    private final Button velocityMode = buttons.add(() -> pad.y);
    private final Button brakeMode    = buttons.add(() -> pad.x);
    private final Button slowMode     = buttons.add(() -> pad.right_trigger_pressed);
    private final Button resetFront   = buttons.add(() -> pad.a);

    // Mechanism controls.
    private final Button toggleIntake = buttons.add(() -> pad.b);
    private final Button pollenGate   = buttons.add(() -> pad.right_bumper);
    private final Button nectarGate   = buttons.add(() -> pad.left_bumper);

    @Override
    public void runOpMode() {
        // Drive.
        chassis = new MecanumDrive(hardwareMap);

        // Hardware names must match the active robot config exactly.
        // Intake.
        intake = new VelocityMotor(hardwareMap, "intake", DcMotorSimple.Direction.FORWARD, 145.1);

        // Pollen shooter and feed.
        shooterPollen = new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE);
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);

        // Nectar shooter and feed.
        shooterNectar = new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
        transportNectar = hardwareMap.get(CRServo.class, "transportNectar");

        // Dashboard.
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

    private void updateIntake() {
        if (toggleIntake.pressed()) {
            toggleIntake.toggle();
        }
        intake.setTargetRPM(toggleIntake.active ? INTAKE_RPM : 0);
        dashboard.addData("intake target RPM", intake.getTargetRPM());
        dashboard.addData("intake RPM", intake.getRPM());
    }

    private void updateServos() {
        transportPollen.setPower(1);
        triggerNectar.setPower(nectarGate.down() ? 1 : 0);
        triggerPollen.setPower(pollenGate.down() ? 1 : 0);
        transportNectar.setPower(1);
    }

    private void updateShooter() {
        shooterPollen.setTargetRPM(POLLEN_FLYWHEEL_RPM);
        shooterNectar.setTargetRPM(NECTAR_FLYWHEEL_RPM);
        dashboard.addData("pollen target RPM", shooterPollen.getTargetRPM());
        dashboard.addData("pollen flywheel RPM", shooterPollen.getRPM());
        dashboard.addData("nectar target RPM", shooterNectar.getTargetRPM());
        dashboard.addData("nectar flywheel RPM", shooterNectar.getRPM());
    }
}
