/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

final class IntegrationNullnessMetadataTest {

    @Test
    void exportedIntegrationSurfaceHasNoNullnessExceptions() throws Exception {
        Set<Class<?>> apiTypes = PublicIntegrationApiSurfaceTest.publicApiTypes(ProtocolAdapters.class);
        assertTrue(ProtocolAdapters.class.getPackage().isAnnotationPresent(NullMarked.class));

        TreeSet<String> nullable = new TreeSet<>();
        int checkedReferencePositions = 0;
        for (Class<?> type : apiTypes) {
            checkedReferencePositions += scanType(type, nullable);
        }

        assertTrue(checkedReferencePositions > 30, "The audit must traverse the complete exported surface");
        assertEquals(Set.of(), nullable);
    }

    private static int scanType(Class<?> type, Set<String> nullable) {
        assertMarked(type, type.getName());
        int checked = scanTypeParameters(type.getTypeParameters(), type.getName(), nullable);
        AnnotatedType superclass = type.getAnnotatedSuperclass();
        if (superclass != null) {
            checked += scan(superclass, type.getName() + " superclass", nullable);
        }
        checked += scanAll(type.getAnnotatedInterfaces(), type.getName() + " interface", nullable);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isPublicOrProtected(constructor) && !constructor.isSynthetic()) {
                assertMarked(constructor, key(constructor));
                checked += scanExecutable(constructor, nullable);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (isPublicOrProtected(method) && !method.isSynthetic() && !method.isBridge()) {
                assertMarked(method, key(method));
                checked += scanExecutable(method, nullable);
                if (method.getReturnType() != void.class) {
                    checked += scan(method.getAnnotatedReturnType(), key(method) + " return", nullable);
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if ((Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers()))
                    && !field.isSynthetic()) {
                checked += scan(field.getAnnotatedType(), type.getName() + '#' + field.getName(), nullable);
            }
        }
        return checked;
    }

    private static int scanExecutable(Executable executable, Set<String> nullable) {
        int checked = scanTypeParameters(executable.getTypeParameters(), key(executable), nullable);
        AnnotatedType receiver = executable.getAnnotatedReceiverType();
        if (receiver != null) {
            checked += scan(receiver, key(executable) + " receiver", nullable);
        }
        AnnotatedType[] parameters = executable.getAnnotatedParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            checked += scan(parameters[index], key(executable) + " parameter[" + index + ']', nullable);
        }
        return checked + scanAll(executable.getAnnotatedExceptionTypes(), key(executable) + " throws", nullable);
    }

    private static int scanTypeParameters(TypeVariable<?>[] parameters, String owner, Set<String> nullable) {
        int checked = 0;
        for (TypeVariable<?> parameter : parameters) {
            AnnotatedType[] bounds = parameter.getAnnotatedBounds();
            for (int index = 0; index < bounds.length; index++) {
                checked += scan(
                        bounds[index],
                        owner + " typeParameter[" + parameter.getName() + "].bound[" + index + ']',
                        nullable);
            }
        }
        return checked;
    }

    private static int scan(AnnotatedType type, String position, Set<String> nullable) {
        int checked = type.getType() instanceof Class<?> raw && raw.isPrimitive() ? 0 : 1;
        if (type.isAnnotationPresent(Nullable.class)) {
            nullable.add(position);
        }
        AnnotatedType owner = type.getAnnotatedOwnerType();
        if (owner != null) {
            checked += scan(owner, position + ".owner", nullable);
        }
        if (type instanceof AnnotatedArrayType array) {
            checked += scan(array.getAnnotatedGenericComponentType(), position + ".component", nullable);
        } else if (type instanceof AnnotatedParameterizedType parameterized) {
            checked += scanAll(parameterized.getAnnotatedActualTypeArguments(), position + ".typeArgument", nullable);
        } else if (type instanceof AnnotatedWildcardType wildcard) {
            checked += scanAll(wildcard.getAnnotatedLowerBounds(), position + ".lowerBound", nullable);
            checked += scanAll(wildcard.getAnnotatedUpperBounds(), position + ".upperBound", nullable);
        }
        return checked;
    }

    private static int scanAll(AnnotatedType[] types, String position, Set<String> nullable) {
        int checked = 0;
        for (int index = 0; index < types.length; index++) {
            checked += scan(types[index], position + '[' + index + ']', nullable);
        }
        return checked;
    }

    private static void assertMarked(AnnotatedElement element, String position) {
        assertFalse(
                element.isAnnotationPresent(NullUnmarked.class),
                () -> position + " must not weaken the public contract with @NullUnmarked");
    }

    private static boolean isPublicOrProtected(Executable executable) {
        return Modifier.isPublic(executable.getModifiers()) || Modifier.isProtected(executable.getModifiers());
    }

    private static String key(Executable executable) {
        return executable.getDeclaringClass().getName()
                + '#'
                + (executable instanceof Constructor<?> ? "<init>" : executable.getName())
                + '('
                + java.util.Arrays.stream(executable.getParameterTypes())
                        .map(Class::getName)
                        .collect(Collectors.joining(","))
                + ')';
    }
}
