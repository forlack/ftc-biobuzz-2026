package org.firstinspires.ftc.teamcode.lib;

import com.pedropathing.api.PoseFactory;
import com.pedropathing.api.Paths;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a small, explicit subset of Pedro's export. Never compiles or executes Java. */
public class PedroPathExport {
    private final Map<String, Pose> poses = new LinkedHashMap<>();
    public final Map<String, Path> paths = new LinkedHashMap<>();
    public final Pose start;
    private static final String ID = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";

    public PedroPathExport(File file) throws IOException {
        String text = readText(file);
        text = text.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        Pattern factory = Pattern.compile("private\\s+final\\s+PoseFactory\\s+poseFactory\\s*=\\s*PoseFactory\\.degrees\\(\\)\\s*;");
        Matcher declaration = factory.matcher(text);
        if (!declaration.find()) throw new IllegalArgumentException("Export must use PoseFactory.degrees()");
        text = declaration.replaceFirst("");
        Pattern pose = Pattern.compile("private\\s+final\\s+Pose\\s+(" + ID + ")\\s*=\\s*poseFactory\\.of\\(\\s*(" + NUMBER + ")\\s*,\\s*(" + NUMBER + ")\\s*,\\s*(" + NUMBER + ")\\s*\\)\\s*;");
        Matcher p = pose.matcher(text);
        while (p.find()) {
            Pose value = PoseFactory.degrees().of(number(p.group(2)), number(p.group(3)), number(p.group(4)));
            if (poses.put(p.group(1), value) != null) throw new IllegalArgumentException("Duplicate pose: " + p.group(1));
        }
        text = p.replaceAll("");
        Pattern method = Pattern.compile("public\\s+Path\\s+(" + ID + ")\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+Paths\\.(line|curve)\\(\\s*(" + ID + "(?:\\s*,\\s*" + ID + ")*)\\s*\\)\\s*\\.(linear|constant)\\(\\s*(" + ID + "(?:\\s*,\\s*" + ID + ")*)\\s*\\)\\s*;\\s*\\}");
        Matcher m = method.matcher(text);
        while (m.find()) {
            String[] names = m.group(3).split("\\s*,\\s*");
            Pose[] points = new Pose[names.length];
            for (int i = 0; i < points.length; i++) points[i] = pose(names[i]);
            if ((m.group(2).equals("line") && points.length != 2) || points.length < 2)
                throw new IllegalArgumentException("Invalid point count in " + m.group(1));
            Path path = m.group(2).equals("line") ? Paths.line(points[0], points[1]) : Paths.curve(points);
            String[] headings = m.group(5).split("\\s*,\\s*");
            if (m.group(4).equals("linear") && headings.length == 2)
                path = path.linear(pose(headings[0]), pose(headings[1]));
            else if (m.group(4).equals("constant") && headings.length == 1)
                path = path.constant(pose(headings[0]));
            else throw new IllegalArgumentException("Invalid heading arguments in " + m.group(1));
            if (paths.put(m.group(1), path) != null) throw new IllegalArgumentException("Duplicate path: " + m.group(1));
        }
        String remaining = m.replaceAll("").trim();
        if (!remaining.isEmpty()) throw new IllegalArgumentException("Unsupported export text: " + remaining.substring(0, Math.min(80, remaining.length())));
        if (paths.isEmpty()) throw new IllegalArgumentException("Export contains no supported paths");
        start = pose("start");
    }

    /** Android 7 hubs do not provide java.nio.file.Files (added in Android 8). */
    static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Pose pose(String name) {
        Pose value = poses.get(name);
        if (value == null) throw new IllegalArgumentException("Unknown pose: " + name);
        return value;
    }

    private static double number(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite coordinate");
        return value;
    }
}
