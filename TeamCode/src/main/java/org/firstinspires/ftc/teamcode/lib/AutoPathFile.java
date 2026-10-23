package org.firstinspires.ftc.teamcode.lib;

import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;

import org.firstinspires.ftc.teamcode.pedro.Constants;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Reads Pedro export geometry and JSON settings each time the OpMode is initialized. */
public class AutoPathFile {
    public static final File LOCATION = new File("/sdcard/FIRST/paths/auto.json");
    private final PedroPathExport exported;

    public final Pose start;
    public final Path path1, path2, path3;
    public final double startupWaitSeconds, shootSeconds;

    public AutoPathFile(File file) throws IOException, JSONException {
        JSONObject json = new JSONObject(PedroPathExport.readText(file));
        if (json.getInt("version") != 1) {
            throw new IllegalArgumentException("Unsupported path file version");
        }
        exported = new PedroPathExport(new File(file.getParentFile(), "auto.java.txt"));
        start = exported.start;
        startupWaitSeconds = nonnegative(json, "startupWaitSeconds");
        shootSeconds = nonnegative(json, "shootSeconds");

        JSONObject speeds = json.optJSONObject("speedLimits");
        if (speeds != null) {
            java.util.Iterator<String> names = speeds.keys();
            while (names.hasNext()) {
                String name = names.next();
                double speed = number(speeds, name);
                if (speed <= 0) throw new IllegalArgumentException("Speed limit must be positive: " + name);
                exported.paths.put(name, getPath(name).with(Constants.foresightConfig.maxVelocityConstraint.at(speed)));
            }
        }
        path1 = getPath("path1");
        path2 = getPath("path2");
        path3 = exported.paths.get("path3");
    }

    /** Retrieve any exported path by name, including paths beyond the current auto sequence. */
    public Path getPath(String name) {
        Path result = exported.paths.get(name);
        if (result == null) throw new IllegalArgumentException("Missing exported path: " + name);
        return result;
    }

    private static double nonnegative(JSONObject json, String key) throws JSONException {
        double value = number(json, key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be nonnegative");
        }
        return value;
    }

    private static double number(JSONObject json, String key) throws JSONException {
        double value = json.getDouble(key);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + " must be finite");
        }
        return value;
    }
}
