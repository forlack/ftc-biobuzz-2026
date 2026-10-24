package org.firstinspires.ftc.teamcode.lib;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Where the robot keeps its files, and safe ways to read and write them.
 *
 * On the robot the folder is /sdcard/FIRST. The desktop simulator points it at its own fake
 * SD card with the system property {@code biobuzz.storage}, so the same code runs in both.
 *
 * Only java.io is used: the Control Hub runs Android 7, which has no java.nio.file.
 */
public final class RobotFiles {
    private RobotFiles() { }

    private static File root = new File(System.getProperty("biobuzz.storage", "/sdcard/FIRST"));

    /** The robot's storage folder: /sdcard/FIRST, or the simulator's fake SD card. */
    public static File root() {
        return root;
    }

    /** For the simulator and tests. */
    public static void setRoot(File folder) {
        root = folder;
    }

    /** A file inside the storage folder, such as "settings/robot.json". */
    public static File file(String relativePath) {
        return new File(root, relativePath);
    }

    public static byte[] readBytes(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readAll(input);
        }
    }

    public static String readText(File file) throws IOException {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    public static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /**
     * Writes a whole file so a crash or power loss leaves either the old or the new one, never
     * half of each. The old file is kept as name.bak.
     */
    public static void writeAtomically(File file, byte[] contents) throws IOException {
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("cannot create " + folder);
        }
        File temporary = new File(file.getPath() + ".tmp");
        File backup = new File(file.getPath() + ".bak");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(contents);
            output.getFD().sync();
        }
        if (file.isFile()) {
            backup.delete();
            if (!file.renameTo(backup)) {
                throw new IOException("cannot back up " + file.getName());
            }
        }
        if (!temporary.renameTo(file)) {
            throw new IOException("cannot replace " + file.getName());
        }
    }

    /** Short content fingerprint, used to notice that a file changed since someone read it. */
    public static String hash(byte[] contents) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(contents);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                text.append(String.format("%02x", digest[i] & 0xff));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
