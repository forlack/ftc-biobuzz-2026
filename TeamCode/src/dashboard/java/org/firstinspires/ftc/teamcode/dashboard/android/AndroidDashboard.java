package org.firstinspires.ftc.teamcode.dashboard.android;

import android.content.Context;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.ftccommon.configuration.RobotConfigFile;
import com.qualcomm.ftccommon.configuration.RobotConfigFileManager;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta;
import org.firstinspires.ftc.robotcore.internal.opmode.RegisteredOpModes;
import org.firstinspires.ftc.teamcode.FieldCentricJava;
import org.firstinspires.ftc.teamcode.dashboard.DashboardServer;
import org.firstinspires.ftc.teamcode.dashboard.RobotHost;
import org.firstinspires.ftc.teamcode.lib.Actions;
import org.firstinspires.ftc.teamcode.lib.Mechanisms;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.StandardActions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Starts the BioBuzz dashboard inside the Robot Controller app, on port 8686.
 *
 * Only in builds made with -Pdashboard (./deploy-linux --dashboard). It starts 5 s after the
 * event loop, on its own threads, and every failure is caught: if anything goes wrong the
 * dashboard is simply not there, and the robot runs exactly as without it.
 */
public final class AndroidDashboard implements RobotHost, OpModeManagerNotifier.Notifications {
    private static AndroidDashboard instance;

    private final Context context;
    private final OpModeManagerImpl opModes;
    private final RobotSettings settings = new RobotSettings(FieldCentricJava.SETTINGS);
    private final Actions actions = StandardActions.create(new Mechanisms() {
        // Only the list of actions is used here; routes run inside PedroAutonomous.
        @Override public void intake(boolean on) { }
        @Override public void flywheels(boolean on) { }
        @Override public boolean flywheelsReady() { return true; }
        @Override public void feed(boolean on) { }
        @Override public void stinger(boolean down) { }
    });
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

    @Override public String robotName() { return "BioBuzz 24621"; }

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

    @Override public String activeHardwareMap() {
        try {
            RobotConfigFile active = new RobotConfigFileManager().getActiveConfig();
            return active.isNoConfig() ? "" : active.getName() + ".xml";
        } catch (Throwable error) {
            return "";
        }
    }

    @Override public RobotSettings settings() { return settings; }

    @Override public Actions actions() { return actions; }

    @Override public InputStream openWebFile(String path) throws IOException {
        try {
            return context.getAssets().open("biobuzz/" + path);   // not "web/": that is Panels'
        } catch (FileNotFoundException error) {
            return null;
        }
    }

    // ---- OpMode notifications -------------------------------------------------

    @Override public void onOpModePreInit(OpMode opMode) { state = "init"; }

    @Override public void onOpModePreStart(OpMode opMode) { state = "running"; }

    @Override public void onOpModePostStop(OpMode opMode) { state = "stopped"; }
}
