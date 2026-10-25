package org.firstinspires.ftc.teamcode.lib;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.teamcode.dashboard.VirtualGamepads;

/**
 * Adds the dashboard's virtual gamepad (the Gamepad drawer: on-screen controller, or a
 * controller plugged into the laptop) to the Driver Station's. Practice only.
 *
 *   drive = DashboardGamepads.combine(gamepad1, 1);
 *
 * With nobody in control on the dashboard (always, in a match) this returns the Driver Station
 * gamepad itself, unchanged. Otherwise a copy where each stick is the stronger of the two and
 * each button is pressed if either has it pressed, so the Driver Station gamepad keeps working.
 * Call once per loop and read only the result. No allocation.
 */
public final class DashboardGamepads {
    private DashboardGamepads() { }

    private static final Gamepad[] COMBINED = {new Gamepad(), new Gamepad()};
    private static final float[] AXES = new float[VirtualGamepads.AXES.length];
    private static final boolean[] BUTTONS = new boolean[VirtualGamepads.BUTTONS.length];

    /** {@code real} plus dashboard gamepad {@code number} (1 or 2), if someone is driving it. */
    public static Gamepad combine(Gamepad real, int number) {
        if (!VirtualGamepads.read(number, AXES, BUTTONS)) return real;
        Gamepad out = COMBINED[number - 1];
        out.copy(real);
        out.left_stick_x = stronger(real.left_stick_x, AXES[VirtualGamepads.LEFT_STICK_X]);
        out.left_stick_y = stronger(real.left_stick_y, AXES[VirtualGamepads.LEFT_STICK_Y]);
        out.right_stick_x = stronger(real.right_stick_x, AXES[VirtualGamepads.RIGHT_STICK_X]);
        out.right_stick_y = stronger(real.right_stick_y, AXES[VirtualGamepads.RIGHT_STICK_Y]);
        out.left_trigger = stronger(real.left_trigger, AXES[VirtualGamepads.LEFT_TRIGGER]);
        out.right_trigger = stronger(real.right_trigger, AXES[VirtualGamepads.RIGHT_TRIGGER]);
        out.left_trigger_pressed = out.left_trigger > out.getTriggerThreshold();
        out.right_trigger_pressed = out.right_trigger > out.getTriggerThreshold();
        // The PlayStation names are the same buttons, so set both.
        out.a = out.cross = real.a || BUTTONS[VirtualGamepads.A];
        out.b = out.circle = real.b || BUTTONS[VirtualGamepads.B];
        out.x = out.square = real.x || BUTTONS[VirtualGamepads.X];
        out.y = out.triangle = real.y || BUTTONS[VirtualGamepads.Y];
        out.left_bumper = real.left_bumper || BUTTONS[VirtualGamepads.LEFT_BUMPER];
        out.right_bumper = real.right_bumper || BUTTONS[VirtualGamepads.RIGHT_BUMPER];
        out.dpad_up = real.dpad_up || BUTTONS[VirtualGamepads.DPAD_UP];
        out.dpad_down = real.dpad_down || BUTTONS[VirtualGamepads.DPAD_DOWN];
        out.dpad_left = real.dpad_left || BUTTONS[VirtualGamepads.DPAD_LEFT];
        out.dpad_right = real.dpad_right || BUTTONS[VirtualGamepads.DPAD_RIGHT];
        out.back = out.share = real.back || BUTTONS[VirtualGamepads.BACK];
        out.start = out.options = real.start || BUTTONS[VirtualGamepads.START];
        out.guide = out.ps = real.guide || BUTTONS[VirtualGamepads.GUIDE];
        out.left_stick_button = real.left_stick_button || BUTTONS[VirtualGamepads.LEFT_STICK_BUTTON];
        out.right_stick_button = real.right_stick_button || BUTTONS[VirtualGamepads.RIGHT_STICK_BUTTON];
        return out;
    }

    private static float stronger(float a, float b) {
        return Math.abs(b) > Math.abs(a) ? b : a;
    }
}
