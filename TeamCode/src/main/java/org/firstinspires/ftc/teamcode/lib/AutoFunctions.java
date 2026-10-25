package org.firstinspires.ftc.teamcode.lib;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Every {@link AutoFunction} of a robot: found on the mechanisms that are public final fields
 * of the robot object (mechanisms/Robot on the robot, a generated stand-in in the simulator).
 *
 * Plain Java (reflection only), so the robot, the dashboard and the simulator share it.
 */
public final class AutoFunctions {

    private static final class Function {
        final String name, label, help;
        final boolean listed, condition;
        final Actions.Param[] params;
        Object target;   // null when only described (the dashboard's list): never called
        Method method;

        Function(String name, String label, String help, boolean listed, boolean condition,
                 Actions.Param[] params, Object target, Method method) {
            this.name = name;
            this.label = label;
            this.help = help;
            this.listed = listed;
            this.condition = condition;
            this.params = params;
            this.target = target;
            this.method = method;
        }

        Object call(Object... args) {
            if (target == null) return Boolean.FALSE;
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new IllegalStateException(name + " failed: " + cause, cause);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(name + " is not public", error);
            }
        }
    }

    private static final Object[] NO_ARGS = {};
    private final Map<String, Function> functions = new LinkedHashMap<>();

    private AutoFunctions() { }

    /** The functions of a robot object, ready to run. */
    public static AutoFunctions of(Object robot) {
        return scan(robot.getClass(), robot);
    }

    /** Only the list (names, labels, settings), for the dashboard: nothing can be run. */
    public static AutoFunctions describe(Class<?> robotClass) {
        return scan(robotClass, null);
    }

    private static AutoFunctions scan(Class<?> robotClass, Object robot) {
        AutoFunctions result = new AutoFunctions();
        for (Field field : sortedFields(robotClass)) {
            Object part = null;
            if (robot != null) {
                try {
                    part = field.get(robot);
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException(error);
                }
            }
            for (Method method : sortedMethods(field.getType())) {
                AutoFunction info = method.getAnnotation(AutoFunction.class);
                if (info == null) continue;
                if (result.functions.containsKey(info.name())) {
                    throw new IllegalStateException("Two auton functions are called \"" + info.name() + "\"");
                }
                method.setAccessible(true);   // a mechanism class need not be public
                result.functions.put(info.name(), describe(info, method, part));
            }
        }
        return result;
    }

    /**
     * The simulator: runs these {@code simulated} methods (marked with the same names) instead,
     * keeping the robot's labels and settings. A name the robot does not have is a mistake.
     */
    public AutoFunctions override(Object simulated) {
        for (Method method : sortedMethods(simulated.getClass())) {
            AutoFunction info = method.getAnnotation(AutoFunction.class);
            if (info == null) continue;
            Function function = functions.get(info.name());
            if (function == null) {
                throw new IllegalStateException("The simulator has \"" + info.name() + "\" but the robot does not");
            }
            if (method.getParameterTypes().length != function.params.length) {
                throw new IllegalStateException("The simulator's \"" + info.name() + "\" takes different settings");
            }
            method.setAccessible(true);   // the simulator's class need not be public
            function.target = simulated;
            function.method = method;
        }
        return this;
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    /** Runs function {@code name} with these settings. Unknown names fail at once. */
    public Runnable action(String name, final double... args) {
        final Function function = get(name, false);
        final Object[] boxed = box(args);
        return () -> function.call(boxed);
    }

    /** Asks condition {@code name} (a boolean function). */
    public BooleanSupplier condition(String name) {
        final Function function = get(name, true);
        return () -> Boolean.TRUE.equals(function.call(NO_ARGS));
    }

    /** Adds every listed function to the editor's actions, by label. */
    public Actions addTo(Actions actions) {
        List<Function> listed = new ArrayList<>();
        for (Function function : functions.values()) {
            if (function.listed && !function.condition) listed.add(function);
        }
        Collections.sort(listed, (a, b) -> a.label.compareTo(b.label));
        for (final Function function : listed) {
            actions.instant(function.name, function.label, function.help, params -> {
                Object[] args = new Object[function.params.length];
                for (int i = 0; i < args.length; i++) args[i] = params.getDouble(function.params[i].name);
                return () -> function.call(args);
            }, function.params);
        }
        return actions;
    }

    // ---- Inside ---------------------------------------------------------------------

    private Function get(String name, boolean condition) {
        Function function = functions.get(name);
        if (function == null) {
            throw new IllegalArgumentException("No auton function \"" + name + "\". Known: " + functions.keySet());
        }
        if (function.condition != condition) {
            throw new IllegalArgumentException("\"" + name + "\" is " + (condition ? "not a condition" : "a condition"));
        }
        return function;
    }

    private static Function describe(AutoFunction info, Method method, Object target) {
        String where = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        if (!info.name().matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalStateException(where + ": function name \"" + info.name() + "\" must be letters and digits");
        }
        Class<?> returns = method.getReturnType();
        boolean condition = returns == boolean.class;
        if (!condition && returns != void.class) {
            throw new IllegalStateException(where + " must return void (an action) or boolean (a condition)");
        }
        Class<?>[] types = method.getParameterTypes();
        Annotation[][] annotations = method.getParameterAnnotations();
        Actions.Param[] params = new Actions.Param[types.length];
        for (int i = 0; i < types.length; i++) {
            AutoFunction.Param param = null;
            for (Annotation annotation : annotations[i]) {
                if (annotation instanceof AutoFunction.Param) param = (AutoFunction.Param) annotation;
            }
            if (types[i] != double.class || param == null) {
                throw new IllegalStateException(where + ": every setting must be a double marked @AutoFunction.Param");
            }
            params[i] = new Actions.Param(param.name(), param.label(), param.unit(), param.value(), param.min(), param.max());
        }
        String label = info.label().isEmpty() ? info.name() : info.label();
        return new Function(info.name(), label, info.help(), info.listed() && !condition, condition,
                params, target, method);
    }

    private static List<Field> sortedFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !field.getType().isPrimitive()) {
                fields.add(field);
            }
        }
        Collections.sort(fields, Comparator.comparing(Field::getName));
        return fields;
    }

    private static List<Method> sortedMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>(Arrays.asList(type.getMethods()));
        Collections.sort(methods, Comparator.comparing(Method::getName));
        return methods;
    }

    private static Object[] box(double[] values) {
        Object[] boxed = new Object[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return boxed;
    }
}
