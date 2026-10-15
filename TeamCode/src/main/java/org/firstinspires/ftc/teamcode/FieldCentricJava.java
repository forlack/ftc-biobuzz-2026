package org.firstinspires.ftc.teamcode;

import com.bylazar.configurables.annotations.Configurable;
import com.bylazar.configurables.annotations.Sorter;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.Servo;

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
 *   left trigger      hold for slow mode (0.25)
 *   right trigger     hold for turbo (1.0)
 *   D-pad Up          toggle intake
 *   D-pad Down        hold to run the intake backwards (clear a jam)
 *   D-pad Left        toggle both flywheels on / off
 *   right bumper      hold pollen gate open
 *   left bumper       hold nectar gate open
 *   A (btn 2)         make the way you are facing the new "forward"
 *   Y (btn 4)         switch between velocity mode and raw power
 *   X (btn 1)         switch between BRAKE and coasting
 */
@Configurable
@TeleOp(name = "Field Centric (Java)", group = "Drive")
public class FieldCentricJava extends LinearOpMode {

    // Intake -- editable live in Panels.
    @Sorter(sort = 10)
    public static double INTAKE_RPM = 600;
    @Sorter(sort = 11)
    public static double INTAKE_P = 15;
    @Sorter(sort = 12)
    public static double INTAKE_F = 15;

    /** Turn both flywheels on or off from Panels. They start on. */
    @Sorter(sort = 20)
    public static boolean FLYWHEELS_ON = true;

    // Pollen flywheel.
    @Sorter(sort = 21)
    public static double POLLEN_FLYWHEEL_RPM = 3400;
    @Sorter(sort = 22)
    public static double POLLEN_FLYWHEEL_P = 200;
    @Sorter(sort = 23)
    public static double POLLEN_FLYWHEEL_F = 12;

    // Nectar flywheel.
    @Sorter(sort = 30)
    public static double NECTAR_FLYWHEEL_RPM = 3650;
    @Sorter(sort = 31)
    public static double NECTAR_FLYWHEEL_P = 200;
    @Sorter(sort = 32)
    public static double NECTAR_FLYWHEEL_F = 12;

    /** Stinger servo end points, 0 to 1. */
    @Sorter(sort = 40)
    public static double STINGER_UP = 0;
    @Sorter(sort = 41)
    public static double STINGER_DOWN = 0.5;

    // Drive and dashboard.
    private MecanumDrive chassis;
    private DriveDashboard dashboard;
    private Gamepad pad;

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

    private Servo stinger;

    // Must be ABOVE the buttons -- they register themselves during construction.
    private final Button.Group buttons = new Button.Group();

    // Drive controls. Each lambda reads the current pad when buttons.update() runs.
    private final Button velocityMode    = buttons.add(() -> pad.y);
    private final Button brakeMode       = buttons.add(() -> pad.x);
    private final Button slowMode        = buttons.add(() -> pad.left_trigger > 0.5);
    private final Button turboMode       = buttons.add(() -> pad.right_trigger > 0.5);
    private final Button resetFront      = buttons.add(() -> pad.a);

    // Mechanism controls.
    private final Button toggleIntake    = buttons.add(() -> pad.dpad_up);
    private final Button reverseIntake   = buttons.add(() -> pad.dpad_down);
    private final Button toggleFlywheels = buttons.add(() -> pad.dpad_left);
    private final Button gate            = buttons.add(() -> pad.right_bumper);
    private final Button sting           = buttons.add(() -> pad.left_bumper);

    @Override
    public void runOpMode() {
        // Drive.
        chassis = new MecanumDrive(hardwareMap);

        // Hardware names must match the active robot config exactly.
        // Intake.
        intake = new VelocityMotor(hardwareMap, "intake", DcMotorSimple.Direction.FORWARD, 145.1);
        intake.setPIDF(INTAKE_P, intake.getI(), intake.getD(), INTAKE_F);

        // Pollen shooter and feed.
        shooterPollen = new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE);
        shooterPollen.setPIDF(POLLEN_FLYWHEEL_P, shooterPollen.getI(), shooterPollen.getD(), POLLEN_FLYWHEEL_F);
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);

        // Nectar shooter and feed.
        shooterNectar = new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD);
        shooterNectar.setPIDF(NECTAR_FLYWHEEL_P, shooterNectar.getI(), shooterNectar.getD(), NECTAR_FLYWHEEL_F);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
        transportNectar = hardwareMap.get(CRServo.class, "transportNectar");
        stinger = hardwareMap.get(Servo.class, "stinger");

        // Dashboard.
        dashboard = new DriveDashboard(chassis, telemetry);
        dashboard.showReady("Field Centric (Java)");

        waitForStart();
        if (!opModeIsActive()) {
            return;
        }
        chassis.resetHeading();
        toggleIntake.active = true;   // intake starts on; D-pad Up still toggles it

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
        if (velocityMode.pressed()) {
            chassis.toggleVelocityMode();
        }
        if (brakeMode.pressed()) {
            chassis.toggleBrakeMode();
        }

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
        } else if (turboMode.down()) {
            speed = MecanumDrive.TURBO_SPEED;
        }

        chassis.drive(strafe, forward, turn, speed);

        // After the driving on purpose, so this loop still uses the old heading.
        if (resetFront.down()) {
            chassis.resetHeading();
        }
    }

    private void updateIntake() {
        if (INTAKE_P != intake.getP() || INTAKE_F != intake.getF()) {
            intake.setPIDF(INTAKE_P, intake.getI(), intake.getD(), INTAKE_F);
        }
        if (toggleIntake.pressed()) {
            toggleIntake.toggle();
        }
        // Holding reverse wins over the toggle, and only lasts while held. The toggle is left
        // alone, so letting go returns the intake to whatever it was doing before.
        double rpm = 0;
        if (reverseIntake.down()) {
            rpm = -INTAKE_RPM;
        } else if (toggleIntake.active) {
            rpm = INTAKE_RPM;
        }
        intake.setTargetRPM(rpm);
        dashboard.addLine("--- INTAKE ---");
        dashboard.addData("Intake", reverseIntake.down() ? "REVERSE" : (toggleIntake.active ? "ON" : "OFF"));
        dashboard.addData("Intake RPM (actual / target)",
                Math.round(intake.getRPM()) + " / " + Math.round(intake.getTargetRPM()));
        dashboard.addData("Intake % off target", percentOff(intake));
        dashboard.addData("Intake P", intake.getP());
    }

    private void updateServos() {
        // Transports run only while the flywheels are on (D-pad left or Panels FLYWHEELS_ON).
        transportPollen.setPower(FLYWHEELS_ON ? 1 : 0);
        triggerNectar.setPower(gate.down() ? 1 : 0);
        triggerPollen.setPower(gate.down() ? 1 : 0);
        transportNectar.setPower(FLYWHEELS_ON ? 1 : 0);
        stinger.setPosition(sting.down() ? STINGER_DOWN : STINGER_UP);
        dashboard.addData("Stinger position", stinger.getPosition());
    }

    private void updateShooter() {
        if (POLLEN_FLYWHEEL_P != shooterPollen.getP() || POLLEN_FLYWHEEL_F != shooterPollen.getF()) {
            shooterPollen.setPIDF(POLLEN_FLYWHEEL_P, shooterPollen.getI(), shooterPollen.getD(), POLLEN_FLYWHEEL_F);
        }
        if (NECTAR_FLYWHEEL_P != shooterNectar.getP() || NECTAR_FLYWHEEL_F != shooterNectar.getF()) {
            shooterNectar.setPIDF(NECTAR_FLYWHEEL_P, shooterNectar.getI(), shooterNectar.getD(), NECTAR_FLYWHEEL_F);
        }
        // Flips the same switch Panels shows, so the button and Panels always agree.
        if (toggleFlywheels.pressed()) {
            FLYWHEELS_ON = !FLYWHEELS_ON;
        }
        shooterPollen.setTargetRPM(FLYWHEELS_ON ? POLLEN_FLYWHEEL_RPM : 0);
        shooterNectar.setTargetRPM(FLYWHEELS_ON ? NECTAR_FLYWHEEL_RPM : 0);
        dashboard.addLine("");
        dashboard.addLine("--- SHOOTERS ---");
        dashboard.addData("Pollen RPM (actual / target)",
                Math.round(shooterPollen.getRPM()) + " / " + Math.round(shooterPollen.getTargetRPM()));
        dashboard.addData("Pollen % off target", percentOff(shooterPollen));
        dashboard.addData("Nectar RPM (actual / target)",
                Math.round(shooterNectar.getRPM()) + " / " + Math.round(shooterNectar.getTargetRPM()));
        dashboard.addData("Nectar % off target", percentOff(shooterNectar));
    }

    /** Numeric percent error for graphing. Negative means too slow; zero target reports 0. */
    private static double percentOff(VelocityMotor motor) {
        double target = motor.getTargetRPM();
        if (target == 0) {
            return 0;
        }
        return (motor.getRPM() - target) / Math.abs(target) * 100;
    }
}
