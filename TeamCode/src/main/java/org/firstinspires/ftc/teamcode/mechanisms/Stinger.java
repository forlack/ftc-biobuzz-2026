package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;

/** The stinger servo: up or down. */
@Configurable
@RobotSettings.MovedFrom("FieldCentricJava")
public class Stinger {
    /** Servo positions, 0 to 1. */
    public static double STINGER_UP = 0;
    public static double STINGER_DOWN = 0.42;

    private final Servo servo;
    private boolean down;

    public Stinger(HardwareMap hardwareMap) {
        servo = hardwareMap.get(Servo.class, "stinger");
    }

    @AutoFunction(name = "deployStinger", label = "stinger down", help = "Lower the stinger.")
    public void down() { down = true; }

    @AutoFunction(name = "stowStinger", label = "stinger up", help = "Raise the stinger.")
    public void up() { down = false; }

    public void set(boolean down) { this.down = down; }

    /** Once per loop (the servo moves here, not when up()/down() is called). */
    public void update() {
        servo.setPosition(down ? STINGER_DOWN : STINGER_UP);
    }

    public void telemetry(Telemetry telemetry) {
        telemetry.addData("Stinger", servo.getPosition());
    }
}
