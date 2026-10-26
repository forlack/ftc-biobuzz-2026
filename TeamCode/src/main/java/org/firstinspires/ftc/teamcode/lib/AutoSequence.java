package org.firstinspires.ftc.teamcode.lib;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import com.pedropathing.utils.Angle;

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

    /** How close the heading must be before aimAt()/turnTo() counts as finished, in degrees. */
    public static double AIM_TOLERANCE_DEGREES = 2.0;

    private final Follower follower;
    private final List<Step> steps = new ArrayList<>();
    private long waitStartNanos;   // plain Java clock, so the desktop simulator can run this too
    private int index;
    private boolean startedCurrent;

    public AutoSequence(Follower follower) {
        this.follower = follower;
    }

    // ---- Building ----------------------------------------------------------

    /** Drive a path, and move on when the follower says it is finished. */
    public AutoSequence follow(Path path) {
        steps.add(new FollowStep(path, null));
        return this;
    }

    /**
     * Drive a path made of several segments followed as one (a Pedro compound path).
     * {@code segmentLengths} (inches, in order) let at() markers mean a fraction of the whole
     * path: Pedro's own completion starts again at 0 on each segment.
     */
    public AutoSequence follow(Path path, double[] segmentLengths) {
        steps.add(new FollowStep(path, segmentLengths));
        return this;
    }

    /** Do something once and move straight on. Use for setting a motor or servo. */
    public AutoSequence run(Runnable action) {
        return run("action", action);
    }

    /** Same, with a name for telemetry, such as "intake on". */
    public AutoSequence run(String name, Runnable action) {
        steps.add(new RunStep(name, action));
        return this;
    }

    /** Sit still for a while. Use sparingly -- a waitUntil on a sensor is usually better. */
    public AutoSequence waitSeconds(double seconds) {
        steps.add(new WaitStep(seconds));
        return this;
    }

    /**
     * Stand still and turn to face a point on the field, then move on.
     *
     * For aiming while driving, put it on the path instead -- no step needed:
     * {@code Paths.line(a, b).facingPoint(GOAL)}
     *
     * @param target where to point at; only its x and y are used
     */
    public AutoSequence aimAt(Pose target) {
        steps.add(new AimStep(target));
        return this;
    }

    /** Stand still and turn to an absolute field heading, in radians. */
    public AutoSequence turnTo(double headingRadians) {
        steps.add(new AimStep(headingRadians));
        return this;
    }

    /** Wait for something to become true, such as an arm reaching its target. */
    public AutoSequence waitUntil(BooleanSupplier done) {
        return waitUntil("wait until", done);
    }

    /** Same, with a name for telemetry. */
    public AutoSequence waitUntil(String name, BooleanSupplier done) {
        steps.add(new UntilStep(name, done));
        return this;
    }

    /** Names the follow step just added, for telemetry, such as "follow path1". */
    public AutoSequence named(String name) {
        if (steps.isEmpty() || !(steps.get(steps.size() - 1) instanceof FollowStep)) {
            throw new IllegalStateException("named() must come straight after follow()");
        }
        ((FollowStep) steps.get(steps.size() - 1)).name = name;
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
        private final double[] lengths;   // per segment, or null for one segment
        private final List<Marker> markers = new ArrayList<>();
        private String name = "follow path";
        private boolean fresh;
        private int segment;
        private Object currentSegment;
        private double progress;

        FollowStep(Path path, double[] segmentLengths) {
            this.path = path;
            this.lengths = segmentLengths != null && segmentLengths.length > 1 ? segmentLengths : null;
        }

        public void start() {
            for (Marker m : markers) {
                m.fired = false;
            }
            follower.follow(path);
            fresh = true;
            segment = 0;
            currentSegment = null;
            progress = 0;
        }

        /** How far along the whole path, 0 to 1 (never goes back). */
        private double progress() {
            double now;
            if (lengths == null) {
                now = follower.completion();
            } else {
                Object current = follower.currentSegment();
                if (currentSegment == null) {
                    currentSegment = current;
                } else if (current != currentSegment) {   // Pedro moved on to the next segment
                    currentSegment = current;
                    segment = Math.min(segment + 1, lengths.length - 1);
                }
                double before = 0, total = 0;
                for (int i = 0; i < lengths.length; i++) {
                    if (i < segment) before += lengths[i];
                    total += lengths[i];
                }
                double done = Math.max(0, Math.min(lengths[segment], lengths[segment] - follower.remainingDistance()));
                now = total > 0 ? (before + done) / total : 1;
            }
            progress = Math.max(progress, now);
            return progress;
        }

        public boolean isDone() {
            // The first call comes straight after start(), before the follower has run the new
            // path: its completion is still the last path's (1.0), which would fire every marker.
            if (fresh) {
                fresh = false;
                return false;
            }
            double progress = progress();
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
            return name;
        }
    }

    /** Holds position while turning. Target heading is resolved when the step starts. */
    private class AimStep implements Step {
        private final Pose target;          // null when a fixed heading was given
        private final double fixedHeading;
        private double desired;

        AimStep(Pose target) {
            this.target = target;
            this.fixedHeading = 0;
        }

        AimStep(double headingRadians) {
            this.target = null;
            this.fixedHeading = headingRadians;
        }

        public void start() {
            Pose here = follower.pose();
            desired = target == null
                    ? fixedHeading
                    : Math.atan2(target.y() - here.y(), target.x() - here.x());
            follower.hold(new Pose(here.x(), here.y(), desired));
        }

        public boolean isDone() {
            double off = Math.abs(Angle.error(follower.pose().heading(), desired));
            return off < Math.toRadians(AIM_TOLERANCE_DEGREES);
        }

        public String name() {
            return target == null ? "turn to heading" : "aim at point";
        }
    }

    private static class RunStep implements Step {
        private final String name;
        private final Runnable action;

        RunStep(String name, Runnable action) {
            this.name = name;
            this.action = action;
        }

        public void start() {
            action.run();
        }

        public boolean isDone() {
            return true;
        }

        public String name() {
            return name;
        }
    }

    private class WaitStep implements Step {
        private final double seconds;

        WaitStep(double seconds) {
            this.seconds = seconds;
        }

        public void start() {
            waitStartNanos = System.nanoTime();
        }

        public boolean isDone() {
            return (System.nanoTime() - waitStartNanos) / 1e9 >= seconds;
        }

        public String name() {
            return "wait " + seconds + "s";
        }
    }

    private static class UntilStep implements Step {
        private final String name;
        private final BooleanSupplier done;

        UntilStep(String name, BooleanSupplier done) {
            this.name = name;
            this.done = done;
        }

        public void start() { }

        public boolean isDone() {
            return done.getAsBoolean();
        }

        public String name() {
            return name;
        }
    }
}
