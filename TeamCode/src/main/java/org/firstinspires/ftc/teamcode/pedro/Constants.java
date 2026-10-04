package org.firstinspires.ftc.teamcode.pedro;

import com.pedropathing.algorithm.Foresight;
import com.pedropathing.algorithm.ForesightConfig;
import com.pedropathing.follower.Follower;
import com.pedropathing.revhub.drivetrains.Mecanum;
import com.pedropathing.revhub.drivetrains.MecanumConfig;
import com.pedropathing.revhub.localizers.PinpointConfig;
import com.pedropathing.revhub.localizers.PinpointLocalizer;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * Pedro Pathing 3.x configuration for team 24620.
 *
 * !! MEASURED ON THE TEST PLATFORM, NOT THE COMPETITION ROBOT. Re-run AutoTune on the real
 * robot; the structure carries over, the numbers do not.
 *
 * Do not hand-edit the blocks below. Run **AutoTune** (the "Tuning" OpMode, then open the
 * robot-hosted page) and paste what it generates. It measures pod directions and offsets
 * instead of making you read telemetry and guess, which is where this team lost hours in
 * August.
 */
public class Constants {

    // Generated shape: MecanumTuner. Directions verified on the test chassis 2026-08-04.
    public static MecanumConfig drivetrainConfig = new MecanumConfig(c -> {
        c.frontLeftName.set("FrontLeft");
        c.frontRightName.set("FrontRight");
        c.backLeftName.set("BackLeft");
        c.backRightName.set("BackRight");
        c.frontLeftDirection.set(DcMotorSimple.Direction.REVERSE);
        c.frontRightDirection.set(DcMotorSimple.Direction.FORWARD);
        c.backLeftDirection.set(DcMotorSimple.Direction.REVERSE);
        c.backRightDirection.set(DcMotorSimple.Direction.FORWARD);
    });

    // Generated shape: PinpointTuner. Offsets are the mean of 4 spins, 2026-08-04.
    // NOTE the 3.x naming: xPodOffset is the FORWARD pod's sideways offset (old forwardPodY),
    // yPodOffset is the STRAFE pod's fore/aft offset (old strafePodX).
    public static PinpointConfig localizerConfig = new PinpointConfig(c -> {
        c.name.set("odo");
        c.podType.set(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        c.xPodOffset.set(2.125);
        c.yPodOffset.set(6.15);
        c.xPodDirection.set(GoBildaPinpointDriver.EncoderDirection.FORWARD);
        c.yPodDirection.set(GoBildaPinpointDriver.EncoderDirection.REVERSED);
        c.globalDistanceUnit.set(DistanceUnit.INCH);
        c.offsetUnits.set(DistanceUnit.INCH);
    });

    /**
     * Only the four values that carry over from 2.x are set. Everything else is a library
     * default on purpose.
     *
     * Foresight REPLACED Predictive Braking, so our measured kLinear 0.0574 / kQuad 0.00376
     * describe an algorithm that no longer exists and are NOT carried over. The brake
     * coefficients and the translational/heading controllers are whatever the library ships
     * until **ForesightTuner** is run.
     *
     * There is also no `setMaxPower` any more — speed is a velocity constraint in inches per
     * second rather than a power fraction. Deliberately not guessed at here. Run AutoTune's
     * Tests (Line Test) before trusting a path at speed.
     */
    public static ForesightConfig foresightConfig = new ForesightConfig(c -> {
        c.maxAchievableForwardVelocity.set(66.0);   // Forward Velocity Tuner, 2026-08-04
        c.maxAchievableStrafeVelocity.set(55.0);    // Lateral Velocity Tuner, 2026-08-04
        c.naturalForwardDeceleration.set(-26.5);    // Zero Power Acceleration, forward
        c.naturalStrafeDeceleration.set(-29.8);     // Zero Power Acceleration, lateral
    });

    /** Argument order is (Localizer, Drivetrain, Algorithm) — not what the Quickstart comment says. */
    public static Follower create(HardwareMap hardwareMap) {
        return new Follower(
                new PinpointLocalizer(hardwareMap, localizerConfig),
                new Mecanum(hardwareMap, drivetrainConfig),
                new Foresight(foresightConfig));
    }
}
