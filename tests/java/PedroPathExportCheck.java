import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.firstinspires.ftc.teamcode.lib.AutoPathFile;
import org.firstinspires.ftc.teamcode.lib.PedroPathExport;

/** Standalone check using the real Pedro core and org.json jars. */
public class PedroPathExportCheck {
    static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-8) throw new AssertionError(actual + " != " + expected);
    }
    static void reject(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try { new PedroPathExport(file); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted invalid export");
    }
    public static void main(String[] args) throws Exception {
        AutoPathFile route = new AutoPathFile(new File("paths/auto.json"));
        near(route.start.y(), 9);
        near(route.start.heading(), Math.toRadians(270));
        near(route.path1.endPose().x(), 60);
        near(route.path2.endPose().x(), 13.2008);
        near(route.path2.endPose().y(), 96.0738);
        near(route.path2.curve.get(.5).x(), 26.14615);
        near(route.path2.curve.get(.5).y(), 42.6841);
        near(route.path2.endPose().heading(), Math.PI / 2);
        near(route.shootSeconds, 3.5);
        String text = new String(Files.readAllBytes(new File("paths/auto.java.txt").toPath()), StandardCharsets.UTF_8);
        // Other fixtures below exercise the snippet form as well as the full export.
        text = text.substring(text.indexOf("private final PoseFactory"), text.lastIndexOf('}')).trim();
        File temp = File.createTempFile("pedro-export", ".txt");
        try {
            String wrapped = "import static com.pedropathing.api.Paths.*;\nimport com.pedropathing.api.Paths;\nimport com.pedropathing.api.PoseFactory;\nimport com.pedropathing.math.Pose;\nimport com.pedropathing.paths.Path;\npublic class Paths {\n" + text + "\n}";
            Files.write(temp.toPath(), wrapped.getBytes(StandardCharsets.UTF_8));
            PedroPathExport complete = new PedroPathExport(temp);
            near(complete.start.y(), 9);
            near(complete.paths.get("path2").endPose().y(), 96.0738);
            reject(temp, wrapped + "visualizer page text");
            String six = text;
            for (int i = 3; i <= 6; i++) six += "public Path path" + i + "() { return Paths.curve(point2, point2Control1, path1, start).constant(start); }";
            Files.write(temp.toPath(), six.getBytes(StandardCharsets.UTF_8));
            PedroPathExport parsed = new PedroPathExport(temp);
            if (parsed.paths.size() != 6) throw new AssertionError("Missing paths");
            near(parsed.paths.get("path6").endPose().x(), 56);
            near(parsed.paths.get("path6").endPose().heading(), Math.toRadians(270));
            reject(temp, text + "System.exit(0);");
            reject(temp, text.replace("Paths.line(start, path1)", "Paths.line(missing, path1)"));
            reject(temp, text.replace(".linear(start, path1)", ".tangent()"));
            reject(temp, text.replace("degrees()", "radians()"));
            reject(temp, text + "public Path path1() { return Paths.line(start, path1).constant(start); }");
            reject(temp, text.replace("(56, 9, 270)", "(1e999, 9, 270)"));
            reject(temp, text.replace("Paths.line(start, path1)", "Paths.line(start)"));
        } finally { temp.delete(); }
        System.out.println("PASS: exact route, six paths, cubic curve, constant heading, invalid exports rejected");
    }
}
