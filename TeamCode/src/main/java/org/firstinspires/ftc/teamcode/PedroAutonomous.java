package org.firstinspires.ftc.teamcode;

import com.bylazar.telemetry.PanelsTelemetry;
import com.bylazar.telemetry.TelemetryManager;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

import org.firstinspires.ftc.teamcode.lib.AutoSequence;
import org.firstinspires.ftc.teamcode.lib.AutoPathFile;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;
import org.firstinspires.ftc.teamcode.lib.VelocityMotor;
import org.firstinspires.ftc.teamcode.pedro.Constants;

/**
 * Loads Pedro export and JSON settings at INIT, drives to shoot, then follows path two to park.
 *
 * Place the robot at the start pose reported during INIT.
 *
 * setPose() does not move the robot; it tells Pedro where the robot already is. Lie to it and
 * the follower immediately lurches to "correct".
 */
@Autonomous(name = "Pedro Pathing Autonomous", group = "Autonomous",
        preselectTeleOp = "Field Centric (Java)")
public class PedroAutonomous extends OpMode {

    private TelemetryManager panels;
    private Follower follower;
    private AutoSequence auto;

    private VelocityMotor intake;
    private VelocityMotor shooterPollen;
    private VelocityMotor shooterNectar;
    private CRServo transportPollen, transportNectar;
    private CRServo triggerPollen, triggerNectar;
    private Servo stinger;


    @Override
    public void init() {
        panels = PanelsTelemetry.INSTANCE.getTelemetry();
        // Same robot.json as teleop, so the flywheel and stinger numbers match.
        RobotSettings settings = new RobotSettings(FieldCentricJava.SETTINGS).load();
        panels.debug("Settings", settings.status());
        for (String warning : settings.warnings()) panels.debug("Settings warning", warning);

        AutoPathFile route;
        try {
            route = new AutoPathFile(AutoPathFile.LOCATION);
        } catch (Exception error) {
            panels.debug("Path file error", error.getMessage());
            panels.debug("Upload", AutoPathFile.LOCATION.getAbsolutePath());
            panels.update(telemetry);
            requestOpModeStop();
            return;
        }

        follower = Constants.create(hardwareMap);
        follower.setPose(route.start);

        intake = new VelocityMotor(hardwareMap, "intake", DcMotorSimple.Direction.FORWARD, 145.1);
        intake.setPIDF(FieldCentricJava.INTAKE_P, 0, 0, FieldCentricJava.INTAKE_F);
        shooterPollen = new VelocityMotor(hardwareMap, "shooterPollen", DcMotorSimple.Direction.REVERSE);
        shooterPollen.setPIDF(FieldCentricJava.POLLEN_FLYWHEEL_P, 0,
                FieldCentricJava.POLLEN_FLYWHEEL_D, FieldCentricJava.FLYWHEEL_F);
        shooterNectar = new VelocityMotor(hardwareMap, "shooterNectar", DcMotorSimple.Direction.FORWARD);
        shooterNectar.setPIDF(FieldCentricJava.NECTAR_FLYWHEEL_P, 0,
                FieldCentricJava.NECTAR_FLYWHEEL_D, FieldCentricJava.FLYWHEEL_F);

        transportPollen = hardwareMap.get(CRServo.class, "transportPollen");
        transportPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        transportNectar = hardwareMap.get(CRServo.class, "transportNectar");
        triggerPollen = hardwareMap.get(CRServo.class, "triggerPollen");
        triggerPollen.setDirection(DcMotorSimple.Direction.REVERSE);
        triggerNectar = hardwareMap.get(CRServo.class, "triggerNectar");
        stinger = hardwareMap.get(Servo.class, "stinger");
        stinger.setPosition(FieldCentricJava.STINGER_UP);
        shoot(false);

        ///Main Sequence
        /////////////////////////////////////////////
        auto = new AutoSequence(follower)
                .waitSeconds(route.startupWaitSeconds)
                .follow(route.path1)
                .run(() -> shoot(true))
                .waitSeconds(route.shootSeconds)
                .run(() -> shoot(false))
                .follow(route.path2);
        if (route.path3 != null) {
            auto.follow(route.path3);
        }

        panels.debug("Status", "Initialized from " + AutoPathFile.LOCATION.getAbsolutePath());
        panels.debug("Start X", route.start.x());
        panels.debug("Start Y", route.start.y());
        panels.debug("Start heading (deg)", Math.toDegrees(route.start.heading()));
        panels.update(telemetry);
    }

    @Override
    public void start() {
        if (auto == null) {
            requestOpModeStop();
            return;
        }
        updateFlywheelCompensation();
        intake.setTargetRPM(FieldCentricJava.INTAKE_RPM);
        shooterPollen.setTargetRPM(FieldCentricJava.POLLEN_FLYWHEEL_RPM);
        shooterNectar.setTargetRPM(FieldCentricJava.NECTAR_FLYWHEEL_RPM);
        transportPollen.setPower(1);
        transportNectar.setPower(1);
    }

    /** Opens both shooting gates while enabled; call shoot(false) to stop feeding. */
    private void shoot(boolean enabled) {
        triggerPollen.setPower(enabled ? 1 : 0);
        triggerNectar.setPower(enabled ? 1 : 0);
    }

    @Override
    public void stop() {
        if (follower != null) follower.stop();
        if (triggerPollen != null) triggerPollen.setPower(0);
        if (triggerNectar != null) triggerNectar.setPower(0);
        if (intake != null) intake.stop();
        if (shooterPollen != null) shooterPollen.stop();
        if (shooterNectar != null) shooterNectar.stop();
        if (transportPollen != null) transportPollen.setPower(0);
        if (transportNectar != null) transportNectar.setPower(0);
    }

    private long nextFlywheelCompensation;
    private double flywheelVoltage = 12.5;

    private void updateFlywheelCompensation() {
        long now = System.nanoTime();
        if (now < nextFlywheelCompensation) return;
        nextFlywheelCompensation = now + 250_000_000L;
        flywheelVoltage = FieldCentricJava.flywheelBatteryVoltage(hardwareMap, flywheelVoltage);
        double f = Math.round(FieldCentricJava.compensatedFlywheelF(flywheelVoltage) * 100) / 100.0;
        if (f != shooterPollen.getF() || FieldCentricJava.POLLEN_FLYWHEEL_P != shooterPollen.getP()
                || FieldCentricJava.POLLEN_FLYWHEEL_D != shooterPollen.getD()) {
            shooterPollen.setPIDF(FieldCentricJava.POLLEN_FLYWHEEL_P, 0,
                    FieldCentricJava.POLLEN_FLYWHEEL_D, f);
        }
        if (f != shooterNectar.getF() || FieldCentricJava.NECTAR_FLYWHEEL_P != shooterNectar.getP()
                || FieldCentricJava.NECTAR_FLYWHEEL_D != shooterNectar.getD()) {
            shooterNectar.setPIDF(FieldCentricJava.NECTAR_FLYWHEEL_P, 0,
                    FieldCentricJava.NECTAR_FLYWHEEL_D, f);
        }
    }

    @Override
    public void loop() {
        if (auto == null) return;
        updateFlywheelCompensation();
        follower.update();
        auto.update();

        Pose pose = follower.pose();
        panels.debug("Step", auto.stepNumber() + "/" + auto.stepCount() + "  " + auto.stepName());
        panels.debug("Following", follower.isBusy());
        panels.debug("Flywheel battery V", flywheelVoltage);
        panels.debug("Flywheel compensated F", shooterNectar.getF());
        panels.debug("Completion", follower.completion());
        panels.debug("X", pose.x());
        panels.debug("Y", pose.y());
        panels.debug("Heading (deg)", Math.toDegrees(pose.heading()));
        panels.update(telemetry);
    }
}
