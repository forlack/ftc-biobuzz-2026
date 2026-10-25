package org.firstinspires.ftc.teamcode.dashboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live telemetry for the Total Chaos Dashboard.
 *
 * During a loop, call {@link #put} for each value; at the end of the loop call
 * {@link #frame}. Numbers become chartable series; anything else shows as text. Frames are
 * kept in a short ring buffer that the dashboard server streams to browsers, so every loop
 * is charted, not just the ones that happened to be sampled.
 *
 * Cheap and safe when no dashboard is running: it only fills a bounded buffer.
 *
 * Every frame also gets {@value #COST_KEY}: the time this loop spent inside Dash (put, pose
 * and frame, including waiting for the server), i.e. what the dashboard adds to the loop.
 */
public final class Dash {
    private Dash() { }

    /**
     * About 10 s at 100 loops per second, in RAM (~2 MB; the Control Hub has 1 GB in all).
     * Browsers keep their own chart history; the server only ever sends a viewer the last 400.
     */
    private static final int CAPACITY = 1000;
    private static final long START = System.nanoTime();

    private static final Object LOCK = new Object();
    private static Map<String, Double> numbers = new LinkedHashMap<>();
    private static Map<String, String> text = new LinkedHashMap<>();
    private static double[] pose;
    private static final ArrayDeque<Frame> FRAMES = new ArrayDeque<>();
    private static long nextSeq = 1;
    /** Nanoseconds spent inside Dash since the last frame. */
    private static long spentNanos;

    /** Added to every frame: milliseconds this loop spent in the dashboard's code. */
    public static final String COST_KEY = "Dashboard ms";

    /** One loop's worth of values. */
    public static final class Frame {
        public final long seq;
        public final double t;
        public final Map<String, Double> numbers;
        public final Map<String, String> text;
        /** x, y (inches) and heading (radians), or null. */
        public final double[] pose;

        Frame(long seq, double t, Map<String, Double> numbers, Map<String, String> text, double[] pose) {
            this.seq = seq;
            this.t = t;
            this.numbers = numbers;
            this.text = text;
            this.pose = pose;
        }
    }

    /** Records a value for this loop. Numbers are charted; anything else is shown as text. */
    public static void put(String key, Object value) {
        long start = System.nanoTime();
        synchronized (LOCK) {
            store(key, value);
            spentNanos += System.nanoTime() - start;
        }
    }

    /** Call holding LOCK. */
    private static void store(String key, Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number)) {
                numbers.put(key, number);
                text.remove(key);
                return;
            }
        }
        if (value instanceof Boolean) {
            numbers.put(key, ((Boolean) value) ? 1.0 : 0.0);
            text.remove(key);
            return;
        }
        text.put(key, String.valueOf(value));
        numbers.remove(key);
    }

    /** Where the robot is on the field, for the dashboard's field view. */
    public static void pose(double x, double y, double headingRadians) {
        long start = System.nanoTime();
        synchronized (LOCK) {
            pose = new double[]{x, y, headingRadians};
            spentNanos += System.nanoTime() - start;
        }
    }

    /** Ends this loop: everything put since the last frame becomes one frame. */
    public static void frame() {
        long start = System.nanoTime();
        synchronized (LOCK) {
            numbers.put(COST_KEY, (spentNanos + System.nanoTime() - start) / 1e6);
            text.remove(COST_KEY);
            FRAMES.addLast(new Frame(nextSeq++, (System.nanoTime() - START) / 1e9, numbers, text, pose));
            while (FRAMES.size() > CAPACITY) {
                FRAMES.removeFirst();
            }
            numbers = new LinkedHashMap<>();
            text = new LinkedHashMap<>();
            pose = null;
            // The rest of this call counts toward the next loop.
            spentNanos = System.nanoTime() - start;
        }
    }

    /** Frames newer than {@code afterSeq}, oldest first, at most {@code limit}. */
    public static List<Frame> since(long afterSeq, int limit) {
        List<Frame> result = new ArrayList<>();
        synchronized (LOCK) {
            for (Frame frame : FRAMES) {
                if (frame.seq > afterSeq) {
                    result.add(frame);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** The newest value of a number sent in the last {@code maxAgeSeconds}, or null. */
    public static Double latestNumber(String key, double maxAgeSeconds) {
        double oldest = now() - maxAgeSeconds;
        synchronized (LOCK) {
            java.util.Iterator<Frame> newestFirst = FRAMES.descendingIterator();
            while (newestFirst.hasNext()) {
                Frame frame = newestFirst.next();
                if (frame.t < oldest) return null;
                Double value = frame.numbers.get(key);
                if (value != null) return value;
            }
        }
        return null;
    }

    /** Sequence number of the newest frame, 0 if none. */
    public static long latestSeq() {
        synchronized (LOCK) {
            return FRAMES.isEmpty() ? 0 : FRAMES.getLast().seq;
        }
    }

    /** Seconds since the app started, on the same clock as frame times. */
    public static double now() {
        return (System.nanoTime() - START) / 1e9;
    }
}
