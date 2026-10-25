package org.biobuzz.sim;

import org.firstinspires.ftc.teamcode.dashboard.DashboardServer;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Starts the simulated robot: physics, the OpModes, and the same dashboard server the robot
 * runs, on http://localhost:8686.
 *
 * Its "SD card" is sim/sdcard/ (like /sdcard/FIRST). On first start it is filled from the
 * repo: robot-config/*.xml, settings/robot.json and autos/.
 *
 * By default it starts "Field Centric (Java)" running, so the dashboard has live telemetry
 * straight away. Options: --port N, --storage DIR, --web DIR, --fresh (empty the SD card
 * first), --init "OpMode name" [--start], --idle (start with no OpMode running), --no-seed (do
 * not copy the repo's files: the tests fill the SD card themselves).
 */
public final class SimMain {
    public static void main(String[] args) throws Exception {
        int port = DashboardServer.DEFAULT_PORT;
        File storage = new File("sdcard");
        File web = new File("../dashboard/web");
        boolean fresh = false, start = false, idle = false, noSeed = false;
        String init = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--storage": storage = new File(args[++i]); break;
                case "--web": web = new File(args[++i]); break;
                case "--fresh": fresh = true; break;
                case "--init": init = args[++i]; break;
                case "--start": start = true; break;
                case "--idle": idle = true; break;
                case "--no-seed": noSeed = true; break;
                default: throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        File repo = new File("..").getCanonicalFile();
        if (fresh) deleteTree(storage.toPath());
        if (noSeed) storage.mkdirs(); else seed(repo, storage);
        RobotFiles.setRoot(storage.getCanonicalFile());

        SimWorld world = new SimWorld();
        world.startThread();
        SimHost host = new SimHost(world, web.getCanonicalFile());
        DashboardServer server = new DashboardServer(host, port);
        server.start();
        Thread.sleep(300);
        if (!server.lastError().isEmpty()) {
            System.err.println("Dashboard server: " + server.lastError());
        }
        System.out.println("Total Chaos simulator running.");
        System.out.println("  Dashboard:  http://localhost:" + port);
        System.out.println("  SD card:    " + storage.getCanonicalPath());
        System.out.println("  Web files:  " + web.getCanonicalPath());
        System.out.println("Press Ctrl+C to stop.");
        if (init == null && !idle) {
            init = "Field Centric (Java)";
            start = true;
        }
        if (init != null) {
            host.initOpMode(init);
            if (start) host.startOpMode();
            System.out.println("  Running:    " + init + (start ? "" : " (INIT)"));
        }
        Thread.currentThread().join();
    }

    /** Copies the repo's robot files onto the fake SD card, without overwriting newer ones there. */
    static void seed(File repo, File storage) throws IOException {
        storage.mkdirs();
        File[] configs = new File(repo, "robot-config").listFiles((dir, name) -> name.endsWith(".xml"));
        if (configs != null) for (File file : configs) copyIfMissing(file, new File(storage, file.getName()));
        copyIfMissing(new File(repo, "settings/robot.json"), new File(storage, "settings/robot.json"));
        for (String folder : new String[]{"autos"}) {
            File[] files = new File(repo, folder).listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) for (File file : files) copyIfMissing(file, new File(storage, folder + "/" + file.getName()));
        }
    }

    private static void copyIfMissing(File from, File to) throws IOException {
        if (!from.isFile() || to.exists()) return;
        to.getParentFile().mkdirs();
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
