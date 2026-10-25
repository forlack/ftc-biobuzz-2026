package org.firstinspires.ftc.teamcode.lib;

/**
 * Expo for the drive sticks (MecanumDrive.EXPO_CONTROLS): gentle near the middle for fine
 * aiming, still full power at full stick. Plain Java, so the simulator drives the same way.
 *
 *   out = (1 - EXPO) * in + EXPO * in^3
 *
 * With EXPO 0.7: a quarter stick gives 0.09 instead of 0.25, half stick 0.24, full stick 1.
 */
public final class StickCurve {
    private StickCurve() { }

    /** How much of the curve is cubic: 0 = straight line, 1 = pure cube. */
    public static final double EXPO = 0.7;

    public static double expo(double value) {
        return (1 - EXPO) * value + EXPO * value * value * value;
    }
}
