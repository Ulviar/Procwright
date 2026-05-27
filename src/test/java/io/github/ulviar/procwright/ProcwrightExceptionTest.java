package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.ulviar.procwright.command.CommandException;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamTranscript;
import org.junit.jupiter.api.Test;

final class ProcwrightExceptionTest {

    @Test
    void coreFailureTypesShareCommonBaseException() {
        assertInstanceOf(ProcwrightException.class, new CommandException(new CommandResult(1, "", "")));
        assertInstanceOf(ProcwrightException.class, new CommandExecutionException("failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new LineSessionException(LineSessionException.Reason.TIMEOUT, new LineTranscript("", false), "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new ProtocolSessionException(
                        ProtocolSessionException.Reason.TIMEOUT,
                        new ProtocolTranscript("", false, false, false),
                        "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new PooledLineSessionException(PooledLineSessionException.Reason.CLOSED, "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new PooledProtocolSessionException(PooledProtocolSessionException.Reason.CLOSED, "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new ExpectException(ExpectException.Reason.TIMEOUT, new LineTranscript("", false), "failed"));
        assertInstanceOf(
                ProcwrightException.class, new StreamException("failed", new StreamTranscript("", false), null));
    }
}
