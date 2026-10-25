package org.firstinspires.ftc.teamcode.lib;

import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/**
 * Field-centric mecanum drivetrain. The sticks point where you want the robot to GO on the
 * field, no matter which way the robot is facing.
 *
 * "Forward" is whichever way the robot faced at the last resetHeading(), and the front of
 * the robot is the POD END. Heading comes from the Control Hub IMU, not the Pinpoint.
 * See AGENTS.md.
 */
@Configurable
public class MecanumDrive {

    /**
     * Stick values smaller than this count as zero. Worn sticks rarely rest at exactly 0,
     * and without this the robot creeps and the motors whine while nobody is touching it.
     */
    public static double STICK_DEADZONE = 0.1;

    /**
     * Expo on the drive and turn sticks (see StickCurve): small stick movements move the robot
     * much less, for aiming; full stick is still full power. Off: straight line.
     */
    public static boolean EXPO_CONTROLS = false;

    /** Speed while slow mode is held down. */
    public static double SLOW_SPEED = 0.25;
    public static double TURBO_SPEED = 1.0;
    /** The speed the robot drives at when the OpMode starts. The driver can change it. */
    public static double INITIAL_SPEED = 0.75;
    /** How much one changeDefaultSpeed() step moves it. */
    public static double SPEED_STEP = 0.05;
    /** The driver can never set the default speed outside these. */
    public static double MIN_ALLOWED_SPEED = 0.10;
    public static double MAX_ALLOWED_SPEED = 1.00;

    /** BRAKE mode makes the robot stop hard instead of rolling when the sticks are released. */
    public static boolean START_IN_BRAKE_MODE = true;

    private final DcMotorEx frontLeft, frontRight, backLeft, backRight;
    private final DcMotorEx[] motors;
    private final IMU imu;

    private double defaultSpeed = INITIAL_SPEED;
    private boolean brakeMode = START_IN_BRAKE_MODE;

    // Saved from the last drive() call so the dashboard can show what the robot was told.
    private double lastStrafe, lastForward, lastTurn;
    private double lastFieldX, lastFieldY, lastSpeedUsed;
    private double lastHeadingDegrees, lastHeadingRadians;

    public MecanumDrive(HardwareMap hardwareMap) {
        frontLeft = hardwareMap.get(DcMotorEx.class, "frontLeft");
        frontRight = hardwareMap.get(DcMotorEx.class, "frontRight");
        backLeft = hardwareMap.get(DcMotorEx.class, "backLeft");
        backRight = hardwareMap.get(DcMotorEx.class, "backRight");
        motors = new DcMotorEx[]{frontLeft, frontRight, backLeft, backRight};

        imu = hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.DOWN,
                RevHubOrientationOnRobot.UsbFacingDirection.LEFT)));

        frontRight.setDirection(DcMotorSimple.Direction.FORWARD);
        backRight.setDirection(DcMotorSimple.Direction.FORWARD);
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);

        // Raw power: the stick sets the motor power directly, no encoder speed control.
        for (DcMotorEx motor : motors) {
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        applyZeroPowerBehavior();
    }

    /** Drives at the driver's current default speed. */
    public void drive(double strafe, double forward, double turn) {
        drive(strafe, forward, turn, defaultSpeed);
    }

    /**
     * Drives the robot at a speed you choose. All three stick inputs are -1 to 1.
     *
     * @param strafe  slide right (negative slides left)
     * @param forward drive forward (negative drives backward)
     * @param turn    spin clockwise (negative spins counter-clockwise)
     * @param speed   how much of full power to use, 0 to 1. Use this overload only to
     *                override the default speed -- slow mode, turbo, and so on. Nothing
     *                clamps it to the default, so turbo really can go faster.
     */
    public void drive(double strafe, double forward, double turn, double speed) {
        strafe = ignoreDrift(strafe);
        forward = ignoreDrift(forward);
        turn = ignoreDrift(turn);
        if (EXPO_CONTROLS) {
            strafe = StickCurve.expo(strafe);
            forward = StickCurve.expo(forward);
            turn = StickCurve.expo(turn);
        }

        // One IMU read per loop -- it is a real sensor read, so don't ask for it twice.
        YawPitchRollAngles angles = imu.getRobotYawPitchRollAngles();
        lastHeadingDegrees = angles.getYaw(AngleUnit.DEGREES);
        lastHeadingRadians = angles.getYaw(AngleUnit.RADIANS);

        // Spin the stick direction backwards by the robot's heading. That turns "where the
        // driver is pointing on the field" into "which way the robot must push".
        double headingRadians = Math.toRadians(-lastHeadingDegrees);
        double fieldX = strafe * Math.cos(headingRadians) - forward * Math.sin(headingRadians);
        double fieldY = strafe * Math.sin(headingRadians) + forward * Math.cos(headingRadians);

        // Not scaled to fit: a hard diagonal plus a turn can go past 1.0 and get clipped.
        frontLeft.setPower((fieldY + fieldX + turn) * speed);
        frontRight.setPower((fieldY - fieldX - turn) * speed);
        backLeft.setPower((fieldY - fieldX + turn) * speed);
        backRight.setPower((fieldY + fieldX - turn) * speed);

        lastStrafe = strafe;
        lastForward = forward;
        lastTurn = turn;
        lastFieldX = fieldX;
        lastFieldY = fieldY;
        lastSpeedUsed = speed;
    }

    public void stop() {
        for (DcMotorEx motor : motors) {
            motor.setPower(0);
        }
    }

    /** Makes whichever way the robot is facing right now the new "forward" for the driver. */
    public void resetHeading() {
        imu.resetYaw();
    }

    /** Which way the robot is facing, in degrees. Reads the IMU right now. */
    public double getHeadingDegrees() {
        return imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
    }

    /** The heading from the last drive() call, with no extra sensor read. */
    public double getLastHeadingDegrees() {
        return lastHeadingDegrees;
    }

    public double getLastHeadingRadians() {
        return lastHeadingRadians;
    }

    /** Moves the default speed by whole steps, e.g. +1 to speed up, -1 to slow down. */
    public void changeDefaultSpeed(int steps) {
        double moved = defaultSpeed + steps * SPEED_STEP;
        defaultSpeed = Math.max(MIN_ALLOWED_SPEED, Math.min(MAX_ALLOWED_SPEED, moved));
    }

    public void toggleBrakeMode() {
        brakeMode = !brakeMode;
        applyZeroPowerBehavior();
    }

    private void applyZeroPowerBehavior() {
        for (DcMotorEx motor : motors) {
            motor.setZeroPowerBehavior(brakeMode
                    ? DcMotor.ZeroPowerBehavior.BRAKE
                    : DcMotor.ZeroPowerBehavior.FLOAT);
        }
    }

    /** Treats a stick that is nearly centred as centred. */
    private static double ignoreDrift(double stickValue) {
        return Math.abs(stickValue) < STICK_DEADZONE ? 0.0 : stickValue;
    }

    // ---- Things the dashboard asks about -------------------------------------

    public DcMotorEx[] getMotors() {
        return motors;
    }

    /** The speed the three-argument drive() uses. */
    public double getDefaultSpeed() {
        return defaultSpeed;
    }

    public boolean isBrakeMode() {
        return brakeMode;
    }

    public double getLastStrafe() { return lastStrafe; }
    public double getLastForward() { return lastForward; }
    public double getLastTurn() { return lastTurn; }
    public double getLastFieldX() { return lastFieldX; }
    public double getLastFieldY() { return lastFieldY; }
    public double getLastSpeedUsed() { return lastSpeedUsed; }
}
