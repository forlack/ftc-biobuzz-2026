package org.firstinspires.ftc.teamcode.dashboard.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.ftccommon.configuration.RobotConfigFile;
import com.qualcomm.ftccommon.configuration.RobotConfigFileManager;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta;
import org.firstinspires.ftc.robotcore.internal.opmode.RegisteredOpModes;
import org.firstinspires.ftc.teamcode.dashboard.DashboardServer;
import org.firstinspires.ftc.teamcode.dashboard.RobotHost;
import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.AutoFunctions;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.AutoStepFunctions;
import org.firstinspires.ftc.teamcode.mechanisms.Robot;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Starts the Total Chaos Dashboard inside the Robot Controller app, on port 8686.
 *
 * Only in builds made with -Pdashboard (./deploy-linux --dashboard). It starts 5 s after the
 * event loop, on its own threads, and every failure is caught: if anything goes wrong the
 * dashboard is simply not there, and the robot runs exactly as without it.
 */
public final class AndroidDashboard implements RobotHost, OpModeManagerNotifier.Notifications {
    private static AndroidDashboard instance;

    private final Context context;
    private final OpModeManagerImpl opModes;
    private final RobotSettings settings = new RobotSettings();
    // Only the list (names, labels, settings) of mechanisms/Robot's auton functions; routes run
    // inside PedroAutonomous. Nothing here touches hardware.
    private final Actions actions = AutoStepFunctions.create(AutoFunctions.describe(Robot.class));
    private volatile String state = "stopped";
    private DashboardServer server;

    private AndroidDashboard(Context context, OpModeManagerImpl opModes) {
        this.context = context;
        this.opModes = opModes;
    }

    @OnCreateEventLoop
    public static void start(final Context context, final FtcEventLoop eventLoop) {
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(5000);   // let the Robot Controller finish starting first
                ensureAdbOn(context.getApplicationContext());
                synchronized (AndroidDashboard.class) {
                    if (instance != null) instance.server.stop();
                    instance = new AndroidDashboard(context.getApplicationContext(), eventLoop.getOpModeManager());
                    instance.opModes.registerListener(instance);
                    instance.server = new DashboardServer(instance, DashboardServer.DEFAULT_PORT);
                    instance.server.start();
                }
            } catch (Throwable ignored) {
                // No dashboard this time. Never take the robot down with it.
            }
        }, "biobuzz-dashboard-start");
        starter.setDaemon(true);
        starter.start();
    }

    // ---- RobotHost --------------------------------------------------------

    @Override public String robotName() { return "Total Chaos 24621"; }

    @Override public boolean simulated() { return false; }

    @Override public JSONArray opModes() {
        JSONArray list = new JSONArray();
        try {
            for (OpModeMeta meta : RegisteredOpModes.getInstance().getOpModes()) {
                if (meta.flavor == OpModeMeta.Flavor.SYSTEM) continue;
                JSONObject item = new JSONObject();
                item.put("name", meta.name);
                item.put("group", meta.group);
                item.put("kind", meta.flavor == OpModeMeta.Flavor.AUTONOMOUS ? "autonomous" : "teleop");
                list.put(item);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    @Override public String activeOpMode() {
        String name = opModes.getActiveOpModeName();
        return name == null || name.equals(OpModeManager.DEFAULT_OP_MODE_NAME) ? "" : name;
    }

    @Override public String opModeState() {
        return activeOpMode().isEmpty() ? "stopped" : state;
    }

    @Override public void initOpMode(String name) { opModes.initOpMode(name); }

    @Override public void startOpMode() { opModes.startActiveOpMode(); }

    @Override public void stopOpMode() { opModes.stopActiveOpMode(); }

    @Override public double batteryVolts() {
        try {
            HardwareMap map = opModes.getHardwareMap();
            double lowest = Double.NaN;
            for (VoltageSensor sensor : map.voltageSensor) {
                double volts = sensor.getVoltage();
                if (volts > 0 && !(volts >= lowest)) lowest = volts;
            }
            return lowest;
        } catch (Throwable error) {
            return Double.NaN;
        }
    }

    private volatile String activeMap = "";
    private volatile long activeMapRead;

    /** Re-read at most every 5 s: it is asked for twice a second and only changes on the DS. */
    @Override public String activeHardwareMap() {
        long now = System.currentTimeMillis();
        if (now - activeMapRead < 5000) return activeMap;
        activeMapRead = now;
        try {
            RobotConfigFile active = new RobotConfigFileManager().getActiveConfig();
            activeMap = active.isNoConfig() ? "" : active.getName() + ".xml";
        } catch (Throwable error) {
            activeMap = "";
        }
        return activeMap;
    }

    /** The active OpMode's gamepad1 / gamepad2 fields: memory only, no hub call. */
    @Override public boolean driverStationGamepad(int number, float[] axes, int[] buttons) {
        OpMode opMode = opModes.getActiveOpMode();
        if (opMode == null || activeOpMode().isEmpty()) return false;
        Gamepad pad = number == 1 ? opMode.gamepad1 : opMode.gamepad2;
        if (pad == null) return false;
        axes[0] = pad.left_stick_x;
        axes[1] = pad.left_stick_y;
        axes[2] = pad.right_stick_x;
        axes[3] = pad.right_stick_y;
        axes[4] = pad.left_trigger;
        axes[5] = pad.right_trigger;
        boolean[] down = {pad.a, pad.b, pad.x, pad.y, pad.left_bumper, pad.right_bumper,
                pad.dpad_up, pad.dpad_down, pad.dpad_left, pad.dpad_right,
                pad.back, pad.start, pad.guide, pad.left_stick_button, pad.right_stick_button};
        int bits = 0;
        for (int i = 0; i < down.length; i++) if (down[i]) bits |= 1 << i;
        buttons[0] = bits;
        return true;
    }

    // ---- adb (Robot tab: "Restart adb") ---------------------------------------------

    private static final int ADB_PORT = 5555;
    private static final String GRANT = "adb shell pm grant com.qualcomm.ftcrobotcontroller "
            + "android.permission.WRITE_SECURE_SETTINGS";

    private static boolean canChangeAdb(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Is adb taking Wi-Fi connections on the hub (something answers on port 5555)? */
    private static boolean adbListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), 300);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    /** Safety net: if a restart was cut off half way, adb would stay off. Turn it back on. */
    private static void ensureAdbOn(Context context) {
        try {
            if (canChangeAdb(context)
                    && Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1) == 0) {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            }
        } catch (Throwable ignored) {
            // nothing more we can do
        }
    }

    @Override public JSONObject adbStatus() {
        JSONObject json = new JSONObject();
        try {
            boolean allowed = canChangeAdb(context);
            json.put("listening", adbListening());
            json.put("canRestart", allowed);
            json.put("note", allowed ? "" : "To allow Restart adb, run this once while adb works (after a reboot): " + GRANT);
        } catch (JSONException ignored) {
        }
        return json;
    }

    /** adb off, then on again: Android stops adbd and starts a fresh one. */
    @Override public synchronized String restartAdb() {
        if (!canChangeAdb(context)) return "Not allowed yet. Run this once while adb works: " + GRANT;
        android.content.ContentResolver resolver = context.getContentResolver();
        try {
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0);
            Thread.sleep(2000);
        } catch (Throwable error) {
            return "Could not turn adb off: " + error;
        } finally {
            try {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1);   // always back on
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < 20; i++) {   // up to 10 s for it to come back
            if (adbListening()) return null;
            try {
                Thread.sleep(500);
            } catch (InterruptedException error) {
                break;
            }
        }
        return "adb is on again but not answering on port " + ADB_PORT + " yet. Wait a few seconds and Refresh; "
                + "if it stays off, power-cycle the hub.";
    }

    @Override public RobotSettings settings() { return settings; }

    @Override public Actions actions() { return actions; }

    @Override public InputStream openWebFile(String path) throws IOException {
        try {
            return context.getAssets().open("biobuzz/" + path);
        } catch (FileNotFoundException error) {
            return null;
        }
    }

    // ---- OpMode notifications -------------------------------------------------

    @Override public void onOpModePreInit(OpMode opMode) { state = "init"; }

    @Override public void onOpModePreStart(OpMode opMode) { state = "running"; }

    @Override public void onOpModePostStop(OpMode opMode) { state = "stopped"; }
}
