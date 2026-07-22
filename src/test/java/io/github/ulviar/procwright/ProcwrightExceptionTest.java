/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                new LineSessionException(
                        LineSessionException.Reason.TIMEOUT, new LineTranscript("", false, false), "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new ProtocolSessionException(
                        ProtocolSessionException.Reason.TIMEOUT, new ProtocolTranscript("", false, false), "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new PooledLineSessionException(PooledLineSessionException.Reason.CLOSED, "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new PooledProtocolSessionException(PooledProtocolSessionException.Reason.CLOSED, "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new ExpectException(ExpectException.Reason.TIMEOUT, new LineTranscript("", false, false), "failed"));
        assertInstanceOf(
                ProcwrightException.class,
                new StreamException(
                        StreamException.Reason.PROCESS_FAILED, "failed", new StreamTranscript("", false), null));
    }

    @Test
    void streamExceptionRequiresAndExposesStructuredFailureState() {
        StreamTranscript transcript = new StreamTranscript("diagnostics", true);
        IllegalStateException cause = new IllegalStateException("failed");
        StreamException failure =
                new StreamException(StreamException.Reason.PROCESS_FAILED, "failed", transcript, cause);

        assertEquals(StreamException.Reason.PROCESS_FAILED, failure.reason());
        assertSame(transcript, failure.diagnostics());
        assertSame(cause, failure.getCause());
        assertThrows(NullPointerException.class, () -> new StreamException(null, "failed", transcript, cause));
        assertThrows(
                NullPointerException.class,
                () -> new StreamException(StreamException.Reason.PROCESS_FAILED, "failed", null, cause));
    }
}
