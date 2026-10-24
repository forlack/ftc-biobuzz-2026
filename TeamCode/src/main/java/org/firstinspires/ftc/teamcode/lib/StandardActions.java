package org.firstinspires.ftc.teamcode.lib;

/**
 * Our robot's autonomous actions. Add a new one here and it appears in the path editor.
 *
 * Every action only talks to {@link Mechanisms}, so it runs the same on the robot and in the
 * desktop simulator.
 */
public final class StandardActions {
    private StandardActions() { }

    public static Actions create(final Mechanisms robot) {
        return new Actions()
                .step("wait", "wait", "Do nothing for a while.",
                        (sequence, p) -> sequence.waitSeconds(p.getDouble("seconds")),
                        new Actions.Param("seconds", "Seconds", "s", 0.5, 0, 30))
                .instant("intakeOn", "intake on", "Start the intake.",
                        p -> () -> robot.intake(true))
                .instant("intakeOff", "intake off", "Stop the intake.",
                        p -> () -> robot.intake(false))
                .instant("flywheelsOn", "flywheels on", "Spin both flywheels up to their target RPMs.",
                        p -> () -> robot.flywheels(true))
                .instant("flywheelsOff", "flywheels off", "Stop both flywheels.",
                        p -> () -> robot.flywheels(false))
                .instant("deployStinger", "stinger down", "Lower the stinger.",
                        p -> () -> robot.stinger(true))
                .instant("stowStinger", "stinger up", "Raise the stinger.",
                        p -> () -> robot.stinger(false))
                .step("waitForFlywheels", "wait for flywheels",
                        "Wait until both flywheels are at speed, or the time limit runs out.",
                        (sequence, p) -> {
                            final double limit = p.getDouble("timeout");
                            final long[] started = {0};
                            sequence.run("start flywheel wait", () -> started[0] = System.nanoTime());
                            sequence.waitUntil("wait for flywheels", () -> robot.flywheelsReady()
                                    || (System.nanoTime() - started[0]) / 1e9 >= limit);
                        },
                        new Actions.Param("timeout", "Time limit", "s", 2, 0, 10))
                .step("shoot", "shoot",
                        "Spin up if needed, wait until ready (2 s limit), then feed balls for a while.",
                        (sequence, p) -> {
                            final long[] started = {0};
                            sequence.run("flywheels on", () -> {
                                robot.flywheels(true);
                                started[0] = System.nanoTime();
                            });
                            sequence.waitUntil("wait for flywheels", () -> robot.flywheelsReady()
                                    || (System.nanoTime() - started[0]) / 1e9 >= 2);
                            sequence.run("feed on", () -> robot.feed(true));
                            sequence.waitSeconds(p.getDouble("seconds"));
                            sequence.run("feed off", () -> robot.feed(false));
                        },
                        new Actions.Param("seconds", "Feed time", "s", 3.5, 0.1, 15))
                .step("turnTo", "turn to",
                        "Stand still and turn to face a field heading.",
                        (sequence, p) -> sequence.turnTo(Math.toRadians(p.getDouble("degrees"))),
                        new Actions.Param("degrees", "Heading", "°", 0, -360, 360));
    }
}
