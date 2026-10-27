package org.firstinspires.ftc.teamcode.lib;

import java.util.function.BooleanSupplier;

/**
 * Every autonomous function the Auton tab offers: each mechanism's {@link AutoFunction}
 * methods (instant, found automatically), plus the timed ones below. A timed function is a
 * small sub-sequence: when the auton reaches it, its steps run in its place, one per loop.
 *
 * Adding one: look up the mechanism functions it uses by name at the top, then add a
 * {@code .step(name, label, help, steps, settings...)} below. Steps use names only, so they
 * run the same on the robot and in the simulator. See dashboard/DEVELOPER.md.
 */
public final class AutoStepFunctions {
    private AutoStepFunctions() {
    }

    public static Actions create(final AutoFunctions robot) {
        final BooleanSupplier flywheelsReady = robot.condition("flywheelsReady");
        final Runnable flywheelsOn = robot.action("flywheelsOn");
        final Runnable triggersOn = robot.action("triggersOn");
        final Runnable triggersOff = robot.action("triggersOff");

        Actions actions = new Actions()
                .step("wait", "wait", "Do nothing for a while.",
                        (sequence, p) -> {
                            sequence.waitSeconds(p.getDouble("seconds"));
                        },
                        new Actions.Param("seconds", "Seconds", "s", 0.5, 0, 30));
        robot.addTo(actions);   // intake on, flywheels on, stinger down, ... from the mechanisms
        return actions
                .step("waitForFlywheels", "wait for flywheels",
                        "Wait until both flywheels are at speed, or the time limit runs out.",
                        (sequence, p) -> {
                            sequence.waitUntil("wait for flywheels", flywheelsReady, p.getDouble("timeout"));
                        },
                        new Actions.Param("timeout", "Time limit", "s", 2, 0, 10))
                .step("shoot", "shoot",
                        "Spin up if needed, wait until ready (2 s limit), then run the triggers for a while.",
                        (sequence, p) -> {
                            sequence.run("flywheels on", flywheelsOn);
                            sequence.waitUntil("wait for flywheels", flywheelsReady, 2);
                            sequence.run("triggers on", triggersOn);
                            sequence.waitSeconds(p.getDouble("seconds"));
                            sequence.run("triggers off", triggersOff);
                        },
                        new Actions.Param("seconds", "Feed time", "s", 3.5, 0.1, 15))
                .step("turnTo", "turn to",
                        "Stand still and turn to face a field heading.",
                        (sequence, p) -> {
                            sequence.turnTo(Math.toRadians(p.getDouble("degrees")));
                        },
                        new Actions.Param("degrees", "Heading", "°", 0, -360, 360))
                // Steering or waiting while a path is being followed makes no sense: steps only.
                .betweenDrivesOnly("wait", "waitForFlywheels", "turnTo");
    }
}
