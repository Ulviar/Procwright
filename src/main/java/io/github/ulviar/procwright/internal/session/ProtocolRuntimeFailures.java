/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.OptionalInt;

/**
 * Creates protocol-session failures with the owning session transcript and process-exit snapshot.
 */
interface ProtocolRuntimeFailures {

    ProtocolSessionException timeout(Throwable cause);

    default ProtocolSessionException interrupted(String message, InterruptedException cause) {
        return failure(ProtocolSessionException.Reason.FAILURE, message, cause);
    }

    ProtocolSessionException closed(Throwable cause);

    ProtocolSessionException eof();

    default ProtocolSessionException processExited(OptionalInt exitCode) {
        return failure(
                ProtocolSessionException.Reason.PROCESS_EXITED,
                "Protocol process exited before a complete response was read",
                null);
    }

    ProtocolSessionException failure(ProtocolSessionException.Reason reason, String message, Throwable cause);
}
