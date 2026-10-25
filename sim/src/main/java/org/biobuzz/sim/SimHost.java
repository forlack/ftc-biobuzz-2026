package org.biobuzz.sim;

import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.dashboard.RobotHost;
import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.StandardActions;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The simulated Robot Controller: a list of OpModes, and one OpMode thread that runs
 * init / init_loop / loop like the SDK does, about 100 times a second.
 */
public class SimHost implements RobotHost {
    private final SimWorld world;
    private final File webRoot;
    static {
        // new RobotSettings() anywhere in the shared code finds the same classes as the robot.
        RobotSettings.setFinder(() -> java.util.Arrays.asList(SimOpModes.SETTINGS));
    }

    private final RobotSettings settings = new RobotSettings(SimOpModes.SETTINGS);
    private final Actions catalog;
    private final Map<String, Supplier<SimOpModes.SimOpMode>> opModes = new LinkedHashMap<>();
    private final Map<String, String> kinds = new LinkedHashMap<>();

    private volatile String active = "";
    private volatile String state = "stopped";
    private volatile SimOpModes.SimOpMode current;
    private Thread thread;

    public SimHost(SimWorld world, File webRoot) {
        this.world = world;
        this.webRoot = webRoot;
        this.catalog = StandardActions.create(SimOpModes.functions(new SimOpModes.SimMechanisms(world)));
        add("Field Centric (Java)", "teleop", SimOpModes.TeleOp::new);
        add("Pedro Pathing Autonomous", "autonomous", SimOpModes.Autonomous::new);
        settings.load();
        startIdleTelemetry();
    }

    /**
     * With no OpMode running the robot still has a battery and coasting wheels, so keep
     * sending those 20 times a second: the charts never go blank between runs.
     */
    private void startIdleTelemetry() {
        Thread idle = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(50);
                    if (current == null) {
                        Dash.put("Battery V", world.battery());
                        Dash.put("Pollen RPM", world.pollen.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV));
                        Dash.put("Pollen target", 0);
                        Dash.put("Nectar RPM", world.nectar.rpm(SimWorld.FLYWHEEL_TICKS_PER_REV));
                        Dash.put("Nectar target", 0);
                        Dash.put("Intake RPM", world.intake.rpm(SimWorld.INTAKE_TICKS_PER_REV));
                        Dash.put("Intake target", 0);
                        Dash.put("Status", "No OpMode running");
                        double[] pose = world.pose();
                        Dash.pose(pose[0], pose[1], pose[2]);
                        Dash.frame();
                    }
                } catch (InterruptedException error) {
                    return;
                } catch (Throwable ignored) {
                    // Keep going.
                }
            }
        }, "sim-idle-telemetry");
        idle.setDaemon(true);
        idle.start();
    }

    private void add(String name, String kind, Supplier<SimOpModes.SimOpMode> factory) {
        opModes.put(name, factory);
        kinds.put(name, kind);
    }

    @Override public String robotName() { return "Total Chaos 24621 (simulator)"; }

    @Override public boolean simulated() { return true; }

    @Override public JSONArray opModes() {
        JSONArray list = new JSONArray();
        for (String name : opModes.keySet()) {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("group", kinds.get(name).equals("teleop") ? "Drive" : "Autonomous");
            item.put("kind", kinds.get(name));
            list.put(item);
        }
        return list;
    }

    @Override public String activeOpMode() { return active; }

    @Override public String opModeState() { return state; }

    @Override public void initOpMode(String name) throws Exception {
        Supplier<SimOpModes.SimOpMode> factory = opModes.get(name);
        if (factory == null) throw new IllegalArgumentException("No OpMode called " + name);
        stopOpMode();
        SimOpModes.SimOpMode opMode = factory.get();
        opMode.attach(world, settings);
        world.reset();
        opMode.init();   // throws here, like a crash at INIT on the robot, if the route is bad
        synchronized (this) {
            current = opMode;
            active = name;
            state = "init";
            thread = new Thread(() -> run(opMode), "sim-opmode");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void run(SimOpModes.SimOpMode opMode) {
        boolean started = false;
        try {
            while (current == opMode) {
                if (state.equals("running")) {
                    if (!started) {
                        opMode.start();
                        started = true;
                    }
                    opMode.loop();
                    if (opMode instanceof SimOpModes.Autonomous && ((SimOpModes.Autonomous) opMode).overTime()) {
                        Dash.put("Status", "30 s autonomous timer ran out");
                        Dash.frame();
                        break;
                    }
                } else {
                    opMode.initLoop();
                }
                Dash.frame();
                Thread.sleep(10);
            }
        } catch (Throwable error) {
            Dash.put("Status", "OpMode crashed: " + error);
            Dash.frame();
        } finally {
            try {
                opMode.stop();
            } catch (Throwable ignored) {
            }
            world.stopAllMotors();   // like the SDK: nothing keeps running after Stop
            synchronized (this) {
                if (current == opMode) {
                    current = null;
                    active = "";
                    state = "stopped";
                }
            }
        }
    }

    @Override public synchronized void startOpMode() {
        if (current == null) throw new IllegalStateException("INIT an OpMode first");
        state = "running";
    }

    @Override public void stopOpMode() throws InterruptedException {
        Thread running;
        synchronized (this) {
            running = thread;
            current = null;
            active = "";
            state = "stopped";
        }
        if (running != null) running.join(2000);
    }

    @Override public double batteryVolts() { return world.battery(); }

    /** Like the Robot Controller's remembered active config, kept next to the sim's SD card. */
    private static File activeMapFile() {
        return new File(org.firstinspires.ftc.teamcode.lib.RobotFiles.root(), ".sim-active-config");
    }

    @Override public String activeHardwareMap() {
        try {
            String name = new String(java.nio.file.Files.readAllBytes(activeMapFile().toPath()), "UTF-8").trim();
            if (org.firstinspires.ftc.teamcode.lib.RobotFiles.file(name).isFile()) return name;
        } catch (IOException ignored) {
            // never switched: the default below
        }
        return org.firstinspires.ftc.teamcode.lib.RobotFiles.file("2222-Config.xml").isFile() ? "2222-Config.xml" : "";
    }

    @Override public String useHardwareMap(String file) {
        try {
            java.nio.file.Files.write(activeMapFile().toPath(), file.getBytes("UTF-8"));
            return null;
        } catch (IOException error) {
            return "Could not switch: " + error.getMessage();
        }
    }

    @Override public RobotSettings settings() { return settings; }

    @Override public Actions actions() { return catalog; }

    @Override public InputStream openWebFile(String path) throws IOException {
        File file = new File(webRoot, path);
        if (!file.getCanonicalPath().startsWith(webRoot.getCanonicalPath()) || !file.isFile()) return null;
        return new FileInputStream(file);
    }
}
