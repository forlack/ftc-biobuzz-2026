import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.firstinspires.ftc.teamcode.dashboard.RobotDiagnostics;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;

public class RobotDiagnosticsCheck {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("ftc-diagnostic-test").toFile();
        RobotFiles.setRoot(root);
        RobotDiagnostics log = new RobotDiagnostics();
        JSONObject sample = new JSONObject().put("camera", "STREAMING");
        RobotDiagnostics.info("Test", "Started");
        RobotDiagnostics.warn("Camera", "Camera stopped");
        RobotDiagnostics.error("Camera", "Encoding failed", new IllegalStateException("sample fault"));
        log.record(sample);
        String events = log.tail("events", false);
        if (!events.contains("Started") || !events.contains("sample fault") || !events.contains("WARN"))
            throw new AssertionError("Missing persistent log messages");
        if (!new RobotDiagnostics().tail("events", false).contains("Started")) throw new AssertionError("Events not persistent");
        if (!log.tail(false).contains("STREAMING")) throw new AssertionError("Missing sample");
        if (!new RobotDiagnostics().tail(false).contains("STREAMING")) throw new AssertionError("Not persistent");
        String payload = new String(new char[2000]).replace('\0', 'x');
        for (int i = 0; i < 700; i++) log.record(new JSONObject().put("index", i).put("padding", payload));
        File current = RobotFiles.file("logs/diagnostics.jsonl"), previous = RobotFiles.file("logs/diagnostics.previous.jsonl");
        if (!previous.isFile() || current.length() > 1048576 || previous.length() > 1048576) throw new AssertionError("Rotation limits");
        String tail = log.tail(false);
        if (tail.getBytes("UTF-8").length > 65536) throw new AssertionError("Tail too large");
        for (String line : tail.trim().split("\n")) new JSONObject(line);
        if (!tail.contains("\"index\":699")) throw new AssertionError("Latest sample missing");
        RobotFiles.setRoot(current); // A file cannot contain a logs directory.
        log.record(new JSONObject());
        if (log.status().getString("logWriteError").isEmpty()) throw new AssertionError("Write error not reported");
        Files.walk(root.toPath()).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        System.out.println("PASS: diagnostic persistence, rotation, bounded complete tail, and write-error isolation");
    }
}
