/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import java.nio.file.Path;
import java.util.List;

final class PtyTestPaths {

    private static final Path ROOT = fixtureRoot();

    static final Path SCRIPT = ROOT.resolve("usr/bin/script");
    static final Path SECOND_SCRIPT = ROOT.resolve("bin/script");
    static final Path SHELL = ROOT.resolve("bin/sh");
    static final Path STTY = ROOT.resolve("usr/bin/stty");
    static final Path ENV = ROOT.resolve("usr/bin/env");
    static final Path DD = ROOT.resolve("bin/dd");
    static final Path MISSING_SCRIPT = ROOT.resolve("missing/script");

    private PtyTestPaths() {}

    private static Path fixtureRoot() {
        Path absoluteTemp =
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path fileSystemRoot = absoluteTemp.getRoot();
        if (fileSystemRoot == null) {
            throw new IllegalStateException("the test filesystem has no absolute root");
        }
        return fileSystemRoot.resolve("procwright-pty-test-tools");
    }

    static SystemPtyProvider.SystemToolCandidates candidates() {
        return new SystemPtyProvider.SystemToolCandidates(
                List.of(SCRIPT, SECOND_SCRIPT), SHELL, List.of(STTY), List.of(ENV), List.of(DD));
    }
}
