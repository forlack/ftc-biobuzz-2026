package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A lock against accidents, not people: while locked, every browser can watch but none can
 * change settings, save files, restore, or run OpModes from the dashboard until someone clicks
 * the padlock again. There is no PIN on purpose; it only stops a stray click.
 *
 * The state lives in settings/dashboard-lock.json, so it survives restarts.
 */
final class DashboardLock {
    private boolean locked;

    DashboardLock() {
        try {
            JSONObject json = new JSONObject(RobotFiles.readText(file()));
            locked = json.optBoolean("locked", false);
        } catch (IOException | JSONException ignored) {
            locked = false;   // no lock file: unlocked
        }
    }

    private static File file() {
        return RobotFiles.file("settings/dashboard-lock.json");
    }

    synchronized boolean locked() {
        return locked;
    }

    synchronized void set(boolean value) throws IOException, JSONException {
        locked = value;
        save();
    }

    private void save() throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("locked", locked);
        File target = file();
        File folder = target.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
