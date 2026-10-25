package org.firstinspires.ftc.teamcode.lib;

/**
 * Every autonomous function the path editor offers: each mechanism's {@link AutoFunction}
 * methods (found automatically), plus the steps below that take time and combine them.
 *
 * Steps only use function names, so they run the same on the robot and in the simulator.
 */
public final class StandardActions {
    private StandardActions() { }

    public static Actions create(final AutoFunctions robot) {
        final java.util.function.BooleanSupplier flywheelsReady = robot.condition("flywheelsReady");
        final Runnable flywheelsOn = robot.action("flywheelsOn");
        final Runnable feedOn = robot.action("feedOn");
        final Runnable feedOff = robot.action("feedOff");

        Actions actions = new Actions()
                .step("wait", "wait", "Do nothing for a while.",
                        (sequence, p) -> sequence.waitSeconds(p.getDouble("seconds")),
                        new Actions.Param("seconds", "Seconds", "s", 0.5, 0, 30));
        robot.addTo(actions);   // intake on, flywheels on, stinger down, ... from the mechanisms
        return actions
                .step("waitForFlywheels", "wait for flywheels",
                        "Wait until both flywheels are at speed, or the time limit runs out.",
                        (sequence, p) -> {
                            final double limit = p.getDouble("timeout");
                            final long[] started = {0};
                            sequence.run("start flywheel wait", () -> started[0] = System.nanoTime());
                            sequence.waitUntil("wait for flywheels", () -> flywheelsReady.getAsBoolean()
                                    || (System.nanoTime() - started[0]) / 1e9 >= limit);
                        },
                        new Actions.Param("timeout", "Time limit", "s", 2, 0, 10))
                .step("shoot", "shoot",
                        "Spin up if needed, wait until ready (2 s limit), then feed balls for a while.",
                        (sequence, p) -> {
                            final long[] started = {0};
                            sequence.run("flywheels on", () -> {
                                flywheelsOn.run();
                                started[0] = System.nanoTime();
                            });
                            sequence.waitUntil("wait for flywheels", () -> flywheelsReady.getAsBoolean()
                                    || (System.nanoTime() - started[0]) / 1e9 >= 2);
                            sequence.run("feed on", feedOn);
                            sequence.waitSeconds(p.getDouble("seconds"));
                            sequence.run("feed off", feedOff);
                        },
                        new Actions.Param("seconds", "Feed time", "s", 3.5, 0.1, 15))
                .step("turnTo", "turn to",
                        "Stand still and turn to face a field heading.",
                        (sequence, p) -> sequence.turnTo(Math.toRadians(p.getDouble("degrees"))),
                        new Actions.Param("degrees", "Heading", "°", 0, -360, 360));
    }
}
