package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;

/**
 * What the dashboard server needs from whatever it runs on: the real Robot Controller app
 * (see the Android hook in src/dashboard) or the desktop simulator (sim/).
 */
public interface RobotHost {
    String robotName();

    /** True in the simulator, so the page can say so. */
    boolean simulated();

    /** [{ "name", "group", "kind": "teleop" | "autonomous" }] */
    JSONArray opModes();

    /** Name of the OpMode that is initialized or running, or "" for none. */
    String activeOpMode();

    /** "stopped", "init" or "running". */
    String opModeState();

    void initOpMode(String name) throws Exception;

    void startOpMode() throws Exception;

    void stopOpMode() throws Exception;

    /** Battery voltage, or NaN when unknown. */
    double batteryVolts();

    /** File name of the active hardware map, such as "2222-Config.xml", or "". */
    String activeHardwareMap();

    RobotSettings settings();

    /** The autonomous actions the path editor may use. */
    Actions actions();

    /** A file of the web page (index.html, app.js, visualizer/...), or null if there is none. */
    InputStream openWebFile(String path) throws IOException;
}
