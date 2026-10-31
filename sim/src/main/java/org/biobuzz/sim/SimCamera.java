package org.biobuzz.sim;

import org.biobuzz.sim.mirror.Vision;
import org.firstinspires.ftc.teamcode.lib.BiobuzzField;
import org.firstinspires.ftc.teamcode.lib.HiveFinder;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * The simulator's pretend webcam: what a camera on the simulated robot would see of the HIVE
 * tags, from the robot's pose, the camera's mount (the Vision settings, the same as the robot's)
 * and the field geometry from the manual (lib/BiobuzzField). It reports each cluster the way the
 * SDK does (x right, y ahead, z up of the camera), so the robot's own HiveFinder makes the
 * choice. {@link #picture} draws what it would see (the CELLS' undersides and the tags, from the
 * same view) for the Live tab's Camera card.
 *
 * A tag counts as seen when it is in the camera's view, close enough, and facing the camera
 * enough for the SDK to read it; a cluster is seen with any of its four tags.
 */
final class SimCamera {
    /** Camera view, degrees (a 640x480 webcam). */
    static final double FOV_WIDE = 60, FOV_TALL = 46;
    /** Farther than this, or more side-on than this, and a 3.25 in tag is not read. */
    static final double MAX_DISTANCE = 130, MAX_TAG_ANGLE = 70;

    private final Random noise = new Random(7);

    /** Where the lens is and which way its axes point, for a robot pose. */
    private static final class View {
        final double[] lens, right, forward, up;

        View(double robotX, double robotY, double heading) {
            double pitch = Math.toRadians(Vision.CAMERA_PITCH_UP_DEG);
            double[] ahead = {Math.cos(heading), Math.sin(heading), 0};
            double[] left = {-Math.sin(heading), Math.cos(heading), 0};
            lens = new double[]{
                    robotX + ahead[0] * Vision.CAMERA_FORWARD_IN + left[0] * Vision.CAMERA_LEFT_IN,
                    robotY + ahead[1] * Vision.CAMERA_FORWARD_IN + left[1] * Vision.CAMERA_LEFT_IN,
                    Vision.CAMERA_HEIGHT_IN};
            right = new double[]{-left[0], -left[1], 0};
            forward = new double[]{ahead[0] * Math.cos(pitch), ahead[1] * Math.cos(pitch), Math.sin(pitch)};
            up = new double[]{-ahead[0] * Math.sin(pitch), -ahead[1] * Math.sin(pitch), Math.cos(pitch)};
        }
    }

    /** What the camera sees with the robot at x, y (inches) facing heading (radians). */
    List<HiveFinder.Sighting> look(double robotX, double robotY, double heading, boolean redScoringUp, boolean blueScoringUp) {
        View view = new View(robotX, robotY, heading);
        List<HiveFinder.Sighting> sightings = new ArrayList<>();
        for (BiobuzzField.Cluster cluster : BiobuzzField.clusters(redScoringUp, blueScoringUp)) {
            int seen = 0;
            for (double[] tag : cluster.tags) {
                if (readable(tag, cluster.facing, view.lens, view.right, view.forward, view.up)) {
                    seen++;
                }
            }
            if (seen == 0) {
                continue;
            }
            double[] d = minus(cluster.center, view.lens);
            double x = dot(d, view.right) + noise.nextGaussian() * 0.2;
            double y = dot(d, view.forward) + noise.nextGaussian() * 0.3;
            double z = dot(d, view.up) + noise.nextGaussian() * 0.2;
            sightings.add(HiveFinder.Sighting.fromCamera(cluster.name, seen * 100 / cluster.tags.length,
                    x, y, z, Vision.CAMERA_HEIGHT_IN, Vision.CAMERA_PITCH_UP_DEG));
        }
        return sightings;
    }

    // ---- The picture -------------------------------------------------------------------------

    static final int WIDTH = 320, HEIGHT = 240;

    /**
     * What the camera would see, as a JPEG: the CELLS' undersides (red / blue), the tags
     * projected through the lens (green outline: readable; grey: too far or side-on), their
     * numbers, and a cross at the middle of the view.
     */
    byte[] picture(double robotX, double robotY, double heading, boolean redScoringUp, boolean blueScoringUp) {
        View view = new View(robotX, robotY, heading);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1b2027));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        // The tiles: everything below the horizon.
        double[] far = project(view, add(view.lens, view.forward[0] * 1000, view.forward[1] * 1000, 0));
        int horizon = far == null ? HEIGHT : (int) Math.round(far[1]);
        g.setColor(new Color(0x2a3038));
        g.fillRect(0, Math.max(0, Math.min(HEIGHT, horizon)), WIDTH, HEIGHT);

        // Farthest first, so nearer things are drawn on top.
        List<BiobuzzField.Cluster> clusters = BiobuzzField.clusters(redScoringUp, blueScoringUp);
        clusters.sort((a, b) -> Double.compare(distance(b.center, view.lens), distance(a.center, view.lens)));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        for (BiobuzzField.Cluster cluster : clusters) {
            Polygon cell = polygon(view, cluster.corners(cluster.center, BiobuzzField.CELL_WIDTH, BiobuzzField.CELL_DEPTH));
            if (cell == null) {
                continue;
            }
            boolean red = cluster.name.startsWith("RED");
            g.setColor(red ? new Color(0x7a2a2a) : new Color(0x2a3f7a));
            g.fillPolygon(cell);
            g.setColor(red ? new Color(0xe5534b) : new Color(0x539bf5));
            g.setStroke(new BasicStroke(1.5f));
            g.drawPolygon(cell);
            for (int i = 0; i < cluster.tags.length; i++) {
                Polygon tag = polygon(view, cluster.corners(cluster.tags[i], BiobuzzField.TAG_SIZE, BiobuzzField.TAG_SIZE));
                if (tag == null) {
                    continue;
                }
                boolean readable = readable(cluster.tags[i], cluster.facing, view.lens, view.right, view.forward, view.up);
                g.setColor(Color.WHITE);
                g.fillPolygon(grow(tag, 1.18));
                g.setColor(Color.BLACK);
                g.fillPolygon(tag);
                g.setColor(readable ? new Color(0x3fb950) : new Color(0x8b949e));
                g.setStroke(new BasicStroke(readable ? 2f : 1f));
                g.drawPolygon(grow(tag, 1.18));
                double[] middle = project(view, cluster.tags[i]);
                if (middle != null) {
                    g.setColor(readable ? new Color(0x3fb950) : new Color(0x8b949e));
                    g.drawString(String.valueOf(cluster.firstId + i), (int) middle[0] - 6, (int) middle[1] + 3);
                }
            }
        }
        // Middle of the view, and a note that this is drawn, not a camera.
        g.setColor(new Color(0xf5b301));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(WIDTH / 2 - 6, HEIGHT / 2, WIDTH / 2 + 6, HEIGHT / 2);
        g.drawLine(WIDTH / 2, HEIGHT / 2 - 6, WIDTH / 2, HEIGHT / 2 + 6);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString("simulated camera", 6, HEIGHT - 6);
        g.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", jpeg);
        } catch (IOException error) {
            return null;
        }
        return jpeg.toByteArray();
    }

    /** A point on the picture (pixels), or null when it is behind the lens. */
    private static double[] project(View view, double[] point) {
        double[] d = minus(point, view.lens);
        double ahead = dot(d, view.forward);
        if (ahead < 1) {
            return null;
        }
        double focalWide = (WIDTH / 2.0) / Math.tan(Math.toRadians(FOV_WIDE / 2));
        double focalTall = (HEIGHT / 2.0) / Math.tan(Math.toRadians(FOV_TALL / 2));
        return new double[]{WIDTH / 2.0 + focalWide * dot(d, view.right) / ahead,
                HEIGHT / 2.0 - focalTall * dot(d, view.up) / ahead};
    }

    private static Polygon polygon(View view, double[][] corners) {
        Polygon polygon = new Polygon();
        for (double[] corner : corners) {
            double[] p = project(view, corner);
            if (p == null || Math.abs(p[0]) > 5000 || Math.abs(p[1]) > 5000) {
                return null;
            }
            polygon.addPoint((int) Math.round(p[0]), (int) Math.round(p[1]));
        }
        return polygon;
    }

    /** The polygon scaled about its middle (a tag's white border). */
    private static Polygon grow(Polygon polygon, double factor) {
        double cx = 0, cy = 0;
        for (int i = 0; i < polygon.npoints; i++) {
            cx += polygon.xpoints[i];
            cy += polygon.ypoints[i];
        }
        cx /= polygon.npoints;
        cy /= polygon.npoints;
        Polygon grown = new Polygon();
        for (int i = 0; i < polygon.npoints; i++) {
            grown.addPoint((int) Math.round(cx + (polygon.xpoints[i] - cx) * factor),
                    (int) Math.round(cy + (polygon.ypoints[i] - cy) * factor));
        }
        return grown;
    }

    private static double[] add(double[] a, double x, double y, double z) {
        return new double[]{a[0] + x, a[1] + y, a[2] + z};
    }

    private static double distance(double[] a, double[] b) {
        double[] d = minus(a, b);
        return Math.sqrt(dot(d, d));
    }

    private static boolean readable(double[] tag, double[] facing, double[] lens,
                                    double[] right, double[] forward, double[] up) {
        double[] d = minus(tag, lens);
        double distance = Math.sqrt(dot(d, d));
        double ahead = dot(d, forward);
        if (ahead <= 0 || distance > MAX_DISTANCE) {
            return false;
        }
        if (Math.abs(Math.toDegrees(Math.atan2(dot(d, right), ahead))) > FOV_WIDE / 2
                || Math.abs(Math.toDegrees(Math.atan2(dot(d, up), ahead))) > FOV_TALL / 2) {
            return false;
        }
        // The tag must face the camera: angle between its face and the line to the lens.
        double facingCamera = -dot(d, facing) / distance;
        return facingCamera > Math.cos(Math.toRadians(MAX_TAG_ANGLE));
    }

    private static double[] minus(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
