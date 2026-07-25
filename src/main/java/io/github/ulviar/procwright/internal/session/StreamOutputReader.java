/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns the bounded incremental read and decode loop for one streaming output. */
final class StreamOutputReader {

    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final CharsetPolicy charsetPolicy;
    private final int configuredLimit;
    private final ZeroReadBackoff zeroReadBackoff;

    StreamOutputReader(CharsetPolicy charsetPolicy, int configuredLimit, ZeroReadBackoff zeroReadBackoff) {
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        if (configuredLimit <= 0) {
            throw new IllegalArgumentException("configuredLimit must be positive");
        }
        this.configuredLimit = configuredLimit;
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
    }

    void read(InputStream input, BooleanSupplier stopping, IncrementalTextDecoder.Sink sink) throws IOException {
        Objects.requireNonNull(input, "input");
        BooleanSupplier stopRequested = Objects.requireNonNull(stopping, "stopping");
        Objects.requireNonNull(sink, "sink");
        try (input) {
            IncrementalTextDecoder decoder = new IncrementalTextDecoder(
                    charsetPolicy,
                    IncrementalTextDecoder.pendingByteLimitFor(configuredLimit),
                    IncrementalTextDecoder.outputWithoutInputLimitFor(configuredLimit));
            byte[] buffer = new byte[1024];
            int consecutiveZeroReads = 0;
            while (!stopRequested.getAsBoolean()) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, stopRequested)) {
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                if (stopRequested.getAsBoolean()) {
                    return;
                }
                decoder.decode(buffer, count, sink);
            }
            if (!stopRequested.getAsBoolean()) {
                decoder.end(sink);
            }
        }
    }
}
