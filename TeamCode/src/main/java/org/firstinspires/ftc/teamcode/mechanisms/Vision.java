package org.firstinspires.ftc.teamcode.mechanisms;

import android.graphics.Bitmap;
import android.util.Size;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.teamcode.Alliance;
import org.firstinspires.ftc.teamcode.dashboard.CameraFeed;
import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.HiveFinder;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The webcam ("Webcam 1" in the hardware map) and the hive's AprilTags.
 *
 * The FTC SDK knows the BIOBUZZ tags as four clusters of four (RED SCORING 30-33, RED AUDIENCE
 * 34-37, BLUE AUDIENCE 38-41, BLUE SCORING 42-45; one per CELL, on its underside, facing down)
 * and reports each cluster it sees relative to the camera. With the camera's mount below,
 * {@link HiveFinder} turns that into distance, turn and height, and picks the hive to aim at:
 * our alliance's color ({@link Alliance}), the CELL set in HIVE_TARGET, and up (higher than
 * HIVE_UP_MIN_HEIGHT_IN). The rest is ignored, and the telemetry says why. The simulator runs
 * the same choice with a pretend camera (lib/BiobuzzField).
 *
 * No webcam in the hardware map (or VISION_ON off): everything else still works, and the
 * telemetry says so. The dashboard's Live tab can show the camera picture (with the tags
 * outlined) while someone is watching it.
 */
@Configurable
public class Vision implements Mechanism {
    /** Off: the camera is not started (saves the Control Hub's time). */
    public static boolean VISION_ON = true;
    /** Camera pictures a second for the dashboard's Live tab, while someone watches it. */
    public static double CAMERA_FEED_FPS = 10;
    /**
     * Which of our HIVE's two CELLS to aim at: ANY (whichever is up), SCORING (away from the
     * audience) or AUDIENCE.
     */
    public static String HIVE_TARGET = "ANY";
    /**
     * A CELL whose tags are lower than this (inches above the tiles) is down and ignored. From
     * the manual: about 45 in when up, 30 in when down. Check `Hive height in` on the Live tab.
     */
    public static double HIVE_UP_MIN_HEIGHT_IN = 38;

    // Where the camera is on the robot (the simulator's camera uses these too).
    /** Lens ahead of the robot's center, inches. */
    public static double CAMERA_FORWARD_IN = 0;
    /** Lens left of the robot's center, inches. */
    public static double CAMERA_LEFT_IN = 0;
    /** Lens above the tiles, inches. */
    public static double CAMERA_HEIGHT_IN = 10;
    /** How far the camera is tilted up, degrees (the tags face down from about 45 in up). */
    public static double CAMERA_PITCH_UP_DEG = 30;

    public static final String CAMERA = "Webcam 1";

    /** Turns pictures into JPEGs for the dashboard, away from the camera's own thread. */
    private static final Executor PICTURES = Executors.newSingleThreadExecutor();

    private VisionPortal portal;
    private AprilTagProcessor tags;
    private String problem = "";

    private final HiveFinder finder = new HiveFinder();
    private final List<HiveFinder.Sighting> sightings = new ArrayList<>();

    private volatile boolean grabbing;
    private long lastGrab;

    public Vision(HardwareMap hardwareMap) {
        if (!VISION_ON) {
            problem = "off (Vision.VISION_ON)";
            return;
        }
        WebcamName camera = hardwareMap.tryGet(WebcamName.class, CAMERA);
        if (camera == null) {
            problem = "no \"" + CAMERA + "\" in the hardware map";
            return;
        }
        try {
            tags = new AprilTagProcessor.Builder()
                    .setTagLibrary(AprilTagGameDatabase.getBioBuzzTagLibrary())
                    .setOutputUnits(DistanceUnit.INCH, AngleUnit.DEGREES)
                    .build();
            portal = new VisionPortal.Builder()
                    .setCamera(camera)
                    .setCameraResolution(new Size(640, 480))
                    .addProcessor(tags)
                    .build();
        } catch (RuntimeException error) {
            // A camera that will not start must never stop the OpMode.
            tags = null;
            portal = null;
            problem = "the camera did not start: " + error.getMessage();
        }
    }

    /** The hive to aim at is in view (our color, the right kind, up). */
    @AutoFunction(name = "hiveSeen", label = "hive seen", help = "The camera sees our alliance's hive (up).")
    public boolean seen() {
        return finder.hive() != null;
    }

    /** The hive to aim at (distance, turn, height), or null when none is in view. */
    public HiveFinder.Sighting hive() {
        return finder.hive();
    }

    @Override
    public void update() {
        if (tags == null) {
            return;
        }
        pickHive(tags.getDetections());
        if (CameraFeed.wanted()) {
            grabPicture();
        }
    }

    private void pickHive(List<AprilTagDetection> detections) {
        sightings.clear();
        for (AprilTagDetection detection : detections) {
            if (detection instanceof AprilTagClusterDetection && detection.ftcPose != null) {
                AprilTagClusterDetection cluster = (AprilTagClusterDetection) detection;
                sightings.add(HiveFinder.Sighting.fromCamera(cluster.metadata.name, cluster.percentClusterFound,
                        cluster.ftcPose.x, cluster.ftcPose.y, cluster.ftcPose.z,
                        CAMERA_HEIGHT_IN, CAMERA_PITCH_UP_DEG));
            }
        }
        finder.pick(sightings, Alliance.isBlue(), HIVE_TARGET, HIVE_UP_MIN_HEIGHT_IN);
    }

    /** CAMERA_FEED_FPS pictures a second for the dashboard, scaled down and compressed. */
    private void grabPicture() {
        long now = System.nanoTime();
        long gap = (long) (1e9 / Math.max(1, CAMERA_FEED_FPS));
        if (grabbing || now - lastGrab < gap || portal.getCameraState() != VisionPortal.CameraState.STREAMING) {
            return;
        }
        grabbing = true;
        lastGrab = now;
        portal.getFrameBitmap(Continuation.create(PICTURES, new Consumer<Bitmap>() {
            @Override
            public void accept(Bitmap frame) {
                try {
                    Bitmap small = Bitmap.createScaledBitmap(frame, 320, 240, true);
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 60, jpeg);
                    if (small != frame) {
                        small.recycle();
                    }
                    CameraFeed.publish(jpeg.toByteArray());
                } finally {
                    grabbing = false;
                }
            }
        }));
    }

    @Override
    public void telemetry(Telemetry telemetry) {
        if (tags == null) {
            telemetry.addData("Hive", "no camera");
            Dash.put("Hive problem", problem);
            return;
        }
        telemetry.addData("Hive", finder.shortLine());
        Dash.put("Hive ignored", finder.ignoredLine());   // dashboard only
        HiveFinder.Sighting hive = finder.hive();
        Dash.put("Hive seen", hive != null);
        if (hive != null) {
            Dash.put("Hive range in", hive.range);
            Dash.put("Hive bearing deg", hive.bearing);
            Dash.put("Hive height in", hive.height);
        }
    }
}
