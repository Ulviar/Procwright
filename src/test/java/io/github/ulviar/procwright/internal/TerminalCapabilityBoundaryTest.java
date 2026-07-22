/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.InteractiveScenario;
import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.RunScenario;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import io.github.ulviar.procwright.terminal.TerminalSize;
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
        assertEquals(Set.of(), terminalMethods(RunScenario.Draft.class));
        assertEquals(Set.of(), terminalMethods(StreamScenario.Draft.class));

        assertEquals(Set.of("withTerminal(TerminalPolicy)"), terminalMethods(InteractiveScenario.Draft.class));
        assertEquals(Set.of("withTerminal(TerminalPolicy)"), terminalMethods(LineSessionScenario.Draft.class));
        assertEquals(Set.of("withTerminal(TerminalPolicy)"), terminalMethods(ProtocolSessionScenario.Draft.class));
    }

    @Test
    void nonSessionPublicApiDoesNotExposeTerminalCapability() {
        for (Class<?> type : List.of(RunScenario.Draft.class, StreamScenario.Draft.class)) {
            assertNoForbiddenTerminalNames(type);
            assertNoForbiddenTerminalTypes(type);
        }
    }

    @Test
    void runAndStreamProfilesDoNotRequestTerminalCapability() {
        assertEquals(
                TerminalPolicy.DISABLED,
                RunSettings.defaults(LaunchSettings.from(CommandSpec.of("tool")))
                        .plan()
                        .launchPlan()
                        .terminalPolicy());
        assertEquals(
                TerminalPolicy.DISABLED,
                StreamSettings.defaults(LaunchSettings.from(CommandSpec.of("tool")))
                        .plan()
                        .sessionPlan()
                        .launchPlan()
                        .terminalPolicy());
    }

    @Test
    void streamExecutionPlanKeepsPtyProviderUnavailable() {
        StreamExecutionPlan plan = StreamSettings.defaults(LaunchSettings.from(CommandSpec.of("tool")))
                .plan();

        assertEquals(TerminalPolicy.DISABLED, plan.sessionPlan().launchPlan().terminalPolicy());
        assertFalse(plan.sessionPlan().ptyProvider().available());
        assertTrue(plan.sessionPlan().ptyProvider().description().contains("listen scenario"));
    }

    private static Set<String> terminalMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals("withTerminal"))
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
