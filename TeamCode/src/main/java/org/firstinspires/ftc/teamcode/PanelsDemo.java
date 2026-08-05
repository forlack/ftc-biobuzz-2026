package org.firstinspires.ftc.teamcode;

import com.bylazar.configurables.annotations.Configurable;
import com.bylazar.field.FieldManager;
import com.bylazar.field.FieldPresets;
import com.bylazar.field.PanelsField;
import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/**
 * Smoke test for the Panels dashboard. Uses no hardware, so it runs on a bare
 * Control Hub with an empty robot configuration.
 *
 * Connect to the robot's WiFi, then open http://192.168.43.1:8001 in a browser.
 *
 * The fields below are annotated @Configurable, so they show up in the Panels
 * "Configurables" tab and can be edited live while the OpMode runs.
 */
@Configurable
@TeleOp(name = "Panels Demo", group = "Diagnostics")
public class PanelsDemo extends LinearOpMode {

    public static double radius = 6.0;
    public static double orbitRadius = 24.0;
    public static double speed = 1.0;

    @Override
    public void runOpMode() {
        TelemetryManager panelsTelemetry = PanelsTelemetry.INSTANCE.getTelemetry();
        FieldManager field = PanelsField.INSTANCE.getField();
        field.setOffsets(FieldPresets.INSTANCE.getPEDRO_PATHING());

        panelsTelemetry.addLine("Panels is up. Waiting for start.");
        panelsTelemetry.update(telemetry);

        waitForStart();

        long startTime = System.currentTimeMillis();

        while (opModeIsActive()) {
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            double angle = elapsed * speed;
            double x = orbitRadius * Math.cos(angle);
            double y = orbitRadius * Math.sin(angle);

            field.setStyle(PanelsField.INSTANCE.getBLUE(), PanelsField.INSTANCE.getWHITE(), 1.0);
            field.moveCursor(x, y);
            field.circle(radius);
            field.update();

            panelsTelemetry.addData("elapsed", elapsed);
            panelsTelemetry.addData("x", x);
            panelsTelemetry.addData("y", y);
            panelsTelemetry.addData("left stick y", gamepad1.left_stick_y);
            // Passing the OpMode's telemetry mirrors these lines to the Driver Station too.
            panelsTelemetry.update(telemetry);
        }
    }
}
