package org.firstinspires.ftc.teamcode.helpers;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.dashboard.RobotDiagnostics;
import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.lib.MecanumDrive;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.mechanisms.Robot;
import org.firstinspires.ftc.teamcode.mechanisms.Stinger;
import org.firstinspires.ftc.teamcode.mechanisms.Vision;

import java.util.Locale;

/**
 * Stability soak test for a robot on a stand. The camera runs the whole time (as in teleop), and
 * every EVERY_SECONDS the next device runs for ON_SECONDS at POWER: all four drive motors
 * together, the intake, each shooter, then every servo together: the four transport and trigger
 * servos and the stinger (down, then back up). One round is 5 devices, 50 s with the defaults.
 *
 * Each start is written to the robot log ("Smoke test"), so after a restart the log shows which
 * device ran last.
 *
 * Wheels off the ground, no game pieces loaded, hands clear of the stinger.
 */
@Configurable
@Configurable.Hidden   // only for testing: Show it on the Tune tab
@TeleOp(name = "Smoke Test", group = "Diagnostics")
public class SmokeTest extends LinearOpMode {
    /** Power for every motor and continuous servo: 1 = full. */
    public static double POWER = 1.0;
    /** A new device starts this often (seconds)... */
    public static double EVERY_SECONDS = 10;
    /** ...and runs this long (seconds). */
    public static double ON_SECONDS = 1;

    private static final String[] DEVICES = {
            "drive (all four)", "intake", "shooterPollen", "shooterNectar", "servos (all, with the stinger)",
    };

    private DcMotorEx[] drive;
    private DcMotorEx[] motors;     // intake, shooterPollen, shooterNectar (DEVICES 1 to 3)
    private CRServo[] servos;       // transport and trigger servos, with the stinger (DEVICES 4)
    private Stinger stinger;

    @Override
    public void runOpMode() {
        new RobotSettings().load();   // robot.json, like teleop (stinger positions, camera settings)
        telemetry = new DashboardTelemetry(telemetry);

        drive = new MecanumDrive(hardwareMap).getMotors();
        motors = new DcMotorEx[]{motor("intake"), motor("shooterPollen"), motor("shooterNectar")};
        servos = new CRServo[]{
                hardwareMap.get(CRServo.class, "transportPollen"),
                hardwareMap.get(CRServo.class, "transportNectar"),
                hardwareMap.get(CRServo.class, "triggerPollen"),
                hardwareMap.get(CRServo.class, "triggerNectar"),
        };
        stinger = new Stinger(hardwareMap);
        Vision vision = new Vision(hardwareMap);
        try {
            while (opModeInInit()) {
                telemetry.addLine("Smoke Test: wheels off the ground, no game pieces, hands clear. PLAY starts it.");
                telemetry.addData("Each device", String.format(Locale.US, "%.0f%% power for %.1f s, every %.0f s",
                        POWER * 100, ON_SECONDS, EVERY_SECONDS));
                vision.update();
                vision.telemetry(telemetry);
                telemetry.update();
                sleep(20);
            }
            waitForStart();

            long started = System.nanoTime();
            long lastLoop = 0;
            long nextBattery = 0;
            double volts = 0;
            int running = -1;   // the device on now, or -1
            while (opModeIsActive()) {
                long now = System.nanoTime();
                double seconds = (now - started) / 1e9;
                int turn = (int) (seconds / EVERY_SECONDS);
                int device = turn % DEVICES.length;
                boolean on = seconds - turn * EVERY_SECONDS < ON_SECONDS;
                int wanted = on ? device : -1;
                if (wanted != running) {
                    if (running >= 0) {
                        set(running, false);
                    }
                    if (wanted >= 0) {
                        set(wanted, true);
                        RobotDiagnostics.info("Smoke test", "Round " + (turn / DEVICES.length + 1) + ": " + DEVICES[wanted]);
                    }
                    running = wanted;
                }

                vision.update();
                if (now >= nextBattery) {
                    volts = Robot.batteryVoltage(hardwareMap, volts);   // a hub call: 4 times a second
                    nextBattery = now + 250_000_000L;
                }
                if (lastLoop != 0) {
                    Dash.put("Loop ms", (now - lastLoop) / 1e6);
                }
                lastLoop = now;

                int next = (turn + 1) % DEVICES.length;
                telemetry.addData("Running", running >= 0 ? DEVICES[running] : "nothing");
                telemetry.addData("Next", String.format(Locale.US, "%s in %.0f s", DEVICES[next], (turn + 1) * EVERY_SECONDS - seconds));
                telemetry.addData("Round", turn / DEVICES.length + 1);
                telemetry.addData("Run time", String.format(Locale.US, "%d:%02d", (int) seconds / 60, (int) seconds % 60));
                vision.telemetry(telemetry);
                telemetry.addData("Battery V", "%.2f", volts);
                telemetry.update();
            }
        } finally {
            for (int i = 0; i < DEVICES.length; i++) {
                set(i, false);
            }
            vision.close();
        }
    }

    private DcMotorEx motor(String name) {
        DcMotorEx motor = hardwareMap.get(DcMotorEx.class, name);
        motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);   // raw power, no speed control
        return motor;
    }

    /** Turns one of DEVICES on (POWER, or the stinger down) or off. */
    private void set(int device, boolean on) {
        double power = on ? POWER : 0;
        if (device == 0) {
            for (DcMotorEx motor : drive) {
                motor.setPower(power);
            }
        } else if (device <= 3) {
            motors[device - 1].setPower(power);
        } else {
            for (CRServo servo : servos) {
                servo.setPower(power);
            }
            stinger.set(on);
            stinger.update();
        }
    }
}
