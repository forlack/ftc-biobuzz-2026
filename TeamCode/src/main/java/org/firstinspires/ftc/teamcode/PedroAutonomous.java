package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.bylazar.configurables.annotations.Configurable;
import com.bylazar.telemetry.TelemetryManager;
import com.bylazar.telemetry.PanelsTelemetry;
import org.firstinspires.ftc.teamcode.pedroPathing.Constants;
import com.pedropathing.geometry.BezierLine;
import com.pedropathing.follower.Follower;
import com.pedropathing.paths.PathChain;
import com.pedropathing.geometry.Pose;

/**
 * Visualizer-generated autonomous, completed so it actually runs.
 *
 * Two changes from the raw visualizer output:
 *
 *  1. START POSE. The generated code declared the robot at (72, 8) while MainChain starts at
 *     (56, 8) -- 16 inches apart. setStartingPose() does not move the robot, it only tells
 *     Pedro where the robot already is; with a 16" lie the follower would immediately lurch
 *     sideways to "correct". Now matches the path start.
 *
 *  2. STATE MACHINE. autonomousPathUpdate() was a stub returning 0 and nothing ever called
 *     followPath(), so the robot would sit still. Implemented as a minimal state machine.
 *
 * PLACE THE ROBOT AT x=56, y=8, HEADING 90 DEGREES BEFORE RUNNING.
 *
 * Speed is not part of a Pedro path -- paths are pure geometry. MAX_POWER scales all drive
 * output and is live-editable in the Panels Configurables panel.
 */
@Autonomous(name = "Pedro Pathing Autonomous", group = "Autonomous")
@Configurable // Panels
public class PedroAutonomous extends OpMode {
  private TelemetryManager panelsTelemetry; // Panels Telemetry instance
  public Follower follower; // Pedro Pathing follower instance
  private int pathState; // Current autonomous path state (state machine)
  private Paths paths; // Paths defined in the Paths class

  /** 0..1 drive output scaling. Keep this low until the PIDs are tuned. */
  public static double MAX_POWER = 0.5;

  /** Must match MainChain's first point AND where the robot physically sits. */
  public static final Pose START_POSE = new Pose(56.000, 8.000, Math.toRadians(90));

  @Override
  public void init() {
    panelsTelemetry = PanelsTelemetry.INSTANCE.getTelemetry();

    follower = Constants.createFollower(hardwareMap);
    follower.setStartingPose(START_POSE);
    follower.setMaxPower(MAX_POWER);

    paths = new Paths(follower); // Build paths
    pathState = 0;

    panelsTelemetry.debug("Status", "Initialized");
    panelsTelemetry.debug("Place robot at x=56 y=8 heading=90");
    panelsTelemetry.update(telemetry);
  }

  @Override
  public void loop() {
    follower.update(); // Update Pedro Pathing
    pathState = autonomousPathUpdate(); // Update autonomous state machine

    // Log values to Panels and Driver Station
    panelsTelemetry.debug("Path State", pathState);
    panelsTelemetry.debug("Following", follower.isBusy());
    panelsTelemetry.debug("X", follower.getPose().getX());
    panelsTelemetry.debug("Y", follower.getPose().getY());
    panelsTelemetry.debug("Heading", Math.toDegrees(follower.getPose().getHeading()));
    panelsTelemetry.debug("Target end", "back at start: x=56 y=8 heading=90");
    panelsTelemetry.update(telemetry);
  }

  public static class Paths {
    public PathChain MainChain;

    public Paths(Follower follower) {
      MainChain = follower.pathBuilder()
          // Out: (56, 8) -> (63.38, 28.855), turning 90 -> 180
          .addPath(
            new BezierLine(
              new Pose(56.000, 8.000),
            new Pose(63.380, 28.855)
            )
          )
          .setLinearHeadingInterpolation(Math.toRadians(90), Math.toRadians(180))
          // Back: reverse of the above, turning 180 -> 90, ending where it started.
          // Written by hand -- the pasted "updated" export was identical to the original
          // and contained no return leg. Replace this if the real export differs.
          .addPath(
            new BezierLine(
              new Pose(63.380, 28.855),
            new Pose(56.000, 8.000)
            )
          )
          .setLinearHeadingInterpolation(Math.toRadians(180), Math.toRadians(90))
          .build();
    }
  }

  /**
   * Minimal state machine.
   *
   *   0 -> start following MainChain, advance immediately
   *   1 -> wait for the follower to finish
   *   2 -> done, robot holds its end pose
   *
   * To extend: add cases that fire followPath() for the next chain, each gated on
   * !follower.isBusy() so a path only starts once the previous one finishes.
   */
  public int autonomousPathUpdate() {
    switch (pathState) {
      case 0:
        follower.followPath(paths.MainChain);
        return 1;

      case 1:
        if (!follower.isBusy()) {
          return 2; // path complete
        }
        return 1;

      default:
        return pathState; // 2 = finished; follower holds the end pose
    }
  }
}
