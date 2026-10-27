package org.firstinspires.ftc.teamcode.mechanisms;

import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * One part of the robot (intake, flywheels, an arm...). {@link Robot} calls {@link #update}
 * once per loop and {@link #telemetry} when the OpMode shows its telemetry; both are optional.
 */
public interface Mechanism {
    /** Once per loop: move the hardware to match what was asked (up(), on(), set()...). */
    default void update() {
    }

    /** Driver Station lines (and dashboard values). */
    default void telemetry(Telemetry telemetry) {
    }
}
