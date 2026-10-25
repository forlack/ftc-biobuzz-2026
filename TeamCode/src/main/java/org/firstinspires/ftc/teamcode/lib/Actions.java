package org.firstinspires.ftc.teamcode.lib;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The named things an autonomous route can do, such as "shoot" or "deployStinger".
 *
 * Each action is written once in Java and registered here. Route files (autos/*.json) refer
 * to actions by name, and the path editor gets this list from the robot, so it only offers
 * actions that really exist.
 *
 * Two kinds:
 * - INSTANT: happens at once (intake on). Can also be placed part way along a path.
 * - STEP: takes time (shoot for 3.5 s, wait until the flywheels are ready). Only between paths.
 */
public class Actions {

    public enum Kind { INSTANT, STEP }

    /** Makes the code for an INSTANT action from its settings. */
    public interface Instant {
        Runnable create(JSONObject params) throws JSONException;
    }

    /** Adds a STEP action's steps to a sequence. */
    public interface Step {
        void add(AutoSequence sequence, JSONObject params) throws JSONException;
    }

    /** One setting an action takes, such as "seconds". */
    public static final class Param {
        final String name, label, unit;
        final double fallback, min, max;

        public Param(String name, String label, String unit, double fallback, double min, double max) {
            this.name = name;
            this.label = label;
            this.unit = unit;
            this.fallback = fallback;
            this.min = min;
            this.max = max;
        }
    }

    private static final class Action {
        final String name, label, description;
        final Kind kind;
        final Param[] params;
        final Instant instant;
        final Step step;

        Action(String name, String label, String description, Kind kind, Param[] params,
               Instant instant, Step step) {
            this.name = name;
            this.label = label;
            this.description = description;
            this.kind = kind;
            this.params = params;
            this.instant = instant;
            this.step = step;
        }
    }

    private final Map<String, Action> actions = new LinkedHashMap<>();

    public Actions instant(String name, String label, String description, Instant code, Param... params) {
        actions.put(name, new Action(name, label, description, Kind.INSTANT, params, code, null));
        return this;
    }

    public Actions step(String name, String label, String description, Step code, Param... params) {
        actions.put(name, new Action(name, label, description, Kind.STEP, params, null, code));
        return this;
    }

    public boolean has(String name) {
        return actions.containsKey(name);
    }

    /** Adds an action of either kind as the next step(s) of a sequence. */
    public void addStep(AutoSequence sequence, String name, JSONObject params) throws JSONException {
        Action action = get(name);
        JSONObject checked = check(action, params);
        if (action.kind == Kind.INSTANT) {
            sequence.run(action.label, action.instant.create(checked));
        } else {
            action.step.add(sequence, checked);
        }
    }

    /** The code for an INSTANT action, to fire part way along a path. */
    public Runnable onPath(String name, JSONObject params) throws JSONException {
        Action action = get(name);
        if (action.kind != Kind.INSTANT) {
            throw new IllegalArgumentException("\"" + name + "\" takes time, so it can only go between paths");
        }
        return action.instant.create(check(action, params));
    }

    /** The list the dashboard and path editor show: names, kinds and settings with units. */
    public JSONArray catalog() throws JSONException {
        JSONArray list = new JSONArray();
        for (Action action : actions.values()) {
            JSONObject item = new JSONObject();
            item.put("name", action.name);
            item.put("label", action.label);
            item.put("description", action.description);
            item.put("kind", action.kind == Kind.INSTANT ? "instant" : "step");
            item.put("onPath", action.kind == Kind.INSTANT);
            JSONArray params = new JSONArray();
            for (Param param : action.params) {
                JSONObject p = new JSONObject();
                p.put("name", param.name);
                p.put("label", param.label);
                p.put("unit", param.unit);
                p.put("default", param.fallback);
                p.put("min", param.min);
                p.put("max", param.max);
                params.put(p);
            }
            item.put("params", params);
            list.put(item);
        }
        return list;
    }

    private Action get(String name) {
        Action action = actions.get(name);
        if (action == null) {
            throw new IllegalArgumentException("Unknown action \"" + name + "\". Known: " + actions.keySet());
        }
        return action;
    }

    /** Fills in defaults and rejects unknown or out-of-range settings. */
    private static JSONObject check(Action action, JSONObject given) throws JSONException {
        JSONObject params = given == null ? new JSONObject() : given;
        List<String> known = new ArrayList<>();
        JSONObject result = new JSONObject();
        for (Param param : action.params) {
            known.add(param.name);
            double value = params.has(param.name) ? params.getDouble(param.name) : param.fallback;
            if (Double.isNaN(value) || value < param.min || value > param.max) {
                throw new IllegalArgumentException(action.name + "." + param.name + " = " + value
                        + " is outside " + param.min + " to " + param.max + " " + param.unit);
            }
            result.put(param.name, value);
        }
        Iterator<String> names = params.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new IllegalArgumentException(action.name + " has no setting \"" + name + "\"");
            }
        }
        return result;
    }
}
