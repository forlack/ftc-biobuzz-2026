package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.Button;
import org.firstinspires.ftc.teamcode.lib.DashboardGamepads;
import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.lib.DriveDashboard;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.mechanisms.Flywheels;
import org.firstinspires.ftc.teamcode.mechanisms.Intake;
import org.firstinspires.ftc.teamcode.mechanisms.Robot;

/**
 * Field-centric teleop. Java version of the Blocks OpMode "Field Centric (Best)".
 *
 * The loop never waits for anything -- read AGENTS.md before adding to it.
 *
 * The mechanisms (intake, flywheels, triggers, stinger) live in mechanisms/: this OpMode only
 * maps buttons to them. Their settings are on the Tune tab under each mechanism's name.
 *
 * The controls are the buttons below: the comment at the end of each line is its label, and
 * the dashboard's Gamepad drawer shows those labels.
 */
@TeleOp(name = "Field Centric (Java)", group = "Drive")
public class FieldCentricJava extends LinearOpMode {

    // Drive and dashboard.
    private MecanumDrive chassis;
    private DriveDashboard dashboard;
    private Robot robot;
    private Gamepad drive;
    private Gamepad operate;

    // Must be ABOVE the buttons -- they register themselves during construction.
    private final Button.Group buttons = new Button.Group();

    // Drive controls. Each lambda reads the current drive gamepad when buttons.update() runs.
    // The comment at the end of a line is its label in the dashboard's Gamepad drawer
    // (read at build time by ../gamepad-controls.gradle): change the button, change the label.
    private final Button slowModeButton        = buttons.add(() -> drive.left_bumper);               // hold: slow
    private final Button turboButton           = buttons.add(() -> drive.right_bumper);              // hold: turbo
    private final Button resetFrontButton      = buttons.add(() -> drive.b);                         // reset forward
    private final Button forceShootButton      = buttons.add(() -> drive.a && drive.right_bumper);   // hold: force shoot

    // Mechanism controls.
    private final Button intakeToggleButton    = buttons.add(() -> operate.x);                       // intake on / off
    private final Button intakeReverseButton   = buttons.add(() -> operate.dpad_down);               // hold: reverse intake
    private final Button flywheelsToggleButton = buttons.add(() -> operate.a);                       // flywheels on / off
    private final Button triggersButton        = buttons.add(() -> operate.right_bumper);            // hold: triggers (shoot)
    private final Button stingerButton         = buttons.add(() -> operate.left_bumper);             // hold: stinger down

    private RobotSettings settings;
    private long lastLoop;

    @Override
    public void runOpMode() {
        // First, before anything reads a setting: robot.json replaces the defaults.
        settings = new RobotSettings().load();   // every @Configurable class
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);

        // Hardware names must match the active robot config exactly (each mechanism has its own).
        chassis = new MecanumDrive(hardwareMap);
        robot = new Robot(hardwareMap);

        // Dashboard.
        dashboard = new DriveDashboard(chassis, telemetry);
        dashboard.showReady("Field Centric (Java)");

        waitForStart();
        if (!opModeIsActive()) {
            return;
        }
        chassis.resetHeading();

        while (opModeIsActive()) {
            long start = System.nanoTime();
            readGamepad();
            updateDriving();
            long driven = System.nanoTime();
            updateMechanisms();
            long mechanisms = System.nanoTime();
            updateTelemetry();
            // Where the loop time goes (Live tab charts). The rest of Loop ms is sending the
            // telemetry to the Driver Station and the SDK between loops.
            Dash.put("Time drive ms", (driven - start) / 1e6);
            Dash.put("Time mechanisms ms", (mechanisms - driven) / 1e6);
            Dash.put("Time sensors+telemetry ms", (System.nanoTime() - mechanisms) / 1e6);
            dashboard.update();
        }
    }

    /**
     * Reads the gamepad once, so everything below sees the same thing. The dashboard's
     * Gamepad drawer adds to it when someone takes control there (practice only); otherwise
     * these are the Driver Station gamepads unchanged.
     */
    private void readGamepad() {
        operate = DashboardGamepads.combine(gamepad2, 2);
        drive = DashboardGamepads.combine(gamepad1, 1);
        buttons.update();
    }

    private void updateDriving() {
        // left_stick_y is negative when you push the stick forward, so flip it.
        double strafe = drive.left_stick_x;     // drive (field-centric)
        double forward = -drive.left_stick_y;   // drive (field-centric)
        double turn = drive.right_stick_x;      // turn

        // Start from the driver's default speed, then let any mode override it. Adding
        // turbo or a precision mode is one more line here -- MecanumDrive never changes.
        double speed = chassis.getDefaultSpeed();
        if (slowModeButton.down()) {
            speed = MecanumDrive.SLOW_SPEED;
        } else if (turboButton.down()) {
            speed = MecanumDrive.TURBO_SPEED;
        }

        chassis.drive(strafe, forward, turn, speed);

        // After the driving on purpose, so this loop still uses the old heading.
        if (resetFrontButton.down()) {
            chassis.resetHeading();
        }
    }

    /** The operator's buttons say what each mechanism should do; robot.update() then does it. */
    private void updateMechanisms() {
        // Flips the same switch the Tune tab shows (it starts on), like the flywheels' A.
        if (intakeToggleButton.pressed()) {
            Intake.INTAKE_ON = !Intake.INTAKE_ON;
        }
        robot.intake.set(Intake.INTAKE_ON);
        // Holding reverse wins over the toggle, and only lasts while held.
        robot.intake.reverse(intakeReverseButton.down());
        robot.triggers.set(triggersButton.down() || forceShootButton.down());
        robot.stinger.set(stingerButton.down());
        // Flips the same switch the Tune tab shows, so the button and the dashboard always agree.
        if (flywheelsToggleButton.pressed()) {
            Flywheels.FLYWHEELS_ON = !Flywheels.FLYWHEELS_ON;
        }
        robot.flywheels.set(Flywheels.FLYWHEELS_ON);

        robot.update();
    }

    /**
     * Everything the drivers and the dashboard see, in one place: each mechanism's lines
     * (Driver Station and dashboard) and chart values (dashboard only). The names match the
     * autonomous and the simulator, so the same charts work everywhere.
     */
    private void updateTelemetry() {
        long now = System.nanoTime();
        robot.telemetry(telemetry);
        settings.update(telemetry::addData);
        if (lastLoop != 0) Dash.put("Loop ms", (now - lastLoop) / 1e6);
        lastLoop = now;
    }
}
