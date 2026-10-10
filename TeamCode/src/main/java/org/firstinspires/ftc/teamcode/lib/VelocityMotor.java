package org.firstinspires.ftc.teamcode.lib;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

/** A motor that holds a target speed using the hub's velocity control. */
public class VelocityMotor {
    public static double RPM_TOLERANCE = 100;    // how close counts as "ready"
    public double P, I, D, F;
    private final DcMotorEx motor;
    private final double ticksPerRevolution;
    private double targetRPM = 0;

    public VelocityMotor(HardwareMap hardwareMap, String name, DcMotorSimple.Direction direction) {
        this(hardwareMap, name, direction, 28);
    }

    /** Encoder ticks per output-shaft revolution, including the motor gearbox. */
    public VelocityMotor(HardwareMap hardwareMap, String name, DcMotorSimple.Direction direction, double ticksPerRevolution) {
        if (!Double.isFinite(ticksPerRevolution) || ticksPerRevolution <= 0) {
            throw new IllegalArgumentException("Ticks per revolution must be positive and finite");
        }
        this.ticksPerRevolution = ticksPerRevolution;
        P=200;
        I=0;
        D=0;
        F=0;
        motor = hardwareMap.get(DcMotorEx.class, name);
        motor.setDirection(direction);
        motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        setPIDF(P, I, D, F);
    }

    /** Applies velocity PIDF coefficients to this motor immediately. */
    public void setPIDF(double proportional, double integral, double derivative, double feedforward) {
        motor.setVelocityPIDFCoefficients(proportional, integral, derivative, feedforward);
        P = proportional;
        I = integral;
        D = derivative;
        F = feedforward;
    }

    /** Spin to this speed. 0 = off. */
    public void setTargetRPM(double rpm) {
        targetRPM = rpm;
        motor.setVelocity(rpm * ticksPerRevolution / 60.0);
    }

    public void stop() {
        setTargetRPM(0);
    }

    /** How fast it is actually spinning. */
    public double getRPM() {
        return motor.getVelocity() * 60.0 / ticksPerRevolution;
    }

    public double getTargetRPM() {
        return targetRPM;
    }

    /** Close enough to the target speed? */
    public boolean atSpeed() {
        return targetRPM > 0 && Math.abs(getRPM() - targetRPM) < RPM_TOLERANCE;
    }

}
