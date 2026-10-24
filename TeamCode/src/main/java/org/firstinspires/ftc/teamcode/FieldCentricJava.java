package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
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

    // Intake -- editable live on the dashboard's Tune tab.
    public static double INTAKE_RPM = 700;
    public static double INTAKE_P = 15;
    public static double INTAKE_F = 15;

    /** Turn both flywheels on or off from the Tune tab. They start on. A switch, not a setting. */
    @RobotSettings.NotSaved
    public static boolean FLYWHEELS_ON = true;
    /** F follows the battery voltage (see FlywheelCompensation). Off: FLYWHEEL_FIXED_F. */
    public static boolean FLYWHEEL_VOLTAGE_COMPENSATION = true;
    public static double FLYWHEEL_FIXED_F = 12; // previous competition behavior when disabled
    public static double FLYWHEEL_F = 11; // F at 12.5 V
    public static double FLYWHEEL_F_AT_10V = 12;

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
    public static double STINGER_DOWN = 0.42;

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

    private RobotSettings settings;
    private long lastLoop, nextBatteryRead;
    private double batteryVolts = 12.5;
    private double appliedFlywheelF = FLYWHEEL_F;
    private boolean feeding;

    @Override
    public void runOpMode() {
        // First, before anything reads a setting: robot.json replaces the defaults above.
        settings = new RobotSettings().load();   // every @Configurable class
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);

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
            updateTelemetry();
            dashboard.update();
        }
    }

    /** Reads the gamepad once, so everything below sees the same thing. */
    private void readGamepad() {
        operate = gamepad2;
        drive = gamepad1;
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
    }

    private void updateServos() {
        // Transports run only while the flywheels are on (operator A, or FLYWHEELS_ON on the Tune tab).
        transportPollen.setPower(FLYWHEELS_ON ? 1 : 0);
        feeding = gate.down() || forceShoot.down();
        triggerNectar.setPower(feeding ? 1 : 0);
        triggerPollen.setPower(feeding ? 1 : 0);
        transportNectar.setPower(FLYWHEELS_ON ? 1 : 0);
        stinger.setPosition(sting.down() ? STINGER_DOWN : STINGER_UP);
    }

    /** Flywheel F for this battery voltage, from the settings above. */
    public static double compensatedFlywheelF(double voltage) {
        return FlywheelCompensation.f(voltage, FLYWHEEL_VOLTAGE_COMPENSATION,
                FLYWHEEL_FIXED_F, FLYWHEEL_F, FLYWHEEL_F_AT_10V);
    }

    /** Lowest valid hub voltage; use the previous reading if no sensor is valid. */
    public static double flywheelBatteryVoltage(com.qualcomm.robotcore.hardware.HardwareMap map,
                                                double previous) {
        double volts = Double.POSITIVE_INFINITY;
        for (VoltageSensor sensor : map.voltageSensor) {
            double reading = sensor.getVoltage();
            if (Double.isFinite(reading) && reading > 0) volts = Math.min(volts, reading);
        }
        return Double.isFinite(volts) ? volts : previous;
    }

    private void updateShooter() {
        long now = System.nanoTime();
        // Reading the battery is a call to the hub that takes a few ms, so only 4 times a second.
        // The same reading drives the flywheel F and the Battery V telemetry.
        if (now >= nextBatteryRead) {
            batteryVolts = flywheelBatteryVoltage(hardwareMap, batteryVolts);
            nextBatteryRead = now + 250_000_000L;
        }
        appliedFlywheelF = compensatedFlywheelF(batteryVolts);
        if (POLLEN_FLYWHEEL_P != shooterPollen.getP()
                || POLLEN_FLYWHEEL_D != shooterPollen.getD() || appliedFlywheelF != shooterPollen.getF()) {
            shooterPollen.setPIDF(POLLEN_FLYWHEEL_P, 0, POLLEN_FLYWHEEL_D, appliedFlywheelF);
        }
        if (NECTAR_FLYWHEEL_P != shooterNectar.getP()
                || NECTAR_FLYWHEEL_D != shooterNectar.getD() || appliedFlywheelF != shooterNectar.getF()) {
            shooterNectar.setPIDF(NECTAR_FLYWHEEL_P, 0, NECTAR_FLYWHEEL_D, appliedFlywheelF);
        }
        // Flips the same switch the Tune tab shows, so the button and the dashboard always agree.
        if (toggleFlywheels.pressed()) {
            FLYWHEELS_ON = !FLYWHEELS_ON;
        }
        shooterPollen.setTargetRPM(FLYWHEELS_ON ? POLLEN_FLYWHEEL_RPM : 0);
        shooterNectar.setTargetRPM(FLYWHEELS_ON ? NECTAR_FLYWHEEL_RPM : 0);
    }

    /**
     * Everything the drivers and the dashboard see, in one place. Each motor speed is read
     * from the hub once per loop (every read is a call to the hub that costs loop time).
     *
     * telemetry.addData goes to the Driver Station and the dashboard; Dash.put to the dashboard
     * only (values for charts that drivers do not need). The names match the autonomous and the
     * simulator, so the same charts work everywhere.
     */
    private void updateTelemetry() {
        double intakeRpm = intake.getRPM();
        double pollenRpm = shooterPollen.getRPM();
        double nectarRpm = shooterNectar.getRPM();
        boolean ready = FLYWHEELS_ON
                && Math.abs(pollenRpm - shooterPollen.getTargetRPM()) < VelocityMotor.RPM_TOLERANCE
                && Math.abs(nectarRpm - shooterNectar.getTargetRPM()) < VelocityMotor.RPM_TOLERANCE;

        long now = System.nanoTime();

        telemetry.addLine("--- INTAKE ---");
        telemetry.addData("Intake", reverseIntake.down() ? "REVERSE" : (toggleIntake.active ? "ON" : "OFF"));
        telemetry.addData("Intake speed", rpmLine(intakeRpm, intake.getTargetRPM()));
        telemetry.addLine("");
        telemetry.addLine("--- SHOOTERS ---");
        telemetry.addData("Flywheels", !FLYWHEELS_ON ? "OFF" : ready ? "READY" : "spinning up");
        telemetry.addData("Pollen", rpmLine(pollenRpm, shooterPollen.getTargetRPM()));
        telemetry.addData("Nectar", rpmLine(nectarRpm, shooterNectar.getTargetRPM()));
        telemetry.addData("Feeding", feeding);
        telemetry.addData("Stinger", stinger.getPosition());
        telemetry.addData("Battery V", "%.2f", batteryVolts);
        telemetry.addData("Flywheel compensated F", "%.2f", appliedFlywheelF);
        telemetry.addLine("Pollen hub PIDF " + shooterPollen.getAppliedPIDF());
        telemetry.addLine("Nectar hub PIDF " + shooterNectar.getAppliedPIDF());
        settings.update(telemetry::addData);

        // Dashboard only: numbers to chart.
        Dash.put("Intake RPM", intakeRpm);
        Dash.put("Intake target", intake.getTargetRPM());
        Dash.put("Pollen RPM", pollenRpm);
        Dash.put("Pollen target", shooterPollen.getTargetRPM());
        Dash.put("Nectar RPM", nectarRpm);
        Dash.put("Nectar target", shooterNectar.getTargetRPM());
        Dash.put("Flywheels on", FLYWHEELS_ON);
        Dash.put("Flywheels ready", ready);
        if (lastLoop != 0) Dash.put("Loop ms", (now - lastLoop) / 1e6);
        lastLoop = now;
    }

    /** "3385 / 3400 RPM (-0.4%)" for the Driver Station. */
    private static String rpmLine(double rpm, double target) {
        String line = Math.round(rpm) + " / " + Math.round(target) + " RPM";
        return target == 0 ? line : String.format(java.util.Locale.US, "%s (%+.1f%%)", line, (rpm - target) / Math.abs(target) * 100);
    }
}
