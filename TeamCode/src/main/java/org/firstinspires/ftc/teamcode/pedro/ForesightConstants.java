package org.firstinspires.ftc.teamcode.pedro;

import com.pedropathing.algorithm.ForesightConfig;
import com.pedropathing.controllers.Controller;
import com.pedropathing.math.Matrix;
import com.pedropathing.math.Vector2D;

/**
 * Foresight path-following tuning (AutoTune results, 2026-10-17). Kept apart from Constants
 * because it needs only Pedro core: the desktop simulator follows paths with these values.
 */
public final class ForesightConstants {
    private ForesightConstants() { }

    public static final ForesightConfig config = new ForesightConfig(c -> {
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

}
