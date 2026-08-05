package org.firstinspires.ftc.teamcode.pedroPathing;

import com.pedropathing.control.PredictiveBrakingCoefficients;
import com.pedropathing.follower.Follower;
import com.pedropathing.follower.FollowerConstants;
import com.pedropathing.ftc.FollowerBuilder;
import com.pedropathing.ftc.drivetrains.MecanumConstants;
import com.pedropathing.ftc.localization.constants.PinpointConstants;
import com.pedropathing.paths.PathConstraints;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * Pedro Pathing configuration for team 2222.
 *
 * Hardware comes from 2222-Config.xml: mecanum drive on FrontLeft/FrontRight/BackLeft/
 * BackRight, goBILDA Pinpoint on "odo".
 *
 * TUNING STATUS as of 2026-08-04:
 *   [x] Localization -- pod directions, offsets, encoder resolution
 *   [x] Forward / Lateral velocity  -> xVelocity 66, yVelocity 55
 *   [x] Zero power acceleration     -> -26.5 / -29.8
 *   [x] Predictive braking          -> kLinear 0.0574, kQuad 0.00376
 *   [ ] PIDs -- still on LIBRARY DEFAULTS. Run Tuning -> Manual, in order:
 *         Translational -> Heading -> Drive (-> Centripetal)
 */
public class Constants {

    // ------------------------------------------------------------------
    // Drivetrain
    // ------------------------------------------------------------------
    // VERIFIED 2026-08-04 via the "Direction Test" OpMode: all four robot-level directions
    // and all four raw per-motor directions behave as expected, and the drive matches the
    // localizer (forward raises pose x, left raises pose y).
    //
    // These directions are the same as the teleop config. Note that Pedro's forward is the
    // POD END of the robot -- the opposite end from what the drivers call the front. That is
    // a labeling difference, not a bug: motors and localizer agree, which is all Pedro needs.
    // The earlier "drives backward / strafes wrong" reports were this frame mismatch plus an
    // upstream telemetry label bug, not a wiring fault.
    public static MecanumConstants driveConstants = new MecanumConstants()
            .leftFrontMotorName("FrontLeft")
            .leftRearMotorName("BackLeft")
            .rightFrontMotorName("FrontRight")
            .rightRearMotorName("BackRight")
            .leftFrontMotorDirection(DcMotorSimple.Direction.REVERSE)
            .leftRearMotorDirection(DcMotorSimple.Direction.REVERSE)
            .rightFrontMotorDirection(DcMotorSimple.Direction.FORWARD)
            .rightRearMotorDirection(DcMotorSimple.Direction.FORWARD)
            .useBrakeModeInTeleOp(true)
            // Measured with the Forward / Lateral Velocity Tuners, 2026-08-04.
            .xVelocity(66.0)
            .yVelocity(55.0);

    // ------------------------------------------------------------------
    // Localization -- goBILDA Pinpoint
    // ------------------------------------------------------------------
    // Offsets are measured from the ROBOT'S CENTER OF ROTATION to each pod, in inches:
    //   forwardPodY -- how far LEFT the forward/parallel pod sits (left = positive)
    //   strafePodX  -- how far FORWARD the strafe/perpendicular pod sits (forward = positive)
    // Get the sign wrong and the robot will spiral instead of drive straight; the
    // Localization tuner is what catches it.
    public static PinpointConstants localizerConstants = new PinpointConstants()
            .hardwareMapName("odo")
            .distanceUnit(DistanceUnit.INCH)
            // Offsets Tuner, 2026-08-04, AFTER strafeEncoderDirection was corrected to
            // REVERSED. Mean of 4 spins (tuner reports these as "strafeX" / "forwardY"):
            //   strafeX : 6.1, 6.7, 5.8, 6.0  -> 6.15  (spread 0.90)
            //   forwardY: 2.4, 2.3, 1.7, 2.1  -> 2.125 (spread 0.70)
            //
            // Supersedes the pre-fix values 3.2 / -7.0, which were measured with an inverted
            // Y axis and are invalid.
            //
            // NOTE: re-running the tuner requires setting BOTH of these back to 0 first --
            // non-zero values bias the result.
            .forwardPodY(2.125)
            .strafePodX(6.15)
            // Confirmed by the team: 4-bar pods.
            .encoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD)
            // NOTE: PinpointConstants has NO forwardTicksToInches/strafeTicksToInches -- the
            // Pinpoint does ticks->inches in firmware from the pod type above. So the Forward
            // and Lateral Tuner multipliers have nowhere to go, and should read ~1.0 already.
            //
            // If BOTH are off by the same factor M, override the pod preset (ticks per INCH,
            // because distanceUnit above is INCH). 4-bar default = 19.894367 ticks/mm =
            // 505.3169 ticks/inch, so:  customEncoderResolution = 505.3169 / M
            //   .customEncoderResolution(505.3169)
            //
            // If only HEADING is off, scale that instead:
            //   .yawScalar(1.0)
            //
            // If forward and strafe differ from each other, do NOT fudge these -- one value
            // covers both axes. Different errors mean wrong pod type, a slipping pod, or bad
            // offsets. Diagnose the cause instead.
            // forward: VERIFIED. The Forward Velocity Tuner completed, which means x climbed
            // 72 -> 120. Backwards it would have run 72 -> 0 -> -120 and never stopped.
            .forwardEncoderDirection(GoBildaPinpointDriver.EncoderDirection.FORWARD)
            // strafe: REVERSED as of 2026-08-04. Measured with Pedro Direction Test -- driving
            // LEFT made pose y DECREASE, but left is +Y. That inverted axis is also why the
            // Lateral Velocity Tuner never stopped: its stop condition is
            // abs(pose.y) > DISTANCE + 72 starting from y = 72, so with y running the wrong
            // way it needed 192 inches of travel instead of 48.
            .strafeEncoderDirection(GoBildaPinpointDriver.EncoderDirection.REVERSED);

    // ------------------------------------------------------------------
    // Follower
    // ------------------------------------------------------------------
    public static FollowerConstants followerConstants = new FollowerConstants()
            // Confirmed by the team: ~5 kg. Revisit if the robot gains significant weight.
            .mass(5.0)
            // Zero Power Acceleration Tuners, 2026-08-04.
            .forwardZeroPowerAcceleration(-26.5)
            .lateralZeroPowerAcceleration(-29.8)
            // Predictive Braking Tuner, 2026-08-04.
            // Constructor order is (P, kLinearBraking, kQuadraticFriction) -- NOT the order
            // the tuner prints them in. P was not measured, so it keeps the library default
            // of 0.15 (library defaults are 0.15 / 0.1 / 0.001).
            // Calling this builder also sets usePredictiveBraking = true, which is otherwise
            // false by default.
            .predictiveBrakingCoefficients(new PredictiveBrakingCoefficients(0.15, 0.0574, 0.00376))
            // PIDs intentionally NOT overridden -- the library defaults are used.
            //
            // This file previously carried hand-invented placeholders, one of which had the
            // drive D term at 0.0006 against a library default of 1.0E-5 (60x too high).
            // On a noisy velocity signal that produced visibly aggressive starts and stops.
            // Library defaults are a far better starting point; run the Manual tuners
            // (Translational -> Heading -> Drive) to refine from here.
            .centripetalScaling(0.0005); // matches the library default

    static {
        // Pedro runs a TWO-STAGE PID: a primary set for large errors and a secondary set
        // that takes over once error is small. The secondary set is much more aggressive
        // (translational P 0.3 vs 0.1) specifically to close out the final approach.
        //
        // All three flags default to FALSE, so without this the robot coasts in on the weak
        // primary gains and stops short -- exactly the "not enough low end to get back on
        // target" symptom.
        //
        // Set directly rather than via the secondaryXxxPIDFCoefficients() builders: those
        // builders enable the flag but also require passing coefficients, and re-typing the
        // library defaults by hand risks transcription errors. This keeps the library's
        // tuned secondary values and only flips the switches.
        //
        // Switch thresholds (library defaults):
        //   translationalPIDFSwitch = 3.0 inches
        //   headingPIDFSwitch       = 0.157 rad (~9 degrees)
        //   drivePIDFSwitch         = 20.0
        followerConstants.useSecondaryTranslationalPIDF = true;
        followerConstants.useSecondaryHeadingPIDF = true;
        followerConstants.useSecondaryDrivePIDF = true;
    }

    /**
     * NOT speed limits. Verified against the bytecode, the 4-arg constructor maps to:
     *   (tValueConstraint, timeoutConstraint, brakingStrength, brakingStart)
     * and it fills in defaults for velocityConstraint (0.1), translationalConstraint (0.1)
     * and headingConstraint (0.007) -- those are path-COMPLETION tolerances, not caps.
     *
     * Speed is not a property of a path in Pedro. Use follower.setMaxPower(0..1) at runtime.
     */
    public static PathConstraints pathConstraints = new PathConstraints(0.99, 100, 1, 1);

    public static Follower createFollower(HardwareMap hardwareMap) {
        return new FollowerBuilder(followerConstants, hardwareMap)
                .mecanumDrivetrain(driveConstants)
                .pinpointLocalizer(localizerConstants)
                .pathConstraints(pathConstraints)
                .build();
    }
}
