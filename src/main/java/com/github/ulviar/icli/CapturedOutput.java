package com.github.ulviar.icli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

record CapturedOutput(byte[] bytes, boolean truncated) {

    static CapturedOutput empty() {
        return new CapturedOutput(new byte[0], false);
    }

    static CapturedOutput capture(InputStream input, CapturePolicy.Bounded policy) throws IOException {
        int limit = policy.byteLimit();
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;

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

        return new CapturedOutput(retained.toByteArray(), truncated);
    }
}
