/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

final class IntegrationNullnessMetadataTest {

    @Test
    void exportedIntegrationPackageIsNullMarked() {
        assertTrue(CommandBackedTool.class.getPackage().isAnnotationPresent(NullMarked.class));
    }
}
