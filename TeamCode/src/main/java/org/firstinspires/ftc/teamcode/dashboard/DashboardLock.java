package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A simple lock for events: while locked, anyone can watch but nobody can change settings,
 * save files, restore, or run OpModes from the dashboard until someone enters the PIN.
 *
 * The state lives in settings/dashboard-lock.json, so it survives restarts. Only a salted
 * fingerprint of the PIN is stored. Forgot the PIN? Delete that file (adb shell rm
 * /sdcard/FIRST/settings/dashboard-lock.json) and restart the app.
 */
final class DashboardLock {
    private boolean locked;
    private String salt = "", fingerprint = "";

    DashboardLock() {
        try {
            JSONObject json = new JSONObject(RobotFiles.readText(file()));
            locked = json.optBoolean("locked", false);
            salt = json.optString("salt", "");
            fingerprint = json.optString("fingerprint", "");
            if (fingerprint.isEmpty()) locked = false;
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

    /** Locks with a new PIN (4 to 12 digits). */
    synchronized void lock(String pin) throws IOException, JSONException {
        if (pin == null || !pin.matches("[0-9]{4,12}")) {
            throw new IllegalArgumentException("The PIN must be 4 to 12 digits");
        }
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        salt = RobotFiles.hash(random);
        fingerprint = fingerprint(pin);
        locked = true;
        save();
    }

    /** Unlocks if the PIN is right. A wrong PIN is slowed down, so guessing takes ages. */
    synchronized boolean unlock(String pin) throws IOException, JSONException {
        if (!locked) return true;
        if (pin == null || !fingerprint(pin).equals(fingerprint)) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
        locked = false;
        save();
        return true;
    }

    private String fingerprint(String pin) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void save() throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("locked", locked);
        json.put("salt", salt);
        json.put("fingerprint", fingerprint);
        File target = file();
        File folder = target.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
