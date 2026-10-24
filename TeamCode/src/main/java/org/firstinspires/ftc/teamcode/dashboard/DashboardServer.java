package org.firstinspires.ftc.teamcode.dashboard;

import org.firstinspires.ftc.teamcode.lib.AutoRoute;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
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
 * The BioBuzz dashboard's web server: the page itself, a small JSON API, and a websocket that
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
 *   GET  /api/files                     robot files the dashboard may read and write
 *   GET  /api/file?path=...             raw file, with its hash in X-Hash
 *   PUT  /api/file?path=...             replace a file; send X-Base-Hash to refuse if it changed
 *   POST /api/opmode                    {action: init|start|stop, name}
 *   GET  /ws                            websocket: telemetry frames and state
 */
public class DashboardServer {
    public static final int DEFAULT_PORT = 8686;
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_SOCKETS = 8;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Files the dashboard may touch, relative to the robot's storage folder. */
    private static final Pattern MANAGED = Pattern.compile(
            "settings/robot\\.json|paths/[A-Za-z0-9_.-]+\\.(json|txt)|autos/[A-Za-z0-9_.-]+\\.json|[A-Za-z0-9_ .-]+\\.xml");

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

    public DashboardServer(RobotHost host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Starts listening on a background thread. Never throws; check {@link #lastError}. */
    public synchronized void start() {
        if (running) return;
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "dashboard-accept");
        acceptor.setDaemon(true);
        acceptor.start();
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
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
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
                case "GET /api/files": return Response.json(200, listFiles());
                case "GET /api/file": return getFile(request.param("path"));
                case "PUT /api/file":
                case "POST /api/file": return putFile(request);
                case "POST /api/opmode": return opMode(request.json());
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
        double volts = host.batteryVolts();
        json.put("battery", Double.isNaN(volts) ? JSONObject.NULL : (Object) round(volts, 2));
        json.put("hardwareMap", host.activeHardwareMap());
        json.put("settingsStatus", host.settings().status());
        json.put("time", Dash.now());
        return json;
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

    private JSONArray listFiles() throws JSONException, IOException {
        JSONArray list = new JSONArray();
        List<String> names = new ArrayList<>();
        if (RobotSettings.location().isFile()) names.add("settings/robot.json");
        for (String folder : new String[]{"paths", "autos", ""}) {
            File[] files = RobotFiles.file(folder).listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File file : files) {
                String relative = folder.isEmpty() ? file.getName() : folder + "/" + file.getName();
                if (file.isFile() && MANAGED.matcher(relative).matches()) names.add(relative);
            }
        }
        for (String name : names) list.put(fileInfo(name));
        return list;
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
        File file = RobotFiles.file(checkPath(relative));
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
        String problem = validate(relative, request.body);
        if (problem != null) return Response.error(400, problem);
        RobotFiles.writeAtomically(file, request.body);
        if (relative.equals("settings/robot.json")) host.settings().reload();
        JSONObject json = fileInfo(relative);
        json.put("ok", true);
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

        final boolean[] open = {true};
        // Reader: answers pings and notices when the browser leaves.
        pool.execute(() -> {
            try {
                while (open[0]) {
                    int opcode = readFrame(input);
                    if (opcode == 8 || opcode < 0) break;
                }
            } catch (Throwable ignored) {
            } finally {
                open[0] = false;
            }
        });

        long cursor = Math.max(0, Dash.latestSeq() - 400);   // start with ~2 s of history
        long lastState = 0;
        while (open[0] && running) {
            List<Dash.Frame> frames = Dash.since(cursor, 600);
            if (!frames.isEmpty()) {
                cursor = frames.get(frames.size() - 1).seq;
                JSONObject message = new JSONObject();
                message.put("type", "frames");
                message.put("frames", framesJson(frames));
                sendText(output, message.toString());
            }
            long now = System.currentTimeMillis();
            if (now - lastState > 500) {
                lastState = now;
                JSONObject message = state();
                message.put("type", "state");
                sendText(output, message.toString());
            }
            sleepQuietly(50);
        }
        try {
            output.write(new byte[]{(byte) 0x88, 0});
            output.flush();
        } catch (IOException ignored) {
        }
    }

    private static JSONArray framesJson(List<Dash.Frame> frames) throws JSONException {
        JSONArray list = new JSONArray();
        for (Dash.Frame frame : frames) {
            JSONObject item = new JSONObject();
            item.put("seq", frame.seq);
            item.put("t", round(frame.t, 4));
            JSONObject numbers = new JSONObject();
            for (Map.Entry<String, Double> entry : frame.numbers.entrySet()) {
                numbers.put(entry.getKey(), round(entry.getValue(), 4));
            }
            item.put("n", numbers);
            if (!frame.text.isEmpty()) {
                JSONObject text = new JSONObject();
                for (Map.Entry<String, String> entry : frame.text.entrySet()) {
                    text.put(entry.getKey(), entry.getValue());
                }
                item.put("s", text);
            }
            if (frame.pose != null) {
                item.put("pose", new JSONArray().put(round(frame.pose[0], 3)).put(round(frame.pose[1], 3))
                        .put(round(frame.pose[2], 4)));
            }
            list.put(item);
        }
        return list;
    }

    private static void sendText(OutputStream output, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
        frame.write(0x81);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length < 65536) {
            frame.write(126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (((long) payload.length >>> shift) & 0xff));
            }
        }
        frame.write(payload);
        synchronized (output) {
            output.write(frame.toByteArray());
            output.flush();
        }
    }

    /** Reads and discards one client frame. Returns its opcode, or -1 at end of stream. */
    private static int readFrame(InputStream input) throws IOException {
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
        if ((second & 0x80) != 0) {
            for (int i = 0; i < 4; i++) input.read();   // mask key; payload is ignored
        }
        for (long i = 0; i < length; i++) {
            if (input.read() < 0) return -1;
        }
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
