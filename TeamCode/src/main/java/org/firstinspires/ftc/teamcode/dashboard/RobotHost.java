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

    /** File name of the active hardware map, such as "24621-Biobuzz.xml", or "". */
    String activeHardwareMap();

    /**
     * Makes {@code file} the active hardware map. Returns null when done, or what to do instead
     * (on the robot, until switching from the dashboard is tested: the Driver Station steps).
     */
    default String useHardwareMap(String file) {
        return "Activate it on the Driver Station: Configure Robot, pick "
                + file.replaceAll("\\.xml$", "") + ", then Activate.";
    }

    /**
     * The Driver Station's gamepad {@code number} (1 or 2) as the active OpMode sees it, for
     * the dashboard's Gamepad drawer: axes in {@link VirtualGamepads#AXES} order, buttons as
     * one bit set in {@code buttons[0]} ({@link VirtualGamepads#BUTTONS} order). Returns false
     * when there is none (the simulator). Memory reads only: never a call to a hub.
     */
    default boolean driverStationGamepad(int number, float[] axes, int[] buttons) {
        return false;
    }

    /**
     * adb on the robot, for the Robot tab: {"listening": true|false|null, "canRestart": bool,
     * "note": "..."}. Null means not available (the simulator).
     */
    default org.json.JSONObject adbStatus() {
        return null;
    }

    /** Restarts adb on the robot. Returns null when it is listening again, or what went wrong. */
    default String restartAdb() {
        return "Only the real robot has adb.";
    }

    RobotSettings settings();

    /** The autonomous actions the path editor may use. */
    Actions actions();

    /** A file of the web page (index.html, js/app.js, fields/...), or null if there is none. */
    InputStream openWebFile(String path) throws IOException;
}
