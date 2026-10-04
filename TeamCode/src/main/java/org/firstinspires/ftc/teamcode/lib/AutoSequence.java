package org.firstinspires.ftc.teamcode.lib;

import com.pedropathing.follower.Follower;
import com.pedropathing.paths.Path;
import com.qualcomm.robotcore.util.ElapsedTime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A list of things the robot does one after another during autonomous.
 *
 * Nothing here ever waits. update() is called once per loop and advances when the current
 * step reports it is finished, so the loop keeps running at full speed the whole time.
 *
 * Build it once in init(), then call update() every loop:
 *
 * <pre>
 * auto = new AutoSequence(follower)
 *         .follow(toBasket).at(0.7, () -> arm.setPower(1))   // arm starts before arriving
 *         .run(() -> claw.setPosition(OPEN))
 *         .waitSeconds(0.4)
 *         .run(() -> claw.setPosition(CLOSED))
 *         .follow(backToStart);
 * </pre>
 */
public class AutoSequence {

    private final Follower follower;
    private final List<Step> steps = new ArrayList<>();
    private final ElapsedTime timer = new ElapsedTime();
    private int index;
    private boolean startedCurrent;

    public AutoSequence(Follower follower) {
        this.follower = follower;
    }

    // ---- Building ----------------------------------------------------------

    /** Drive a path, and move on when the follower says it is finished. */
    public AutoSequence follow(Path path) {
        steps.add(new FollowStep(path));
        return this;
    }

    /** Do something once and move straight on. Use for setting a motor or servo. */
    public AutoSequence run(Runnable action) {
        steps.add(new RunStep(action));
        return this;
    }

    /** Sit still for a while. Use sparingly -- a waitUntil on a sensor is usually better. */
    public AutoSequence waitSeconds(double seconds) {
        steps.add(new WaitStep(seconds));
        return this;
    }

    /** Wait for something to become true, such as an arm reaching its target. */
    public AutoSequence waitUntil(BooleanSupplier done) {
        steps.add(new UntilStep(done));
        return this;
    }

    /**
     * Fire an action PART WAY THROUGH the path you just added, instead of after it. Use it to
     * spin up an intake or raise an arm while the robot is still driving.
     *
     * @param completion how far along the path to fire, 0 to 1
     * @param action     runs once
     */
    public AutoSequence at(double completion, Runnable action) {
        if (steps.isEmpty() || !(steps.get(steps.size() - 1) instanceof FollowStep)) {
            throw new IllegalStateException("at() must come straight after follow()");
        }
        ((FollowStep) steps.get(steps.size() - 1)).markers.add(new Marker(completion, action));
        return this;
    }

    // ---- Running ----------------------------------------------------------

    /** Call once per loop, after follower.update(). */
    public void update() {
        if (finished()) {
            return;
        }
        Step step = steps.get(index);
        if (!startedCurrent) {
            step.start();
            startedCurrent = true;
        }
        if (step.isDone()) {
            step.end();
            index++;
            startedCurrent = false;
        }
    }

    public boolean finished() {
        return index >= steps.size();
    }

    /** 1-based, for telemetry. */
    public int stepNumber() {
        return Math.min(index + 1, steps.size());
    }

    public int stepCount() {
        return steps.size();
    }

    public String stepName() {
        return finished() ? "done" : steps.get(index).name();
    }

    // ---- Steps ------------------------------------------------------------

    private interface Step {
        void start();
        boolean isDone();
        default void end() { }
        String name();
    }

    private static class Marker {
        final double completion;
        final Runnable action;
        boolean fired;

        Marker(double completion, Runnable action) {
            this.completion = completion;
            this.action = action;
        }
    }

    private class FollowStep implements Step {
        private final Path path;
        private final List<Marker> markers = new ArrayList<>();

        FollowStep(Path path) {
            this.path = path;
        }

        public void start() {
            for (Marker m : markers) {
                m.fired = false;
            }
            follower.follow(path);
        }

        public boolean isDone() {
            double progress = follower.completion();
            for (Marker m : markers) {
                if (!m.fired && progress >= m.completion) {
                    m.fired = true;
                    m.action.run();
                }
            }
            return !follower.isBusy();
        }

        /**
         * Any marker the path finished without reaching still runs here. Skipping it silently
         * would leave a mechanism in the wrong state and be very hard to debug.
         */
        public void end() {
            for (Marker m : markers) {
                if (!m.fired) {
                    m.fired = true;
                    m.action.run();
                }
            }
        }

        public String name() {
            return "follow path";
        }
    }

    private static class RunStep implements Step {
        private final Runnable action;

        RunStep(Runnable action) {
            this.action = action;
        }

        public void start() {
            action.run();
        }

        public boolean isDone() {
            return true;
        }

        public String name() {
            return "action";
        }
    }

    private class WaitStep implements Step {
        private final double seconds;

        WaitStep(double seconds) {
            this.seconds = seconds;
        }

        public void start() {
            timer.reset();
        }

        public boolean isDone() {
            return timer.seconds() >= seconds;
        }

        public String name() {
            return "wait " + seconds + "s";
        }
    }

    private static class UntilStep implements Step {
        private final BooleanSupplier done;

        UntilStep(BooleanSupplier done) {
            this.done = done;
        }

        public void start() { }

        public boolean isDone() {
            return done.getAsBoolean();
        }

        public String name() {
            return "wait until";
        }
    }
}
