package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class TerminalCapabilityBoundaryTest {

    private static final Set<Class<?>> FORBIDDEN_NON_SESSION_TYPES =
            Set.of(TerminalPolicy.class, TerminalSize.class, TerminalSignal.class, PtyProvider.class, PtyRequest.class);

    @Test
    void terminalPolicyIsExposedOnlyBySessionFamilyDrafts() {
        assertEquals(Set.of(), terminalMethods(CommandInvocation.Builder.class));
        assertEquals(Set.of(), terminalMethods(StreamInvocation.Builder.class));

        assertEquals(Set.of("terminal(TerminalPolicy)"), terminalMethods(SessionInvocation.Builder.class));
        assertEquals(Set.of("terminal(TerminalPolicy)"), terminalMethods(LineSessionInvocation.Builder.class));
        assertEquals(Set.of("terminal(TerminalPolicy)"), terminalMethods(PooledLineSessionInvocation.Builder.class));
    }

    @Test
    void nonSessionPublicApiDoesNotExposeTerminalCapability() {
        for (Class<?> type : List.of(
                CommandInvocation.class,
                CommandInvocation.Builder.class,
                RunOptions.class,
                StreamInvocation.class,
                StreamInvocation.Builder.class,
                StreamOptions.class)) {
            assertNoForbiddenTerminalNames(type);
            assertNoForbiddenTerminalTypes(type);
        }
    }

    @Test
    void runAndStreamProfilesDoNotRequestTerminalCapability() {
        assertEquals(
                TerminalPolicy.DISABLED,
                ScenarioProfile.run(RunOptions.defaults()).terminalPolicy());
        assertEquals(
                TerminalPolicy.DISABLED,
                ScenarioProfile.stream(StreamOptions.defaults()).terminalPolicy());
    }

    @Test
    void streamExecutionPlanKeepsPtyProviderUnavailable() {
        StreamExecutionPlan plan = ExecutionPlanResolver.resolve(
                ScenarioProfile.stream(StreamOptions.defaults()),
                CommandSpec.of("tool"),
                StreamInvocation.builder().build());

        assertEquals(TerminalPolicy.DISABLED, plan.sessionPlan().launchPlan().terminalPolicy());
        assertFalse(plan.sessionPlan().ptyProvider().available());
        assertTrue(plan.sessionPlan().ptyProvider().description().contains("listen scenario"));
    }

    private static Set<String> terminalMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals("terminal"))
                .map(TerminalCapabilityBoundaryTest::signature)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + ")";
    }

    private static void assertNoForbiddenTerminalNames(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            assertFalse(
                    name.contains("terminal") || name.contains("pty"),
                    () -> type.getName() + "#" + method.getName() + " leaks terminal capability by name");
        }
    }

    private static void assertNoForbiddenTerminalTypes(Class<?> type) {
        for (Constructor<?> constructor : type.getConstructors()) {
            assertNoForbiddenTerminalTypes(constructor, type.getName());
        }
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            assertNoForbiddenTerminalTypes(method, type.getName() + "#" + method.getName());
            assertNoForbiddenTerminalType(method.getGenericReturnType(), type.getName() + "#" + method.getName());
        }
    }

    private static void assertNoForbiddenTerminalTypes(Executable executable, String location) {
        for (Type parameter : executable.getGenericParameterTypes()) {
            assertNoForbiddenTerminalType(parameter, location);
        }
        for (Type exception : executable.getGenericExceptionTypes()) {
            assertNoForbiddenTerminalType(exception, location);
        }
    }

    private static void assertNoForbiddenTerminalType(Type type, String location) {
        if (type instanceof Class<?> classType) {
            assertFalse(
                    FORBIDDEN_NON_SESSION_TYPES.contains(classType),
                    () -> location + " leaks terminal capability type " + classType.getName());
            if (classType.isArray()) {
                assertNoForbiddenTerminalType(classType.componentType(), location);
            }
        } else if (type instanceof java.lang.reflect.ParameterizedType parameterizedType) {
            assertNoForbiddenTerminalType(parameterizedType.getRawType(), location);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                assertNoForbiddenTerminalType(argument, location);
            }
        } else if (type instanceof java.lang.reflect.GenericArrayType arrayType) {
            assertNoForbiddenTerminalType(arrayType.getGenericComponentType(), location);
        } else if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                assertNoForbiddenTerminalType(bound, location);
            }
        } else if (type instanceof java.lang.reflect.WildcardType wildcard) {
            for (Type bound : wildcard.getLowerBounds()) {
                assertNoForbiddenTerminalType(bound, location);
            }
            for (Type bound : wildcard.getUpperBounds()) {
                assertNoForbiddenTerminalType(bound, location);
            }
        }
    }
}
