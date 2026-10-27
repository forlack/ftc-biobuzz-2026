package org.firstinspires.ftc.teamcode.lib;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Every saved version of the robot's files, so a bad change can be undone at the field.
 *
 * Each time a file such as settings/robot.json or autos/saturday.json is saved (from the
 * dashboard or robot-sync), a copy goes into
 * history/settings__robot.json/2026-10-23_18-42-07-123.json. The first save of a file also
 * keeps the version it replaced (marked "-original"). Only the newest {@link #KEEP} versions of
 * each file are kept.
 */
public final class RobotHistory {
    private RobotHistory() { }

    public static final int KEEP = 30;
    public static final String FOLDER = "history";

    /** Folder holding one file's versions, e.g. history/autos__saturday.json. */
    public static File folderFor(String relativePath) {
        return RobotFiles.file(FOLDER + "/" + relativePath.replace("/", "__"));
    }

    /** The robot-relative path of a file in the storage folder, or null if it is outside it. */
    public static String relative(File file) {
        String root = RobotFiles.root().getAbsolutePath() + File.separator;
        String path = file.getAbsolutePath();
        return path.startsWith(root) ? path.substring(root.length()).replace(File.separatorChar, '/') : null;
    }

    /** History and snapshot folders are not themselves versioned. */
    public static boolean tracked(String relativePath) {
        return relativePath != null && !relativePath.startsWith(FOLDER + "/") && !relativePath.startsWith("snapshots/");
    }

    /** Before a save: if this file has no history yet, keep the version about to be replaced. */
    static void beforeSave(File file) {
        String relativePath = relative(file);
        if (!tracked(relativePath) || !file.isFile()) return;
        File folder = folderFor(relativePath);
        String[] existing = folder.list();
        if (existing != null && existing.length > 0) return;
        try {
            write(folder, stamp(file.lastModified()) + "-original" + extension(relativePath), RobotFiles.readBytes(file));
        } catch (IOException ignored) {
            // History is a safety net; never let it stop a save.
        }
    }

    /** After a save: keep a copy of what was saved, and drop versions beyond KEEP. */
    static void afterSave(File file, byte[] contents) {
        String relativePath = relative(file);
        if (!tracked(relativePath)) return;
        File folder = folderFor(relativePath);
        try {
            write(folder, stamp(RobotClock.now()) + extension(relativePath), contents);
            List<String> versions = versionIds(relativePath);
            for (int i = KEEP; i < versions.size(); i++) {
                new File(folder, versions.get(i)).delete();
            }
        } catch (IOException ignored) {
            // History is a safety net; never let it stop a save.
        }
    }

    /** Version file names, newest first. */
    public static List<String> versionIds(String relativePath) {
        String[] names = folderFor(relativePath).list();
        if (names == null) return new ArrayList<>();
        List<String> list = new ArrayList<>(Arrays.asList(names));
        Collections.sort(list, Collections.<String>reverseOrder());
        return list;
    }

    public static File version(String relativePath, String id) {
        if (id.contains("/") || id.contains("..")) throw new IllegalArgumentException("bad version id");
        return new File(folderFor(relativePath), id);
    }

    private static void write(File folder, String name, byte[] contents) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        File target = new File(folder, name);
        for (int n = 2; target.exists(); n++) {
            target = new File(folder, name.replaceFirst("(\\.[A-Za-z]+)?$", "-" + n + "$1"));
        }
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(contents);
        }
    }

    /** When a version was saved, from its name (the file's own date may be 1970); 0 if unknown. */
    public static long savedAt(String id) {
        try {
            return RobotClock.shown(new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).parse(id).getTime());
        } catch (java.text.ParseException error) {
            return 0;
        }
    }

    private static String stamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(new Date(millis));
    }

    private static String extension(String relativePath) {
        int dot = relativePath.lastIndexOf('.');
        return dot < 0 ? "" : relativePath.substring(dot);
    }
}
