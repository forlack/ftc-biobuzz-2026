package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Bounded, once-a-second evidence written by the dashboard meter, never the drive loop. */
public final class RobotDiagnostics {
    private static final long LIMIT = 1024 * 1024;
    private static final int TAIL = 64 * 1024;
    private static final java.util.ArrayDeque<JSONObject> pending = new java.util.ArrayDeque<>();

    /** In-memory enqueue only; the dashboard meter flushes to disk once a second. */
    public static void info(String source, String message) { log("INFO", source, message); }
    public static void warn(String source, String message) { log("WARN", source, message); }
    public static void error(String source, String message) { log("ERROR", source, message); }
    public static void error(String source, String message, Throwable error) {
        java.io.StringWriter trace = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(trace));
        log("ERROR", source, message + "\n" + trace);
    }

    private static void log(String level, String source, String message) {
        try {
            JSONObject event = new JSONObject();
            event.put("kind", "event");
            event.put("timeMs", System.currentTimeMillis());
            event.put("monotonicMs", System.nanoTime() / 1_000_000L);
            event.put("level", level);
            event.put("source", source == null ? "Robot" : source.substring(0, Math.min(80, source.length())));
            String text = String.valueOf(message);
            event.put("message", text.substring(0, Math.min(2048, text.length())));
            synchronized (pending) {
                if (pending.size() == 100) pending.removeFirst();
                pending.addLast(event);
            }
        } catch (Exception ignored) { }
    }

    private static final Object BATTERY_LOCK = new Object();
    private static double batteryLow = Double.NaN;
    private static int batteryReads;

    /**
     * Every battery reading (OpModes read 4 times a second); each sample keeps the lowest since
     * the last one. Catches a sagging battery, not millisecond spikes between reads.
     */
    public static void battery(double volts) {
        synchronized (BATTERY_LOCK) {
            if (Double.isNaN(batteryLow) || volts < batteryLow) {
                batteryLow = volts;
            }
            batteryReads++;
        }
    }

    private final String session =Long.toHexString(System.currentTimeMillis()) + "-" + Long.toHexString(System.nanoTime());
    private volatile String writeError = "";
    private volatile JSONObject latest = new JSONObject();

    public synchronized void record(JSONObject sample) {
        try {
            sample.put("session", session);
            sample.put("timeMs", System.currentTimeMillis());
            sample.put("monotonicMs", System.nanoTime() / 1_000_000L);
            synchronized (BATTERY_LOCK) {
                // null when nothing read the battery this second (no OpMode running).
                sample.put("batteryMinV", Double.isNaN(batteryLow) ? JSONObject.NULL : (Object) batteryLow);
                sample.put("batteryReads", batteryReads);
                batteryLow = Double.NaN;
                batteryReads = 0;
            }
            latest = sample;
            append("diagnostics", sample.toString() + "\n", LIMIT);
            java.util.List<JSONObject> events = new java.util.ArrayList<>();
            synchronized (pending) {
                while (!pending.isEmpty()) events.add(pending.removeFirst());
            }
            for (JSONObject event : events) {
                event.put("session", session);
                append("events", event.toString() + "\n", 512 * 1024);
            }
            writeError = "";
        } catch (Exception error) { writeError = error.toString(); }
    }

    private void append(String name, String text, long limit) throws java.io.IOException {
        File file = RobotFiles.file("logs/" + name + ".jsonl");
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create logs directory");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (file.length() + bytes.length > limit) {
            File previous = RobotFiles.file("logs/" + name + ".previous.jsonl");
            if (previous.exists() && !previous.delete()) throw new java.io.IOException("Cannot rotate previous log");
            if (!file.renameTo(previous)) throw new java.io.IOException("Cannot rotate log");
        }
        try (FileOutputStream output = new FileOutputStream(file, true)) { output.write(bytes); }
    }

    public JSONObject status() throws org.json.JSONException {
        JSONObject result = new JSONObject(latest.toString());
        result.put("logWriteError", writeError);
        return result;
    }

    /** At most 64 KB returned; fixed names prevent arbitrary file access. */
    public synchronized String tail(boolean previous) throws java.io.IOException {
        return tail("diagnostics", previous);
    }

    public synchronized String tail(String name, boolean previous) throws java.io.IOException {
        if (!name.equals("diagnostics") && !name.equals("events")) throw new java.io.IOException("Unknown log");
        File file = RobotFiles.file("logs/" + name + (previous ? ".previous.jsonl" : ".jsonl"));
        if (!file.isFile()) return "No entries recorded yet.\n";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long offset = Math.max(0, input.length() - TAIL);
            input.seek(offset);
            if (offset > 0) input.readLine();
            byte[] bytes = new byte[(int) (input.length() - input.getFilePointer())];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
