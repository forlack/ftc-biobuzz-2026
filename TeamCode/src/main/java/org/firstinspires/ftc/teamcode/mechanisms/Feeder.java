package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;

/** The two triggers (gates) that push balls into the flywheels. */
public class Feeder {
    private final CRServo triggerPollen, triggerNectar;
    private boolean feeding;

    public Feeder(HardwareMap hardwareMap) {
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
    }

    /** Not in the editor on its own: the "shoot" step uses it. */
    @AutoFunction(name = "feedOn", label = "feed on", listed = false)
    public void on() { feeding = true; }

    @AutoFunction(name = "feedOff", label = "feed off", listed = false)
    public void off() { feeding = false; }

    public void set(boolean feeding) { this.feeding = feeding; }

    public boolean isFeeding() { return feeding; }

    /** Once per loop. */
    public void update() {
        triggerNectar.setPower(feeding ? 1 : 0);
        triggerPollen.setPower(feeding ? 1 : 0);
    }

    public void telemetry(Telemetry telemetry) {
        telemetry.addData("Feeding", feeding);
    }
}
