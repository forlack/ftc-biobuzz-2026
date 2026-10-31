package org.firstinspires.ftc.teamcode.dashboard;

/**
 * The latest camera picture for the dashboard's Live tab, as a JPEG. The vision code publishes
 * pictures (Vision.CAMERA_FEED_FPS a second), but only while a browser has asked for one in the
 * last 2 s, so nobody watching costs nothing. A browser asks for "the next picture after number
 * N" and the server answers as soon as it exists (waiting up to half a second), so a picture is
 * shown the moment it is made. Plain Java (the simulator draws its pictures).
 */
public final class CameraFeed {
    private CameraFeed() {
    }

    /** A picture and its number (they count up from 1). */
    static final class Picture {
        final byte[] jpeg;
        final long number;

        Picture(byte[] jpeg, long number) {
            this.jpeg = jpeg;
            this.number = number;
        }
    }

    private static final Object LOCK = new Object();
    private static Picture latest;
    private static long publishedNanos;
    private static volatile long askedNanos;

    /** For the vision code: does anyone want pictures right now? */
    public static boolean wanted() {
        return askedNanos != 0 && System.nanoTime() - askedNanos < 2_000_000_000L;
    }

    /** For the vision code: the newest picture. */
    public static void publish(byte[] jpeg) {
        synchronized (LOCK) {
            latest = new Picture(jpeg, latest == null ? 1 : latest.number + 1);
            publishedNanos = System.nanoTime();
            LOCK.notifyAll();
        }
    }

    /**
     * For the server: the first picture newer than {@code after}, waiting up to
     * {@code waitMillis} for it; the newest one if none comes; null when there has been none in
     * the last 2 s.
     */
    static Picture next(long after, long waitMillis) throws InterruptedException {
        askedNanos = System.nanoTime();
        long until = System.nanoTime() + waitMillis * 1_000_000L;
        synchronized (LOCK) {
            while ((latest == null || latest.number <= after) && System.nanoTime() < until) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    break;
                }
                LOCK.wait(left);
            }
            if (latest == null || System.nanoTime() - publishedNanos > 2_000_000_000L) {
                return null;
            }
            return latest;
        }
    }
}
