package org.firstinspires.ftc.teamcode.lib;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the BIOBUZZ AprilTags are, from the Competition Manual (Section 9, TU06), in the
 * dashboard's field coordinates: inches, x to the right, y away from the audience (the audience
 * is at y = 0), z up from the tiles.
 *
 * The HIVE structure is in the middle of the field: a red HIVE (x 59.25) and a blue HIVE
 * (x 84.75), 25.5 in center to center, each on a pivot 43.95 in above the tiles at y = 72. Each
 * HIVE is two CELLS 18.84 in apart (each 12.04 in deep) and tips 30 degrees so that one CELL is
 * up. Each CELL has a cluster of four 3.25 in tags on its bottom face, facing down: the SCORING
 * clusters (red 30-33, blue 42-45) are on the CELL away from the audience, the AUDIENCE ones
 * (red 34-37, blue 38-41) on the audience side. Plain Java: the simulator's pretend camera
 * uses it (and the robot can, to check where it is).
 */
public final class BiobuzzField {
    private BiobuzzField() {
    }

    public static final double RED_HIVE_X = 59.25;
    public static final double BLUE_HIVE_X = 84.75;
    public static final double HIVE_Y = 72;
    public static final double PIVOT_HEIGHT = 43.95;
    /** Pivot to the middle of a CELL, along the HIVE: half the gap plus half a CELL. */
    public static final double PIVOT_TO_CELL = 18.84 / 2 + 12.04 / 2;
    /** Middle of a CELL to its bottom face (about half its height; 30.6 in "bottom of HIVE"). */
    public static final double CELL_TO_BOTTOM = 7;
    public static final double TILT_DEGREES = 30;
    public static final double TAG_SIZE = 3.25;
    /** Each tag's center across the cluster, from the SDK's BIOBUZZ library. */
    public static final double[] TAG_OFFSETS = {-6.5, -2.75, 2.75, 6.5};

    /** A CELL's bottom face, about 20 in across the field by 12 in along the HIVE. */
    public static final double CELL_WIDTH = 20, CELL_DEPTH = 12;

    /** One tag cluster: the middle of its tags, which way they face, and each tag's center. */
    public static final class Cluster {
        public final String name;          // "RED SCORING", ...
        public final int firstId;          // its tags are firstId .. firstId + 3
        public final double[] center;      // x, y, z
        public final double[] facing;      // unit vector the tags face (downward)
        public final double[][] tags;      // the four tag centers
        /** Directions in the face: across the field (x) and along the HIVE. */
        public final double[] across, along;

        Cluster(String name, int firstId, double[] center, double[] facing, double[][] tags) {
            this.name = name;
            this.firstId = firstId;
            this.center = center;
            this.facing = facing;
            this.tags = tags;
            this.across = new double[]{1, 0, 0};
            this.along = new double[]{0, facing[2], -facing[1]};
        }

        /** The four corners of a square of {@code width} by {@code depth} centered on {@code middle}. */
        public double[][] corners(double[] middle, double width, double depth) {
            double[][] corners = new double[4][];
            double[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++) {
                corners[i] = new double[3];
                for (int k = 0; k < 3; k++) {
                    corners[i][k] = middle[k] + signs[i][0] * width / 2 * across[k] + signs[i][1] * depth / 2 * along[k];
                }
            }
            return corners;
        }
    }

    /**
     * The four clusters, for which CELL of each HIVE is up ({@code redScoringUp}: the red HIVE's
     * SCORING CELL, away from the audience, is up; else its AUDIENCE CELL is).
     */
    public static List<Cluster> clusters(boolean redScoringUp, boolean blueScoringUp) {
        List<Cluster> list = new ArrayList<>();
        addHive(list, "RED", RED_HIVE_X, redScoringUp);
        addHive(list, "BLUE", BLUE_HIVE_X, blueScoringUp);
        return list;
    }

    private static void addHive(List<Cluster> list, String color, double x, boolean scoringUp) {
        double tilt = Math.toRadians(TILT_DEGREES);
        // Along the HIVE, from the pivot toward the SCORING end (away from the audience).
        double alongY = Math.cos(tilt);
        double alongZ = scoringUp ? Math.sin(tilt) : -Math.sin(tilt);
        // The bottom faces (the whole HIVE turns together): square to the HIVE, pointing down.
        double[] facing = {0, alongZ, -alongY};
        // Tag numbers from the manual / the SDK's BIOBUZZ library.
        addCell(list, color + " SCORING", color.equals("RED") ? 30 : 42, x, +1, alongY, alongZ, facing);
        addCell(list, color + " AUDIENCE", color.equals("RED") ? 34 : 38, x, -1, alongY, alongZ, facing);
    }

    private static void addCell(List<Cluster> list, String name, int firstId, double x, int end,
                                double alongY, double alongZ, double[] facing) {
        double cy = HIVE_Y + end * PIVOT_TO_CELL * alongY + facing[1] * CELL_TO_BOTTOM;
        double cz = PIVOT_HEIGHT + end * PIVOT_TO_CELL * alongZ + facing[2] * CELL_TO_BOTTOM;
        double[][] tags = new double[TAG_OFFSETS.length][];
        for (int i = 0; i < TAG_OFFSETS.length; i++) {
            tags[i] = new double[]{x + TAG_OFFSETS[i], cy, cz};
        }
        list.add(new Cluster(name, firstId, new double[]{x, cy, cz}, facing, tags));
    }
}
