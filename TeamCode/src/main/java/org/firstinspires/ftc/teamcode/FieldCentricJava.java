package org.firstinspires.ftc.teamcode;

import com.bylazar.configurables.annotations.Configurable;
import com.bylazar.configurables.annotations.IgnoreConfigurable;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;

/**
 * Field-centric teleop. Java version of the Blocks OpMode "Field Centric (Best)".
 *
 * The loop never waits for anything -- read AGENTS.md before adding to it.
 *
 * Driver (gamepad 1):
 *   left stick        drive (field-centric, raw power with brake)
 *   right stick X     turn
 *   left bumper       hold for slow mode (0.25)
 *   right bumper      hold for turbo (1.0)
 *   B                 reset forward heading
 *   A + right bumper  hold to force shoot (testing shortcut)
 *
 * Operator (gamepad 2):
 *   X                 toggle intake
 *   D-pad Down        hold to reverse intake
 *   A                 toggle both flywheels
 *   right bumper      hold both gates open
 *   left bumper       lower stinger while held
 */
@Configurable
@TeleOp(name = "Field Centric (Java)", group = "Drive")
public class FieldCentricJava extends LinearOpMode {

    // Intake -- editable live in Panels.
    public static double INTAKE_RPM = 700;
    public static double INTAKE_P = 15;
    public static double INTAKE_F = 15;

    /** Turn both flywheels on or off from Panels. They start on. A switch, not a setting. */
    @RobotSettings.NotSaved
    public static boolean FLYWHEELS_ON = true;
    public static double FLYWHEEL_F = 12;

    // Pollen flywheel.
    public static double POLLEN_FLYWHEEL_RPM = 3400;
    public static double POLLEN_FLYWHEEL_P = 100;
    public static double POLLEN_FLYWHEEL_D = 0;

    // Nectar flywheel.
    public static double NECTAR_FLYWHEEL_RPM = 3650;
    public static double NECTAR_FLYWHEEL_P = 125;
    public static double NECTAR_FLYWHEEL_D = 0;

    /** Stinger servo end points, 0 to 1. */
    public static double STINGER_UP = 0;
    public static double STINGER_DOWN = 0.45;

    // Drive and dashboard.
    private MecanumDrive chassis;
    private DriveDashboard dashboard;
    private Gamepad drive;
    private Gamepad operate;


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

    // Drive controls. Each lambda reads the current drive gamepad when buttons.update() runs.
    private final Button slowMode        = buttons.add(() -> drive.left_bumper);
    private final Button turboMode       = buttons.add(() -> drive.right_bumper);
    private final Button resetFront      = buttons.add(() -> drive.b);
    private final Button forceShoot       = buttons.add(() -> drive.a && drive.right_bumper);

    // Mechanism controls.
    private final Button toggleIntake    = buttons.add(() -> operate.x);
    private final Button reverseIntake   = buttons.add(() -> operate.dpad_down);
    private final Button toggleFlywheels = buttons.add(() -> operate.a);
    private final Button gate            = buttons.add(() -> operate.right_bumper);
    private final Button sting           = buttons.add(() -> operate.left_bumper);

    /** Classes whose public static values are saved in robot.json. Autonomous loads them too. */
    @IgnoreConfigurable
    public static final Class<?>[] SETTINGS = {FieldCentricJava.class, MecanumDrive.class};
    private RobotSettings settings;

    @Override
    public void runOpMode() {
        // First, before anything reads a setting: robot.json replaces the defaults above.
        settings = new RobotSettings(SETTINGS).load();

        // Drive.
        chassis = new MecanumDrive(hardwareMap);

        // Hardware names must match the active robot config exactly.
        // Intake.
        intake = new VelocityMotor(hardwareMap, "intake", DcMotorSimple.Direction.FORWARD, 145.1);
        intake.setPIDF(INTAKE_P, intake.getI(), intake.getD(), INTAKE_F);

        // Pollen shooter and feed.
        shooterPollen = new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE);
        shooterPollen.setPIDF(POLLEN_FLYWHEEL_P, 0, POLLEN_FLYWHEEL_D, FLYWHEEL_F);
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);

        // Nectar shooter and feed.
        shooterNectar = new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD);
        shooterNectar.setPIDF(NECTAR_FLYWHEEL_P, 0, NECTAR_FLYWHEEL_D, FLYWHEEL_F);
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
            updateDriving();
            updateIntake();
            updateServos();
            updateShooter();
            settings.update(dashboard);
            dashboard.update();
        }
    }

    /** Reads the gamepad once, so everything below sees the same thing. */
    private void readGamepad() {
        operate = dashboard.mergeOperatorGamepad(gamepad2);
        drive = dashboard.mergeGamepad(gamepad1);
        buttons.update();
    }

    private void updateDriving() {
        // left_stick_y is negative when you push the stick forward, so flip it.
        double strafe = drive.left_stick_x;
        double forward = -drive.left_stick_y;
        double turn = drive.right_stick_x;

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
        // Transports run only while the flywheels are on (operator A or Panels FLYWHEELS_ON).
        transportPollen.setPower(FLYWHEELS_ON ? 1 : 0);
        boolean shooting = gate.down() || forceShoot.down();
        triggerNectar.setPower(shooting ? 1 : 0);
        triggerPollen.setPower(shooting ? 1 : 0);
        transportNectar.setPower(FLYWHEELS_ON ? 1 : 0);
        stinger.setPosition(sting.down() ? STINGER_DOWN : STINGER_UP);
        dashboard.addData("Stinger position", stinger.getPosition());
    }

    private void updateShooter() {
        if (POLLEN_FLYWHEEL_P != shooterPollen.getP()
                || POLLEN_FLYWHEEL_D != shooterPollen.getD() || FLYWHEEL_F != shooterPollen.getF()) {
            shooterPollen.setPIDF(POLLEN_FLYWHEEL_P, 0, POLLEN_FLYWHEEL_D, FLYWHEEL_F);
        }
        if (NECTAR_FLYWHEEL_P != shooterNectar.getP()
                || NECTAR_FLYWHEEL_D != shooterNectar.getD() || FLYWHEEL_F != shooterNectar.getF()) {
            shooterNectar.setPIDF(NECTAR_FLYWHEEL_P, 0, NECTAR_FLYWHEEL_D, FLYWHEEL_F);
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
        dashboard.addData("Pollen hub PIDF", shooterPollen.getAppliedPIDF());
        dashboard.addData("Nectar RPM (actual / target)",
                Math.round(shooterNectar.getRPM()) + " / " + Math.round(shooterNectar.getTargetRPM()));
        dashboard.addData("Nectar % off target", percentOff(shooterNectar));
        dashboard.addData("Nectar hub PIDF", shooterNectar.getAppliedPIDF());
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
