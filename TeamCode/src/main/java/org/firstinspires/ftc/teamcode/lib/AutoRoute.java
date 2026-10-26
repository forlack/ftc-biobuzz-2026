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
import java.util.List;

/**
 * A whole autonomous routine from one file (autos/*.json, "version": 3): where to start, and
 * the steps in order, drives and functions alike. Written by the dashboard's auton editor; read
 * at INIT, so changing a route never needs a rebuild.
 *
 * <pre>
 * { "version": 3, "name": "Saturday",
 *   "start": { "x": 56, "y": 9, "heading": 270 },
 *   "steps": [
 *     { "action": "wait", "params": { "seconds": 1 } },
 *     { "drive": { "name": "to shoot",
 *                  "segments": [ { "to": [60, 16] } ],
 *                  "heading": { "type": "keep" } } },
 *     { "action": "shoot", "params": { "seconds": 3.5 } },
 *     { "drive": { "name": "park",
 *                  "segments": [ { "via": [[15.7, 29.3]], "to": [13.2, 96.1] } ],
 *                  "heading": { "type": "turn", "degrees": 90 },
 *                  "speedPercent": 60,
 *                  "events": [ { "at": 0.45, "action": "deployStinger" } ] } } ] }
 * </pre>
 *
 * A drive starts where the one before it ended (the first at "start"), position and heading:
 * there is no start point to line up. Each segment goes to "to" (inches), bent by its "via"
 * control points (none: a straight line). Several segments are one path the robot drives
 * through without stopping (a Pedro compound path); separate drives stop in between.
 *
 * Headings (degrees): "travel" faces where it is going ("reverse": true drives backwards),
 * "keep" keeps the heading it starts with, "turn" turns to "degrees" while driving, the shorter
 * way round (exactly half a turn: clockwise), done by "by" (a fraction of the drive, default 1),
 * "face" keeps facing the point "x", "y".
 * "speedPercent" (1 to 100) caps the drive's top speed as a % of the robot's calibrated top
 * speed (Foresight maxAchievableForwardVelocity); absent: full speed. "events" fire part way
 * along the drive ("at", 0 to 1, of the whole drive).
 *
 * Unknown keys are ignored (the editor keeps its own, such as "smooth").
 */
public class AutoRoute {
    public static final int VERSION = 3;

    public final String name;
    public final Pose start;
    private final List<Object> steps = new ArrayList<>();   // Drive or JSONObject (an action step)

    /** One drive step: the Pedro path and the functions along it. */
    private static final class Drive {
        final String name;
        final Path path;
        final JSONArray events;
        final Pose end;
        final double[] segmentLengths;

        Drive(String name, Path path, JSONArray events, Pose end, double[] segmentLengths) {
            this.name = name;
            this.path = path;
            this.events = events;
            this.end = end;
            this.segmentLengths = segmentLengths;
        }
    }

    public AutoRoute(String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        int version = json.optInt("version", 0);
        if (version == 2) {
            throw new IllegalArgumentException("This auton file is the old version 2: convert it on the computer"
                    + " with python3 tools/autos_v2_to_v3.py autos/NAME.json, then ./robot-sync push");
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("Auton files must say \"version\": " + VERSION);
        }
        name = json.optString("name", "route");
        JSONObject startJson = json.getJSONObject("start");
        start = pose(startJson.getDouble("x"), startJson.getDouble("y"), startJson.getDouble("heading"));

        JSONArray list = json.getJSONArray("steps");
        Pose at = start;
        int drives = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject step = list.getJSONObject(i);
            if (step.has("drive")) {
                drives++;
                JSONObject drive = step.getJSONObject("drive");
                String driveName = drive.optString("name", "path" + drives);
                Drive built = buildDrive(driveName, drive, at);
                steps.add(built);
                at = built.end;
            } else if (step.has("action")) {
                steps.add(step);
            } else {
                throw new IllegalArgumentException("Step " + (i + 1) + " needs \"drive\" or \"action\"");
            }
        }
    }

    public static AutoRoute read(File file) throws IOException, JSONException {
        return new AutoRoute(RobotFiles.readText(file));
    }

    /** How many drive steps the route has. */
    public int driveCount() {
        int count = 0;
        for (Object step : steps) if (step instanceof Drive) count++;
        return count;
    }

    /** Where the route ends (position and heading). */
    public Pose end() {
        Pose end = start;
        for (Object step : steps) if (step instanceof Drive) end = ((Drive) step).end;
        return end;
    }

    /**
     * Builds the sequence the robot runs. Also checks every step and action, so call it at
     * INIT: a mistake in the file shows up before PLAY, not half way through the match.
     */
    public AutoSequence build(Follower follower, Actions actions) throws JSONException {
        AutoSequence sequence = new AutoSequence(follower);
        for (Object item : steps) {
            if (item instanceof Drive) {
                Drive drive = (Drive) item;
                sequence.follow(drive.path, drive.segmentLengths).named("follow " + drive.name);
                for (int e = 0; e < drive.events.length(); e++) {
                    JSONObject event = drive.events.getJSONObject(e);
                    double at = event.getDouble("at");
                    if (at < 0 || at > 1) {
                        throw new IllegalArgumentException(drive.name + ": a function is at " + at
                                + "; it must be between 0 (start) and 1 (end)");
                    }
                    actions.addOnPath(sequence, at, event.getString("action"), event.optJSONObject("params"));
                }
            } else {
                JSONObject step = (JSONObject) item;
                actions.addStep(sequence, step.getString("action"), step.optJSONObject("params"));
            }
        }
        return sequence;
    }

    private static Drive buildDrive(String driveName, JSONObject drive, Pose from) throws JSONException {
        JSONArray segments = drive.getJSONArray("segments");
        if (segments.length() == 0) throw new IllegalArgumentException(driveName + " has no segments");
        List<Path> pieces = new ArrayList<>();
        Pose point = from;
        for (int s = 0; s < segments.length(); s++) {
            JSONObject segment = segments.getJSONObject(s);
            JSONArray via = segment.optJSONArray("via");
            List<Pose> poses = new ArrayList<>();
            poses.add(new Pose(point.x(), point.y(), 0));
            for (int v = 0; via != null && v < via.length(); v++) poses.add(xy(via.getJSONArray(v)));
            Pose to = xy(segment.getJSONArray("to"));
            poses.add(to);
            if (Math.hypot(to.x() - point.x(), to.y() - point.y()) < 1e-6) {
                throw new IllegalArgumentException(driveName + ": segment " + (s + 1) + " does not go anywhere");
            }
            pieces.add(poses.size() == 2 ? Paths.line(poses.get(0), poses.get(1))
                    : Paths.curve(poses.toArray(new Pose[0])));
            point = to;
        }
        Path path = pieces.size() == 1 ? pieces.get(0) : Paths.path(pieces.toArray(new Path[0]));
        double[] lengths = new double[pieces.size()];
        for (int i = 0; i < lengths.length; i++) lengths[i] = pieces.get(i).curve.length();

        JSONObject heading = drive.optJSONObject("heading");
        String type = heading == null ? "travel" : heading.getString("type");
        double startHeading = from.heading();
        double endHeading = Double.NaN;   // known for keep and turn; else from the path
        switch (type) {
            case "travel":
                path = heading != null && heading.optBoolean("reverse", false) ? path.reverseTangent() : path.tangent();
                break;
            case "keep":
                path = path.constant(startHeading);
                endHeading = startHeading;
                break;
            case "turn": {
                double target = Math.toRadians(finite(heading.getDouble("degrees")));
                double by = heading.optDouble("by", 1);
                if (!(by > 0 && by <= 1)) throw new IllegalArgumentException(driveName + ": \"by\" must be more than 0 and at most 1");
                // The shorter way round, in [-180°, 180°): half a turn goes clockwise.
                double change = target - startHeading;
                change -= 2 * Math.PI * Math.floor((change + Math.PI) / (2 * Math.PI));
                endHeading = startHeading + change;
                path = path.linear(startHeading, endHeading, by);
                break;
            }
            case "face":
                path = path.facingPoint(new Pose(finite(heading.getDouble("x")), finite(heading.getDouble("y")), 0));
                break;
            default:
                throw new IllegalArgumentException(driveName + ": unknown heading type \"" + type
                        + "\" (travel, keep, turn or face)");
        }

        if (drive.has("speedPercent") && !drive.isNull("speedPercent")) {
            double percent = drive.getDouble("speedPercent");
            if (!(percent > 0 && percent <= 100)) {
                throw new IllegalArgumentException(driveName + ": speedPercent must be from 1 to 100");
            }
            double top = ForesightConstants.config.maxAchievableForwardVelocity.get();
            path = path.with(ForesightConstants.config.maxVelocityConstraint.at(top * percent / 100));
        }
        JSONArray events = drive.optJSONArray("events");
        // The end heading as a plain angle (not wrapped), so the next drive turns from it the
        // same way every time.
        Pose end = new Pose(point.x(), point.y(), Double.isNaN(endHeading) ? path.heading(1.0) : endHeading);
        return new Drive(driveName, path, events == null ? new JSONArray() : events, end, lengths);
    }

    private static Pose xy(JSONArray point) throws JSONException {
        return new Pose(finite(point.getDouble(0)), finite(point.getDouble(1)), 0);
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
