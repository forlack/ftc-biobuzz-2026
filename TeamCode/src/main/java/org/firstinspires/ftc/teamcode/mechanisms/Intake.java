package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;

/** The intake motor: on, off, or held in reverse. */
@Configurable
@RobotSettings.MovedFrom("FieldCentricJava")
public class Intake implements Mechanism {
    public static double INTAKE_RPM = 700;
    public static double INTAKE_P = 15;
    public static double INTAKE_I = 0;
    public static double INTAKE_F = 15;
    /** Teleop's on/off switch (operator X flips it too). It starts on. A switch, not saved. */
    @RobotSettings.NotSaved
    public static boolean INTAKE_ON = true;

    private final VelocityMotor motor;
    private boolean on, reverse;

    public Intake(HardwareMap hardwareMap) {
        motor = new VelocityMotor(hardwareMap, "intake", DcMotorSimple.Direction.FORWARD, 145.1);
        motor.setPIDF(INTAKE_P, INTAKE_I, motor.getD(), INTAKE_F);
    }

    @AutoFunction(name = "intakeOn", label = "intake on", help = "Start the intake.")
    public void on() {
        on = true;
    }

    @AutoFunction(name = "intakeOff", label = "intake off", help = "Stop the intake.")
    public void off() {
        on = false;
    }

    public void set(boolean on) {
        this.on = on;
    }

    public boolean isOn() {
        return on;
    }

    /** While true the intake runs backwards, whatever on/off says; letting go goes back to that. */
    public void reverse(boolean held) {
        reverse = held;
    }

    /** Once per loop: the Tune tab's PIDF, and the speed for on / off / reverse. */
    @Override
    public void update() {
        if (INTAKE_P != motor.getP() || INTAKE_I != motor.getI() || INTAKE_F != motor.getF()) {
            motor.setPIDF(INTAKE_P, INTAKE_I, motor.getD(), INTAKE_F);
        }
        double rpm = 0;
        if (reverse) {
            rpm = -INTAKE_RPM;
        } else if (on) {
            rpm = INTAKE_RPM;
        }
        motor.setTargetRPM(rpm);
    }

    /** Driver Station lines and chart values. Reads the motor speed once. */
    @Override
    public void telemetry(Telemetry telemetry) {
        double rpm = motor.getRPM();
        telemetry.addLine("--- INTAKE ---");
        telemetry.addData("Intake", reverse ? "REVERSE" : (on ? "ON" : "OFF"));
        telemetry.addData("Intake speed", Robot.rpmLine(rpm, motor.getTargetRPM()));
        Dash.put("Intake RPM", rpm);
        Dash.put("Intake target", motor.getTargetRPM());
    }
}
