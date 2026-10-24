package org.firstinspires.ftc.teamcode.lib;

import com.bylazar.configurables.annotations.Configurable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Keeps tunable numbers in settings/robot.json on the robot, so changing one does not need a
 * rebuild.
 *
 * Every {@code public static} (not final) number, boolean or String in the classes you pass
 * in is a setting. At INIT, {@link #load} replaces the code's values with the file's. A
 * missing file, a typo or an unknown name never stops the robot: that value keeps its code
 * default and a warning shows on telemetry. If robot.json cannot be read at all, the backup
 * from the previous save (robot.json.bak) is used instead.
 *
 * To save: press Save in the BioBuzz dashboard, or turn on SAVE_TO_ROBOT in Panels while
 * the teleop is running.
 */
@Configurable
public class RobotSettings {
    /** Turn on in Panels to write every current value to the robot. Turns itself off. */
    public static boolean SAVE_TO_ROBOT = false;

    /** Marks a public static field that is a live switch, not a setting, so it is never saved. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface NotSaved {}

    /** When the file was last read or written, so an unchanged file is not read again. */
    private static long loadedStamp = -1;
    /** Each setting's value in the code, captured the first time its class is seen. */
    private static final Map<String, Object> DEFAULTS = new HashMap<>();

    private final Class<?>[] classes;
    private final List<String> warnings = new ArrayList<>();
    private String status = "not loaded";

    public RobotSettings(Class<?>... classes) {
        this.classes = classes;
        synchronized (DEFAULTS) {
            for (Class<?> settingsClass : classes) {
                for (Field field : allFields(settingsClass)) {
                    String key = key(settingsClass, field.getName());
                    if (!DEFAULTS.containsKey(key)) {
                        DEFAULTS.put(key, safeGet(field));
                    }
                }
            }
        }
    }

    /** settings/robot.json in the robot's storage folder. */
    public static File location() {
        return RobotFiles.file("settings/robot.json");
    }

    private static File backup() {
        return new File(location().getPath() + ".bak");
    }

    /**
     * Reads robot.json (or its backup) into the settings classes. Never throws.
     *
     * Only reads when the file changed since the last load or save. Unsaved edits therefore
     * survive a re-INIT; a newly uploaded file wins at the next INIT.
     */
    public synchronized RobotSettings load() {
        warnings.clear();
        File location = location();
        if (!location.isFile()) {
            status = "no robot.json yet: using code defaults";
            return this;
        }
        if (location.lastModified() == loadedStamp) {
            status = "robot.json unchanged; keeping current values";
            return this;
        }
        loadedStamp = location.lastModified();
        JSONObject json = read(location);
        String source = location.getName();
        if (json == null && backup().isFile()) {
            json = read(backup());
            source = backup().getName();
        }
        if (json == null) {
            status = "robot.json is broken: using code defaults";
            return this;
        }
        int applied = 0;
        for (Class<?> settingsClass : classes) {
            JSONObject section = json.optJSONObject(settingsClass.getSimpleName());
            if (section == null) {
                continue;
            }
            Iterator<String> names = section.keys();
            while (names.hasNext()) {
                String name = names.next();
                String problem = apply(settingsClass, name, section.opt(name), false);
                if (problem == null) {
                    applied++;
                } else {
                    warnings.add(problem);
                }
            }
        }
        status = "loaded " + applied + " values from " + source;
        return this;
    }

    /** Re-reads robot.json even if it looks unchanged, such as after a dashboard upload. */
    public synchronized RobotSettings reload() {
        loadedStamp = -1;
        return load();
    }

    /**
     * Call once per loop. Saves when SAVE_TO_ROBOT has been turned on in Panels, then reports
     * the status and any warnings, for example {@code settings.update(dashboard::addData)}.
     */
    public void update(BiConsumer<String, Object> telemetry) {
        if (SAVE_TO_ROBOT) {
            SAVE_TO_ROBOT = false;
            save();
        }
        telemetry.accept("Settings", status);
        for (String warning : warnings()) {
            telemetry.accept("Settings warning", warning);
        }
    }

    /** Writes every current value. The previous file becomes robot.json.bak. Never throws. */
    public synchronized void save() {
        try {
            RobotFiles.writeAtomically(location(),
                    (currentJson().toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            loadedStamp = location().lastModified();   // what is running is what is saved
            status = "saved " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
        } catch (IOException | JSONException error) {
            status = "SAVE FAILED: " + error.getMessage();
        }
    }

    public synchronized String status() {
        return status;
    }

    public synchronized List<String> warnings() {
        return new ArrayList<>(warnings);
    }

    // ---- For the dashboard -------------------------------------------------

    /** Every current value, as robot.json would hold it. */
    public synchronized JSONObject currentJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        for (Class<?> settingsClass : classes) {
            JSONObject section = new JSONObject();
            for (Field field : settingsFields(settingsClass)) {
                section.put(field.getName(), safeGet(field));
            }
            if (section.length() > 0) {
                json.put(settingsClass.getSimpleName(), section);
            }
        }
        return json;
    }

    /**
     * Every setting and live switch, with its type, current value, code default and the value
     * in robot.json (null if the file does not have it). Live switches have "saved": false.
     */
    public synchronized JSONArray describe() throws JSONException {
        JSONObject file = null;
        if (location().isFile()) {
            try {
                file = new JSONObject(RobotFiles.readText(location()));
            } catch (IOException | JSONException ignored) {
                // A broken file just means no "in file" column.
            }
        }
        JSONArray list = new JSONArray();
        for (Class<?> settingsClass : classes) {
            JSONObject section = file == null ? null : file.optJSONObject(settingsClass.getSimpleName());
            for (Field field : allFields(settingsClass)) {
                boolean saved = isSetting(field);
                JSONObject item = new JSONObject();
                item.put("group", settingsClass.getSimpleName());
                item.put("name", field.getName());
                item.put("type", typeName(field.getType()));
                item.put("value", safeGet(field));
                Object fallback = DEFAULTS.get(key(settingsClass, field.getName()));
                item.put("default", fallback == null ? JSONObject.NULL : fallback);
                item.put("saved", saved);
                Object inFile = section == null ? null : section.opt(field.getName());
                item.put("inFile", inFile == null || !saved ? JSONObject.NULL : inFile);
                list.put(item);
            }
        }
        return list;
    }

    /** Changes one setting or live switch now. Returns null, or why it was refused. */
    public synchronized String set(String group, String name, Object value) {
        for (Class<?> settingsClass : classes) {
            if (settingsClass.getSimpleName().equals(group)) {
                return apply(settingsClass, name, value, true);
            }
        }
        return "unknown group " + group;
    }

    // ---- Inside ------------------------------------------------------------

    private JSONObject read(File file) {
        try {
            return new JSONObject(RobotFiles.readText(file));
        } catch (IOException | JSONException error) {
            warnings.add(file.getName() + ": " + error.getMessage());
            return null;
        }
    }

    /** Sets one field. Returns null, or a message saying why the value does not fit. */
    private String apply(Class<?> settingsClass, String name, Object value, boolean allowLive) {
        String label = settingsClass.getSimpleName() + "." + name;
        Field field;
        try {
            field = settingsClass.getField(name);
        } catch (NoSuchFieldException error) {
            return label + " is not in the code (renamed?)";
        }
        if (!(allowLive ? isLive(field) || isSetting(field) : isSetting(field))) {
            return label + " cannot be set from the file";
        }
        try {
            Class<?> type = field.getType();
            if (value instanceof String && type != String.class) {
                value = parse((String) value, type);
            }
            if ((type == double.class || type == float.class) && value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number)) {
                    throw new IllegalArgumentException();
                }
                if (type == double.class) field.setDouble(null, number);
                else field.setFloat(null, (float) number);
            } else if ((type == int.class || type == long.class) && value instanceof Number
                    && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue())) {
                if (type == int.class) field.setInt(null, ((Number) value).intValue());
                else field.setLong(null, ((Number) value).longValue());
            } else if (type == boolean.class && value instanceof Boolean) {
                field.setBoolean(null, (Boolean) value);
            } else if (type == String.class && value instanceof String) {
                field.set(null, value);
            } else {
                throw new IllegalArgumentException();
            }
            return null;
        } catch (IllegalArgumentException | IllegalAccessException error) {
            return label + " = " + value + " is the wrong kind of value; kept " + safeGet(field);
        }
    }

    private static Object parse(String text, Class<?> type) {
        String trimmed = text.trim();
        if (type == boolean.class) {
            if (trimmed.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (trimmed.equalsIgnoreCase("false")) return Boolean.FALSE;
            return text;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException error) {
            return text;
        }
    }

    private static List<Field> settingsFields(Class<?> settingsClass) {
        List<Field> fields = new ArrayList<>();
        for (Field field : settingsClass.getDeclaredFields()) {
            if (isSetting(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** Settings and live switches, in source order. */
    private static List<Field> allFields(Class<?> settingsClass) {
        List<Field> fields = new ArrayList<>();
        for (Field field : settingsClass.getDeclaredFields()) {
            if (isSetting(field) || isLive(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean isSetting(Field field) {
        return isEditable(field) && field.getAnnotation(NotSaved.class) == null;
    }

    private static boolean isLive(Field field) {
        return isEditable(field) && field.getAnnotation(NotSaved.class) != null;
    }

    private static boolean isEditable(Field field) {
        int modifiers = field.getModifiers();
        Class<?> type = field.getType();
        return Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                && !Modifier.isFinal(modifiers)
                && (type == double.class || type == float.class || type == int.class
                        || type == long.class || type == boolean.class || type == String.class);
    }

    private static String typeName(Class<?> type) {
        if (type == boolean.class) return "boolean";
        if (type == String.class) return "string";
        if (type == int.class || type == long.class) return "integer";
        return "number";
    }

    private static String key(Class<?> settingsClass, String name) {
        return settingsClass.getName() + "#" + name;
    }

    private static Object safeGet(Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException error) {
            return "?";
        }
    }
}
