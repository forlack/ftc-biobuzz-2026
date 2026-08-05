package org.firstinspires.ftc.teamcode;

import com.bylazar.configurables.annotations.Configurable;
import com.bylazar.gamepad.PanelsGamepad;
import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/**
 * Field-centric teleop. Java port of the Blocks OpMode "Field Centric (Best)".
 *
 * !! DRIVING DIRECTION CHANGED 2026-08-04 !!
 * The front is now the POD END, matching Pedro/auton. This REVERSES the driving direction
 * relative to every previous version -- drivers have muscle memory for the old direction and
 * must re-learn. Both translation components were negated to achieve this; see the comment
 * at the x/y assignment for why that is equivalent to a 180-degree frame rotation.
 *
 * Powers are still scaled by a flat multiplier with NO normalization, as in the Blocks
 * original. Diagonal + rotation inputs can exceed 1.0 and clip. Left as-is deliberately.
 *
 * FIELD-CENTRIC MEANS translation is relative to the FIELD, not the robot. "Forward" is
 * whatever direction the robot faced when yaw was last reset (start of match, or A). So aim
 * the POD END downfield when you reset, or the whole frame is off.
 *
 * Teleop heading comes from the Control Hub IMU, independent of the Pinpoint that Pedro
 * uses. The two never interact unless teleop is driven through Pedro's setTeleOpDrive().
 *
 * Controls:
 *   left stick        translate (field-centric)
 *   right stick X     rotate
 *   A (btn 2)         reset field-centric heading to current facing
 *   right bumper      hold for slow mode
 *   D-pad Up / Down   raise / lower the speed cap (clamped)
 *   Y (btn 4)         toggle velocity mode (encoder PID) vs raw power
 *   X (btn 1)         toggle zero-power BRAKE vs FLOAT (coast)
 */
@Configurable
@TeleOp(name = "Field Centric (Java)", group = "Drive")
public class FieldCentricJava extends LinearOpMode {

    /** Speed while the right bumper is held. Live-editable in Panels. */
    public static double SLOW_MULTIPLIER = 0.25;
    /** Starting speed cap. D-pad adjusts from here at runtime. */
    public static double NORMAL_MULTIPLIER = 0.5;
    /** How much one D-pad press changes the speed cap. */
    public static double SPEED_STEP = 0.05;
    /** Clamp bounds for the D-pad-adjusted speed cap. */
    public static double MIN_SPEED = 0.10;
    public static double MAX_SPEED = 1.00;
    /** Start in velocity (encoder PID) mode rather than raw power. */
    public static boolean START_IN_VELOCITY_MODE = true;
    /** Start with BRAKE at zero power rather than FLOAT (coast). */
    public static boolean START_IN_BRAKE_MODE = true;

    // ---- Velocity PIDF, live-tunable from Panels ----------------------------
    // These are the REV hub's BUILT-IN motor velocity PIDF, used only in VELOCITY mode
    // (RUN_USING_ENCODER). They have NOTHING to do with Pedro's translational/heading/drive
    // PIDs, which apply only to path following.
    //
    // TUNE_PIDF must be true for these to be pushed to the motors; leave it false to keep
    // the hub's factory tuning. The actual factory values are printed to telemetry at init
    // so you have a baseline to return to.
    //
    // F dominates: it is the feedforward and should carry most of the output. Rough method:
    // raise F until the wheels hold the commanded speed, then add P for stiffness, then a
    // little I for steady-state error. D is rarely needed and amplifies encoder noise.
    public static boolean TUNE_PIDF = false;
    public static double VEL_P = 10.0;
    public static double VEL_I = 3.0;
    public static double VEL_D = 0.0;
    public static double VEL_F = 12.0;

    private DcMotorEx frontLeft, frontRight, backLeft, backRight;
    private IMU imu;

    @Override
    public void runOpMode() {
        TelemetryManager panels = PanelsTelemetry.INSTANCE.getTelemetry();

        frontLeft = hardwareMap.get(DcMotorEx.class, "FrontLeft");
        frontRight = hardwareMap.get(DcMotorEx.class, "FrontRight");
        backLeft = hardwareMap.get(DcMotorEx.class, "BackLeft");
        backRight = hardwareMap.get(DcMotorEx.class, "BackRight");
        DcMotorEx[] motors = {frontLeft, frontRight, backLeft, backRight};

        imu = hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));

        frontRight.setDirection(DcMotorSimple.Direction.FORWARD);
        backRight.setDirection(DcMotorSimple.Direction.FORWARD);
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);

        boolean velocityMode = START_IN_VELOCITY_MODE;
        applyRunMode(motors, velocityMode);

        boolean brakeMode = START_IN_BRAKE_MODE;
        applyZeroPowerBehavior(motors, brakeMode);

        double speedCap = NORMAL_MULTIPLIER;
        boolean lastUp = false, lastDown = false, lastToggle = false, lastBrake = false;

        // Baseline: the hub's factory velocity PIDF, so there is a known-good set to return
        // to if live tuning goes badly.
        String factoryPidf;
        try {
            factoryPidf = frontLeft.getPIDFCoefficients(DcMotor.RunMode.RUN_USING_ENCODER).toString();
        } catch (Exception e) {
            factoryPidf = "unavailable (" + e.getClass().getSimpleName() + ")";
        }

        // Tracks the last pushed values so coefficients are only written when they change --
        // these are I2C/bulk writes, not free to spam every loop.
        double appliedP = Double.NaN, appliedI = Double.NaN;
        double appliedD = Double.NaN, appliedF = Double.NaN;

        panels.addLine("Field Centric (Java) ready.");
        panels.addData("factory velocity PIDF", factoryPidf);
        panels.update(telemetry);

        waitForStart();

        if (!opModeIsActive()) {
            return;
        }
        imu.resetYaw();

        while (opModeIsActive()) {
            // Merges a gamepad connected to the Panels browser tab with the Driver Station
            // gamepad. With nothing connected in the browser this behaves as plain gamepad1.
            Gamepad pad = PanelsGamepad.INSTANCE.getFirstManager().asCombinedFTCGamepad(gamepad1);

            if (pad.dpad_up && !lastUp) {
                speedCap = clamp(speedCap + SPEED_STEP);
            }
            if (pad.dpad_down && !lastDown) {
                speedCap = clamp(speedCap - SPEED_STEP);
            }
            lastUp = pad.dpad_up;
            lastDown = pad.dpad_down;

            if (pad.y && !lastToggle) {
                velocityMode = !velocityMode;
                applyRunMode(motors, velocityMode);
            }
            lastToggle = pad.y;

            if (pad.x && !lastBrake) {
                brakeMode = !brakeMode;
                applyZeroPowerBehavior(motors, brakeMode);
            }
            lastBrake = pad.x;

            // Push velocity PIDF only when a value actually changed in Panels.
            if (TUNE_PIDF && (VEL_P != appliedP || VEL_I != appliedI
                    || VEL_D != appliedD || VEL_F != appliedF)) {
                for (DcMotorEx m : motors) {
                    m.setVelocityPIDFCoefficients(VEL_P, VEL_I, VEL_D, VEL_F);
                }
                appliedP = VEL_P;
                appliedI = VEL_I;
                appliedD = VEL_D;
                appliedF = VEL_F;
            }

            YawPitchRollAngles angles = imu.getRobotYawPitchRollAngles();

            double multiplier = pad.right_bumper ? SLOW_MULTIPLIER : speedCap;

            // FRONT = POD END (changed 2026-08-04 to match Pedro/auton).
            //
            // Both translation components are negated relative to the Blocks original, which
            // used x = -left_stick_x and y = +left_stick_y. Negating the translation vector
            // before the heading rotation is equivalent to rotating the robot's reference
            // frame 180 degrees -- i.e. declaring the other end to be the front. Rotation
            // (rx) is deliberately NOT negated: which end you call the front does not change
            // the sense of spinning about the vertical axis.
            //
            // Side benefit: this is now the STANDARD FTC convention. left_stick_y is negative
            // when pushed forward, so negating it makes forward positive; left_stick_x is
            // already positive to the right.
            double x = pad.left_stick_x;
            double y = -pad.left_stick_y;
            double rx = pad.right_stick_x;

            double theta = -angles.getYaw(AngleUnit.DEGREES);
            double thetaRad = Math.toRadians(theta);

            double rotX = x * Math.cos(thetaRad) - y * Math.sin(thetaRad);
            double rotY = x * Math.sin(thetaRad) + y * Math.cos(thetaRad);

            double flPower = (rotY + rotX + rx) * multiplier;
            double frPower = (rotY - rotX - rx) * multiplier;
            double blPower = (rotY - rotX + rx) * multiplier;
            double brPower = (rotY + rotX - rx) * multiplier;

            // In RUN_USING_ENCODER the motor controller treats setPower as a fraction of the
            // motor's max velocity and closes the loop on the encoder -- that is the built-in
            // velocity PID, and it needs no max-ticks-per-second constant from us.
            frontLeft.setPower(flPower);
            frontRight.setPower(frPower);
            backLeft.setPower(blPower);
            backRight.setPower(brPower);

            if (pad.a) {
                imu.resetYaw();
            }

            panels.addData("mode", velocityMode ? "VELOCITY (encoder PID)" : "RAW POWER");
            panels.addData("zero power", brakeMode ? "BRAKE" : "FLOAT (coast)");
            panels.addData("speed cap", round(speedCap));
            panels.addData("active multiplier", round(multiplier));
            panels.addLine("D-pad U/D = speed | Y = mode | X = brake/coast");
            panels.addLine("RB = slow | A = reset heading");
            panels.addLine("");
            panels.addData("IMU Yaw:", angles.getYaw(AngleUnit.DEGREES));
            panels.addData("rotX", rotX);
            panels.addData("rotY", rotY);
            panels.addData("Y", y);
            panels.addData("X", x);
            panels.addData("Theta (Radians)", angles.getYaw(AngleUnit.RADIANS));
            panels.addData("rx", rx);
            panels.addLine("");
            // If these stay at 0 while the wheels turn, the motor encoders are NOT wired --
            // velocity mode cannot work and you should stay in RAW POWER.
            panels.addData("enc FL", frontLeft.getCurrentPosition());
            panels.addData("enc FR", frontRight.getCurrentPosition());
            panels.addData("enc BL", backLeft.getCurrentPosition());
            panels.addData("enc BR", backRight.getCurrentPosition());
            panels.addData("vel FL", round(frontLeft.getVelocity()));
            panels.addData("vel FR", round(frontRight.getVelocity()));
            panels.addData("vel BL", round(backLeft.getVelocity()));
            panels.addData("vel BR", round(backRight.getVelocity()));
            panels.addLine("");
            panels.addData("PIDF tuning", TUNE_PIDF ? "ON (Panels values pushed)" : "off (factory)");
            if (TUNE_PIDF) {
                panels.addData("applied PIDF",
                        round(appliedP) + " / " + round(appliedI) + " / "
                                + round(appliedD) + " / " + round(appliedF));
            }
            panels.addData("factory PIDF", factoryPidf);
            panels.update(telemetry);
        }
    }

    private static void applyZeroPowerBehavior(DcMotorEx[] motors, boolean brakeMode) {
        for (DcMotorEx m : motors) {
            m.setZeroPowerBehavior(brakeMode
                    ? DcMotor.ZeroPowerBehavior.BRAKE
                    : DcMotor.ZeroPowerBehavior.FLOAT);
        }
    }

    private static void applyRunMode(DcMotorEx[] motors, boolean velocityMode) {
        for (DcMotorEx m : motors) {
            m.setMode(velocityMode
                    ? DcMotor.RunMode.RUN_USING_ENCODER
                    : DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
    }

    private static double clamp(double v) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
