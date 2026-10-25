package org.firstinspires.ftc.teamcode.dashboard;

/**
 * The dashboard's virtual gamepads 1 and 2: what a browser sends from the Gamepad drawer
 * (the on-screen controller, or a controller plugged into the laptop).
 *
 * Practice only, never in a match. Safety:
 * - One browser at a time per gamepad, and only after it clicks Take control.
 * - Inputs older than {@link #FRESH_NANOS} count as nothing (all zero): a dropped Wi-Fi link
 *   or a closed laptop stops the robot within a quarter of a second.
 * - Control ends when the OpMode stops or changes, when the browser leaves, and while the
 *   dashboard is locked nobody can take it.
 *
 * OpModes read it with {@link #read} once per loop (the Android teleop through
 * lib/DashboardGamepads, which combines it with the Driver Station gamepad). Plain Java, so
 * the simulator uses the same class.
 */
public final class VirtualGamepads {
    private VirtualGamepads() { }

    /** Bit order of the buttons, in messages and in {@link #read}'s array. */
    public static final String[] BUTTONS = {
            "a", "b", "x", "y", "left_bumper", "right_bumper",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right",
            "back", "start", "guide", "left_stick_button", "right_stick_button"};
    /** Axis order: left stick x, y, right stick x, y (-1 to 1, y negative when pushed up), triggers (0 to 1). */
    public static final String[] AXES = {
            "left_stick_x", "left_stick_y", "right_stick_x", "right_stick_y", "left_trigger", "right_trigger"};

    public static final int A = 0, B = 1, X = 2, Y = 3, LEFT_BUMPER = 4, RIGHT_BUMPER = 5,
            DPAD_UP = 6, DPAD_DOWN = 7, DPAD_LEFT = 8, DPAD_RIGHT = 9,
            BACK = 10, START = 11, GUIDE = 12, LEFT_STICK_BUTTON = 13, RIGHT_STICK_BUTTON = 14;
    public static final int LEFT_STICK_X = 0, LEFT_STICK_Y = 1, RIGHT_STICK_X = 2, RIGHT_STICK_Y = 3,
            LEFT_TRIGGER = 4, RIGHT_TRIGGER = 5;

    /** Inputs this old are ignored: the browser sends 20 a second, so 5 in a row were lost. */
    public static final long FRESH_NANOS = 250_000_000L;

    private static final class Pad {
        long owner;            // browser connection id, 0 = nobody
        String opMode = "";    // the OpMode it was taken for
        long updated;          // System.nanoTime() of the last inputs
        final float[] axes = new float[AXES.length];
        int buttons;
    }

    private static final Pad[] PADS = {new Pad(), new Pad()};

    private static Pad pad(int number) {
        if (number != 1 && number != 2) throw new IllegalArgumentException("Gamepad 1 or 2");
        return PADS[number - 1];
    }

    /**
     * For OpModes: fills {@code axes} and {@code buttons} with gamepad {@code number}'s inputs
     * and returns true, or returns false (arrays untouched) when nobody is in control or the
     * inputs are too old. No allocation, safe to call every loop.
     */
    public static synchronized boolean read(int number, float[] axes, boolean[] buttons) {
        Pad pad = pad(number);
        if (pad.owner == 0 || System.nanoTime() - pad.updated > FRESH_NANOS) return false;
        System.arraycopy(pad.axes, 0, axes, 0, Math.min(axes.length, pad.axes.length));
        for (int i = 0; i < buttons.length && i < BUTTONS.length; i++) buttons[i] = (pad.buttons & (1 << i)) != 0;
        return true;
    }

    /** True while someone controls gamepad {@code number} and their inputs are arriving. */
    public static synchronized boolean active(int number) {
        Pad pad = pad(number);
        return pad.owner != 0 && System.nanoTime() - pad.updated <= FRESH_NANOS;
    }

    // ---- For the server ----------------------------------------------------------------

    /** Who controls gamepad {@code number}: a connection id, or 0. */
    static synchronized long owner(int number) {
        return pad(number).owner;
    }

    /**
     * Gives gamepad {@code number} to {@code viewer}. Returns null when done, or why not.
     * Someone else's control can be taken over only once their inputs stopped (2 s).
     */
    static synchronized String take(long viewer, int number, String opMode) {
        if (opMode == null || opMode.isEmpty()) return "Init an OpMode first: the gamepad only works while one runs.";
        Pad pad = pad(number);
        if (pad.owner != 0 && pad.owner != viewer && System.nanoTime() - pad.updated < 2_000_000_000L) {
            return "Another browser is driving gamepad " + number + ".";
        }
        clear(pad);
        pad.owner = viewer;
        pad.opMode = opMode;
        pad.updated = System.nanoTime() - FRESH_NANOS - 1;   // nothing pressed until inputs arrive
        return null;
    }

    /** New inputs from {@code viewer}; ignored unless it controls that gamepad. */
    static synchronized boolean update(long viewer, int number, float[] axes, int buttons) {
        Pad pad = pad(number);
        if (pad.owner != viewer || viewer == 0) return false;
        for (int i = 0; i < pad.axes.length; i++) {
            float value = i < axes.length && !Float.isNaN(axes[i]) ? axes[i] : 0;
            float low = i >= LEFT_TRIGGER ? 0 : -1;
            pad.axes[i] = Math.max(low, Math.min(1, value));
        }
        pad.buttons = buttons & ((1 << BUTTONS.length) - 1);
        pad.updated = System.nanoTime();
        return true;
    }

    /** Ends {@code viewer}'s control of gamepad {@code number} (0: both). */
    static synchronized void release(long viewer, int number) {
        for (int n = 1; n <= 2; n++) {
            if ((number == 0 || number == n) && PADS[n - 1].owner == viewer) clear(PADS[n - 1]);
        }
    }

    /**
     * Ends control taken for an OpMode that is no longer the active one. Returns the gamepad
     * numbers released, as a bit set (1 = gamepad 1, 2 = gamepad 2).
     */
    static synchronized int releaseIfNot(String activeOpMode) {
        int released = 0;
        for (int n = 1; n <= 2; n++) {
            Pad pad = PADS[n - 1];
            if (pad.owner != 0 && !pad.opMode.equals(activeOpMode)) {
                clear(pad);
                released |= n;
            }
        }
        return released;
    }

    /** The inputs as last sent (zeros when too old), for the drawer of other browsers. */
    static synchronized void snapshot(int number, float[] axes, int[] buttons) {
        Pad pad = pad(number);
        boolean fresh = pad.owner != 0 && System.nanoTime() - pad.updated <= FRESH_NANOS;
        for (int i = 0; i < axes.length; i++) axes[i] = fresh ? pad.axes[i] : 0;
        buttons[0] = fresh ? pad.buttons : 0;
    }

    private static void clear(Pad pad) {
        pad.owner = 0;
        pad.opMode = "";
        pad.buttons = 0;
        java.util.Arrays.fill(pad.axes, 0);
    }
}
