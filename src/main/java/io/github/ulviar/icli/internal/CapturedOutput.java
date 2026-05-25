package io.github.ulviar.icli.internal;

import io.github.ulviar.icli.command.CapturePolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public record CapturedOutput(byte[] bytes, boolean truncated) {

    static CapturedOutput empty() {
        return new CapturedOutput(new byte[0], false);
    }

    static CapturedOutput capture(InputStream input, CapturePolicy.Bounded policy) throws IOException {
        int limit = policy.byteLimit();
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;

        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = limit - retained.size();
                if (remaining > 0) {
                    retained.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        } catch (IOException exception) {
            throw new PartialCaptureException(new CapturedOutput(retained.toByteArray(), truncated), exception);
        }

        return new CapturedOutput(retained.toByteArray(), truncated);
    }

    static final class PartialCaptureException extends IOException {

        private final CapturedOutput output;

        private PartialCaptureException(CapturedOutput output, IOException cause) {
            super(cause);
            this.output = output;
        }

        CapturedOutput output() {
            return output;
        }
    }
}
