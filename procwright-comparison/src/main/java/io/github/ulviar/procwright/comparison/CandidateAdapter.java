/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import java.time.Duration;

interface CandidateAdapter {

    String id();

    String displayName();

    String scope();

    default CommandOutcome run(CommandRequest request, int captureLimit) throws Exception {
        return CommandOutcome.unsupported("one-shot process execution is outside this candidate scope");
    }

    default CommandOutcome stream(CommandRequest request, int captureLimit) throws Exception {
        return CommandOutcome.unsupported("streaming is outside this candidate scope");
    }

    default CommandOutcome lineSession(CommandRequest request, Duration requestTimeout) throws Exception {
        return CommandOutcome.unsupported("line session is outside this candidate scope");
    }

    default CommandOutcome expectPrompt(CommandRequest request, Duration timeout) throws Exception {
        return CommandOutcome.unsupported("expect prompt automation is outside this candidate scope");
    }

    default CommandOutcome ptyProbe(Duration timeout) throws Exception {
        return CommandOutcome.unsupported("PTY is outside this candidate scope");
    }
}
