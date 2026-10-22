package org.firstinspires.ftc.teamcode.pedro;

import com.pedropathing.algorithm.Foresight;
import com.pedropathing.algorithm.ForesightConfig;
import com.pedropathing.controllers.Controller;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Matrix;
import com.pedropathing.math.Vector2D;
import com.pedropathing.revhub.drivetrains.Mecanum;
import com.pedropathing.revhub.drivetrains.MecanumConfig;
import com.pedropathing.revhub.localizers.PinpointConfig;
import com.pedropathing.revhub.localizers.PinpointLocalizer;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/** Pedro Pathing 3.x configuration for Team 24621's competition robot. */
public class Constants {

    // Pinpoint and Foresight: user-supplied AutoTune results, 2026-10-17.
    // Re-run the matching procedure when the hardware changes.

    // 1. Mecanum Drivetrain
    public static MecanumConfig drivetrainConfig = new MecanumConfig(c -> {
        c.frontLeftName.set("frontLeft");
        c.frontRightName.set("frontRight");
        c.backLeftName.set("backLeft");
        c.backRightName.set("backRight");
        c.frontLeftDirection.set(DcMotorSimple.Direction.REVERSE);
        c.frontRightDirection.set(DcMotorSimple.Direction.FORWARD);
        c.backLeftDirection.set(DcMotorSimple.Direction.REVERSE);
        c.backRightDirection.set(DcMotorSimple.Direction.FORWARD);
    });

    // 2. Pinpoint Localizer
    public static PinpointConfig localizerConfig = new PinpointConfig(c -> {
        c.name.set("pinpoint");
        c.podType.set(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        c.xPodOffset.set(0.20296776388573837);
        c.yPodOffset.set(0.2779039060036967);
        c.xPodDirection.set(GoBildaPinpointDriver.EncoderDirection.FORWARD); // forward pod flipped 2026-10-15
        c.yPodDirection.set(GoBildaPinpointDriver.EncoderDirection.REVERSED);
        c.globalDistanceUnit.set(DistanceUnit.INCH);
        c.offsetUnits.set(DistanceUnit.INCH);
    });

    // 3. Foresight
    public static ForesightConfig foresightConfig = new ForesightConfig(c -> {
        Controller primaryTranslationalForward = Controller.proportional(0.2414321386126047);
        Controller secondaryTranslationalForward = Controller.proportional(0.08920272403180053);
        Controller primaryTranslationalLateral = Controller.proportional(0.2988873729069272);
        Controller secondaryTranslationalLateral = Controller.proportional(0.11043089787141759);

        c.forwardTranslational.set(Controller.piecewise(secondaryTranslationalForward).put(2.5, primaryTranslationalForward));
        c.strafeTranslational.set(Controller.piecewise(secondaryTranslationalLateral).put(2.5, primaryTranslationalLateral));

        c.coast.set(Controller.proportionalFeedforward(0.015115405161013694));
        c.brake.set(Controller.proportionalFeedforward(0.012848094386861639));

        c.headingFeedback.set(Controller.proportional(4.175704740744205));
        c.headingBrakeCoefficients.set(Vector2D.cartesian(0.04889036930843421, 0.0048745579757984575));

        c.linearBrakeCoefficients.set(Matrix.diag(0.07436963785044273, 0.046948354034016884));
        c.quadraticBrakeCoefficients.set(Matrix.diag(0.0014785251608225255, 0.0021240054139418127));

        c.maxAchievableForwardVelocity.set(68.46566852514579);
        c.maxAchievableStrafeVelocity.set(58.215729692412836);
        // Positive in 3.x. The 2.x zero-power accelerations were negative (-26.5 / -29.8),
        // and carrying one over is what made the Tests procedure throw a config error.
        c.naturalForwardDeceleration.set(30.550069979696943);
        c.naturalStrafeDeceleration.set(55.42782729827723);
    });

    /** Argument order is (Localizer, Drivetrain, Algorithm) — not what the Quickstart comment says. */
    public static Follower create(HardwareMap hardwareMap) {
        return new Follower(
                new PinpointLocalizer(hardwareMap, localizerConfig),
                new Mecanum(hardwareMap, drivetrainConfig),
                new Foresight(foresightConfig));
    }
}
