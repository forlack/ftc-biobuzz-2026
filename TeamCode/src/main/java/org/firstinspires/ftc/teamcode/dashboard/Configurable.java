package org.firstinspires.ftc.teamcode.dashboard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put this on a class and every {@code public static} (not {@code final}) number, boolean or
 * String in it becomes a setting: shown on the dashboard's Tune tab, changeable live, and saved
 * in robot.json. The same idea as FTC Dashboard's {@code @Config} and Panels' {@code @Configurable}.
 *
 * <pre>
 * &#64;Configurable
 * public class Arm {
 *     public static double UP = 0.8;
 *     public static double DOWN = 0.1;
 * }
 * </pre>
 *
 * Found automatically, on the robot and in the simulator. A live switch that should never be
 * saved gets {@code @RobotSettings.NotSaved}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Configurable {}
