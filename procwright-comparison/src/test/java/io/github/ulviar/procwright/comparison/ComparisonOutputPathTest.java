/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ComparisonOutputPathTest {

    @Test
    void defaultResolversStayInsideTheModuleBuildDirectory() {
        assertBuildReportPath(ComparisonRunner.outputPath(new String[0], false), "results.md");
        assertBuildReportPath(ComparisonRunner.outputPath(new String[] {"--verify"}, true), "results.md");
        assertBuildReportPath(StressComparisonRunner.outputPath(new String[0]), "stress-results.md");
    }

    private static void assertBuildReportPath(Path defaultPath, String fileName) {
        Path expected = Path.of("build", "reports", "comparison", fileName);
        Path normalized = defaultPath.normalize();
        assertEquals(expected, normalized);

        Path moduleRoot =
                Path.of("workspace", "procwright-comparison").toAbsolutePath().normalize();
        Path resolved = moduleRoot.resolve(normalized).normalize();
        Path reportsRoot = moduleRoot.resolve(Path.of("build", "reports", "comparison"));
        assertTrue(resolved.startsWith(reportsRoot));
        assertFalse(resolved.startsWith(moduleRoot.resolve("src")));
        assertFalse(resolved.startsWith(moduleRoot.getParent().resolve("context")));
    }
}
