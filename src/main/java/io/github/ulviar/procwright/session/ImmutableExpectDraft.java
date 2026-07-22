/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.session.SessionInternals;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;

final class ImmutableExpectDraft implements Expect.Draft {

    private final Session session;
    private final ExpectSettings settings;

    ImmutableExpectDraft(Session session, ExpectSettings settings) {
        this.session = Objects.requireNonNull(session, "session");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Expect.Draft withTimeout(Duration timeout) {
        return copy(settings.withTimeout(timeout));
    }

    @Override
    public Expect.Draft withTranscriptLimit(int transcriptLimit) {
        return copy(settings.withTranscriptLimit(transcriptLimit));
    }

    @Override
    public Expect.Draft withMatchBufferLimit(int matchBufferLimit) {
        return copy(settings.withMatchBufferLimit(matchBufferLimit));
    }

    @Override
    public Expect.Draft withCharset(Charset charset) {
        return copy(settings.withCharset(charset));
    }

    @Override
    public Expect.Draft withAnsiControlSequenceStripping() {
        return copy(settings.withAnsiControlSequenceStripping());
    }

    @Override
    public Expect.Draft withTranscriptValues(ExpectTranscriptValues transcriptValues) {
        return copy(settings.withTranscriptValues(transcriptValues));
    }

    @Override
    public Expect open() {
        return SessionInternals.openExpect(session, settings);
    }

    private Expect.Draft copy(ExpectSettings updated) {
        return new ImmutableExpectDraft(session, updated);
    }
}
