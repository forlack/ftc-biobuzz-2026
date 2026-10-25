package org.firstinspires.ftc.teamcode.lib;

import com.pedropathing.api.Paths;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;

import org.firstinspires.ftc.teamcode.pedro.ForesightConstants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole autonomous routine from one file (autos/*.json, "version": 2): where to start, the
 * paths, actions placed part way along paths, and the steps in between. Written by the dashboard's
 * path editor; read at INIT, so changing a route never needs a rebuild.
 *
 * <pre>
 * { "version": 2, "name": "Saturday",
 *   "start": { "x": 56, "y": 8, "heading": 270 },
 *   "paths": [ { "name": "path1", "points": [[56, 8], [60, 16]],
 *                "heading": { "type": "linear", "start": 270, "end": 270 },
 *                "events": [ { "at": 0.5, "action": "flywheelsOn" } ] } ],
 *   "steps": [ { "action": "wait", "params": { "seconds": 1 } },
 *              { "follow": "path1" },
 *              { "action": "shoot", "params": { "seconds": 3.5 } } ] }
 * </pre>
 *
 * points: the path's start, any control points, then its end, in inches. Headings are in
 * degrees: linear (start, end), constant (degrees) or tangential (reverse true/false).
 * A path may set "speedPercent" (1 to 100): its top speed as a % of the robot's calibrated
 * top speed (Foresight maxAchievableForwardVelocity). Absent: full speed.
 */
public class AutoRoute {
    public static final int VERSION = 2;

    public final String name;
    public final Pose start;
    private final Map<String, Path> paths = new LinkedHashMap<>();
    private final Map<String, JSONArray> events = new LinkedHashMap<>();
    private final JSONArray steps;

    public AutoRoute(String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        if (json.optInt("version", 0) != VERSION) {
            throw new IllegalArgumentException("Route files must say \"version\": " + VERSION);
        }
        name = json.optString("name", "route");
        JSONObject startJson = json.getJSONObject("start");
        start = pose(startJson.getDouble("x"), startJson.getDouble("y"), startJson.getDouble("heading"));

        JSONArray pathList = json.getJSONArray("paths");
        for (int i = 0; i < pathList.length(); i++) {
            JSONObject item = pathList.getJSONObject(i);
            String pathName = item.getString("name");
            if (paths.containsKey(pathName)) {
                throw new IllegalArgumentException("Two paths are called \"" + pathName + "\"");
            }
            paths.put(pathName, buildPath(pathName, item));
            JSONArray pathEvents = item.optJSONArray("events");
            events.put(pathName, pathEvents == null ? new JSONArray() : pathEvents);
        }
        steps = json.getJSONArray("steps");
    }

    public static AutoRoute read(File file) throws IOException, JSONException {
        return new AutoRoute(RobotFiles.readText(file));
    }

    public Path path(String pathName) {
        Path path = paths.get(pathName);
        if (path == null) {
            throw new IllegalArgumentException("No path called \"" + pathName + "\". Paths: " + paths.keySet());
        }
        return path;
    }

    public int pathCount() {
        return paths.size();
    }

    /**
     * Builds the sequence the robot runs. Also checks every step and action, so call it at
     * INIT: a mistake in the file shows up before PLAY, not half way through the match.
     */
    public AutoSequence build(Follower follower, Actions actions) throws JSONException {
        AutoSequence sequence = new AutoSequence(follower);
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step.has("follow")) {
                String pathName = step.getString("follow");
                sequence.follow(path(pathName)).named("follow " + pathName);
                JSONArray pathEvents = events.get(pathName);
                for (int e = 0; e < pathEvents.length(); e++) {
                    JSONObject event = pathEvents.getJSONObject(e);
                    double at = event.getDouble("at");
                    if (at < 0 || at > 1) {
                        throw new IllegalArgumentException(pathName + ": an action is at " + at
                                + "; it must be between 0 (start) and 1 (end)");
                    }
                    sequence.at(at, actions.onPath(event.getString("action"), event.optJSONObject("params")));
                }
            } else if (step.has("action")) {
                actions.addStep(sequence, step.getString("action"), step.optJSONObject("params"));
            } else {
                throw new IllegalArgumentException("Step " + (i + 1) + " needs \"follow\" or \"action\"");
            }
        }
        return sequence;
    }

    /** Names of the paths the steps follow, in order, for telemetry. */
    public List<String> followedPaths() throws JSONException {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step.has("follow")) names.add(step.getString("follow"));
        }
        return names;
    }

    private static Path buildPath(String pathName, JSONObject item) throws JSONException {
        JSONArray points = item.getJSONArray("points");
        if (points.length() < 2) {
            throw new IllegalArgumentException(pathName + " needs at least a start and an end point");
        }
        Pose[] poses = new Pose[points.length()];
        for (int i = 0; i < points.length(); i++) {
            JSONArray point = points.getJSONArray(i);
            poses[i] = new Pose(finite(point.getDouble(0)), finite(point.getDouble(1)), 0);
        }
        Path path = poses.length == 2 ? Paths.line(poses[0], poses[1]) : Paths.curve(poses);

        JSONObject heading = item.optJSONObject("heading");
        String type = heading == null ? "tangential" : heading.getString("type");
        if (type.equals("linear")) {
            path = path.linear(Math.toRadians(heading.getDouble("start")), Math.toRadians(heading.getDouble("end")));
        } else if (type.equals("constant")) {
            path = path.constant(Math.toRadians(heading.getDouble("degrees")));
        } else if (type.equals("tangential")) {
            path = heading != null && heading.optBoolean("reverse", false) ? path.reverseTangent() : path.tangent();
        } else {
            throw new IllegalArgumentException(pathName + ": unknown heading type \"" + type + "\"");
        }

        if (item.has("speedPercent") && !item.isNull("speedPercent")) {
            double percent = item.getDouble("speedPercent");
            if (!(percent > 0 && percent <= 100)) {
                throw new IllegalArgumentException(pathName + ": speedPercent must be from 1 to 100");
            }
            double top = ForesightConstants.config.maxAchievableForwardVelocity.get();
            path = path.with(ForesightConstants.config.maxVelocityConstraint.at(top * percent / 100));
        }
        return path;
    }

    private static Pose pose(double x, double y, double headingDegrees) {
        return new Pose(finite(x), finite(y), Math.toRadians(finite(headingDegrees)));
    }

    private static double finite(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Numbers in a route must be finite");
        }
        return value;
    }
}
