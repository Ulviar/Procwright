package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.session.ProtocolSessionException;

/**
 * Creates protocol-session failures with the owning session transcript and process-exit snapshot.
 */
interface ProtocolRuntimeFailures {

    ProtocolSessionException timeout(Throwable cause);

    ProtocolSessionException closed(Throwable cause);

    ProtocolSessionException eof();

    ProtocolSessionException failure(ProtocolSessionException.Reason reason, String message, Throwable cause);
}
