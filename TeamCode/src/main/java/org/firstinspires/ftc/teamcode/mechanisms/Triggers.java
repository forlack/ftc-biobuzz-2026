package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;

/** The two trigger servos (Pollen and Nectar) that push balls into the flywheels. */
public class Triggers implements Mechanism {
    private final CRServo triggerPollen, triggerNectar;
    private boolean on;

    public Triggers(HardwareMap hardwareMap) {
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
    }

    /** Not in the editor on its own: the "shoot" step uses it. */
    @AutoFunction(name = "triggersOn", label = "triggers on", listed = false)
    public void on() {
        on = true;
    }

    @AutoFunction(name = "triggersOff", label = "triggers off", listed = false)
    public void off() {
        on = false;
    }

    public void set(boolean on) {
        this.on = on;
    }

    public boolean isOn() {
        return on;
    }

    @Override
    public void update() {
        triggerNectar.setPower(on ? 1 : 0);
        triggerPollen.setPower(on ? 1 : 0);
    }

    @Override
    public void telemetry(Telemetry telemetry) {
        Dash.put("Triggers", on);   // dashboard only
    }
}
