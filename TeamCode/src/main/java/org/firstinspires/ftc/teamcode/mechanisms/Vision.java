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
import org.firstinspires.ftc.teamcode.dashboard.RobotDiagnostics;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.HiveFinder;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
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
    /** Dashboard preview only. Off leaves AprilTag detection and aiming running. */
    public static boolean CAMERA_FEED_ON = false;
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

    // Aiming (lib/AimController): a PD controller on the hive's angle (+ = hive to the left).
    /** Turn stick per degree of angle. */
    public static double AIM_P = 0.02;
    /** Turn stick per degree-per-second the angle is closing: brakes the turn so it does not overshoot. */
    public static double AIM_D = 0;
    /** D is off within this many degrees of the target (camera noise there would twitch it). */
    public static double AIM_D_MIN_DEG = 1;
    /** The most turn stick aiming may use (0 to 1). */
    public static double AIM_MAX_TURN = 0.75;
    /** Within this many degrees counts as on target: aiming sends no turn there. */
    public static double AIM_TOLERANCE_DEG = 1;
    /** Positive aims further LEFT to correct shots landing right; degrees. */
    public static double AIM_LEFT_TRIM_DEG = 0;

    public static final String CAMERA = "Webcam 1";

    /** Turns pictures into JPEGs for the dashboard, away from the camera's own thread. */
    private static final Executor PICTURES = Executors.newSingleThreadExecutor();

    /*
     * One camera session at a time. At STOP the SDK closes the camera itself but never updates
     * the VisionPortal, whose state then says STREAMING forever; only VisionPortal.close() moves
     * it to CAMERA_DEVICE_CLOSED. So close() below does that, on its own thread (it can block
     * while the camera is still opening, and an OpMode stuck in STOP makes the SDK restart the
     * robot), and the next INIT opens a new session only once the last one says CLOSED.
     */
    private static final Object CAMERA_LOCK = new Object();
    private static VisionPortal lastPortal;
    private static boolean lastPortalClosing;
    /** The SDK makes one HardwareMap per robot start: a new one means the robot was restarted. */
    private static HardwareMap lastHardwareMap;

    /** No new frame for this long: the camera has stopped, so its last target is dropped. */
    private static final long FRAME_TIMEOUT_NANOS = 500_000_000L;

    private final HardwareMap hardwareMap;
    private volatile boolean closed;
    private volatile VisionPortal portal;
    private volatile AprilTagProcessor tags;
    private volatile String problem = "";
    private long lastFrameNanos;

    private final HiveFinder finder = new HiveFinder();
    private final List<HiveFinder.Sighting> sightings = new ArrayList<>();

    private volatile boolean grabbing;
    private long lastGrab;

    public Vision(HardwareMap hardwareMap) {
        this(hardwareMap, true);
    }

    public Vision(HardwareMap hardwareMap, boolean useCamera) {
        this.hardwareMap = hardwareMap;
        if (!useCamera) {
            problem = "off (not used by this OpMode)";
            return;
        }
        if (!VISION_ON) {
            problem = "off (Vision.VISION_ON)";
            return;
        }
        synchronized (CAMERA_LOCK) {
            if (lastHardwareMap != hardwareMap) {
                lastPortal = null;   // the robot restarted, and the old camera went with it
            }
            if (lastPortal != null && !waitForLastClose()) {
                problem = "the last camera session did not close (" + lastPortal.getCameraState()
                        + "). INIT again, or Restart Robot";
                RobotDiagnostics.warn("Camera", problem);
                return;
            }
            lastPortal = null;
            open();
            if (portal != null) {
                lastPortal = portal;
                lastPortalClosing = false;
                lastHardwareMap = hardwareMap;
            }
        }
    }

    /** Closes lastPortal if nobody has, and waits up to 2 s for it. True once it is CLOSED. */
    private static boolean waitForLastClose() {
        closeLast();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (lastPortal.getCameraState() != VisionPortal.CameraState.CAMERA_DEVICE_CLOSED) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Asks lastPortal to close, once, on its own thread. Call holding CAMERA_LOCK. */
    private static void closeLast() {
        if (lastPortalClosing) {
            return;
        }
        lastPortalClosing = true;
        VisionPortal closing = lastPortal;
        Thread thread = new Thread(() -> {
            try {
                closing.close();
            } catch (RuntimeException error) {
                RobotDiagnostics.error("Camera", "Camera close failed", error);
            }
        }, "camera-close");
        thread.setDaemon(true);
        thread.start();
    }

    private void open() {
        WebcamName camera = hardwareMap.tryGet(WebcamName.class, CAMERA);
        if (camera == null) {
            problem = "no \"" + CAMERA + "\" in the hardware map";
            return;
        }
        try {
            tags = new AprilTagProcessor.Builder()
                    // goBILDA 3122-0004-0001 factory calibration at 640x480.
                    .setLensIntrinsics(545.584, 545.584, 350.588, 223.150)
                    .setTagLibrary(AprilTagGameDatabase.getBioBuzzTagLibrary())
                    .setOutputUnits(DistanceUnit.INCH, AngleUnit.DEGREES)
                    .build();
            portal = new VisionPortal.Builder()
                    .setCamera(camera)
                    .setCameraResolution(new Size(640, 480))
                    // This camera exposes 640x480 in MJPEG, not default YUY2.
                    .setStreamFormat(VisionPortal.StreamFormat.MJPEG)
                    // No preview on the hub itself (it has no screen); the dashboard's uses getFrameBitmap.
                    .enableLiveView(false)
                    .addProcessor(tags)
                    .build();
        } catch (RuntimeException error) {
            // A camera that will not start must never stop the OpMode.
            tags = null;
            portal = null;
            problem = "the camera did not start: " + error.getMessage();
        }
    }

    /** At STOP: no more targets or pictures, and the camera closes (without blocking the OpMode). */
    public void close() {
        closed = true;
        synchronized (CAMERA_LOCK) {
            if (portal != null && portal == lastPortal) {
                closeLast();
            }
        }
    }

    /** The hive to aim at is in view (our color, the right kind, up). */
    @AutoFunction(name = "hiveSeen", label = "hive seen", help = "The camera sees our alliance's hive (up).")
    public boolean seen() {
        return hive() != null;
    }

    /** The hive to aim at (distance, turn, height), or null when none is in view. */
    public HiveFinder.Sighting hive() {
        return closed || tags == null ? null : finder.hive();
    }

    private long nextDiagnostic;

    @Override
    public void update() {
        if (closed) return;
        long now = System.nanoTime();
        if (now >= nextDiagnostic) {
            nextDiagnostic = now + 1_000_000_000L;
            CameraFeed.report(portal == null ? "unavailable" : portal.getCameraState().name(),
                    portal == null ? 0 : portal.getFps(), CAMERA_FEED_ON, problem);
        }
        if (tags == null || portal == null || portal.getCameraState() != VisionPortal.CameraState.STREAMING) {
            lastFrameNanos = 0;
            pickHive(Collections.emptyList());   // a stopped camera must not leave an old target
            return;
        }
        List<AprilTagDetection> fresh = tags.getFreshDetections();   // null: no new frame since last time
        if (fresh != null) {
            lastFrameNanos = now;
            pickHive(fresh);
        } else if (now - lastFrameNanos > FRAME_TIMEOUT_NANOS) {
            pickHive(Collections.emptyList());   // frames stopped coming: the last target is stale
        }
        if (CAMERA_FEED_ON && CameraFeed.wanted()) {
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

    /** CAMERA_FEED_FPS full-resolution pictures a second for the dashboard, JPEG quality 85. */
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
                    if (closed || !CAMERA_FEED_ON) return; // A queued request may finish after disabling.
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
                    CameraFeed.publish(jpeg.toByteArray());
                } catch (RuntimeException error) {
                    problem = "Preview failed: " + error.getMessage();
                    RobotDiagnostics.error("Camera", "Preview encoding failed", error);
                } finally {
                    grabbing = false;
                }
            }
        }));
    }

    @Override
    public void telemetry(Telemetry telemetry) {
        HiveFinder.Sighting hive = hive();
        // Driver-requested axes: X is ground range, Y is height above the floor (inches).
        telemetry.addData("Hive X distance in", hive == null ? "--" : Math.round(hive.range * 10) / 10.0);
        telemetry.addData("Hive Y height in", hive == null ? "--" : Math.round(hive.height * 10) / 10.0);
        // For the drive team: wait for STREAMING before pressing STOP.
        telemetry.addData("Camera", portal == null ? (problem.isEmpty() ? "off" : problem) : portal.getCameraState().name());
        if (tags == null) {
            telemetry.addData("Hive", "no camera");
            Dash.put("Hive problem", problem);
            return;
        }
        telemetry.addData("Hive", finder.shortLine());
        if (hive != null) {
            Dash.put("Hive bearing deg", hive.bearing);
        }
    }
}
