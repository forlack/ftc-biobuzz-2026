package org.firstinspires.ftc.teamcode.dashboard;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends live telemetry to every browser while costing the robot as little as possible:
 *
 * - One thread builds each batch (20 a second) once; every viewer is sent the same bytes, so
 *   a second viewer costs almost nothing extra.
 * - Compact: value names are sent once per connection ("names"), then each loop is just
 *   numbers in that order. Text values are sent when they change, and all of them once a
 *   second (so a table cleared when an OpMode stops fills again even if nothing changed).
 * - Viewers not on the Live tab ask for no loop data ("subscribe", frames: false) and get
 *   only the twice-a-second status.
 * - {@link DashboardConfig#MAX_SAMPLES_PER_SECOND} caps how many loops a second are sent.
 *
 * Messages to the browser:
 *   {"type":"hello","boot":"..."}                          once per connection; a new boot id
 *                                                          means the app restarted
 *   {"type":"names","from":3,"names":["Pollen RPM",...]}   names for value positions 3, 4, ...
 *   {"type":"frames","frames":[[seq, t, [values], {"position": "text"}, [x, y, heading]], ...]}
 *                                                          values: null = not sent that loop;
 *                                                          text and pose are 0 when absent
 *   {"type":"state", ...}                                  robot status, twice a second
 *   {"type":"gamepads","pads":[{...}, {...}]}              gamepads 1 and 2 as the robot sees
 *                                                          them, when they change (only to
 *                                                          viewers with the Gamepad drawer open)
 * From the browser: {"type":"subscribe","frames":true|false,"gamepads":true|false}, and the
 * gamepad messages DashboardServer handles (control, gamepad).
 */
final class TelemetryStream {
    static final int UPDATES_PER_SECOND = 20;
    private static final int HISTORY_FRAMES = 400;   // ~2-4 s of history for a new viewer

    final AtomicLong busyNanos = new AtomicLong();
    final AtomicLong bytesSent = new AtomicLong();
    final AtomicInteger viewers = new AtomicInteger();
    final AtomicInteger liveViewers = new AtomicInteger();
    final AtomicInteger padViewers = new AtomicInteger();

    private final String boot = Long.toHexString(System.nanoTime() ^ System.currentTimeMillis());
    private final Callable<JSONObject> state;
    private final Callable<String> gamepads;

    // Value names, in the order positions were given out. Positions never change while the app runs.
    private final Map<String, Integer> positions = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    // The shared output, guarded by `signal`.
    private final Object signal = new Object();
    private long batchNumber, batchLastSeq, stateNumber, padsNumber;
    private byte[] batchBytes, stateBytes, padsBytes;
    private String lastPads = "";   // broadcaster thread only

    // Broadcaster state (broadcaster thread only).
    private long cursor = Dash.latestSeq();
    private double lastSample = -1e9;
    private Map<String, String> lastText = new HashMap<>();

    TelemetryStream(Callable<JSONObject> state, Callable<String> gamepads) {
        this.state = state;
        this.gamepads = gamepads;
    }

    // ---- Broadcaster -------------------------------------------------------------------

    /** Runs until {@code running} turns false: builds each batch and status once for everyone. */
    void broadcast(java.util.function.BooleanSupplier running) {
        long lastState = 0, lastAllText = 0;
        boolean hadLiveViewers = false;
        while (running.getAsBoolean()) {
            long started = System.nanoTime();
            try {
                boolean live = liveViewers.get() > 0;
                if (!live) {
                    cursor = Dash.latestSeq();   // nobody watching loops: build nothing
                } else {
                    long nowMs = System.currentTimeMillis();
                    if (!hadLiveViewers || nowMs - lastAllText >= 1000) {   // all text again
                        lastText = new HashMap<>();
                        lastAllText = nowMs;
                    }
                    List<Dash.Frame> frames = Dash.since(cursor, 2000);
                    if (!frames.isEmpty()) {
                        cursor = frames.get(frames.size() - 1).seq;
                        frames = thin(frames);
                    }
                    if (!frames.isEmpty()) {
                        byte[] bytes = message(framesMessage(frames, lastText));
                        synchronized (signal) {
                            batchBytes = bytes;
                            batchLastSeq = frames.get(frames.size() - 1).seq;
                            batchNumber++;
                            signal.notifyAll();
                        }
                    }
                }
                hadLiveViewers = live;
                if (padViewers.get() > 0) {
                    String pads = gamepads.call();
                    if (!pads.equals(lastPads)) {
                        lastPads = pads;
                        byte[] bytes = message(pads);
                        synchronized (signal) {
                            padsBytes = bytes;
                            padsNumber++;
                            signal.notifyAll();
                        }
                    }
                }
                long now = System.currentTimeMillis();
                if (viewers.get() > 0 && now - lastState >= 500) {
                    lastState = now;
                    byte[] bytes = stateMessage();
                    synchronized (signal) {
                        stateBytes = bytes;
                        stateNumber++;
                        signal.notifyAll();
                    }
                }
            } catch (Throwable ignored) {
                // A bad value must never stop the stream.
            }
            long spent = System.nanoTime() - started;
            busyNanos.addAndGet(spent);
            sleep(Math.max(1, 1000 / UPDATES_PER_SECOND - spent / 1000000));
        }
    }

    /** Keeps at most MAX_SAMPLES_PER_SECOND loops a second (0 keeps all). */
    private List<Dash.Frame> thin(List<Dash.Frame> frames) {
        int cap = DashboardConfig.MAX_SAMPLES_PER_SECOND;
        if (cap <= 0) return frames;
        List<Dash.Frame> kept = new ArrayList<>();
        for (Dash.Frame frame : frames) {
            if (frame.t - lastSample >= 1.0 / cap) {
                kept.add(frame);
                lastSample = frame.t;
            }
        }
        return kept;
    }

    private byte[] stateMessage() throws Exception {
        JSONObject json = state.call();
        json.put("type", "state");
        return message(json.toString());
    }

    // ---- One viewer ----------------------------------------------------------------------

    /** Per-connection flags, changed by the connection's reader thread. */
    static final class Viewer {
        private static final AtomicLong IDS = new AtomicLong();
        /** Names this connection for the virtual gamepads (never 0). */
        final long id = IDS.incrementAndGet();
        volatile boolean open = true;
        volatile boolean wantsFrames = true;   // scripts that never subscribe get everything
        volatile boolean wantsGamepads;
    }

    /** One message to one viewer only, from any thread (such as a reply to its request). */
    void sendTo(OutputStream output, String json) throws IOException {
        send(output, message(json));
    }

    /** Wakes the viewer's sending thread after its reader changed something. */
    void poke() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    /** Sends to one browser until it leaves. */
    void serve(Viewer viewer, OutputStream output, java.util.function.BooleanSupplier running) throws Exception {
        viewers.incrementAndGet();
        boolean live = false, pads = false;
        long seenBatch, seenState, seenPads, lastSeq = 0;
        int namesSent = 0;
        try {
            byte[] firstState;
            synchronized (signal) {
                seenBatch = batchNumber;   // registered before history is read, so nothing is missed
                seenState = stateNumber;
                seenPads = padsNumber;
                firstState = stateBytes;
            }
            long started = System.nanoTime();
            send(output, message("{\"type\":\"hello\",\"boot\":\"" + boot + "\",\"id\":" + viewer.id + "}"));
            send(output, firstState != null ? firstState : stateMessage());
            busyNanos.addAndGet(System.nanoTime() - started);
            while (viewer.open && running.getAsBoolean()) {
                byte[] batch = null, status = null, padsNow = null;
                long batchEnd = 0;
                boolean padsChanged = false;
                synchronized (signal) {
                    if (batchNumber == seenBatch && stateNumber == seenState && padsNumber == seenPads
                            && viewer.wantsFrames == live && viewer.wantsGamepads == pads) {
                        signal.wait(600);
                    }
                    if (padsNumber != seenPads) {
                        seenPads = padsNumber;
                        padsChanged = true;
                    }
                    padsNow = padsBytes;
                    if (batchNumber != seenBatch) {
                        seenBatch = batchNumber;
                        batch = batchBytes;
                        batchEnd = batchLastSeq;
                    }
                    if (stateNumber != seenState) {
                        seenState = stateNumber;
                        status = stateBytes;
                    }
                }
                started = System.nanoTime();
                boolean wants = viewer.wantsFrames;
                if (wants && !live) {
                    live = true;
                    liveViewers.incrementAndGet();
                    // Catch up: the last few seconds, or what was missed while on another tab.
                    List<Dash.Frame> history = Dash.since(Math.max(lastSeq, Dash.latestSeq() - HISTORY_FRAMES), HISTORY_FRAMES);
                    if (!history.isEmpty()) {
                        String text = framesMessage(history, new HashMap<String, String>());
                        namesSent = sendNames(output, namesSent);
                        send(output, message(text));
                        lastSeq = history.get(history.size() - 1).seq;
                    }
                } else if (!wants && live) {
                    live = false;
                    liveViewers.decrementAndGet();
                }
                if (live && batch != null && batchEnd > lastSeq) {
                    namesSent = sendNames(output, namesSent);
                    send(output, batch);
                    lastSeq = batchEnd;
                }
                if (status != null) send(output, status);
                boolean wantsPads = viewer.wantsGamepads;
                if (wantsPads != pads) {
                    pads = wantsPads;
                    if (pads) padViewers.incrementAndGet(); else padViewers.decrementAndGet();
                    // Just opened: what the broadcaster last built (it starts building now if
                    // nobody had the drawer open, and sends the next change).
                    if (pads && padsNow != null) send(output, padsNow);
                } else if (pads && padsChanged && padsNow != null) {
                    send(output, padsNow);
                }
                busyNanos.addAndGet(System.nanoTime() - started);
            }
        } finally {
            viewers.decrementAndGet();
            if (live) liveViewers.decrementAndGet();
            if (pads) padViewers.decrementAndGet();
        }
    }

    /** Sends names the browser does not have yet; returns how many it has now. */
    private int sendNames(OutputStream output, int namesSent) throws IOException {
        StringBuilder json;
        int total;
        synchronized (positions) {
            total = names.size();
            if (total == namesSent) return total;
            json = new StringBuilder("{\"type\":\"names\",\"from\":").append(namesSent).append(",\"names\":[");
            for (int i = namesSent; i < total; i++) {
                if (i > namesSent) json.append(',');
                json.append(JSONObject.quote(names.get(i)));
            }
        }
        send(output, message(json.append("]}").toString()));
        return total;
    }

    // ---- Encoding -------------------------------------------------------------------------

    /** Frames as one message. {@code shownText} is the text the receiver already has; updated. */
    String framesMessage(List<Dash.Frame> frames, Map<String, String> shownText) {
        StringBuilder json = new StringBuilder(64 + frames.size() * 120);
        json.append("{\"type\":\"frames\",\"frames\":[");
        double[] values = new double[16];
        boolean[] present = new boolean[16];
        for (int f = 0; f < frames.size(); f++) {
            Dash.Frame frame = frames.get(f);
            if (f > 0) json.append(',');
            json.append('[').append(frame.seq).append(',');
            number(json, frame.t);
            // Numbers, by position; positions not sent this loop are null.
            int count = 0;
            for (Map.Entry<String, Double> entry : frame.numbers.entrySet()) {
                int position = position(entry.getKey());
                if (position >= values.length) {
                    values = java.util.Arrays.copyOf(values, position * 2);
                    present = java.util.Arrays.copyOf(present, position * 2);
                }
                values[position] = entry.getValue();
                present[position] = true;
                count = Math.max(count, position + 1);
            }
            json.append(",[");
            for (int i = 0; i < count; i++) {
                if (i > 0) json.append(',');
                if (present[i]) number(json, values[i]); else json.append("null");
                present[i] = false;
            }
            json.append(']');
            // Text, only what changed.
            StringBuilder text = null;
            for (Map.Entry<String, String> entry : frame.text.entrySet()) {
                String value = entry.getValue();
                if (value.equals(shownText.get(entry.getKey()))) continue;
                shownText.put(entry.getKey(), value);
                text = text == null ? new StringBuilder("{") : text.append(',');
                text.append('"').append(position(entry.getKey())).append("\":").append(JSONObject.quote(value));
            }
            if (text != null || frame.pose != null) json.append(',').append(text == null ? "0" : text.append('}'));
            if (frame.pose != null) {
                json.append(",[");
                number(json, frame.pose[0]);
                json.append(',');
                number(json, frame.pose[1]);
                json.append(',');
                number(json, frame.pose[2]);
                json.append(']');
            }
            json.append(']');
        }
        return json.append("]}").toString();
    }

    private int position(String name) {
        synchronized (positions) {
            Integer position = positions.get(name);
            if (position == null) {
                position = names.size();
                positions.put(name, position);
                names.add(name);
            }
            return position;
        }
    }

    /** At most 4 decimals, and whole numbers without ".0". */
    static void number(StringBuilder json, double value) {
        double rounded = Math.round(value * 10000) / 10000.0;
        if (rounded == (long) rounded && Math.abs(rounded) < 1e15) json.append((long) rounded);
        else json.append(rounded);
    }

    // ---- Websocket framing -----------------------------------------------------------------

    /** A complete websocket text frame, ready to write to any number of browsers. */
    static byte[] message(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int header = payload.length < 126 ? 2 : payload.length < 65536 ? 4 : 10;
        byte[] frame = new byte[header + payload.length];
        frame[0] = (byte) 0x81;
        if (payload.length < 126) {
            frame[1] = (byte) payload.length;
        } else if (payload.length < 65536) {
            frame[1] = 126;
            frame[2] = (byte) (payload.length >>> 8);
            frame[3] = (byte) payload.length;
        } else {
            frame[1] = 127;
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((long) payload.length >>> (56 - 8 * i));
        }
        System.arraycopy(payload, 0, frame, header, payload.length);
        return frame;
    }

    private void send(OutputStream output, byte[] frame) throws IOException {
        synchronized (output) {
            output.write(frame);
            output.flush();
        }
        bytesSent.addAndGet(frame.length);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
