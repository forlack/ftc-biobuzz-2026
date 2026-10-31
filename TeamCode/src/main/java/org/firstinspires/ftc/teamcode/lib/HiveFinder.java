package org.firstinspires.ftc.teamcode.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the hive to aim at from the tag clusters the camera sees, and says why the others were
 * left out. Plain Java: the robot (mechanisms/Vision, from the SDK's AprilTag detections) and
 * the simulator (its pretend camera) both use it, so the simulator tests the real choice.
 *
 * A hive counts when it is our alliance's color, the CELL asked for (ANY, SCORING: away from
 * the audience, or AUDIENCE), and up: its tags higher than {@code upMinHeight} inches above the
 * tiles (the manual's geometry: about 45 in for a CELL that is up, 30 in for one that is down).
 * Of those, the one with the most of its tags in view wins, then the nearest.
 */
public final class HiveFinder {

    /**
     * One tag cluster the camera sees. Made with {@link #fromCamera} from the SDK's numbers
     * (relative to the camera, which may be tilted up), turned into what aiming needs: the
     * distance across the ground, the turn to face it, and how high it is above the tiles.
     */
    public static final class Sighting {
        public final String name;          // such as "RED SCORING"
        public final int percentFound;     // of the cluster's tags
        public final double x;             // inches, + right of the camera (the SDK's x)
        public final double range;         // inches, across the ground
        public final double bearing;       // degrees, + means turn counter-clockwise (left)
        public final double height;        // inches above the tiles

        private Sighting(String name, int percentFound, double x, double range, double bearing, double height) {
            this.name = name;
            this.percentFound = percentFound;
            this.x = x;
            this.range = range;
            this.bearing = bearing;
            this.height = height;
        }

        /**
         * From the camera's x (right), y (ahead) and z (up), in inches, for a camera
         * {@code cameraHeight} inches above the tiles, tilted up by {@code pitchUpDegrees}.
         */
        public static Sighting fromCamera(String name, int percentFound, double x, double y, double z,
                                          double cameraHeight, double pitchUpDegrees) {
            double pitch = Math.toRadians(pitchUpDegrees);
            double ahead = y * Math.cos(pitch) - z * Math.sin(pitch);
            double up = y * Math.sin(pitch) + z * Math.cos(pitch);
            return new Sighting(name, percentFound, x, Math.hypot(x, ahead),
                    Math.toDegrees(Math.atan2(-x, ahead)), cameraHeight + up);
        }
    }

    private Sighting hive;
    private final List<String> ignored = new ArrayList<>();
    private String alliance = "RED";
    private String target = "ANY";

    /** Chooses from this frame's sightings. */
    public void pick(List<Sighting> sightings, boolean blue, String hiveTarget, double upMinHeight) {
        alliance = blue ? "BLUE" : "RED";
        target = hiveTarget.trim().toUpperCase(Locale.US);
        hive = null;
        ignored.clear();
        for (Sighting sighting : sightings) {
            String why = whyIgnored(sighting, upMinHeight);
            if (why != null) {
                ignored.add(sighting.name + " (" + why + ")");
                continue;
            }
            if (hive == null) {
                hive = sighting;
                continue;
            }
            boolean better = sighting.percentFound > hive.percentFound
                    || (sighting.percentFound == hive.percentFound && sighting.range < hive.range);
            if (better) {
                ignored.add(hive.name + " (another one is closer or clearer)");
                hive = sighting;
            } else {
                ignored.add(sighting.name + " (another one is closer or clearer)");
            }
        }
    }

    private String whyIgnored(Sighting sighting, double upMinHeight) {
        String name = sighting.name.toUpperCase(Locale.US);
        if (!name.startsWith(alliance)) {
            return "other alliance";
        }
        if (!target.equals("ANY") && !name.endsWith(target)) {
            return "not " + target;
        }
        if (sighting.height < upMinHeight) {
            return String.format(Locale.US, "down: %.1f in high < HIVE_UP_MIN_HEIGHT_IN", sighting.height);
        }
        return null;
    }

    /** The hive to aim at, or null when none qualifies. */
    public Sighting hive() {
        return hive;
    }

    /** The "Hive" telemetry line. */
    public String hiveLine() {
        if (hive == null) {
            return "not seen (" + alliance + ", " + target + ")";
        }
        return String.format(Locale.US, "%s · %.1f in away · angle %+.1f° · %.1f in high · %d%% of its tags",
                hive.name, hive.range, hive.bearing, hive.height, hive.percentFound);
    }

    /** The Driver Station's short "Hive" line: which, how far, the angle (+ = turn left). */
    public String shortLine() {
        if (hive == null) {
            return "not seen";
        }
        return String.format(Locale.US, "%s · %.1f in · angle %+.1f°", hive.name, hive.range, hive.bearing);
    }

    /** The "Hive ignored" telemetry line, or "" when nothing was ignored. */
    public String ignoredLine() {
        StringBuilder line = new StringBuilder();
        for (String item : ignored) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(item);
        }
        return line.toString();
    }
}
