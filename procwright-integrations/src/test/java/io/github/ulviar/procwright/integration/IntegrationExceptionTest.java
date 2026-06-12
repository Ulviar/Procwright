/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.ulviar.procwright.ProcwrightException;
import org.junit.jupiter.api.Test;

final class IntegrationExceptionTest {

    @Test
    void integrationFailureTypesShareCommonBaseException() {
        assertInstanceOf(ProcwrightException.class, new JsonParseException("bad json"));
        assertInstanceOf(
                ProcwrightException.class,
                new IntegrationProtocolException(IntegrationProtocolException.Reason.BAD_FRAME, "bad frame"));
    }
}
