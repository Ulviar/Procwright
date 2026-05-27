package io.github.ulviar.icli.integration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.ulviar.icli.IcliException;
import org.junit.jupiter.api.Test;

final class IntegrationExceptionTest {

    @Test
    void integrationFailureTypesShareCommonBaseException() {
        assertInstanceOf(IcliException.class, new JsonParseException("bad json"));
        assertInstanceOf(
                IcliException.class,
                new IntegrationProtocolException(IntegrationProtocolException.Reason.BAD_FRAME, "bad frame"));
    }
}
