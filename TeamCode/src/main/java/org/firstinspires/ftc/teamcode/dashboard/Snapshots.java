package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.RobotClock;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Named "known good" copies of all the robot's files (settings, autons, paths, hardware map),
 * in snapshots/&lt;name&gt;_&lt;time&gt;/ with a manifest.json. Restoring writes each file back through
 * the normal save, so it is checked first and recorded in the history like any other save.
 */
final class Snapshots {
    private Snapshots() { }

    static final String FOLDER = "snapshots";

    /** {@code hardwareMap}: the map the robot uses now ("" if none), remembered with the files. */
    static JSONObject create(String name, List<String> files, String hardwareMap) throws IOException, JSONException {
        String clean = name.trim().replaceAll("[^A-Za-z0-9 _-]+", "").trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Give the snapshot a name");
        long now = RobotClock.now();
        String id = clean.replace(' ', '-') + "_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(now));
        File folder = RobotFiles.file(FOLDER + "/" + id);
        if (folder.exists()) throw new IllegalArgumentException("A snapshot " + id + " already exists");
        JSONArray saved = new JSONArray();
        for (String relative : files) {
            File source = RobotFiles.file(relative);
            if (!source.isFile()) continue;
            write(new File(folder, relative), RobotFiles.readBytes(source));
            saved.put(relative);
        }
        JSONObject manifest = new JSONObject();
        manifest.put("id", id);
        manifest.put("name", clean);
        manifest.put("time", now);
        manifest.put("files", saved);
        if (hardwareMap != null && !hardwareMap.isEmpty()) {
            manifest.put("hardwareMap", hardwareMap);
        }
        write(new File(folder, "manifest.json"), manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        return manifest;
    }

    /** Deletes a snapshot and every file in it (the robot's own files are not touched). */
    static void delete(String id) throws IOException, JSONException {
        manifest(id);   // checks the id and that it is a snapshot
        File folder = RobotFiles.file(FOLDER + "/" + id);
        deleteTree(folder);
        if (folder.exists()) throw new IOException("could not delete snapshot " + id);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    /** Newest first. */
    static JSONArray list() throws JSONException {
        JSONArray list = new JSONArray();
        String[] ids = RobotFiles.file(FOLDER).list();
        if (ids == null) return list;
        List<JSONObject> manifests = new ArrayList<>();
        for (String id : ids) {
            try {
                manifests.add(new JSONObject(RobotFiles.readText(RobotFiles.file(FOLDER + "/" + id + "/manifest.json"))));
            } catch (IOException | JSONException ignored) {
                // Not a snapshot folder.
            }
        }
        Collections.sort(manifests, (a, b) -> Long.compare(b.optLong("time"), a.optLong("time")));
        for (JSONObject manifest : manifests) list.put(manifest);
        return list;
    }

    static JSONObject manifest(String id) throws IOException, JSONException {
        if (id.contains("/") || id.contains("..")) throw new IllegalArgumentException("bad snapshot id");
        File file = RobotFiles.file(FOLDER + "/" + id + "/manifest.json");
        if (!file.isFile()) throw new IllegalArgumentException("No snapshot " + id);
        return new JSONObject(RobotFiles.readText(file));
    }

    static byte[] read(String id, String relative) throws IOException {
        return RobotFiles.readBytes(RobotFiles.file(FOLDER + "/" + id + "/" + relative));
    }

    private static void write(File file, byte[] contents) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents);
        }
    }

    static List<String> filesOf(JSONObject manifest) throws JSONException {
        JSONArray files = manifest.getJSONArray("files");
        List<String> list = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) list.add(files.getString(i));
        return list;
    }

    static List<String> sorted(String[] names) {
        List<String> list = names == null ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(names));
        Collections.sort(list);
        return list;
    }
}
