package org.firstinspires.ftc.teamcode.helpers;

import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.firstinspires.ftc.teamcode.lib.DashboardTelemetry;
import org.firstinspires.ftc.teamcode.pedro.Constants;

/**
 * Push the robot by hand and check the numbers move the right way. Motors never run.
 *
 * FRONT = POD END. With the pod end pointing away from you:
 *   push FORWARD (toward the pod end)  ->  x goes UP
 *   push LEFT                           ->  y goes UP
 *   spin COUNTER-CLOCKWISE (from above) ->  heading goes UP
 *   spin in place a full turn           ->  x and y barely move
 *
 * A reset button (A) zeroes everything so each push starts from 0. No controller? Stop and
 * re-run the OpMode -- it starts at zero too.
 *
 * The RAW counts are the encoders before any direction setting -- exactly what AutoTune's
 * Pinpoint tuner sees, since it configures both pods FORWARD. Push LEFT: if "raw strafe"
 * goes DOWN, a correct tuner must answer REVERSED.
 */
@TeleOp(name = "Localization Check", group = "Diagnostics")
public class LocalizationCheck extends LinearOpMode {
    @Override
    public void runOpMode() {
        // Everything sent to telemetry also reaches the Total Chaos Dashboard.
        telemetry = new DashboardTelemetry(telemetry);
        Follower follower = Constants.create(hardwareMap);
        GoBildaPinpointDriver pinpoint = hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint");
        follower.setPose(new Pose(0, 0, 0));

        telemetry.addLine("Push by hand. Motors are off. A = reset to 0.");
        telemetry.update();
        waitForStart();
        follower.update();
        int rawForward0 = pinpoint.getEncoderX();
        int rawStrafe0 = pinpoint.getEncoderY();

        while (opModeIsActive()) {
            follower.update();
            if (gamepad1.a) {
                follower.setPose(new Pose(0, 0, 0));
                rawForward0 = pinpoint.getEncoderX();
                rawStrafe0 = pinpoint.getEncoderY();
            }
            Pose p = follower.pose();
            telemetry.addLine("FRONT = POD END");
            telemetry.addData("x  (forward +)", Math.round(p.x() * 10) / 10.0);
            telemetry.addData("y  (left +)", Math.round(p.y() * 10) / 10.0);
            telemetry.addData("heading deg (CCW +)", Math.round(Math.toDegrees(p.heading()) * 10) / 10.0);
            telemetry.addLine("");
            telemetry.addLine("RAW (what AutoTune sees, both FORWARD):");
            telemetry.addData("raw forward ticks", pinpoint.getEncoderX() - rawForward0);
            telemetry.addData("raw strafe ticks", pinpoint.getEncoderY() - rawStrafe0);
            telemetry.update();
        }
    }
}
