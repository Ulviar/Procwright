/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Validates filesystem identities that cannot be established when a capture policy is created. */
final class CaptureTargetValidator {

    private CaptureTargetValidator() {}

    static void validate(CapturePolicy capturePolicy) {
        if (!(capturePolicy instanceof CapturePolicy.ToPath toPath)
                || toPath.stderr().isEmpty()) {
            return;
        }

        Path stdout = toPath.stdout();
        Path stderr = toPath.stderr().orElseThrow();
        try {
            Path resolvedStdout = resolvedTarget(stdout);
            Path resolvedStderr = resolvedTarget(stderr);
            if (sameExistingFile(stdout, stderr)
                    || resolvedStdout.equals(resolvedStderr)
                    || portableIdentity(resolvedStdout).equals(portableIdentity(resolvedStderr))) {
                throw new IllegalArgumentException("stdout and stderr capture paths must resolve to distinct files");
            }
        } catch (IOException | SecurityException exception) {
            throw new IllegalArgumentException(
                    "could not verify that stdout and stderr capture paths are distinct", exception);
        }
    }

    private static boolean sameExistingFile(Path first, Path second) throws IOException {
        return existsFollowingLinks(first) && existsFollowingLinks(second) && Files.isSameFile(first, second);
    }

    private static boolean existsFollowingLinks(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static Path resolvedTarget(Path path) throws IOException {
        return resolvedTarget(path.toAbsolutePath().normalize(), new HashSet<>(), 0);
    }

    private static Path resolvedTarget(Path absolute, Set<Path> visitedLinks, int linkDepth) throws IOException {
        BasicFileAttributes attributes = readAttributesWithoutFollowingLinks(absolute);
        if (attributes != null && attributes.isSymbolicLink()) {
            if (linkDepth >= 40 || !visitedLinks.add(absolute)) {
                throw new IOException("capture path contains a cyclic or overlong symbolic-link chain");
            }
            Path linkTarget = Files.readSymbolicLink(absolute);
            Path resolvedLink =
                    linkTarget.isAbsolute() ? linkTarget : absolute.getParent().resolve(linkTarget);
            return resolvedTarget(resolvedLink.normalize(), visitedLinks, linkDepth + 1);
        }
        if (attributes != null) {
            return absolute.toRealPath();
        }

        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("capture path has no existing ancestor");
        }
        return resolvedTarget(parent, visitedLinks, linkDepth)
                .resolve(absolute.getFileName())
                .normalize();
    }

    private static BasicFileAttributes readAttributesWithoutFollowingLinks(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static String portableIdentity(Path path) {
        StringBuilder identity = new StringBuilder();
        Path root = path.getRoot();
        if (root != null) {
            identity.append(portableComponent(root.toString()));
        }
        for (Path component : path) {
            identity.append('/').append(portableComponent(component.toString()));
        }
        return identity.toString();
    }

    private static String portableComponent(String component) {
        String normalized = Normalizer.normalize(component, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        int end = normalized.length();
        while (end > 0) {
            char last = normalized.charAt(end - 1);
            if (last != '.' && last != ' ') {
                break;
            }
            end--;
        }
        return normalized.substring(0, end);
    }
}
