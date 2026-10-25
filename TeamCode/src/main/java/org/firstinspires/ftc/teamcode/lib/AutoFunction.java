package org.firstinspires.ftc.teamcode.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a mechanism's method an autonomous function: it appears in the dashboard's Auton
 * Sequence (Add to the sequence) and route files can call it by {@link #name}.
 *
 * <pre>
 * &#64;AutoFunction(name = "armUp", label = "arm up", help = "Raise the arm.")
 * public void up() { servo.setPosition(UP); }
 *
 * &#64;AutoFunction(name = "armTo", label = "arm to", help = "Move the arm to a height.")
 * public void to(&#64;AutoFunction.Param(name = "inches", label = "Height", unit = "in",
 *                                    value = 10, min = 0, max = 30) double inches) { ... }
 * </pre>
 *
 * The method must be public, in a mechanism that is a {@code public final} field of
 * mechanisms/Robot, return void, and take only double settings marked {@link Param}. It
 * should only set what the mechanism does next (the mechanism's update() does the work), so
 * it can also fire part way along a path.
 *
 * A method returning boolean is a condition instead (such as "flywheelsReady"): never in the
 * editor, but StandardActions can wait for it. {@code listed = false} hides an action from the
 * editor too (for pieces of StandardActions' own steps, such as "feedOn").
 *
 * The name is permanent once an auton file uses it: the robot refuses a route naming a
 * function it does not have.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AutoFunction {
    /** What route files call it, like "armUp". Letters and digits; never change it once used. */
    String name();

    /** What the editor shows, like "arm up". Empty: the name. */
    String label() default "";

    /** One sentence for the editor. */
    String help() default "";

    /** False: route files and StandardActions can use it, but the editor does not offer it. */
    boolean listed() default true;

    /** A number setting of a function, shown as a box in the editor. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Param {
        String name();
        String label();
        String unit() default "";
        /** The value the editor fills in. */
        double value();
        double min();
        double max();
    }
}
