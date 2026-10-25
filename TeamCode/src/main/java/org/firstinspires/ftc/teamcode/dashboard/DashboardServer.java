package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.firstinspires.ftc.teamcode.lib.RobotHistory;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The Total Chaos Dashboard's web server: the page itself, a small JSON API, and a websocket that
 * streams live telemetry.
 *
 * Plain Java sockets only, so the exact same server runs in the Robot Controller app and in
 * the desktop simulator. Every request is handled inside try/catch on its own thread: a broken
 * request or a dropped browser can never reach, let alone crash, the robot's OpModes.
 *
 * API (all JSON unless noted):
 *   GET  /api/state                     robot, OpModes, active OpMode, battery
 *   GET  /api/settings                  every setting with value, default, value in file
 *   POST /api/settings                  {group, name, value}: change one now
 *   POST /api/settings/save             write robot.json
 *   POST /api/settings/reload           re-read robot.json
 *   GET  /api/actions                   autonomous actions for the path editor
 *   GET  /api/files                     robot files the dashboard may read and write:
 *                                       settings/robot.json, autos/*.json, hardware maps (*.xml)
 *   GET  /api/file?path=...             raw file, with its hash in X-Hash
 *   PUT  /api/file?path=...             replace a file; send X-Base-Hash to refuse if it changed
 *   POST /api/opmode                    {action: init|start|stop, name}
 *   GET  /api/history?path=...          saved versions of a file; /api/history/file?path=&id=
 *   POST /api/history/restore           {path, id}
 *   GET  /api/snapshots                 known-good snapshots; POST {name} makes one
 *   POST /api/snapshots/restore         {id}
 *   POST /api/lock                      {action: lock|unlock, pin}. While locked, every change
 *                                       is refused with 423 until unlocked.
 *   GET  /api/adb                       adb on the robot: {listening, canRestart, note}
 *   POST /api/adb/restart               turn adb off and on (needs a one-time grant, see
 *                                       AndroidDashboard); answers with the new status
 *   GET  /ws                            websocket: telemetry frames and state; also the
 *                                       virtual gamepads (see VirtualGamepads):
 *                                       {type: control, pad, take: true|false} answered with
 *                                       {type: control, pad, owner, error}, then
 *                                       {type: gamepad, pad, axes: [6], buttons: bits} 20 a second
 */
public class DashboardServer {
    public static final int DEFAULT_PORT = 8686;
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_SOCKETS = 8;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Files the dashboard may touch, relative to the robot's storage folder. */
    private static final Pattern MANAGED = Pattern.compile(
            "settings/robot\\.json|autos/[A-Za-z0-9_.-]+\\.json|[A-Za-z0-9_ .-]+\\.xml");

    /** History and snapshot copies: readable (for robot-sync and the History panel), never writable. */
    private static final Pattern ARCHIVE = Pattern.compile(
            "(history|snapshots)/[A-Za-z0-9_. -]+(/[A-Za-z0-9_. -]+)+");

    private final RobotHost host;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "dashboard");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger sockets = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile String lastError = "";
    private final DashboardLock lock;

    private final TelemetryStream stream = new TelemetryStream(this::state, this::gamepadsMessage);
    private volatile JSONObject load = new JSONObject();   // what the dashboard costs the robot

    public DashboardServer(RobotHost host, int port) {
        this.host = host;
        this.port = port;
        this.lock = new DashboardLock();
    }

    /** Starts listening on a background thread. Never throws; check {@link #lastError}. */
    public synchronized void start() {
        if (running) return;
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "dashboard-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        Thread broadcaster = new Thread(() -> stream.broadcast(() -> running), "dashboard-stream");
        broadcaster.setDaemon(true);
        broadcaster.start();
        Thread meter = new Thread(this::measureLoad, "dashboard-load");
        meter.setDaemon(true);
        meter.start();
    }

    /**
     * Once a second: CPU used by the dashboard's own work (preparing and sending data to
     * browsers), CPU used by the whole app (from /proc/self/stat, like top), viewers, Wi-Fi
     * traffic, and the OpMode's loop time and rate.
     */
    private void measureLoad() {
        long lastWall = System.nanoTime(), lastBusy = stream.busyNanos.get(), lastBytes = stream.bytesSent.get();
        long lastTicks = cpuTicks(), lastSeq = Dash.latestSeq();
        while (running) {
            sleepQuietly(1000);
            try {
                long wall = System.nanoTime(), busy = stream.busyNanos.get(), bytes = stream.bytesSent.get(), ticks = cpuTicks();
                double seconds = (wall - lastWall) / 1e9;
                List<Dash.Frame> frames = Dash.since(lastSeq, 100000);
                double loopSum = 0, costSum = 0;
                int loopCount = 0, costCount = 0;
                for (Dash.Frame frame : frames) {
                    Double ms = frame.numbers.get("Loop ms");
                    if (ms != null) {
                        loopSum += ms;
                        loopCount++;
                    }
                    Double cost = frame.numbers.get(Dash.COST_KEY);
                    if (cost != null) {
                        costSum += cost;
                        costCount++;
                    }
                }
                if (!frames.isEmpty()) lastSeq = frames.get(frames.size() - 1).seq;
                JSONObject json = new JSONObject();
                json.put("viewers", stream.viewers.get());
                json.put("liveViewers", stream.liveViewers.get());
                json.put("dashboardCpu", round((busy - lastBusy) / 1e9 / seconds * 100, 1));
                json.put("appCpu", ticks < 0 || lastTicks < 0 ? JSONObject.NULL
                        : (Object) round((ticks - lastTicks) / 100.0 / seconds * 100, 1));
                json.put("cores", Runtime.getRuntime().availableProcessors());
                json.put("kbPerSecond", round((bytes - lastBytes) / 1024.0 / seconds, 1));
                json.put("loopsPerSecond", round(frames.size() / seconds, 1));
                json.put("loopMs", loopCount == 0 ? JSONObject.NULL : (Object) round(loopSum / loopCount, 2));
                // Time each loop spent in Dash (put/pose/frame): the dashboard's direct cost.
                json.put("dashboardMs", costCount == 0 ? JSONObject.NULL : (Object) round(costSum / costCount, 3));
                json.put("maxSamplesPerSecond", DashboardConfig.MAX_SAMPLES_PER_SECOND);
                load = json;
                lastWall = wall;
                lastBusy = busy;
                lastBytes = bytes;
                lastTicks = ticks;
            } catch (Throwable ignored) {
                // Measuring must never break the dashboard.
            }
        }
    }

    /** User + system CPU time of this whole process in clock ticks (100 a second), or -1. */
    private static long cpuTicks() {
        try {
            String stat = new String(RobotFiles.readBytes(new File("/proc/self/stat")), StandardCharsets.US_ASCII);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);   // utime, stime
        } catch (Throwable error) {
            return -1;   // not Linux or Android
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    public String lastError() {
        return lastError;
    }

    public int port() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket listening = new ServerSocket()) {
                listening.setReuseAddress(true);
                listening.bind(new InetSocketAddress(port));
                server = listening;
                while (running) {
                    final Socket socket = listening.accept();
                    pool.execute(() -> handle(socket));
                }
            } catch (Throwable error) {
                lastError = String.valueOf(error);
                if (!running) return;
                sleepQuietly(2000);   // port busy or network down: try again later
            }
        }
    }

    // ---- HTTP --------------------------------------------------------------

    private static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }

        String param(String name) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).equals(name)) {
                    try {
                        return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                    } catch (IOException error) {
                        return null;
                    }
                }
            }
            return null;
        }

        JSONObject json() throws JSONException {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        }
    }

    private static final class Response {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        final Map<String, String> headers = new LinkedHashMap<>();

        static Response json(int status, Object json) {
            Response response = new Response();
            response.status = status;
            response.body = json.toString().getBytes(StandardCharsets.UTF_8);
            return response;
        }

        static Response error(int status, String message) {
            JSONObject json = new JSONObject();
            try {
                json.put("ok", false);
                json.put("error", message);
            } catch (JSONException ignored) {
            }
            return json(status, json);
        }
    }

    private void handle(Socket socket) {
        if (sockets.incrementAndGet() > MAX_SOCKETS * 4) {
            sockets.decrementAndGet();
            closeQuietly(socket);
            return;
        }
        try {
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            InputStream input = socket.getInputStream();
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            Request request = readRequest(input);
            if (request == null) return;
            if (request.path.equals("/ws") && request.header("upgrade").equalsIgnoreCase("websocket")) {
                webSocket(socket, request, input, output);
                return;
            }
            Response response;
            try {
                response = route(request);
            } catch (IllegalArgumentException | JSONException error) {
                response = Response.error(400, error.getMessage());
            } catch (Throwable error) {
                response = Response.error(500, String.valueOf(error));
            }
            write(output, response);
        } catch (Throwable ignored) {
            // Dropped connection or garbage request: nothing to do.
        } finally {
            sockets.decrementAndGet();
            closeQuietly(socket);
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = input.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > 16384) throw new IOException("header too large");
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request request = new Request();
        request.method = first[0];
        String target = first[1];
        int question = target.indexOf('?');
        request.path = question < 0 ? target : target.substring(0, question);
        request.query = question < 0 ? "" : target.substring(question + 1);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                request.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }
        String length = request.header("content-length");
        if (!length.isEmpty()) {
            int size = Integer.parseInt(length);
            if (size < 0 || size > MAX_BODY) throw new IOException("body too large");
            byte[] body = new byte[size];
            int read = 0;
            while (read < size) {
                int count = input.read(body, read, size - read);
                if (count < 0) throw new IOException("body cut short");
                read += count;
            }
            request.body = body;
        }
        return request;
    }

    private static void write(OutputStream output, Response response) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n");
        head.append("Content-Type: ").append(response.type).append("\r\n");
        head.append("Content-Length: ").append(response.body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: Content-Type, X-Base-Hash\r\n");
        head.append("Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n");
        head.append("Access-Control-Expose-Headers: X-Hash\r\n");
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(response.body);
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 423: return "Locked";
            default: return "Error";
        }
    }

    // ---- Routes -------------------------------------------------------------

    private Response route(Request request) throws Exception {
        String method = request.method;
        String path = request.path;
        if (method.equals("OPTIONS")) {
            Response response = new Response();
            response.status = 204;
            return response;
        }
        if (path.startsWith("/api/")) {
            boolean changes = !method.equals("GET") && !path.equals("/api/lock");
            if (changes && lock.locked()) {
                return Response.error(423, "The dashboard is locked. Unlock it with the PIN to make changes.");
            }
            switch (method + " " + path) {
                case "GET /api/state": return Response.json(200, state());
                case "GET /api/settings": return Response.json(200, settingsJson());
                case "POST /api/settings": return setSetting(request.json());
                case "POST /api/settings/save": {
                    RobotSettings settings = host.settings();
                    settings.save();
                    return Response.json(200, settingsJson());
                }
                case "POST /api/settings/reload": {
                    host.settings().reload();
                    return Response.json(200, settingsJson());
                }
                case "GET /api/actions": return Response.json(200, host.actions().catalog());
                case "GET /api/files": return Response.json(200, listFiles(request.param("all") != null));
                case "GET /api/file": return getFile(request.param("path"));
                case "PUT /api/file":
                case "POST /api/file": return putFile(request);
                case "POST /api/opmode": return opMode(request.json());
                case "GET /api/history": return Response.json(200, history(checkPath(request.param("path"))));
                case "GET /api/history/file": return historyFile(checkPath(request.param("path")), request.param("id"));
                case "POST /api/history/restore": return restoreVersion(request.json());
                case "GET /api/snapshots": return Response.json(200, Snapshots.list());
                case "POST /api/snapshots": return Response.json(200, Snapshots.create(request.json().getString("name"), managedNames()));
                case "POST /api/snapshots/restore": return restoreSnapshot(request.json().getString("id"));
                case "POST /api/lock": return lockAction(request.json());
                case "POST /api/hardware/active": return useHardwareMap(request.json());
                case "GET /api/adb": return adb(null);
                case "POST /api/adb/restart": return adb(host.restartAdb());
                default: return Response.error(404, "no such API: " + method + " " + path);
            }
        }
        if (!method.equals("GET")) return Response.error(405, "only GET for pages");
        return staticFile(path);
    }

    private JSONObject state() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("robot", host.robotName());
        json.put("simulated", host.simulated());
        json.put("opModes", host.opModes());
        json.put("active", host.activeOpMode());
        json.put("state", host.opModeState());
        json.put("battery", battery());
        json.put("hardwareMap", host.activeHardwareMap());
        json.put("settingsStatus", host.settings().status());
        json.put("locked", lock.locked());
        json.put("gamepads", gamepadOwners());
        json.put("load", load);
        json.put("time", Dash.now());
        return json;
    }

    /**
     * While an OpMode runs, the server never reads the hubs itself: its reads make the OpMode's
     * hub calls wait (seen on the robot: ~40 ms loop spikes every 500 ms, the state message's
     * rate). It shows the "Battery V" the OpMode sends instead, and reads the hubs only when
     * nothing is running.
     */
    private Object battery() {
        if (!host.activeOpMode().isEmpty()) {
            Double sent = Dash.latestNumber("Battery V", 2.0);
            return sent == null ? JSONObject.NULL : (Object) round(sent, 2);
        }
        double volts = host.batteryVolts();
        return Double.isNaN(volts) ? JSONObject.NULL : (Object) round(volts, 2);
    }

    private JSONObject settingsJson() throws JSONException {
        RobotSettings settings = host.settings();
        JSONObject json = new JSONObject();
        json.put("status", settings.status());
        json.put("warnings", new JSONArray(settings.warnings()));
        json.put("items", settings.describe());
        json.put("file", RobotSettings.location().isFile() ? fileInfo("settings/robot.json") : JSONObject.NULL);
        return json;
    }

    private Response setSetting(JSONObject body) throws JSONException {
        String problem = host.settings().set(body.getString("group"), body.getString("name"), body.opt("value"));
        if (problem != null) return Response.error(400, problem);
        return Response.json(200, settingsJson());
    }

    private Response opMode(JSONObject body) throws Exception {
        String action = body.getString("action");
        switch (action) {
            case "init": host.initOpMode(body.getString("name")); break;
            case "start": host.startOpMode(); break;
            case "stop": host.stopOpMode(); break;
            default: throw new IllegalArgumentException("action must be init, start or stop");
        }
        return Response.json(200, state());
    }

    // ---- Files ------------------------------------------------------------

    private static String checkPath(String relative) {
        if (relative == null || relative.contains("..") || !MANAGED.matcher(relative).matches()) {
            throw new IllegalArgumentException("The dashboard cannot use that file: " + relative);
        }
        return relative;
    }

    private JSONArray listFiles(boolean withArchive) throws JSONException, IOException {
        JSONArray list = new JSONArray();
        List<String> names = managedNames();
        if (withArchive) {
            for (String folder : new String[]{RobotHistory.FOLDER, Snapshots.FOLDER}) {
                addTree(RobotFiles.file(folder), folder, names);
            }
        }
        for (String name : names) list.put(fileInfo(name));
        return list;
    }

    private static void addTree(File folder, String relative, List<String> names) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            String child = relative + "/" + file.getName();
            if (file.isDirectory()) addTree(file, child, names);
            else if (ARCHIVE.matcher(child).matches()) names.add(child);
        }
    }

    /** Every robot file the dashboard manages: settings, paths, autons, hardware maps. */
    private static List<String> managedNames() {
        List<String> names = new ArrayList<>();
        if (RobotSettings.location().isFile()) names.add("settings/robot.json");
        for (String folder : new String[]{"autos", ""}) {
            File[] files = RobotFiles.file(folder).listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File file : files) {
                String relative = folder.isEmpty() ? file.getName() : folder + "/" + file.getName();
                if (file.isFile() && MANAGED.matcher(relative).matches()) names.add(relative);
            }
        }
        return names;
    }

    private static JSONObject fileInfo(String relative) throws JSONException {
        File file = RobotFiles.file(relative);
        JSONObject info = new JSONObject();
        info.put("path", relative);
        info.put("size", file.length());
        info.put("modified", file.lastModified());
        try {
            info.put("hash", RobotFiles.hash(RobotFiles.readBytes(file)));
        } catch (IOException error) {
            info.put("hash", JSONObject.NULL);
        }
        return info;
    }

    private Response getFile(String relative) throws IOException {
        boolean archive = relative != null && !relative.contains("..") && ARCHIVE.matcher(relative).matches();
        File file = RobotFiles.file(archive ? relative : checkPath(relative));
        if (!file.isFile()) return Response.error(404, "no such file on the robot: " + relative);
        byte[] contents = RobotFiles.readBytes(file);
        Response response = new Response();
        response.type = relative.endsWith(".json") ? "application/json; charset=utf-8"
                : relative.endsWith(".xml") ? "application/xml; charset=utf-8" : "text/plain; charset=utf-8";
        response.body = contents;
        response.headers.put("X-Hash", RobotFiles.hash(contents));
        return response;
    }

    private synchronized Response putFile(Request request) throws Exception {
        String relative = checkPath(request.param("path"));
        File file = RobotFiles.file(relative);
        String base = request.header("x-base-hash");
        if (!base.isEmpty() && !base.equals("*")) {
            String current = file.isFile() ? RobotFiles.hash(RobotFiles.readBytes(file)) : "new";
            if (!current.equals(base)) {
                return Response.error(409, relative + " changed on the robot since you opened it. Reload it first.");
            }
        }
        return save(relative, request.body);
    }

    /** Checks a file, then saves it (which also records it in the history). */
    private synchronized Response save(String relative, byte[] contents) throws Exception {
        String problem = validate(relative, contents);
        if (problem != null) return Response.error(400, problem);
        RobotFiles.writeAtomically(RobotFiles.file(relative), contents);
        if (relative.equals("settings/robot.json")) host.settings().reload();
        JSONObject json = fileInfo(relative);
        json.put("ok", true);
        return Response.json(200, json);
    }

    // ---- History, snapshots, lock ----------------------------------------------------

    private static JSONObject history(String relative) throws JSONException {
        JSONArray versions = new JSONArray();
        for (String id : RobotHistory.versionIds(relative)) {
            File file = RobotHistory.version(relative, id);
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("time", file.lastModified());
            item.put("size", file.length());
            item.put("original", id.contains("-original"));
            try {
                item.put("hash", RobotFiles.hash(RobotFiles.readBytes(file)));
            } catch (IOException error) {
                item.put("hash", JSONObject.NULL);
            }
            versions.put(item);
        }
        JSONObject json = new JSONObject();
        json.put("path", relative);
        json.put("versions", versions);
        File current = RobotFiles.file(relative);
        if (current.isFile()) {
            try {
                json.put("currentHash", RobotFiles.hash(RobotFiles.readBytes(current)));
            } catch (IOException ignored) {
            }
        }
        return json;
    }

    private static Response historyFile(String relative, String id) throws IOException {
        File file = RobotHistory.version(relative, id);
        if (!file.isFile()) return Response.error(404, "no such version");
        Response response = new Response();
        response.type = "text/plain; charset=utf-8";
        response.body = RobotFiles.readBytes(file);
        return response;
    }

    private Response restoreVersion(JSONObject body) throws Exception {
        String relative = checkPath(body.getString("path"));
        File file = RobotHistory.version(relative, body.getString("id"));
        if (!file.isFile()) return Response.error(404, "no such version");
        return save(relative, RobotFiles.readBytes(file));
    }

    /** Puts every file in a snapshot back. All are checked before any is written. */
    private synchronized Response restoreSnapshot(String id) throws Exception {
        JSONObject manifest = Snapshots.manifest(id);
        List<String> files = Snapshots.filesOf(manifest);
        for (String relative : files) {
            checkPath(relative);
            String problem = validate(relative, Snapshots.read(id, relative));
            if (problem != null) return Response.error(400, "Snapshot not restored: " + problem);
        }
        JSONArray restored = new JSONArray();
        for (String relative : files) {
            RobotFiles.writeAtomically(RobotFiles.file(relative), Snapshots.read(id, relative));
            restored.put(relative);
        }
        if (files.contains("settings/robot.json")) host.settings().reload();
        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("name", manifest.optString("name"));
        json.put("restored", restored);
        return Response.json(200, json);
    }

    private Response lockAction(JSONObject body) throws Exception {
        String action = body.getString("action");
        String pin = body.optString("pin", null);
        if (action.equals("lock")) {
            if (lock.locked()) return Response.error(400, "Already locked");
            lock.lock(pin);
            VirtualGamepads.releaseIfNot("");   // locked is view only: nobody drives
        } else if (action.equals("unlock")) {
            if (!lock.unlock(pin)) return Response.error(403, "Wrong PIN");
        } else {
            throw new IllegalArgumentException("action must be lock or unlock");
        }
        JSONObject json = new JSONObject();
        json.put("locked", lock.locked());
        return Response.json(200, json);
    }

    /** Switches the active hardware map, where the host can; otherwise says how. */
    /** adb status, plus the result of a restart when {@code problem} came from one. */
    private Response adb(String problem) throws JSONException {
        JSONObject status = host.adbStatus();
        if (status == null) {
            status = new JSONObject();
            status.put("listening", JSONObject.NULL);
            status.put("canRestart", false);
            status.put("note", "The simulator has no adb.");
        }
        if (problem != null) status.put("error", problem);
        return Response.json(200, status);
    }

    private Response useHardwareMap(JSONObject body) throws JSONException {
        String path = body.optString("path", "");
        if (!path.matches("[A-Za-z0-9_ .-]+\\.xml") || !RobotFiles.file(path).isFile()) {
            return Response.error(400, "No hardware map called " + path);
        }
        String instead = host.useHardwareMap(path);
        JSONObject json = new JSONObject();
        json.put("active", host.activeHardwareMap());
        json.put("switched", instead == null);
        if (instead != null) json.put("message", instead);
        return Response.json(200, json);
    }

    /** Refuses files the robot could not use, before they replace a good one. */
    private String validate(String relative, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            if (relative.endsWith(".xml")) {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(body));
            } else if (relative.endsWith(".json")) {
                JSONObject json = new JSONObject(text);
                if (relative.equals("settings/robot.json") && json.optInt("version", 0) != 1) {
                    return "robot.json must say \"version\": 1";
                }
                if (relative.startsWith("autos/")) {
                    AutoRoute route = new AutoRoute(text);
                    // Also check every step and action name, with a stand-in follower.
                    route.build(null, host.actions());
                }
            }
            return null;
        } catch (Exception error) {
            return relative + ": " + error.getMessage();
        }
    }

    // ---- Static page ----------------------------------------------------------

    private Response staticFile(String path) throws IOException {
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        if (relative.endsWith("/")) relative += "index.html";
        if (relative.contains("..")) return Response.error(404, "not found");
        InputStream input = host.openWebFile(relative);
        if (input == null) {
            // The path editor asks for its own files at the root (/fields/..., /robot.png).
            input = host.openWebFile("visualizer/" + relative);
        }
        if (input == null) return Response.error(404, "not found: " + relative);
        try {
            Response response = new Response();
            response.body = RobotFiles.readAll(input);
            response.type = mimeType(relative);
            return response;
        } finally {
            input.close();
        }
    }

    private static String mimeType(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ---- WebSocket ------------------------------------------------------------

    private void webSocket(Socket socket, Request request, InputStream input, OutputStream output) throws Exception {
        String key = request.header("sec-websocket-key");
        byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes(StandardCharsets.ISO_8859_1));
        String accept = Base64Codec.encode(digest);
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        socket.setSoTimeout(0);

        TelemetryStream.Viewer viewer = new TelemetryStream.Viewer();
        // Reader: notices when the browser leaves, and which data it wants.
        pool.execute(() -> {
            try {
                while (viewer.open) {
                    String[] text = new String[1];
                    int opcode = readFrame(input, text);
                    if (opcode == 8 || opcode < 0) break;
                    if (opcode == 1 && text[0] != null) {
                        JSONObject message = new JSONObject(text[0]);
                        String type = message.optString("type");
                        if ("subscribe".equals(type)) {
                            viewer.wantsFrames = message.optBoolean("frames", true);
                            viewer.wantsGamepads = message.optBoolean("gamepads", false);
                            stream.poke();
                        } else if ("control".equals(type)) {
                            control(viewer, message, output);
                        } else if ("gamepad".equals(type)) {
                            gamepadInput(viewer, message);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                viewer.open = false;
                VirtualGamepads.release(viewer.id, 0);   // the browser left: its gamepad stops now
                stream.poke();
            }
        });
        stream.serve(viewer, output, () -> running);
        try {
            output.write(new byte[]{(byte) 0x88, 0});
            output.flush();
        } catch (IOException ignored) {
        }
    }

    // ---- Virtual gamepads ------------------------------------------------------------

    /** Take control of a gamepad, or give it back; the answer goes to that browser only. */
    private void control(TelemetryStream.Viewer viewer, JSONObject message, OutputStream output) throws Exception {
        int pad = message.optInt("pad", 1) == 2 ? 2 : 1;
        String error = null;
        if (!message.optBoolean("take", false)) {
            VirtualGamepads.release(viewer.id, pad);
        } else if (lock.locked()) {
            error = "The dashboard is locked. Unlock it with the PIN to drive.";
        } else {
            error = VirtualGamepads.take(viewer.id, pad, host.activeOpMode());
        }
        JSONObject reply = new JSONObject();
        reply.put("type", "control");
        reply.put("pad", pad);
        reply.put("owner", VirtualGamepads.owner(pad));
        if (error != null) reply.put("error", error);
        stream.sendTo(output, reply.toString());
    }

    /** Inputs from the browser in control (anyone else's are ignored). */
    private void gamepadInput(TelemetryStream.Viewer viewer, JSONObject message) {
        // Control ends with the OpMode it was taken for.
        VirtualGamepads.releaseIfNot(host.activeOpMode());
        int pad = message.optInt("pad", 1) == 2 ? 2 : 1;
        JSONArray list = message.optJSONArray("axes");
        float[] axes = new float[VirtualGamepads.AXES.length];
        for (int i = 0; list != null && i < axes.length && i < list.length(); i++) axes[i] = (float) list.optDouble(i, 0);
        VirtualGamepads.update(viewer.id, pad, axes, message.optInt("buttons", 0));
    }

    /** [owner of gamepad 1, owner of gamepad 2] (connection ids, 0 = nobody), for the state message. */
    private JSONArray gamepadOwners() {
        VirtualGamepads.releaseIfNot(host.activeOpMode());
        JSONArray owners = new JSONArray();
        owners.put(VirtualGamepads.owner(1));
        owners.put(VirtualGamepads.owner(2));
        return owners;
    }

    private final float[] padAxes = new float[VirtualGamepads.AXES.length];
    private final float[] stationAxes = new float[VirtualGamepads.AXES.length];
    private final int[] padButtons = new int[1], stationButtons = new int[1];

    /**
     * Gamepads 1 and 2 as the OpMode sees them: the Driver Station's and the dashboard's
     * combined (the stronger stick, either button), like lib/DashboardGamepads does. Built by
     * the stream's one broadcaster thread, 20 times a second while a drawer is open.
     */
    private String gamepadsMessage() {
        StringBuilder json = new StringBuilder("{\"type\":\"gamepads\",\"pads\":[");
        for (int n = 1; n <= 2; n++) {
            boolean virtual = VirtualGamepads.active(n);
            VirtualGamepads.snapshot(n, padAxes, padButtons);
            boolean station = false;
            try {
                station = host.driverStationGamepad(n, stationAxes, stationButtons);
            } catch (Throwable ignored) {
                // no Driver Station gamepad this time
            }
            if (n > 1) json.append(',');
            json.append("{\"owner\":").append(VirtualGamepads.owner(n))
                    .append(",\"virtual\":").append(virtual)
                    .append(",\"station\":").append(station)
                    .append(",\"axes\":[");
            for (int i = 0; i < padAxes.length; i++) {
                float value = padAxes[i];
                if (station && Math.abs(stationAxes[i]) > Math.abs(value)) value = stationAxes[i];
                if (i > 0) json.append(',');
                TelemetryStream.number(json, Math.round(value * 100) / 100.0);
            }
            json.append("],\"buttons\":").append(padButtons[0] | (station ? stationButtons[0] : 0)).append('}');
        }
        return json.append("]}").toString();
    }

    /** Reads one client frame; a small text payload is unmasked into {@code text[0]}. Returns its opcode, or -1 at the end. */
    private static int readFrame(InputStream input, String[] text) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0) return -1;
        long length = second & 0x7f;
        if (length == 126) {
            length = (input.read() << 8) | input.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | input.read();
        }
        byte[] mask = new byte[4];
        if ((second & 0x80) != 0) {
            for (int i = 0; i < 4; i++) mask[i] = (byte) input.read();
        }
        boolean keep = (first & 0x0f) == 1 && length <= 4096;
        byte[] payload = keep ? new byte[(int) length] : null;
        for (long i = 0; i < length; i++) {
            int b = input.read();
            if (b < 0) return -1;
            if (keep) payload[(int) i] = (byte) (b ^ mask[(int) (i & 3)]);
        }
        if (keep) text[0] = new String(payload, StandardCharsets.UTF_8);
        return first & 0x0f;
    }

    // ---- Small helpers ----------------------------------------------------------

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
