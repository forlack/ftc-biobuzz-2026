package org.firstinspires.ftc.teamcode.lib;

/**
 * The robot's mechanisms, as autonomous actions see them. The real robot implements this with
 * motors and servos; the desktop simulator implements it with physics. Actions written against
 * this interface run the same way on both.
 */
public interface Mechanisms {
    void intake(boolean on);

    /** Spin both flywheels up to their target RPMs, or stop them. */
    void flywheels(boolean on);

    /** Both flywheels are close enough to their target RPMs to shoot. */
    boolean flywheelsReady();

    /** Run the triggers that push balls into the flywheels. */
    void feed(boolean on);

    void stinger(boolean down);
}
