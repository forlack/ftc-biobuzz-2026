package org.firstinspires.ftc.teamcode.mechanisms;

import android.graphics.Canvas;

import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.vision.VisionProcessor;
import org.opencv.core.Mat;

/**
 * Lets one AprilTag processor serve every camera session, to work around an FTC SDK 12.0 leak
 * (https://github.com/FIRST-Tech-Challenge/FtcRobotController/issues/2366).
 *
 * VisionPortal.Builder.addProcessor() puts each processor in a static list that is never
 * cleared, so a new AprilTagProcessor per INIT (with its native detector, ~30 MB) is never
 * freed. Instead, Vision builds one AprilTagProcessor for the life of the app and gives each new
 * portal one of these small pass-throughs. Only the pass-throughs pile up in the SDK's list.
 *
 * Only the newest pass-through is live: an older portal that is still finishing a frame gets
 * nothing, so two camera sessions never use the shared processor at once.
 */
final class SharedProcessor implements VisionProcessor {
    private static volatile SharedProcessor newest;

    private final VisionProcessor shared;

    private SharedProcessor(VisionProcessor shared) {
        this.shared = shared;
    }

    /** A pass-through for a new portal; every earlier one stops passing frames on. */
    static SharedProcessor next(VisionProcessor shared) {
        SharedProcessor created = new SharedProcessor(shared);
        newest = created;
        return created;
    }

    private boolean live() {
        return newest == this;
    }

    @Override
    public void init(int width, int height, CameraCalibration calibration) {
        if (live()) {
            shared.init(width, height, calibration);
        }
    }

    @Override
    public Object processFrame(Mat frame, long captureTimeNanos) {
        if (!live()) {
            return null;
        }
        return shared.processFrame(frame, captureTimeNanos);
    }

    @Override
    public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight,
                            float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object userContext) {
        if (live() && userContext != null) {
            shared.onDrawFrame(canvas, onscreenWidth, onscreenHeight,
                    scaleBmpPxToCanvasPx, scaleCanvasDensity, userContext);
        }
    }
}
