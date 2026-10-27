package org.firstinspires.ftc.teamcode.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A gamepad button. down() asks if it is held; pressed()/released() catch the moment it changes. */
public class Button {

    private final BooleanSupplier source;
    private boolean now, before;

    public Button(BooleanSupplier source) {
        this.source = source;
    }

    /** Reads the gamepad. Call once per loop, before anything asks about this button. */
    public void update() {
        before = now;
        now = source.getAsBoolean();
    }

    /** Is the button held down right now? */
    public boolean down() {
        return now;
    }

    /** Was the button just pushed down? True for one loop per press -- use this for toggles. */
    public boolean pressed() {
        return now && !before;
    }

    /** Was the button just let go? */
    public boolean released() {
        return !now && before;
    }

    /** A set of buttons that get read together, so none can be forgotten. */
    public static class Group {
        private final List<Button> all = new ArrayList<>();

        /** Makes a button, adds it to this group, and hands it back. */
        public Button add(BooleanSupplier source) {
            Button button = new Button(source);
            all.add(button);
            return button;
        }

        public void update() {
            for (Button button : all) {
                button.update();
            }
        }
    }
}
