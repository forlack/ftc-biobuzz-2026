package org.firstinspires.ftc.teamcode.mechanisms;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.dashboard.Configurable;
import org.firstinspires.ftc.teamcode.dashboard.Dash;
import org.firstinspires.ftc.teamcode.lib.AutoFunction;
import org.firstinspires.ftc.teamcode.lib.FlywheelCompensation;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;

import java.util.function.DoubleSupplier;

/**
 * Both flywheels (Pollen and Nectar) and the transports that feed them: the transports run
 * whenever the flywheels do. F follows the battery (see FlywheelCompensation).
 */
@Configurable
@RobotSettings.MovedFrom("FieldCentricJava")
public class Flywheels implements Mechanism {
    /** Teleop's on/off switch (operator A flips it too). They start on. A switch, not saved. */
    @RobotSettings.NotSaved
    public static boolean FLYWHEELS_ON = true;
    /**
     * F follows the battery: FLYWHEEL_F x 12.5 / battery volts (see FlywheelCompensation).
     * Off: FLYWHEEL_F at any voltage.
     */
    public static boolean FLYWHEEL_VOLTAGE_COMPENSATION = true;
    public static double FLYWHEEL_F = 11; // F at 12.5 V (and always, with compensation off)
    /** Compensate from the battery averaged over this long, so a dip from driving is ignored. */
    public static double FLYWHEEL_VOLTS_AVERAGE_SECONDS = 1;

    // Pollen flywheel.
    public static double POLLEN_FLYWHEEL_RPM = 3400;
    public static double POLLEN_FLYWHEEL_P = 100;
    public static double POLLEN_FLYWHEEL_I = 0;
    public static double POLLEN_FLYWHEEL_D = 0;

    // Nectar flywheel.
    public static double NECTAR_FLYWHEEL_RPM = 3650;
    public static double NECTAR_FLYWHEEL_P = 125;
    public static double NECTAR_FLYWHEEL_I = 0;
    public static double NECTAR_FLYWHEEL_D = 0;

    private final VelocityMotor pollen, nectar;
    private final CRServo transportPollen, transportNectar;
    private final DoubleSupplier batteryVolts;
    private final FlywheelCompensation.Average averageVolts = new FlywheelCompensation.Average();
    private boolean on;
    private double appliedF = FLYWHEEL_F;

    /** {@code batteryVolts}: the battery now (Robot reads it), for the voltage-compensated F. */
    public Flywheels(HardwareMap hardwareMap, DoubleSupplier batteryVolts) {
        this.batteryVolts = batteryVolts;
        pollen = new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE);
        pollen.setPIDF(POLLEN_FLYWHEEL_P, POLLEN_FLYWHEEL_I, POLLEN_FLYWHEEL_D, FLYWHEEL_F);
        nectar = new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD);
        nectar.setPIDF(NECTAR_FLYWHEEL_P, NECTAR_FLYWHEEL_I, NECTAR_FLYWHEEL_D, FLYWHEEL_F);
        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportNectar = hardwareMap.get(CRServo.class, "transportNectar");
    }

    @AutoFunction(name = "flywheelsOn", label = "flywheels on", help = "Spin both flywheels up to their target RPMs.")
    public void on() {
        on = true;
    }

    @AutoFunction(name = "flywheelsOff", label = "flywheels off", help = "Stop both flywheels.")
    public void off() {
        on = false;
    }

    public void set(boolean on) {
        this.on = on;
    }

    public boolean isOn() {
        return on;
    }

    /** Both wheels within VelocityMotor.RPM_TOLERANCE of their targets (reads both speeds). */
    @AutoFunction(name = "flywheelsReady")
    public boolean ready() {
        return on && pollen.atSpeed() && nectar.atSpeed();
    }

    /** Flywheel F for this battery voltage, from the settings above. */
    public static double compensatedF(double volts) {
        return FlywheelCompensation.f(volts, FLYWHEEL_VOLTAGE_COMPENSATION, FLYWHEEL_F);
    }

    /**
     * Once per loop: PIDF from the Tune tab with F for the averaged battery, the targets, and
     * the transport servos (on whenever the flywheels are).
     */
    @Override
    public void update() {
        double volts = averageVolts.update(batteryVolts.getAsDouble(), FLYWHEEL_VOLTS_AVERAGE_SECONDS);
        appliedF = compensatedF(volts);
        if (POLLEN_FLYWHEEL_P != pollen.getP() || POLLEN_FLYWHEEL_I != pollen.getI()
                || POLLEN_FLYWHEEL_D != pollen.getD() || appliedF != pollen.getF()) {
            pollen.setPIDF(POLLEN_FLYWHEEL_P, POLLEN_FLYWHEEL_I, POLLEN_FLYWHEEL_D, appliedF);
        }
        if (NECTAR_FLYWHEEL_P != nectar.getP() || NECTAR_FLYWHEEL_I != nectar.getI()
                || NECTAR_FLYWHEEL_D != nectar.getD() || appliedF != nectar.getF()) {
            nectar.setPIDF(NECTAR_FLYWHEEL_P, NECTAR_FLYWHEEL_I, NECTAR_FLYWHEEL_D, appliedF);
        }
        pollen.setTargetRPM(on ? POLLEN_FLYWHEEL_RPM : 0);
        nectar.setTargetRPM(on ? NECTAR_FLYWHEEL_RPM : 0);
        transportPollen.setPower(on ? 1 : 0);
        transportNectar.setPower(on ? 1 : 0);
    }

    /** Driver Station lines and chart values. Reads each wheel's speed once. */
    @Override
    public void telemetry(Telemetry telemetry) {
        double pollenRpm = pollen.getRPM();
        double nectarRpm = nectar.getRPM();
        boolean ready = on
                && Math.abs(pollenRpm - pollen.getTargetRPM()) < VelocityMotor.RPM_TOLERANCE
                && Math.abs(nectarRpm - nectar.getTargetRPM()) < VelocityMotor.RPM_TOLERANCE;
        telemetry.addLine("--- SHOOTERS ---");
        telemetry.addData("Flywheels", !on ? "OFF" : ready ? "READY" : "spinning up");
        telemetry.addData("Pollen", Robot.rpmLine(pollenRpm, pollen.getTargetRPM()));
        telemetry.addData("Nectar", Robot.rpmLine(nectarRpm, nectar.getTargetRPM()));
        telemetry.addData("Flywheel compensated F", "%.2f", appliedF);
        Dash.put("Flywheel averaged V", averageVolts.volts());
        telemetry.addLine("Pollen hub PIDF " + pollen.getAppliedPIDF());
        telemetry.addLine("Nectar hub PIDF " + nectar.getAppliedPIDF());
        Dash.put("Pollen RPM", pollenRpm);
        Dash.put("Pollen target", pollen.getTargetRPM());
        Dash.put("Nectar RPM", nectarRpm);
        Dash.put("Nectar target", nectar.getTargetRPM());
        Dash.put("Flywheels on", on);
        Dash.put("Flywheels ready", ready);
    }
}
