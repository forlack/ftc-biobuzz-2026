package org.firstinspires.ftc.teamcode.lib;

import com.bylazar.configurables.annotations.Configurable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps tunable numbers in /sdcard/FIRST/settings/robot.json, so changing one does not need
 * a rebuild.
 *
 * Every {@code public static} (not final) number, boolean or String in the classes you pass
 * in is a setting. At INIT, {@link #load} replaces the code's values with the file's. A
 * missing file, a typo or an unknown name never stops the robot: that value keeps its code
 * default and a warning shows on telemetry. If robot.json cannot be read at all, the backup
 * from the previous save (robot.json.bak) is used instead.
 *
 * To save: tune live in Panels, then turn on SAVE_TO_ROBOT while the teleop is running.
 */
@Configurable
public class RobotSettings {
    public static final File LOCATION = new File("/sdcard/FIRST/settings/robot.json");
    private static final File BACKUP = new File(LOCATION.getPath() + ".bak");
    private static final File TEMPORARY = new File(LOCATION.getPath() + ".tmp");

    /** Turn on in Panels to write every current value to the robot. Turns itself off. */
    public static boolean SAVE_TO_ROBOT = false;

    /** Marks a public static field that is a live switch, not a setting, so it is never saved. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface NotSaved {}

    /** When the file was last read or written, so an unchanged file is not read again. */
    private static long loadedStamp = -1;

    private final Class<?>[] classes;
    private final List<String> warnings = new ArrayList<>();
    private String status = "not loaded";

    public RobotSettings(Class<?>... classes) {
        this.classes = classes;
    }

    /**
     * Reads robot.json (or its backup) into the settings classes. Never throws.
     *
     * Only reads when the file changed since the last load or save. Unsaved Panels edits
     * therefore survive a re-INIT, like before; a newly uploaded file wins at the next INIT.
     */
    public RobotSettings load() {
        warnings.clear();
        if (!LOCATION.isFile()) {
            status = "no robot.json yet: using code defaults";
            return this;
        }
        if (LOCATION.lastModified() == loadedStamp) {
            status = "robot.json unchanged; keeping current values";
            return this;
        }
        loadedStamp = LOCATION.lastModified();
        JSONObject json = read(LOCATION);
        String source = LOCATION.getName();
        if (json == null && BACKUP.isFile()) {
            json = read(BACKUP);
            source = BACKUP.getName();
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
                if (apply(settingsClass, name, section.opt(name))) {
                    applied++;
                }
            }
        }
        status = "loaded " + applied + " values from " + source;
        return this;
    }

    /** Call once per loop. Saves when SAVE_TO_ROBOT has been turned on in Panels. */
    public void update(DriveDashboard dashboard) {
        if (SAVE_TO_ROBOT) {
            SAVE_TO_ROBOT = false;
            save();
        }
        dashboard.addData("Settings", status);
        for (String warning : warnings) {
            dashboard.addData("Settings warning", warning);
        }
    }

    /** Writes every current value. The previous file becomes robot.json.bak. Never throws. */
    public void save() {
        try {
            JSONObject json = new JSONObject();
            json.put("version", 1);
            for (Class<?> settingsClass : classes) {
                JSONObject section = new JSONObject();
                for (Field field : settingsFields(settingsClass)) {
                    section.put(field.getName(), field.get(null));
                }
                json.put(settingsClass.getSimpleName(), section);
            }
            File folder = LOCATION.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("cannot create " + folder);
            }
            try (FileOutputStream output = new FileOutputStream(TEMPORARY)) {
                output.write((json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            // Keep the old file as the backup, then swap the new one in.
            if (LOCATION.isFile()) {
                BACKUP.delete();
                if (!LOCATION.renameTo(BACKUP)) {
                    throw new IOException("cannot back up " + LOCATION.getName());
                }
            }
            if (!TEMPORARY.renameTo(LOCATION)) {
                throw new IOException("cannot replace " + LOCATION.getName());
            }
            loadedStamp = LOCATION.lastModified();   // what is running is what is saved
            status = "saved " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
        } catch (IOException | JSONException | IllegalAccessException error) {
            status = "SAVE FAILED: " + error.getMessage();
        }
    }

    public String status() {
        return status;
    }

    public List<String> warnings() {
        return warnings;
    }

    private JSONObject read(File file) {
        try {
            return new JSONObject(PedroPathExport.readText(file));
        } catch (IOException | JSONException error) {
            warnings.add(file.getName() + ": " + error.getMessage());
            return null;
        }
    }

    /** Sets one field from the file. Returns false, with a warning, if it does not fit. */
    private boolean apply(Class<?> settingsClass, String name, Object value) {
        String label = settingsClass.getSimpleName() + "." + name;
        Field field;
        try {
            field = settingsClass.getField(name);
        } catch (NoSuchFieldException error) {
            warnings.add(label + " is not in the code (renamed?)");
            return false;
        }
        if (!isSetting(field)) {
            warnings.add(label + " cannot be set from the file");
            return false;
        }
        try {
            Class<?> type = field.getType();
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
            return true;
        } catch (IllegalArgumentException | IllegalAccessException error) {
            warnings.add(label + " = " + value + " is the wrong kind of value; kept "
                    + safeGet(field));
            return false;
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

    private static boolean isSetting(Field field) {
        int modifiers = field.getModifiers();
        Class<?> type = field.getType();
        return Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                && !Modifier.isFinal(modifiers)
                && field.getAnnotation(NotSaved.class) == null
                && (type == double.class || type == float.class || type == int.class
                        || type == long.class || type == boolean.class || type == String.class);
    }

    private static Object safeGet(Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException error) {
            return "?";
        }
    }
}
